// Admin REST controller for async quiz generation jobs, Retry, and Compare Models (Set A vs Set B).
package com.arabicquest.controller;

import com.arabicquest.model.GenerationJob;
import com.arabicquest.service.CrossModelQuizPipelineService;
import com.arabicquest.service.GenerationJobService;
import com.arabicquest.service.GenerationJobService.DuplicateEpisodeJobException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@RestController
@RequestMapping("/api/admin")
public class GenerationJobController {

    private final GenerationJobService jobService;
    private final CrossModelQuizPipelineService pipelineService;
    private final Map<String, Deque<Instant>> compareRateBuckets = new ConcurrentHashMap<>();

    public GenerationJobController(
            GenerationJobService jobService,
            CrossModelQuizPipelineService pipelineService) {
        this.jobService = jobService;
        this.pipelineService = pipelineService;
    }

    public record CreateJobRequest(String writerModel, String transcript) {}
    public record ChooseCompareSetRequest(String chosenSet) {}

    @PostMapping("/episodes/{episodeId}/generation-jobs")
    public ResponseEntity<Map<String, Object>> startGenerationJob(
            @PathVariable Long episodeId,
            @RequestBody(required = false) CreateJobRequest request) {
        try {
            String writer = request != null ? request.writerModel() : null;
            String transcript = request != null ? request.transcript() : null;
            GenerationJob job = jobService.enqueueJob(episodeId, writer, transcript, false);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobService.toJobDto(job));
        } catch (DuplicateEpisodeJobException dup) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", dup.getMessage(),
                    "episodeId", episodeId
            ));
        }
    }

    @GetMapping("/episodes/{episodeId}/generation-jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getEpisodeGenerationJob(
            @PathVariable Long episodeId,
            @PathVariable Long jobId) {
        GenerationJob job = jobService.getJobOrThrow(jobId);
        if (!Objects.equals(job.getEpisode().getId(), episodeId)) {
            throw new IllegalArgumentException("Generation job does not belong to episode " + episodeId);
        }
        return ResponseEntity.ok(jobService.toJobDto(job));
    }

    @GetMapping("/generation-jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getGenerationJobById(@PathVariable Long jobId) {
        GenerationJob job = jobService.getJobOrThrow(jobId);
        return ResponseEntity.ok(jobService.toJobDto(job));
    }

    @PostMapping("/episodes/{episodeId}/generation-jobs/{jobId}/retry")
    public ResponseEntity<Map<String, Object>> retryFailedJob(
            @PathVariable Long episodeId,
            @PathVariable Long jobId) {
        GenerationJob prev = jobService.getJobOrThrow(jobId);
        try {
            GenerationJob next = jobService.enqueueJob(episodeId, prev.getWriterModel(), null, false);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobService.toJobDto(next));
        } catch (DuplicateEpisodeJobException dup) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", dup.getMessage(),
                    "episodeId", episodeId
            ));
        }
    }

    @PostMapping("/episodes/{episodeId}/compare-models")
    public ResponseEntity<Map<String, Object>> compareModels(@PathVariable Long episodeId) {
        enforceCompareRateLimit("compare:" + episodeId);
        return ResponseEntity.ok(pipelineService.compareModelsForEpisode(episodeId));
    }

    @PostMapping("/episodes/{episodeId}/compare-models/choose")
    public ResponseEntity<Map<String, Object>> chooseComparedModelSet(
            @PathVariable Long episodeId,
            @RequestBody ChooseCompareSetRequest request) {
        String chosen = request != null ? request.chosenSet() : "SET_A";
        return ResponseEntity.ok(pipelineService.chooseComparedSet(episodeId, chosen));
    }

    private void enforceCompareRateLimit(String key) {
        Instant now = Instant.now();
        Deque<Instant> bucket = compareRateBuckets.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        while (!bucket.isEmpty() && bucket.peekFirst().isBefore(now.minusSeconds(300))) {
            bucket.pollFirst();
        }
        if (bucket.size() >= 3) {
            throw new com.arabicquest.config.TooManyRequestsException(
                    "Compare Models is rate-limited to 3 runs per 5 minutes per episode.", 60);
        }
        bucket.addLast(now);
    }
}
