// Admin-only REST controller for previewing, uploading, and saving structured written text sources per episode.
package com.arabicquest.controller;

import com.arabicquest.model.Episode;
import com.arabicquest.model.EpisodeTranscript;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.EpisodeTranscriptRepository;
import com.arabicquest.service.TranscriptParserService;
import com.arabicquest.service.WrittenTextSourceService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class TextSourceImportController {

    private final WrittenTextSourceService writtenTextSourceService;
    private final EpisodeRepository episodeRepository;
    private final EpisodeTranscriptRepository transcriptRepository;
    private final TranscriptParserService transcriptParserService;

    public TextSourceImportController(
            WrittenTextSourceService writtenTextSourceService,
            EpisodeRepository episodeRepository,
            EpisodeTranscriptRepository transcriptRepository,
            TranscriptParserService transcriptParserService) {
        this.writtenTextSourceService = writtenTextSourceService;
        this.episodeRepository = episodeRepository;
        this.transcriptRepository = transcriptRepository;
        this.transcriptParserService = transcriptParserService;
    }

    public record TextSourceImportRequest(String text, String sourceUrl, boolean confirmSave) {}

    @PostMapping(value = "/episodes/{episodeId}/text-source", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> importTextSourceJson(
            @PathVariable Long episodeId,
            @RequestBody TextSourceImportRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("Written text source content is required.");
        }
        return ResponseEntity.ok(writtenTextSourceService.previewOrSave(
                episodeId, request.text(), request.sourceUrl(), request.confirmSave()));
    }

    @PostMapping(value = "/episodes/{episodeId}/text-source/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadTextSourceFile(
            @PathVariable Long episodeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceUrl", required = false) String sourceUrl,
            @RequestParam(value = "confirmSave", defaultValue = "false") boolean confirmSave) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A .txt or .md file is required.");
        }
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().trim().toLowerCase(Locale.ROOT) : "";
        if (!name.endsWith(".txt") && !name.endsWith(".md")) {
            throw new IllegalArgumentException("Only .txt and .md files are allowed for written text sources.");
        }
        String utf8Text = writtenTextSourceService.decodeStrictUtf8(file.getBytes());
        return ResponseEntity.ok(writtenTextSourceService.previewOrSave(episodeId, utf8Text, sourceUrl, confirmSave));
    }

    @Transactional(readOnly = true)
    @GetMapping("/episodes/{episodeId}/text-source")
    public ResponseEntity<Map<String, Object>> getEpisodeTextSourceMeta(@PathVariable Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found: " + episodeId));
        Optional<EpisodeTranscript> opt = transcriptRepository.findByEpisodeId(episodeId);
        if (opt.isEmpty() || !opt.get().isTextSource()) {
            return ResponseEntity.ok(Map.of(
                    "episodeId", episode.getId(),
                    "hasTextSource", false,
                    "source", opt.map(EpisodeTranscript::getSource).orElse("NONE")
            ));
        }
        EpisodeTranscript et = opt.get();
        List<TranscriptParserService.Cue> cues = transcriptParserService.fromCuesJson(et.getCuesJson());
        List<Map<String, Object>> sectionMeta = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            TranscriptParserService.Cue c = cues.get(i);
            String firstLine = c.text() != null ? c.text().split("\\R", 2)[0].replaceFirst("^Heading:\\s*", "") : ("Section " + (i + 1));
            sectionMeta.add(Map.of(
                    "index", i,
                    "heading", firstLine,
                    "startSeconds", c.startSeconds(),
                    "startTimeFormatted", TranscriptParserService.formatMmSs(c.startSeconds()),
                    "endSeconds", c.endSeconds()
            ));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("episodeId", episode.getId());
        out.put("hasTextSource", true);
        out.put("source", et.getSource());
        out.put("sourceUrl", et.getSourceUrl() != null ? et.getSourceUrl() : "");
        out.put("wordCount", et.getWordCount());
        out.put("qualityStatus", et.getQualityStatus());
        out.put("qualityReport", et.getQualityReport());
        out.put("useAnyway", et.isUseAnyway());
        out.put("usableForGeneration", et.isUsableForGeneration());
        out.put("sectionCount", sectionMeta.size());
        out.put("sections", sectionMeta);
        out.put("uploadedAt", et.getUploadedAt() != null ? et.getUploadedAt().toString() : null);
        return ResponseEntity.ok(out);
    }
}
