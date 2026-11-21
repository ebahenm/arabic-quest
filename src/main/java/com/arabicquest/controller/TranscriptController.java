// Admin REST controller for previewing, confirming, and inspecting SRT/VTT episode transcripts and quality gate status.
package com.arabicquest.controller;

import com.arabicquest.model.Episode;
import com.arabicquest.model.EpisodeTranscript;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.EpisodeTranscriptRepository;
import com.arabicquest.service.TranscriptParserService;
import com.arabicquest.service.TranscriptQualityGate;
import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin")
public class TranscriptController {

    public static final int MAX_BULK_FILES = 100;
    private static final Pattern EPISODE_TAG_PATTERN = Pattern.compile("(?i)(?:ep(?:isode)?|حلقة|الحلقة)[\\s_\\-.#]*(\\d{1,3})");
    private static final Pattern NUMBER_TOKEN_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,3})(?!\\d)");

    private final EpisodeRepository episodeRepository;
    private final EpisodeTranscriptRepository transcriptRepository;
    private final TranscriptParserService parserService;
    private final TranscriptQualityGate qualityGate;

    public TranscriptController(
            EpisodeRepository episodeRepository,
            EpisodeTranscriptRepository transcriptRepository,
            TranscriptParserService parserService,
            TranscriptQualityGate qualityGate) {
        this.episodeRepository = episodeRepository;
        this.transcriptRepository = transcriptRepository;
        this.parserService = parserService;
        this.qualityGate = qualityGate;
    }

    public record FileUploadItem(@JsonAlias({"filename", "name"}) String fileName, String content, String base64Bytes, Long episodeId) {}
    public record BulkTranscriptRequest(Long playlistId, Long episodeId, boolean confirmSave, List<FileUploadItem> files) {}

    @Transactional
    @PostMapping(value = "/transcripts/upload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> uploadTranscriptsJson(@RequestBody BulkTranscriptRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("At least one .srt or .vtt file is required.");
        }
        if (request.files().size() > MAX_BULK_FILES) {
            throw new IllegalArgumentException("Bulk upload exceeds maximum of 100 files.");
        }
        List<RawFileInput> inputs = new ArrayList<>();
        for (FileUploadItem item : request.files()) {
            byte[] bytes = (item.base64Bytes() != null && !item.base64Bytes().isBlank())
                    ? Base64.getDecoder().decode(item.base64Bytes())
                    : (item.content() != null ? item.content().getBytes(StandardCharsets.UTF_8) : new byte[0]);
            inputs.add(new RawFileInput(item.fileName(), bytes, item.episodeId()));
        }
        return ResponseEntity.ok(processFiles(inputs, request.playlistId(), request.episodeId(), request.confirmSave()));
    }

    @Transactional
    @PostMapping(value = "/transcripts/upload-multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadTranscriptsMultipart(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "playlistId", required = false) Long playlistId,
            @RequestParam(value = "episodeId", required = false) Long episodeId,
            @RequestParam(value = "confirmSave", defaultValue = "false") boolean confirmSave) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one .srt or .vtt file is required.");
        }
        if (files.size() > MAX_BULK_FILES) {
            throw new IllegalArgumentException("Bulk upload exceeds maximum of 100 files.");
        }
        List<RawFileInput> inputs = new ArrayList<>();
        for (MultipartFile mf : files) {
            inputs.add(new RawFileInput(mf.getOriginalFilename(), mf.getBytes(), null));
        }
        return ResponseEntity.ok(processFiles(inputs, playlistId, episodeId, confirmSave));
    }

    @Transactional(readOnly = true)
    @GetMapping("/episodes/{episodeId}/transcript")
    public ResponseEntity<Map<String, Object>> getEpisodeTranscriptStatus(@PathVariable Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        Optional<EpisodeTranscript> opt = transcriptRepository.findByEpisodeId(episodeId);
        if (opt.isEmpty()) {
            return ResponseEntity.ok(Map.of("episodeId", episode.getId(), "hasTranscript", false,
                    "qualityStatus", "NONE", "useAnyway", false, "wordCount", 0, "reasons", List.of()));
        }
        return ResponseEntity.ok(toStatusMap(opt.get()));
    }

    @Transactional
    @PostMapping("/episodes/{episodeId}/transcript/use-anyway")
    public ResponseEntity<Map<String, Object>> markTranscriptUseAnyway(
            @PathVariable Long episodeId, @RequestBody(required = false) Map<String, Object> body) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        EpisodeTranscript transcript = transcriptRepository.findByEpisodeId(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("No uploaded transcript found for this episode."));
        boolean enable = body == null || !body.containsKey("useAnyway") || Boolean.TRUE.equals(body.get("useAnyway"));
        transcript.setUseAnyway(enable);
        transcriptRepository.save(transcript);
        episode.setTranscriptText((!transcript.isTextSource() && transcript.isUsableForGeneration()) ? transcript.getPlainText() : null);
        episodeRepository.save(episode);
        return ResponseEntity.ok(toStatusMap(transcript));
    }

    private record RawFileInput(String clientName, byte[] bytes, Long itemEpisodeId) {}
    private record MatchResult(Episode episode, String status, String reason) {}

    private Map<String, Object> processFiles(List<RawFileInput> inputs, Long playlistId, Long explicitEpisodeId, boolean confirmSave) {
        List<Episode> candidateEpisodes = (playlistId != null)
                ? episodeRepository.findNonArchivedByPlaylistIdOrderBySortOrderAsc(playlistId)
                : episodeRepository.findAllNonArchivedOrderByCourseAndSortOrderAsc();
        List<Map<String, Object>> items = new ArrayList<>();
        int matchedCount = 0, savedCount = 0, rejectedCount = 0;

        for (RawFileInput input : inputs) {
            String safeLabel = sanitizeFileLabel(input.clientName());
            String lower = safeLabel.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".srt") && !lower.endsWith(".vtt")) {
                rejectedCount++;
                items.add(buildRejectedRow(safeLabel, "REJECTED_EXTENSION", "Only .srt and .vtt files are allowed."));
                continue;
            }
            String utf8Text;
            try {
                utf8Text = parserService.decodeStrictUtf8(input.bytes());
            } catch (IllegalArgumentException ex) {
                rejectedCount++;
                items.add(buildRejectedRow(safeLabel, "REJECTED_INVALID_FILE", ex.getMessage()));
                continue;
            }
            Long targetEpId = input.itemEpisodeId() != null ? input.itemEpisodeId() : (inputs.size() == 1 ? explicitEpisodeId : null);
            MatchResult match = matchEpisode(safeLabel, candidateEpisodes, targetEpId);
            if (match.episode() == null) {
                rejectedCount++;
                items.add(buildRejectedRow(safeLabel, match.status(), match.reason()));
                continue;
            }
            TranscriptParserService.ParsedTranscript parsed;
            try {
                parsed = parserService.parseSrtOrVtt(utf8Text);
            } catch (IllegalArgumentException ex) {
                rejectedCount++;
                items.add(buildRejectedRow(safeLabel, "REJECTED_PARSE_ERROR", ex.getMessage()));
                continue;
            }
            matchedCount++;
            Episode ep = match.episode();
            TranscriptQualityGate.QualityAssessment qa = qualityGate.evaluate(parsed, ep.getDurationSeconds());
            boolean saved = false;
            if (confirmSave) {
                EpisodeTranscript entity = transcriptRepository.findByEpisodeId(ep.getId()).orElseGet(EpisodeTranscript::new);
                entity.setEpisode(ep);
                entity.setSource("UPLOAD");
                entity.setCuesJson(parserService.toCuesJson(parsed.cues()));
                entity.setPlainText(parsed.plainText());
                entity.setWordCount(parsed.wordCount());
                entity.setQualityStatus(qa.status());
                entity.setQualityReport(qualityGate.toReportJson(qa));
                entity.setUseAnyway(false);
                entity.setUploadedAt(LocalDateTime.now());
                transcriptRepository.save(entity);
                ep.setTranscriptText(qa.passed() ? parsed.plainText() : null);
                episodeRepository.save(ep);
                saved = true;
                savedCount++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fileName", safeLabel);
            row.put("matchStatus", "MATCHED");
            row.put("episodeId", ep.getId());
            row.put("episodeNumber", ep.getEpisodeNumber());
            row.put("episodeTitle", ep.getTitle());
            row.put("wordCount", parsed.wordCount());
            row.put("cueCount", parsed.cues().size());
            row.put("qualityStatus", qa.status());
            row.put("qualityReasons", qa.reasons());
            row.put("wordsPerMinute", qa.wordsPerMinute());
            row.put("arabicScriptRatio", qa.arabicScriptRatio());
            row.put("coverageRatio", qa.coverageRatio());
            row.put("saved", saved);
            items.add(row);
        }
        return Map.of("confirmed", confirmSave, "matchedCount", matchedCount,
                "savedCount", savedCount, "rejectedCount", rejectedCount, "files", items);
    }

    private MatchResult matchEpisode(String safeLabel, List<Episode> candidates, Long explicitEpisodeId) {
        String baseName = safeLabel.replaceFirst("(?i)\\.(srt|vtt)$", "");
        List<Episode> videoMatches = candidates.stream()
                .filter(ep -> ep.getYoutubeVideoId() != null && ep.getYoutubeVideoId().length() >= 6 && baseName.contains(ep.getYoutubeVideoId()))
                .toList();
        if (videoMatches.size() == 1) return new MatchResult(videoMatches.getFirst(), "MATCHED", null);
        if (videoMatches.size() > 1) return new MatchResult(null, "AMBIGUOUS", "Multiple episodes match YouTube video ID in filename.");

        Set<Integer> extractedNums = new LinkedHashSet<>();
        Matcher tagged = EPISODE_TAG_PATTERN.matcher(baseName);
        while (tagged.find()) extractedNums.add(Integer.parseInt(tagged.group(1)));
        if (extractedNums.isEmpty()) {
            Matcher numMatcher = NUMBER_TOKEN_PATTERN.matcher(baseName);
            while (numMatcher.find()) extractedNums.add(Integer.parseInt(numMatcher.group(1)));
        }
        if (extractedNums.size() > 1) return new MatchResult(null, "AMBIGUOUS", "Ambiguous episode numbers in filename: " + extractedNums);
        if (extractedNums.size() == 1) {
            int targetNum = extractedNums.iterator().next();
            List<Episode> numMatches = candidates.stream().filter(e -> Objects.equals(e.getEpisodeNumber(), targetNum)).toList();
            if (numMatches.size() == 1) return new MatchResult(numMatches.getFirst(), "MATCHED", null);
            if (numMatches.size() > 1) {
                if (explicitEpisodeId != null) {
                    Optional<Episode> exact = numMatches.stream().filter(e -> Objects.equals(e.getId(), explicitEpisodeId)).findFirst();
                    if (exact.isPresent()) return new MatchResult(exact.get(), "MATCHED", null);
                }
                return new MatchResult(null, "AMBIGUOUS", "Episode number " + targetNum + " matches multiple courses; select a course first.");
            }
            return new MatchResult(null, "UNMATCHED", "No episode found with episode number " + targetNum + ".");
        }
        return new MatchResult(null, "UNMATCHED", "Could not match filename to an episode number or YouTube video ID.");
    }

    private Map<String, Object> buildRejectedRow(String fileName, String status, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fileName", fileName);
        row.put("matchStatus", status);
        row.put("episodeId", null);
        row.put("episodeTitle", null);
        row.put("wordCount", 0);
        row.put("qualityStatus", "REJECTED");
        row.put("qualityReasons", List.of(reason != null ? reason : "Rejected"));
        row.put("saved", false);
        return row;
    }

    private Map<String, Object> toStatusMap(EpisodeTranscript t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("episodeId", t.getEpisode().getId());
        map.put("hasTranscript", true);
        map.put("source", t.getSource());
        map.put("wordCount", t.getWordCount());
        map.put("qualityStatus", t.getQualityStatus());
        map.put("qualityReport", t.getQualityReport());
        map.put("useAnyway", t.isUseAnyway());
        map.put("usableForGeneration", t.isUsableForGeneration());
        map.put("uploadedAt", t.getUploadedAt() != null ? t.getUploadedAt().toString() : null);
        return map;
    }

    private static String sanitizeFileLabel(String raw) {
        if (raw == null || raw.isBlank()) return "unnamed.txt";
        String clean = raw.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        if (slash >= 0) clean = clean.substring(slash + 1);
        return clean.replaceAll("[\\r\\n\\t\\x00]", "").trim();
    }
}
