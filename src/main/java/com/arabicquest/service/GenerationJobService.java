// Manages the single-worker async quiz generation job queue, startup recovery, and daily auto-gen limits.
package com.arabicquest.service;

import com.arabicquest.model.Episode;
import com.arabicquest.model.GenerationJob;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.GenerationJobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);
    public static final int MAX_AUTO_ATTEMPTS_PER_DAY = 3;
    private static final Set<String> ACTIVE_STATUSES = Set.of("QUEUED", "RUNNING");

    private final GenerationJobRepository jobRepository;
    private final EpisodeRepository episodeRepository;
    private final CrossModelQuizPipelineService pipelineService;
    private final GeminiTextClient geminiTextClient;
    private final ClaudeClient claudeClient;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService singleWorkerExecutor;
    private final ConcurrentHashMap<Long, CompletableFuture<GenerationJob>> jobFutures = new ConcurrentHashMap<>();

    @Value("${AUTO_QUIZ_GENERATION_ENABLED:${app.quiz.auto-generate-enabled:true}}")
    private boolean autoQuizGenerationEnabled = true;

    public GenerationJobService(
            GenerationJobRepository jobRepository,
            EpisodeRepository episodeRepository,
            CrossModelQuizPipelineService pipelineService,
            GeminiTextClient geminiTextClient,
            ClaudeClient claudeClient,
            TransactionTemplate transactionTemplate) {
        this.jobRepository = jobRepository;
        this.episodeRepository = episodeRepository;
        this.pipelineService = pipelineService;
        this.geminiTextClient = geminiTextClient;
        this.claudeClient = claudeClient;
        this.transactionTemplate = transactionTemplate;
        this.singleWorkerExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "quiz-gen-worker-1");
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    @PreDestroy
    public void shutdown() {
        singleWorkerExecutor.shutdownNow();
    }

    public void setAutoQuizGenerationEnabled(boolean enabled) {
        this.autoQuizGenerationEnabled = enabled;
    }

    public boolean isAutoQuizGenerationEnabled() {
        return autoQuizGenerationEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public int recoverStaleRunningJobs() {
        return Boolean.TRUE.equals(transactionTemplate.execute(st -> {
            List<GenerationJob> stale = jobRepository.findByStatusIn(List.of("RUNNING"));
            LocalDateTime now = LocalDateTime.now();
            for (GenerationJob job : stale) {
                job.setStatus("FAILED");
                job.setStage("FAILED");
                job.setSanitizedError("Server restarted while generation job was running. Click Retry to run again.");
                job.setCompletedAt(now);
                jobRepository.save(job);
            }
            if (!stale.isEmpty()) {
                log.warn("Marked {} stale RUNNING generation job(s) as FAILED on startup.", stale.size());
            }
            return true;
        })) ? 1 : 0;
    }

    public boolean canTriggerAutoGenerationToday(Long episodeId) {
        if (!autoQuizGenerationEnabled || episodeId == null) {
            return false;
        }
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        long attemptsToday = jobRepository.countAutoAttemptsSince(episodeId, startOfDay);
        return attemptsToday < MAX_AUTO_ATTEMPTS_PER_DAY;
    }

    public GenerationJob enqueueJob(
            Long episodeId,
            String writerModel,
            String optionalTranscript,
            boolean autoTriggered) {
        if (autoTriggered && !canTriggerAutoGenerationToday(episodeId)) {
            throw new IllegalStateException("Daily auto-generation limit (3 attempts/day) reached for episode " + episodeId);
        }

        GenerationJob created = transactionTemplate.execute(st -> {
            if (jobRepository.existsByEpisodeIdAndStatusIn(episodeId, ACTIVE_STATUSES)) {
                return null;
            }
            Episode ep = episodeRepository.findById(episodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
            String model = (writerModel != null && !writerModel.isBlank())
                    ? writerModel.trim()
                    : geminiTextClient.getModel();
            GenerationJob job = new GenerationJob(ep, model, autoTriggered);
            return jobRepository.save(job);
        });

        if (created == null) {
            throw new DuplicateEpisodeJobException("A generation job is already QUEUED or RUNNING for episode " + episodeId);
        }

        CompletableFuture<GenerationJob> future = CompletableFuture.supplyAsync(
                () -> executeQueuedJob(created.getId(), optionalTranscript),
                singleWorkerExecutor
        );
        jobFutures.put(created.getId(), future);
        return created;
    }

    public GenerationJob awaitJob(Long jobId, long timeoutMs) {
        CompletableFuture<GenerationJob> f = jobFutures.get(jobId);
        if (f != null) {
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        return getJobOrThrow(jobId);
    }

    public GenerationJob getJobOrThrow(Long jobId) {
        return jobRepository.findByIdWithEpisode(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Generation job not found: " + jobId));
    }

    public Map<String, Object> toJobDto(GenerationJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", job.getId());
        map.put("episodeId", job.getEpisode().getId());
        map.put("writerModel", job.getWriterModel());
        map.put("status", job.getStatus());
        map.put("stage", job.getStage());
        map.put("progress", job.getProgress());
        map.put("autoTriggered", job.isAutoTriggered());
        map.put("savedCount", job.getSavedCount());
        map.put("sanitizedError", job.getSanitizedError());
        map.put("createdAt", job.getCreatedAt() != null ? job.getCreatedAt().toString() : null);
        map.put("startedAt", job.getStartedAt() != null ? job.getStartedAt().toString() : null);
        map.put("completedAt", job.getCompletedAt() != null ? job.getCompletedAt().toString() : null);
        return map;
    }

    private GenerationJob executeQueuedJob(Long jobId, String optionalTranscript) {
        GenerationJob running = transactionTemplate.execute(st -> {
            GenerationJob j = jobRepository.findByIdWithEpisode(jobId).orElseThrow();
            j.setStatus("RUNNING");
            j.setStage("PASS_1_WRITER");
            j.setProgress(10);
            j.setStartedAt(LocalDateTime.now());
            return jobRepository.save(j);
        });

        try {
            CrossModelQuizPipelineService.PipelineResult result = pipelineService.executePipeline(
                    running.getEpisode().getId(),
                    running.getWriterModel(),
                    optionalTranscript,
                    running.isAutoTriggered(),
                    running.isAutoTriggered() ? "AUTO" : "MANUAL",
                    (stage, pct) -> updateJobStage(jobId, stage, pct)
            );
            return transactionTemplate.execute(st -> {
                GenerationJob j = jobRepository.findByIdWithEpisode(jobId).orElseThrow();
                j.setStatus("DONE");
                j.setStage("COMPLETED");
                j.setProgress(100);
                j.setSavedCount(result.savedMcqs());
                j.setCompletedAt(LocalDateTime.now());
                return jobRepository.save(j);
            });
        } catch (Exception ex) {
            String sanitized = AiErrorSanitizer.sanitizeText(
                    ex.getMessage(),
                    geminiTextClient.getApiKey(),
                    claudeClient.getApiKey()
            );
            return transactionTemplate.execute(st -> {
                GenerationJob j = jobRepository.findByIdWithEpisode(jobId).orElseThrow();
                j.setStatus("FAILED");
                j.setStage("FAILED");
                j.setSanitizedError(sanitized.isBlank() ? "Generation job failed." : sanitized);
                j.setCompletedAt(LocalDateTime.now());
                return jobRepository.save(j);
            });
        }
    }

    private void updateJobStage(Long jobId, String stage, int progress) {
        transactionTemplate.executeWithoutResult(st -> {
            jobRepository.findById(jobId).ifPresent(j -> {
                j.setStage(stage);
                j.setProgress(progress);
                jobRepository.save(j);
            });
        });
    }

    public static class DuplicateEpisodeJobException extends RuntimeException {
        public DuplicateEpisodeJobException(String message) {
            super(message);
        }
    }
}
