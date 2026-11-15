// Runs Pass 1 writer, Pass 2 cross-model verifier, timestamp/option guards, Pass 3 blind test, and automatic caption acquisition.
package com.arabicquest.service;

import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.dto.QuestionBankAdminResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOpen;
import com.arabicquest.service.AiQuizService.CandidateOption;
import com.arabicquest.service.BlindTestService.BlindTestedCandidate;
import com.arabicquest.service.BlindTestService.EpisodeBlindTestOutcome;
import com.arabicquest.service.TranscriptParserService.Cue;
import com.arabicquest.service.TranscriptParserService.TranscriptChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

@Service
public class CrossModelQuizPipelineService {

    public static final int TARGET_EPISODE_MCQ_COUNT = 22;

    private final EpisodeRepository episodeRepository;
    private final EpisodeTranscriptRepository transcriptRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final TranscriptParserService transcriptParserService;
    private final TranscriptQualityGate transcriptQualityGate;
    private final GeminiTextClient geminiTextClient;
    private final ClaudeClient claudeClient;
    private final BlindTestService blindTestService;
    private final AiQuizService aiQuizService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<Long, Map<String, String>> hiddenCompareModelsByEpisode = new ConcurrentHashMap<>();

    public CrossModelQuizPipelineService(
            EpisodeRepository episodeRepository, EpisodeTranscriptRepository transcriptRepository,
            QuizRepository quizRepository, QuestionRepository questionRepository,
            OpenQuestionRepository openQuestionRepository, TranscriptParserService transcriptParserService,
            TranscriptQualityGate transcriptQualityGate, GeminiTextClient geminiTextClient,
            ClaudeClient claudeClient, BlindTestService blindTestService,
            AiQuizService aiQuizService, ObjectMapper objectMapper) {
        this.episodeRepository = episodeRepository; this.transcriptRepository = transcriptRepository;
        this.quizRepository = quizRepository; this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository; this.transcriptParserService = transcriptParserService;
        this.transcriptQualityGate = transcriptQualityGate; this.geminiTextClient = geminiTextClient;
        this.claudeClient = claudeClient; this.blindTestService = blindTestService;
        this.aiQuizService = aiQuizService; this.objectMapper = objectMapper;
    }

    public record PipelineResult(int savedMcqs, int savedOpenQuestions, String writerModelUsed, String verifierModelUsed,
                                 boolean blindTestCompleted, boolean needsReview, boolean autoApproved) {}

    @Transactional
    public Optional<EpisodeTranscript> autoAcquireTranscriptIfMissing(Episode episode) {
        if (episode == null || episode.getId() == null) return Optional.empty();
        Optional<EpisodeTranscript> existing = transcriptRepository.findByEpisodeId(episode.getId());
        if (existing.isPresent()) return existing;
        Optional<String> rawOpt = aiQuizService.getYouTubeCaptionService().fetchCaptionsTranscript(episode.getYoutubeVideoId());
        if (rawOpt.isEmpty() || rawOpt.get().isBlank()) return Optional.empty();
        try {
            TranscriptParserService.ParsedTranscript parsed = transcriptParserService.parseSrtOrVtt(rawOpt.get());
            if (parsed.plainText().isBlank()) return Optional.empty();
            TranscriptQualityGate.QualityAssessment qa = transcriptQualityGate.evaluate(parsed, episode.getDurationSeconds());
            EpisodeTranscript entity = new EpisodeTranscript();
            entity.setEpisode(episode); entity.setSource("AUTOFETCH");
            entity.setCuesJson(transcriptParserService.toCuesJson(parsed.cues()));
            entity.setPlainText(parsed.plainText()); entity.setWordCount(parsed.wordCount());
            entity.setQualityStatus(qa.status()); entity.setQualityReport(transcriptQualityGate.toReportJson(qa));
            entity.setUseAnyway(!qa.passed()); entity.setUploadedAt(LocalDateTime.now());
            entity = transcriptRepository.save(entity);
            episode.setTranscriptText(parsed.plainText()); episodeRepository.save(episode);
            return Optional.of(entity);
        } catch (Exception ex) { return Optional.empty(); }
    }

    @Transactional
    public PipelineResult executePipeline(
            Long episodeId, String requestedWriter, String optionalTranscriptOverride,
            boolean autoTriggered, String sourceTag, BiConsumer<String, Integer> progressCallback) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        boolean explicitGemini = requestedWriter != null && requestedWriter.toLowerCase(Locale.ROOT).contains("gemini");
        boolean useClaudeWriter = claudeClient.isConfigured() && (!explicitGemini || !geminiTextClient.isConfigured());
        boolean useClaudeVerifier = !geminiTextClient.isConfigured() || (!useClaudeWriter && claudeClient.isConfigured());
        String writerModelName = useClaudeWriter ? claudeClient.getModel() : geminiTextClient.getModel();
        String verifierModelName = useClaudeVerifier ? claudeClient.getModel() : geminiTextClient.getModel();

        List<TranscriptChunk> chunks = resolveUsableTranscriptChunks(episode, optionalTranscriptOverride);
        if (chunks.isEmpty()) {
            try {
                if (progressCallback != null) progressCallback.accept("VIDEO_FALLBACK", 30);
                AiQuizService.GeneratedBankSummary v = aiQuizService.generateQuestionBankForEpisode(null, episodeId, null, autoTriggered, false);
                return new PipelineResult(v.savedDraftMcqs(), v.savedDraftOpenQuestions(), writerModelName, verifierModelName, false, false, autoTriggered);
            } catch (Exception videoEx) {
                Optional<EpisodeTranscript> fallbackEt = transcriptRepository.findByEpisodeId(episode.getId());
                if (fallbackEt.isPresent() && fallbackEt.get().getPlainText() != null && !fallbackEt.get().getPlainText().isBlank()) {
                    EpisodeTranscript et = fallbackEt.get();
                    chunks = transcriptParserService.buildMarkedChunks(
                            transcriptParserService.fromCuesJson(et.getCuesJson()), et.getPlainText(), episode.getDurationSeconds());
                } else throw videoEx;
            }
        }

        List<BlindTestedCandidate> allTestedMcqs = new ArrayList<>();
        List<CandidateOpen> allVerifiedOpens = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TranscriptChunk chunk = chunks.get(i);
            int basePct = (i * 80) / Math.max(1, chunks.size());
            if (progressCallback != null) progressCallback.accept("PASS_1_WRITER", basePct + 10);
            String pass1Sys = AiPromptTemplates.pass1TranscriptSystemInstruction(12, 2);
            String pass1User = "Episode #" + episode.getEpisodeNumber() + ": " + episode.getTitle()
                    + "\nChunk #" + chunk.chunkIndex() + " [" + chunk.startSeconds() + "s - " + chunk.endSeconds() + "s]\n"
                    + "Use ONLY timestamps near the [mm:ss] markers below.\n<episode_transcript>\n" + chunk.markedText() + "\n</episode_transcript>";
            ParsedCandidates pass1 = parsePass1Json(callModel(useClaudeWriter, pass1Sys, pass1User));
            List<CandidateMcq> timestampValidMcqs = pass1.mcqs().stream()
                    .filter(m -> transcriptParserService.isTimestampValidForChunk(m.evidenceTimestamp(), chunk))
                    .map(CrossModelQuizPipelineService::enforceCorrectOptionNeverLongest).filter(Objects::nonNull).toList();
            List<CandidateOpen> timestampValidOpens = pass1.opens().stream()
                    .filter(o -> o.evidenceTimestamp() == null || transcriptParserService.isTimestampValidForChunk(o.evidenceTimestamp(), chunk)).toList();
            if (timestampValidMcqs.isEmpty()) continue;
            if (progressCallback != null) progressCallback.accept("PASS_2_VERIFIER", basePct + 35);
            List<CandidateMcq> verifiedChunkMcqs = runPass2Verification(useClaudeVerifier, chunk.markedText(), timestampValidMcqs, timestampValidOpens, allVerifiedOpens);
            if (progressCallback != null) progressCallback.accept("PASS_3_BLIND_TEST", basePct + 60);
            allTestedMcqs.addAll(blindTestService.runBlindTestForChunk(verifiedChunkMcqs, chunk.chunkIndex(), useClaudeVerifier));
        }
        if (allTestedMcqs.isEmpty()) throw new IllegalStateException("No candidate questions passed timestamp and verification checks.");
        EpisodeBlindTestOutcome outcome = blindTestService.applySurvivalAndFallback(allTestedMcqs, TARGET_EPISODE_MCQ_COUNT);
        if (outcome.needsReview()) {
            episode.setNeedsReview(true);
            episodeRepository.save(episode);
        }
        boolean autoApproveNow = autoTriggered && !outcome.selectedQuestions().isEmpty();
        QuestionStatus targetStatus = autoApproveNow ? QuestionStatus.APPROVED : QuestionStatus.DRAFT;
        String effectiveSource = autoTriggered ? "AUTO" : (sourceTag != null ? sourceTag : "MANUAL");
        int savedMcqs = persistMcqsAndOpens(episode, outcome.selectedQuestions(), allVerifiedOpens, targetStatus, effectiveSource);
        if (progressCallback != null) progressCallback.accept("COMPLETED", 100);
        return new PipelineResult(savedMcqs, allVerifiedOpens.size(), writerModelName, verifierModelName, true, outcome.needsReview(), autoApproveNow);
    }

    @Transactional
    public Map<String, Object> compareModelsForEpisode(Long episodeId) {
        boolean claudeSetA = secureRandom.nextBoolean();
        PipelineResult resA = executePipeline(episodeId, claudeSetA ? "claude" : "gemini", null, false, "SET_A", null);
        PipelineResult resB = executePipeline(episodeId, claudeSetA ? "gemini" : "claude", null, false, "SET_B", null);
        hiddenCompareModelsByEpisode.put(episodeId, Map.of("SET_A", resA.writerModelUsed(), "SET_B", resB.writerModelUsed()));
        List<Question> all = questionRepository.findByEpisodeIdWithOptions(episodeId);
        List<QuestionBankAdminResponse.McqAdminItem> setA = all.stream().filter(q -> "SET_A".equals(q.getSource()) && q.getStatus() == QuestionStatus.DRAFT).map(QuestionBankAdminResponse.McqAdminItem::from).toList();
        List<QuestionBankAdminResponse.McqAdminItem> setB = all.stream().filter(q -> "SET_B".equals(q.getSource()) && q.getStatus() == QuestionStatus.DRAFT).map(QuestionBankAdminResponse.McqAdminItem::from).toList();
        return Map.of("episodeId", episodeId, "setA", setA, "setB", setB, "modelsHidden", true,
                "costWarning", "Compare Models runs both writer pipelines (Set A and Set B) and doubles token usage.");
    }

    @Transactional
    public Map<String, Object> chooseComparedSet(Long episodeId, String chosenSet) {
        String winner = "SET_B".equalsIgnoreCase(chosenSet) ? "SET_B" : "SET_A";
        String loser = "SET_A".equals(winner) ? "SET_B" : "SET_A";
        int approved = 0, retired = 0;
        for (Question q : questionRepository.findByEpisodeIdWithOptions(episodeId)) {
            if (winner.equals(q.getSource())) {
                q.setStatus(QuestionStatus.APPROVED); q.setActive(true); questionRepository.save(q); approved++;
            } else if (loser.equals(q.getSource())) {
                q.setStatus(QuestionStatus.RETIRED); q.setActive(false); questionRepository.save(q); retired++;
            }
        }
        Map<String, String> revealed = hiddenCompareModelsByEpisode.getOrDefault(
                episodeId, Map.of("SET_A", geminiTextClient.getModel(), "SET_B", claudeClient.getModel()));
        return Map.of("episodeId", episodeId, "chosenSet", winner, "approvedCount", approved, "retiredCount", retired, "revealedModels", revealed);
    }

    public static CandidateMcq enforceCorrectOptionNeverLongest(CandidateMcq mcq) {
        if (mcq == null || mcq.options() == null || mcq.options().size() != 4) return null;
        int maxDistractorLen = 0, correctLen = 0;
        for (CandidateOption o : mcq.options()) {
            String t = o.optionText() != null ? o.optionText().trim() : "";
            if (t.isEmpty() || t.toLowerCase(Locale.ROOT).contains("all of the above") || t.toLowerCase(Locale.ROOT).contains("none of the above")) return null;
            if (o.isCorrect()) correctLen = t.length();
            else maxDistractorLen = Math.max(maxDistractorLen, t.length());
        }
        if (correctLen <= maxDistractorLen) return mcq;
        List<CandidateOption> adjusted = new ArrayList<>(4);
        for (CandidateOption o : mcq.options()) {
            String t = o.optionText().trim();
            if (o.isCorrect() && t.length() > maxDistractorLen && maxDistractorLen >= 10) t = t.substring(0, maxDistractorLen).trim();
            adjusted.add(new CandidateOption(t, o.isCorrect()));
        }
        return new CandidateMcq(mcq.questionText(), mcq.type(), mcq.evidence(), mcq.evidenceTimestamp(), adjusted);
    }

    private List<TranscriptChunk> resolveUsableTranscriptChunks(Episode episode, String overrideText) {
        if (overrideText != null && !overrideText.isBlank()) {
            return transcriptParserService.buildMarkedChunks(List.of(), overrideText, episode.getDurationSeconds());
        }
        Optional<EpisodeTranscript> opt = transcriptRepository.findByEpisodeId(episode.getId());
        if (opt.isEmpty() && (episode.getTranscriptText() == null || episode.getTranscriptText().isBlank())) opt = autoAcquireTranscriptIfMissing(episode);
        if (opt.isPresent()) {
            EpisodeTranscript et = opt.get();
            return et.isUsableForGeneration() ? transcriptParserService.buildMarkedChunks(transcriptParserService.fromCuesJson(et.getCuesJson()), et.getPlainText(), episode.getDurationSeconds()) : List.of();
        }
        return (episode.getTranscriptText() != null && !episode.getTranscriptText().isBlank())
                ? transcriptParserService.buildMarkedChunks(List.of(), episode.getTranscriptText(), episode.getDurationSeconds()) : List.of();
    }

    private List<CandidateMcq> runPass2Verification(
            boolean useClaudeVerifier, String markedTranscript, List<CandidateMcq> mcqs,
            List<CandidateOpen> opens, List<CandidateOpen> verifiedOpensOut) {
        try {
            String sys = "Verify each candidate MCQ and open question against <episode_transcript>. Keep only items with one unambiguous answer supported by the transcript and an accurate timestamp. Return JSON: {\"verifiedMcqIndices\":[...],\"verifiedOpenIndices\":[...]}.";
            String user = "<episode_transcript>\n" + markedTranscript + "\n</episode_transcript>\n<candidates>\n"
                    + objectMapper.writeValueAsString(Map.of("mcqs", mcqs, "openQuestions", opens)) + "\n</candidates>";
            JsonNode root = GeminiJsonRepairSupport.parseLenientJson(objectMapper, callModel(useClaudeVerifier, sys, user));
            List<CandidateMcq> keptMcqs = new ArrayList<>();
            JsonNode mArr = root.path("verifiedMcqIndices");
            if (mArr.isArray()) {
                for (JsonNode n : mArr) { int idx = n.asInt(-1); if (idx >= 0 && idx < mcqs.size()) keptMcqs.add(mcqs.get(idx)); }
            } else keptMcqs.addAll(mcqs);
            JsonNode oArr = root.path("verifiedOpenIndices");
            if (oArr.isArray()) {
                for (JsonNode n : oArr) { int idx = n.asInt(-1); if (idx >= 0 && idx < opens.size()) verifiedOpensOut.add(opens.get(idx)); }
            } else verifiedOpensOut.addAll(opens);
            return keptMcqs.isEmpty() ? mcqs : keptMcqs;
        } catch (Exception ex) { verifiedOpensOut.addAll(opens); return mcqs; }
    }

    private int persistMcqsAndOpens(Episode episode, List<BlindTestedCandidate> selectedMcqs,
                                    List<CandidateOpen> opens, QuestionStatus status, String source) {
        Quiz quiz = quizRepository.findByEpisodeId(episode.getId()).orElseGet(() -> quizRepository.save(new Quiz(episode)));
        quiz.setPassingScorePercentage(100.0);
        int order = quiz.getQuestions().size() + 1, vocabCount = 0, saved = 0;
        for (BlindTestedCandidate btc : selectedMcqs) {
            CandidateMcq c = btc.candidate();
            if (c.type() == QuestionType.VOCABULARY && ++vocabCount > 2) continue;
            Question q = new Question(quiz, c.questionText(), order++);
            q.setType(c.type() != null ? c.type() : QuestionType.REASONING);
            q.setStatus(status); q.setActive(status == QuestionStatus.APPROVED);
            q.setEvidence(c.evidence()); q.setEvidenceTimestamp(c.evidenceTimestamp());
            q.setChunkIndex(btc.chunkIndex()); q.setSource(source);
            for (CandidateOption co : c.options()) q.getOptions().add(new QuestionOption(q, co.optionText(), co.isCorrect()));
            questionRepository.save(q);
            saved++;
        }
        for (CandidateOpen co : opens) {
            OpenQuestion oq = new OpenQuestion(episode, co.promptText(), co.evidence(), co.evidenceTimestamp(), status);
            oq.setActive(status == QuestionStatus.APPROVED); oq.setSource(source);
            openQuestionRepository.save(oq);
        }
        return saved;
    }

    private String callModel(boolean useClaude, String systemPrompt, String userPrompt) {
        if (useClaude && claudeClient.isConfigured()) return claudeClient.callMessagesJson(systemPrompt, userPrompt, 4096);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return geminiTextClient.callTextJson(systemPrompt, userPrompt);
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                boolean retryable = (ex instanceof TooManyRequestsException) || msg.contains("429") || msg.contains("503") || msg.contains("HTTP 5");
                if (retryable && attempt == 1) continue;
                if (claudeClient.isConfigured()) return claudeClient.callMessagesJson(systemPrompt, userPrompt, 4096);
                if (ex instanceof RuntimeException re) throw re;
                throw new IllegalStateException(ex);
            }
        }
        throw new IllegalStateException("AI model call failed.");
    }

    private record ParsedCandidates(List<CandidateMcq> mcqs, List<CandidateOpen> opens) {}

    private ParsedCandidates parsePass1Json(String raw) {
        try {
            JsonNode root = GeminiJsonRepairSupport.parseLenientJson(objectMapper, raw);
            List<CandidateMcq> mcqs = new ArrayList<>();
            for (JsonNode qn : root.path("mcqs")) {
                List<CandidateOption> opts = new ArrayList<>();
                for (JsonNode on : qn.path("options")) opts.add(new CandidateOption(on.path("optionText").asText(""), on.path("isCorrect").asBoolean(false)));
                QuestionType type = QuestionBankMutationHelper.parseTypeOrDefault(qn.path("type").asText("REASONING"));
                Integer ts = qn.hasNonNull("evidenceTimestamp") ? Integer.valueOf(qn.get("evidenceTimestamp").asInt()) : null;
                mcqs.add(new CandidateMcq(qn.path("questionText").asText(""), type, qn.path("evidence").asText(""), ts, opts));
            }
            List<CandidateOpen> opens = new ArrayList<>();
            for (JsonNode opn : root.path("openQuestions")) {
                Integer ts = opn.hasNonNull("evidenceTimestamp") ? Integer.valueOf(opn.get("evidenceTimestamp").asInt()) : null;
                opens.add(new CandidateOpen(opn.path("promptText").asText(""), opn.path("evidence").asText(""), ts));
            }
            return new ParsedCandidates(mcqs, opens);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Pass 1 JSON: " + ex.getMessage());
        }
    }
}
