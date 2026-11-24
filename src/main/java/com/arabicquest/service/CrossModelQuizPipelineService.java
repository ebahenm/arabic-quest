// Runs Pass 1 writer, Pass 2 cross-model verifier, timestamp/option guards, Pass 3 blind test, and automatic caption acquisition.
package com.arabicquest.service;

import com.arabicquest.dto.QuestionBankAdminResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.AiQuizService.*;
import com.arabicquest.service.BlindTestService.*;
import com.arabicquest.service.ClaudeClient.ClaudeMessageResponse;
import com.arabicquest.service.PipelineCandidateNormalizer.*;
import com.arabicquest.service.PipelinePersistenceHelper.PersistOutcome;
import com.arabicquest.service.PipelinePersistenceHelper.PersistableOpen;
import com.arabicquest.service.TranscriptParserService.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

@Service
public class CrossModelQuizPipelineService {

    private static final Logger log = LoggerFactory.getLogger(CrossModelQuizPipelineService.class);
    public static final int TARGET_EPISODE_MCQ_COUNT = 22;
    public static final int PASS1_MAX_TOKENS = 8192;

    private final EpisodeRepository episodeRepository;
    private final EpisodeTranscriptRepository transcriptRepository;
    private final QuestionRepository questionRepository;
    private final TranscriptParserService transcriptParserService;
    private final TranscriptQualityGate transcriptQualityGate;
    private final GeminiTextClient geminiTextClient;
    private final ClaudeClient claudeClient;
    private final BlindTestService blindTestService;
    private final AiQuizService aiQuizService;
    private final ObjectMapper objectMapper;
    private final WrittenTextSourceService writtenTextSourceService;
    private final PipelinePersistenceHelper persistenceHelper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<Long, Map<String, String>> hiddenCompareModelsByEpisode = new ConcurrentHashMap<>();

    public CrossModelQuizPipelineService(
            EpisodeRepository episodeRepository, EpisodeTranscriptRepository transcriptRepository,
            QuestionRepository questionRepository, TranscriptParserService transcriptParserService,
            TranscriptQualityGate transcriptQualityGate, GeminiTextClient geminiTextClient,
            ClaudeClient claudeClient, BlindTestService blindTestService,
            AiQuizService aiQuizService, ObjectMapper objectMapper,
            WrittenTextSourceService writtenTextSourceService, PipelinePersistenceHelper persistenceHelper) {
        this.episodeRepository = episodeRepository; this.transcriptRepository = transcriptRepository;
        this.questionRepository = questionRepository; this.transcriptParserService = transcriptParserService;
        this.transcriptQualityGate = transcriptQualityGate; this.geminiTextClient = geminiTextClient;
        this.claudeClient = claudeClient; this.blindTestService = blindTestService;
        this.aiQuizService = aiQuizService; this.objectMapper = objectMapper;
        this.writtenTextSourceService = writtenTextSourceService; this.persistenceHelper = persistenceHelper;
    }

    public record PipelineResult(int savedMcqs, int savedOpenQuestions, String writerModelUsed, String verifierModelUsed,
                                 boolean blindTestCompleted, boolean needsReview, boolean autoApproved) {}

    private record Pass2Outcome(List<CandidateMcq> verifiedMcqs, boolean parsedCleanly) {}

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
            EpisodeTranscript e = new EpisodeTranscript();
            e.setEpisode(episode); e.setSource("AUTOFETCH"); e.setCuesJson(transcriptParserService.toCuesJson(parsed.cues()));
            e.setPlainText(parsed.plainText()); e.setWordCount(parsed.wordCount()); e.setQualityStatus(qa.status());
            e.setQualityReport(transcriptQualityGate.toReportJson(qa)); e.setUseAnyway(!qa.passed()); e.setUploadedAt(LocalDateTime.now());
            e = transcriptRepository.save(e);
            episode.setTranscriptText(parsed.plainText()); episodeRepository.save(episode);
            return Optional.of(e);
        } catch (Exception ex) { return Optional.empty(); }
    }

    public PipelineResult executePipeline(
            Long episodeId, String requestedWriter, String optionalTranscriptOverride,
            boolean autoTriggered, String sourceTag, BiConsumer<String, Integer> progressCallback) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        boolean explicitGemini = requestedWriter != null && requestedWriter.toLowerCase(Locale.ROOT).contains("gemini");
        boolean useClaudeWriter = claudeClient.isConfigured() && (!explicitGemini || !geminiTextClient.isConfigured());
        boolean useClaudeVerifier = !geminiTextClient.isConfigured() || (!useClaudeWriter && claudeClient.isConfigured());
        AtomicBoolean geminiUnavailable = new AtomicBoolean(false);
        AtomicBoolean claudeUnavailable = new AtomicBoolean(false);
        String writerModelName = useClaudeWriter ? claudeClient.getModel() : geminiTextClient.getModel();
        String verifierModelName = useClaudeVerifier ? claudeClient.getModel() : geminiTextClient.getModel();

        boolean isTextSource = writtenTextSourceService.isTextSource(episode.getId(), optionalTranscriptOverride);
        List<TranscriptChunk> chunks = resolveUsableTranscriptChunks(episode, optionalTranscriptOverride);
        if (chunks.isEmpty()) {
            Optional<EpisodeTranscript> fallbackEt = transcriptRepository.findByEpisodeId(episode.getId());
            if (fallbackEt.isPresent() && fallbackEt.get().getPlainText() != null && !fallbackEt.get().getPlainText().isBlank()) {
                EpisodeTranscript et = fallbackEt.get();
                chunks = et.isTextSource() ? writtenTextSourceService.buildChunksFromTranscript(et, episode.getDurationSeconds())
                        : transcriptParserService.buildMarkedChunks(transcriptParserService.fromCuesJson(et.getCuesJson()), et.getPlainText(), episode.getDurationSeconds());
            } else {
                if (progressCallback != null) progressCallback.accept("VIDEO_FALLBACK", 30);
                AiQuizService.GeneratedBankSummary v = aiQuizService.generateQuestionBankForEpisode(null, episodeId, null, autoTriggered, false);
                return new PipelineResult(v.savedDraftMcqs(), v.savedDraftOpenQuestions(), writerModelName, verifierModelName, false, true, false);
            }
        }

        List<Cue> episodeCues = resolveEpisodeCues(episode, optionalTranscriptOverride);
        List<BlindTestedCandidate> allTestedMcqs = new ArrayList<>();
        List<PersistableOpen> allVerifiedOpens = new ArrayList<>();
        Map<Integer, Boolean> pass2ParsedByChunk = new HashMap<>();
        StageCounts totalCounts = new StageCounts();
        boolean anySoftFlagged = false;
        int totalChunks = Math.max(1, chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            TranscriptChunk chunk = chunks.get(i);
            String chunkLabel = (isTextSource ? "Section " : "Chunk ") + (i + 1) + "/" + totalChunks;
            int p1Pct = totalChunks == 1 ? 10 : 10 + (i * 75) / totalChunks;
            int p2Pct = totalChunks == 1 ? 40 : 10 + ((i * 75) + 25) / totalChunks;
            int p3Pct = totalChunks == 1 ? 65 : 10 + ((i * 75) + 50) / totalChunks;
            int donePct = totalChunks == 1 ? 85 : 10 + ((i + 1) * 75) / totalChunks;
            if (progressCallback != null) progressCallback.accept(chunkLabel + " • PASS_1_WRITER", p1Pct);
            ParsedPass1Batch pass1 = runPass1WithRetryOnTruncation(useClaudeWriter, geminiUnavailable, claudeUnavailable, episode, chunk, isTextSource);
            ChunkNormalizationResult norm = PipelineCandidateNormalizer.normalizeChunkCandidates(pass1, chunk, episodeCues, transcriptParserService);
            if (isTextSource) norm = writtenTextSourceService.applySectionStartDefaults(norm, chunk);
            if (norm.softFlaggedOptionCount() > 0 || norm.unverifiedTimestampCount() > 0) anySoftFlagged = true;

            List<CandidateMcq> verifiedChunkMcqs = List.of();
            boolean chunkPass2Parsed = false;
            if (!norm.keptMcqs().isEmpty() || !norm.keptOpens().isEmpty()) {
                if (progressCallback != null) progressCallback.accept(chunkLabel + " • PASS_2_VERIFIER", p2Pct);
                Pass2Outcome p2 = runPass2Verification(useClaudeVerifier, geminiUnavailable, claudeUnavailable, chunk.markedText(), norm.keptMcqs(), norm.keptOpens(), allVerifiedOpens, isTextSource);
                verifiedChunkMcqs = p2.verifiedMcqs();
                chunkPass2Parsed = p2.parsedCleanly();
            }
            pass2ParsedByChunk.put(chunk.chunkIndex(), chunkPass2Parsed);
            norm.counts().pass2Verified = verifiedChunkMcqs.size();
            if (!verifiedChunkMcqs.isEmpty()) {
                if (progressCallback != null) progressCallback.accept(chunkLabel + " • PASS_3_BLIND_TEST", p3Pct);
                List<BlindTestedCandidate> chunkTested = blindTestService.runBlindTestForChunk(verifiedChunkMcqs, chunk.chunkIndex(), useClaudeVerifier, geminiUnavailable, claudeUnavailable);
                norm.counts().pass3Survived = (int) chunkTested.stream().filter(BlindTestedCandidate::survivedBlindTest).count();
                allTestedMcqs.addAll(chunkTested);
            }
            if (progressCallback != null) progressCallback.accept(chunkLabel + " • VERIFIED", donePct);
            totalCounts.add(norm.counts());
            log.info("Pipeline episode={} chunk=#{} [{}s-{}s]: {} (softFlaggedOptions={}, unverifiedTimestamps={}, pass2Parsed={})",
                    episodeId, chunk.chunkIndex(), chunk.startSeconds(), chunk.endSeconds(),
                    norm.counts().formatSummary(), norm.softFlaggedOptionCount(), norm.unverifiedTimestampCount(), chunkPass2Parsed);
        }
        if (allTestedMcqs.isEmpty()) {
            String msg = "No candidate questions passed timestamp and verification checks [" + totalCounts.formatSummary() + "].";
            log.warn("Pipeline failed for episode={}: {}", episodeId, msg);
            throw new IllegalStateException(msg);
        }

        EpisodeBlindTestOutcome outcome = blindTestService.applySurvivalAndFallback(allTestedMcqs, TARGET_EPISODE_MCQ_COUNT);
        boolean needsReview = outcome.needsReview() || anySoftFlagged;
        String effectiveSource = autoTriggered ? "AUTO" : (sourceTag != null ? sourceTag : "MANUAL");
        String effectiveWriter = claudeUnavailable.get() ? geminiTextClient.getModel() : (geminiUnavailable.get() ? claudeClient.getModel() : writerModelName);
        String effectiveVerifier = claudeUnavailable.get() ? geminiTextClient.getModel() : (geminiUnavailable.get() ? claudeClient.getModel() : verifierModelName);

        PersistOutcome persisted = persistenceHelper.persistResultsTransactionally(
                episodeId, autoTriggered, needsReview, outcome.selectedQuestions(),
                pass2ParsedByChunk, allVerifiedOpens, effectiveSource, effectiveWriter, effectiveVerifier);
        if (progressCallback != null) progressCallback.accept("COMPLETED", 100);
        return new PipelineResult(persisted.savedMcqs(), persisted.savedOpens(), effectiveWriter, effectiveVerifier, true, needsReview, persisted.anyApproved());
    }

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

    public Map<String, Object> chooseComparedSet(Long episodeId, String chosenSet) {
        Map<String, String> revealed = hiddenCompareModelsByEpisode.getOrDefault(episodeId, Map.of("SET_A", geminiTextClient.getModel(), "SET_B", claudeClient.getModel()));
        return persistenceHelper.chooseComparedSet(episodeId, chosenSet, revealed);
    }

    public static CandidateMcq enforceCorrectOptionNeverLongest(CandidateMcq mcq) {
        return PipelineCandidateNormalizer.enforceOptionHardRulesOnly(mcq);
    }

    private ParsedPass1Batch runPass1WithRetryOnTruncation(
            boolean useClaudeWriter, AtomicBoolean geminiUnavailable, AtomicBoolean claudeUnavailable,
            Episode episode, TranscriptChunk chunk, boolean isTextSource) {
        String pass1User = writtenTextSourceService.buildPass1UserPrompt(isTextSource, episode, chunk);
        ClaudeMessageResponse firstResp = callModelDetailed(useClaudeWriter, geminiUnavailable, claudeUnavailable, writtenTextSourceService.pass1SystemInstruction(isTextSource, 12, 2), pass1User, PASS1_MAX_TOKENS);
        if (!firstResp.isMaxTokensTruncated()) {
            try {
                ParsedPass1Batch parsed = PipelineCandidateNormalizer.parsePass1Node(GeminiJsonRepairSupport.parseLenientJson(objectMapper, firstResp.text()));
                if (!parsed.mcqs().isEmpty()) return parsed;
            } catch (Exception ex) { log.warn("Pass 1 chunk #{} JSON parse failed (stop_reason={}); retrying smaller batch (6+1): {}", chunk.chunkIndex(), firstResp.stopReason(), ex.getMessage()); }
        } else { log.warn("Pass 1 chunk #{} hit stop_reason=max_tokens (output_tokens={}); retrying smaller batch (6+1).", chunk.chunkIndex(), firstResp.outputTokens()); }
        ClaudeMessageResponse retryResp = callModelDetailed(useClaudeWriter, geminiUnavailable, claudeUnavailable, writtenTextSourceService.pass1SystemInstruction(isTextSource, 6, 1), pass1User, PASS1_MAX_TOKENS);
        try { return PipelineCandidateNormalizer.parsePass1Node(GeminiJsonRepairSupport.parseLenientJson(objectMapper, retryResp.text())); }
        catch (Exception ex) { throw new IllegalStateException("Failed to parse Pass 1 JSON after smaller-batch retry: " + ex.getMessage()); }
    }

    private List<Cue> resolveEpisodeCues(Episode episode, String overrideText) {
        if (overrideText != null && !overrideText.isBlank()) return transcriptParserService.parseSrtOrVtt(overrideText).cues();
        return transcriptRepository.findByEpisodeId(episode.getId()).map(et -> transcriptParserService.fromCuesJson(et.getCuesJson())).orElse(List.of());
    }

    private List<TranscriptChunk> resolveUsableTranscriptChunks(Episode episode, String overrideText) {
        if (overrideText != null && !overrideText.isBlank()) {
            TranscriptParserService.ParsedTranscript p = transcriptParserService.parseSrtOrVtt(overrideText);
            return transcriptParserService.buildMarkedChunks(p.cues(), p.plainText(), episode.getDurationSeconds());
        }
        Optional<EpisodeTranscript> opt = transcriptRepository.findByEpisodeId(episode.getId());
        if (opt.isEmpty() && (episode.getTranscriptText() == null || episode.getTranscriptText().isBlank())) opt = autoAcquireTranscriptIfMissing(episode);
        if (opt.isPresent()) {
            EpisodeTranscript et = opt.get();
            if (!et.isUsableForGeneration()) return List.of();
            return et.isTextSource() ? writtenTextSourceService.buildChunksFromTranscript(et, episode.getDurationSeconds())
                    : transcriptParserService.buildMarkedChunks(transcriptParserService.fromCuesJson(et.getCuesJson()), et.getPlainText(), episode.getDurationSeconds());
        }
        return (episode.getTranscriptText() != null && !episode.getTranscriptText().isBlank())
                ? transcriptParserService.buildMarkedChunks(List.of(), episode.getTranscriptText(), episode.getDurationSeconds()) : List.of();
    }

    private Pass2Outcome runPass2Verification(
            boolean useClaudeVerifier, AtomicBoolean geminiUnavailable, AtomicBoolean claudeUnavailable,
            String markedTranscript, List<CandidateMcq> mcqs, List<CandidateOpen> opens,
            List<PersistableOpen> verifiedOpensOut, boolean isTextSource) {
        try {
            String user = writtenTextSourceService.buildPass2UserPrompt(isTextSource, markedTranscript, objectMapper.writeValueAsString(Map.of("mcqs", mcqs, "openQuestions", opens)));
            String raw = callModelDetailed(useClaudeVerifier, geminiUnavailable, claudeUnavailable, writtenTextSourceService.pass2SystemInstruction(isTextSource), user, 4096).text();
            JsonNode root = GeminiJsonRepairSupport.parseLenientJson(objectMapper, raw);
            JsonNode mArr = root.path("verifiedMcqIndices"), oArr = root.path("verifiedOpenIndices");
            if (!mArr.isArray() && !oArr.isArray()) {
                log.warn("Pass 2 response missing verifiedMcqIndices/verifiedOpenIndices; keeping {} MCQs and {} opens as unverified DRAFT.", mcqs.size(), opens.size());
                opens.forEach(o -> verifiedOpensOut.add(new PersistableOpen(o, false)));
                return new Pass2Outcome(mcqs, false);
            }
            List<CandidateMcq> keptMcqs = new ArrayList<>();
            if (mArr.isArray()) { for (JsonNode n : mArr) { int idx = n.asInt(-1); if (idx >= 0 && idx < mcqs.size()) keptMcqs.add(mcqs.get(idx)); } }
            else keptMcqs.addAll(mcqs);
            if (oArr.isArray()) { for (JsonNode n : oArr) { int idx = n.asInt(-1); if (idx >= 0 && idx < opens.size()) verifiedOpensOut.add(new PersistableOpen(opens.get(idx), true)); } }
            else opens.forEach(o -> verifiedOpensOut.add(new PersistableOpen(o, false)));
            return new Pass2Outcome(keptMcqs, mArr.isArray());
        } catch (Exception ex) {
            if (claudeUnavailable.get() && geminiUnavailable.get()) {
                throw (ex instanceof RuntimeException re) ? re : new IllegalStateException(ex);
            }
            log.warn("Pass 2 response could not be parsed; keeping {} MCQ and {} open candidates as DRAFT: {}", mcqs.size(), opens.size(), ex.getMessage());
            opens.forEach(o -> verifiedOpensOut.add(new PersistableOpen(o, false)));
            return new Pass2Outcome(mcqs, false);
        }
    }

    private ClaudeMessageResponse callModelDetailed(
            boolean useClaude, AtomicBoolean geminiUnavailable, AtomicBoolean claudeUnavailable,
            String systemPrompt, String userPrompt, int maxTokens) {
        return PipelineModelFallbackHelper.callModelDetailed(
                claudeClient, geminiTextClient, useClaude, geminiUnavailable, claudeUnavailable,
                systemPrompt, userPrompt, maxTokens, 2, 0L);
    }
}
