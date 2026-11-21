// Stores admin-only written text sources, builds section chunks, applies section-start timestamp defaults, and routes pipeline prompts.
package com.arabicquest.service;

import com.arabicquest.model.Episode;
import com.arabicquest.model.EpisodeTranscript;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.EpisodeTranscriptRepository;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOpen;
import com.arabicquest.service.PipelineCandidateNormalizer.ChunkNormalizationResult;
import com.arabicquest.service.TranscriptParserService.Cue;
import com.arabicquest.service.TranscriptParserService.TranscriptChunk;
import com.arabicquest.service.WrittenTextSourceParser.ParsedSection;
import com.arabicquest.service.WrittenTextSourceParser.ParsedWrittenSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WrittenTextSourceService {

    public static final String SOURCE_TEXT = "TEXT_SOURCE";
    public static final int MAX_TEXT_SOURCE_BYTES = WrittenTextSourceParser.MAX_TEXT_SOURCE_BYTES;
    private static final Pattern INLINE_MARKER = Pattern.compile("\\[(?:(\\d{1,2}):)?(\\d{1,3}):(\\d{2})]");

    private final EpisodeRepository episodeRepository;
    private final EpisodeTranscriptRepository transcriptRepository;
    private final TranscriptParserService transcriptParserService;
    private final TranscriptQualityGate transcriptQualityGate;

    public WrittenTextSourceService(
            EpisodeRepository episodeRepository, EpisodeTranscriptRepository transcriptRepository,
            TranscriptParserService transcriptParserService, TranscriptQualityGate transcriptQualityGate) {
        this.episodeRepository = episodeRepository;
        this.transcriptRepository = transcriptRepository;
        this.transcriptParserService = transcriptParserService;
        this.transcriptQualityGate = transcriptQualityGate;
    }

    public String decodeStrictUtf8(byte[] bytes) {
        return WrittenTextSourceParser.decodeStrictUtf8(bytes);
    }

    public static Integer parseStartTimeSeconds(String line) {
        return WrittenTextSourceParser.parseStartTimeSeconds(line);
    }

    public ParsedWrittenSource parseWrittenSource(String rawText, int episodeDurationSeconds) {
        return WrittenTextSourceParser.parse(rawText, episodeDurationSeconds);
    }

    @Transactional
    public Map<String, Object> previewOrSave(Long episodeId, String rawText, String sourceUrl, boolean confirmSave) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        ParsedWrittenSource parsed = parseWrittenSource(rawText, episode.getDurationSeconds());
        TranscriptQualityGate.QualityAssessment qa = transcriptQualityGate.evaluateWrittenTextSource(
                parsed.plainText(), parsed.wordCount(), parsed.sections().size());

        String cleanUrl = (sourceUrl != null && !sourceUrl.isBlank()) ? sourceUrl.trim() : null;
        if (cleanUrl != null && cleanUrl.length() > 1000) cleanUrl = cleanUrl.substring(0, 1000);

        boolean saved = false;
        if (confirmSave) {
            EpisodeTranscript entity = transcriptRepository.findByEpisodeId(episodeId).orElseGet(EpisodeTranscript::new);
            entity.setEpisode(episode);
            entity.setSource(SOURCE_TEXT);
            entity.setCuesJson(transcriptParserService.toCuesJson(parsed.cues()));
            entity.setPlainText(parsed.plainText());
            entity.setWordCount(parsed.wordCount());
            entity.setQualityStatus(qa.status());
            entity.setQualityReport(transcriptQualityGate.toReportJson(qa));
            entity.setUseAnyway(false);
            entity.setSourceUrl(cleanUrl);
            entity.setUploadedAt(LocalDateTime.now());
            transcriptRepository.save(entity);
            // Keep Episode.transcriptText null for TEXT_SOURCE so it only lives in admin-only EpisodeTranscript
            episode.setTranscriptText(null);
            episodeRepository.save(episode);
            saved = true;
        }

        List<Map<String, Object>> sectionRows = new ArrayList<>();
        for (ParsedSection s : parsed.sections()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", s.index());
            row.put("heading", s.heading());
            row.put("startSeconds", s.startSeconds());
            row.put("startTimeFormatted", TranscriptParserService.formatMmSs(s.startSeconds()));
            row.put("endSeconds", s.endSeconds());
            row.put("wordCount", s.wordCount());
            row.put("hasTakeaways", !s.keyTakeaways().isEmpty());
            row.put("hasReferences", !s.references().isEmpty());
            sectionRows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("episodeId", episode.getId());
        out.put("episodeNumber", episode.getEpisodeNumber());
        out.put("episodeTitle", episode.getTitle());
        out.put("source", SOURCE_TEXT);
        out.put("sourceUrl", cleanUrl != null ? cleanUrl : "");
        out.put("sectionCount", parsed.sections().size());
        out.put("wordCount", parsed.wordCount());
        out.put("qualityStatus", qa.status());
        out.put("qualityReasons", qa.reasons());
        out.put("arabicScriptRatio", qa.arabicScriptRatio());
        out.put("confirmed", confirmSave);
        out.put("saved", saved);
        out.put("sections", sectionRows);
        return out;
    }

    public boolean isTextSource(Long episodeId, String optionalTranscriptOverride) {
        if (optionalTranscriptOverride != null && !optionalTranscriptOverride.isBlank()) return false;
        return transcriptRepository.findByEpisodeId(episodeId).map(EpisodeTranscript::isTextSource).orElse(false);
    }

    public List<TranscriptChunk> buildChunksFromTranscript(EpisodeTranscript et, int episodeDurationSeconds) {
        List<Cue> cues = transcriptParserService.fromCuesJson(et.getCuesJson());
        if (cues.isEmpty()) {
            return transcriptParserService.buildMarkedChunks(List.of(), et.getPlainText(), episodeDurationSeconds);
        }
        List<TranscriptChunk> chunks = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            int start = Math.max(0, c.startSeconds());
            int end = Math.max(start + 1, c.endSeconds());
            List<Integer> markers = new ArrayList<>();
            markers.add(start);
            Matcher m = INLINE_MARKER.matcher(c.text());
            while (m.find()) {
                int h = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
                int sec = h * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
                if (sec >= start && sec <= end && !markers.contains(sec)) markers.add(sec);
            }
            String marked = "[" + TranscriptParserService.formatMmSs(start) + "] " + c.text();
            chunks.add(new TranscriptChunk(i, start, end, marked, markers));
        }
        return chunks;
    }

    public ChunkNormalizationResult applySectionStartDefaults(ChunkNormalizationResult norm, TranscriptChunk chunk) {
        List<CandidateMcq> fixedMcqs = norm.keptMcqs().stream().map(m -> {
            if (m.evidenceTimestamp() != null) return m;
            return new CandidateMcq(m.questionText(), m.type(), stripUnverifiedTag(m.evidence()), chunk.startSeconds(), m.options());
        }).toList();
        List<CandidateOpen> fixedOpens = norm.keptOpens().stream().map(o -> {
            if (o.evidenceTimestamp() != null) return o;
            return new CandidateOpen(o.promptText(), stripUnverifiedTag(o.evidence()), chunk.startSeconds());
        }).toList();
        return new ChunkNormalizationResult(fixedMcqs, fixedOpens, norm.counts(), norm.softFlaggedOptionCount(), 0);
    }

    public String pass1SystemInstruction(boolean isTextSource, int targetMcqs, int targetOpens) {
        return isTextSource
                ? AiPromptTemplates.pass1WrittenSourceSystemInstruction(targetMcqs, targetOpens)
                : AiPromptTemplates.pass1TranscriptSystemInstruction(targetMcqs, targetOpens);
    }

    public String pass2SystemInstruction(boolean isTextSource) {
        return isTextSource
                ? AiPromptTemplates.pass2WrittenSourceSystemInstruction()
                : AiPromptTemplates.pass2TranscriptSystemInstruction();
    }

    public String buildPass1UserPrompt(boolean isTextSource, Episode episode, TranscriptChunk chunk) {
        String tag = isTextSource ? "episode_written_source" : "episode_transcript";
        String label = isTextSource ? "Section" : "Chunk";
        return "Episode #" + episode.getEpisodeNumber() + ": " + episode.getTitle() + "\n"
                + label + " #" + chunk.chunkIndex() + " [" + chunk.startSeconds() + "s - " + chunk.endSeconds() + "s]\n"
                + "Set \"evidenceMarker\" to the exact [mm:ss] marker from <" + tag + "> where each fact appears.\n<"
                + tag + ">\n" + chunk.markedText() + "\n</" + tag + ">";
    }

    public String buildPass2UserPrompt(boolean isTextSource, String markedText, String candidatesJson) {
        String tag = isTextSource ? "episode_written_source" : "episode_transcript";
        return "<" + tag + ">\n" + markedText + "\n</" + tag + ">\n<candidates>\n" + candidatesJson + "\n</candidates>";
    }

    private static String stripUnverifiedTag(String evidence) {
        return evidence == null ? "" : evidence.replace(PipelineCandidateNormalizer.UNVERIFIED_TIMESTAMP_TAG, "").trim();
    }
}
