// Manages the single-worker async quiz generation job queue, stale-job expiry, cancellation, and progress commits.
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);
    public static final int MAX_AUTO_ATTEMPTS_PER_DAY = 3;
    public static final long STALE_JOB_TIMEOUT_MINUTES = 5;
    private static final Set<String> ACTIVE_STATUSES = Set.of("QUEUED", "RUNNING");

    private final GenerationJobRepository jobRepository;
    private final EpisodeRepository episodeRepository;
    private final CrossModelQuizPipelineService pipelineService;
    private final GeminiTextClient geminiTextClient;
    private final ClaudeClient claudeClient;
    private final AiUsageGuard aiUsageGuard;
    private final TransactionTemplate requiresNewTx;
    private final ExecutorService singleWorkerExecutor;
    private final ConcurrentHashMap<Long, CompletableFuture<GenerationJob>> jobFutures = new ConcurrentHashMap<>();
    private final Set<Long> cancelledJobIds = ConcurrentHashMap.newKeySet();

    @Value("${AUTO_QUIZ_GENERATION_ENABLED:${app.quiz.auto-generate-enabled:true}}")
    private boolean autoQuizGenerationEnabled = true;

    public GenerationJobService(
            GenerationJobRepository jobRepository, EpisodeRepository episodeRepository,
            CrossModelQuizPipelineService pipelineService, GeminiTextClient geminiTextClient,
            ClaudeClient claudeClient, AiUsageGuard aiUsageGuard, TransactionTemplate transactionTemplate) {
        this.jobRepository = jobRepository;
        this.episodeRepository = episodeRepository;
        this.pipelineService = pipelineService;
        this.geminiTextClient = geminiTextClient;
        this.claudeClient = claudeClient;
        this.aiUsageGuard = aiUsageGuard;
        if (transactionTemplate.getTransactionManager() != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionTemplate.getTransactionManager());
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tx;
        } else {
            this.requiresNewTx = transactionTemplate;
        }
        this.singleWorkerExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100),
                r -> { Thread t = new Thread(r, "quiz-gen-worker-1"); t.setDaemon(true); return t; });
    }

    @PreDestroy
    public void shutdown() {
        singleWorkerExecutor.shutdownNow();
    }

    public void setAutoQuizGenerationEnabled(boolean enabled) { this.autoQuizGenerationEnabled = enabled; }
    public boolean isAutoQuizGenerationEnabled() { return autoQuizGenerationEnabled; }

    @EventListener(ApplicationReadyEvent.class)
    public int recoverStaleRunningJobs() {
        Integer count = requiresNewTx.execute(st -> {
            List<GenerationJob> stale = jobRepository.findByStatusIn(List.of("RUNNING"));
            LocalDateTime now = LocalDateTime.now();
            for (GenerationJob job : stale) {
                job.setStatus("FAILED");
                job.setStage("FAILED");
                job.setSanitizedError("Server restarted while generation job was running. Click Retry to run again.");
                job.setCompletedAt(now);
                job.setUpdatedAt(now);
                jobRepository.save(job);
            }
            if (!stale.isEmpty()) log.warn("Marked {} stale RUNNING generation job(s) as FAILED on startup.", stale.size());
            return stale.size();
        });
        return count != null ? count : 0;
    }

    public int expireStaleActiveJobs() {
        return expireStaleActiveJobs(LocalDateTime.now().minusMinutes(STALE_JOB_TIMEOUT_MINUTES));
    }

    public int expireStaleActiveJobs(LocalDateTime cutoff) {
        Integer expiredCount = requiresNewTx.execute(st -> {
            List<GenerationJob> active = jobRepository.findByStatusIn(ACTIVE_STATUSES);
            LocalDateTime now = LocalDateTime.now();
            int count = 0;
            for (GenerationJob job : active) {
                LocalDateTime lastTouch = job.getUpdatedAt() != null ? job.getUpdatedAt()
                        : (job.getStartedAt() != null ? job.getStartedAt() : job.getCreatedAt());
                if (lastTouch != null && lastTouch.isBefore(cutoff)) {
                    job.setStatus("FAILED");
                    job.setStage("FAILED");
                    job.setSanitizedError("FAILED: Timed out / stale (no progress update for over 5 minutes).");
                    job.setCompletedAt(now);
                    job.setUpdatedAt(now);
                    jobRepository.save(job);
                    cancelledJobIds.add(job.getId());
                    CompletableFuture<GenerationJob> f = jobFutures.remove(job.getId());
                    if (f != null) f.cancel(true);
                    count++;
                }
            }
            if (count > 0) log.warn("Auto-expired {} stale QUEUED/RUNNING generation job(s) older than {}.", count, cutoff);
            return count;
        });
        return expiredCount != null ? expiredCount : 0;
    }

    public GenerationJob cancelJob(Long jobId) {
        cancelledJobIds.add(jobId);
        CompletableFuture<GenerationJob> f = jobFutures.remove(jobId);
        if (f != null) f.cancel(true);
        return requiresNewTx.execute(st -> {
            GenerationJob job = jobRepository.findByIdWithEpisode(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Generation job not found: " + jobId));
            LocalDateTime now = LocalDateTime.now();
            job.setStatus("FAILED");
            job.setStage("CANCELLED");
            job.setSanitizedError("Cancelled by admin.");
            job.setCompletedAt(now);
            job.setUpdatedAt(now);
            log.info("Cancelled generation job #{} for episode #{}", jobId, job.getEpisode().getId());
            return jobRepository.save(job);
        });
    }

    public boolean canTriggerAutoGenerationToday(Long episodeId) {
        if (!autoQuizGenerationEnabled || episodeId == null || aiUsageGuard.isGlobalCallCapReached()) return false;
        long attemptsToday = jobRepository.countAutoAttemptsSince(episodeId, LocalDate.now().atStartOfDay());
        return attemptsToday < MAX_AUTO_ATTEMPTS_PER_DAY;
    }

    public GenerationJob enqueueJob(Long episodeId, String writerModel, String optionalTranscript, boolean autoTriggered) {
        expireStaleActiveJobs();
        aiUsageGuard.assertGlobalCallCapAvailable();
        if (autoTriggered && !canTriggerAutoGenerationToday(episodeId)) {
            throw new IllegalStateException("Daily auto-generation limit (3 attempts/day) reached for episode " + episodeId);
        }
        Long existingActiveJobId = requiresNewTx.execute(st -> {
            List<GenerationJob> active = jobRepository.findByEpisodeIdAndStatusInOrderByCreatedAtDesc(episodeId, ACTIVE_STATUSES);
            return active.isEmpty() ? null : active.get(0).getId();
        });
        if (existingActiveJobId != null) {
            throw new DuplicateEpisodeJobException(
                    "A generation job is already QUEUED or RUNNING for episode " + episodeId, existingActiveJobId);
        }
        GenerationJob created = requiresNewTx.execute(st -> {
            Episode ep = episodeRepository.findById(episodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
            String defaultModel = claudeClient.isConfigured() ? claudeClient.getModel() : geminiTextClient.getModel();
            String model = (writerModel != null && !writerModel.isBlank()) ? writerModel.trim() : defaultModel;
            GenerationJob job = new GenerationJob(ep, model, autoTriggered);
            return jobRepository.save(job);
        });
        CompletableFuture<GenerationJob> future = CompletableFuture.supplyAsync(
                () -> executeQueuedJob(created.getId(), optionalTranscript), singleWorkerExecutor);
        jobFutures.put(created.getId(), future);
        return created;
    }

    public GenerationJob awaitJob(Long jobId, long timeoutMs) {
        CompletableFuture<GenerationJob> f = jobFutures.get(jobId);
        if (f != null) {
            try { return f.get(timeoutMs, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        }
        return getJobOrThrow(jobId);
    }

    public GenerationJob getJobOrThrow(Long jobId) {
        expireStaleActiveJobs();
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
        map.put("updatedAt", job.getUpdatedAt() != null ? job.getUpdatedAt().toString() : null);
        map.put("completedAt", job.getCompletedAt() != null ? job.getCompletedAt().toString() : null);
        return map;
    }

    GenerationJob executeQueuedJob(Long jobId, String optionalTranscript) {
        if (cancelledJobIds.contains(jobId)) return getJobOrThrow(jobId);
        GenerationJob running = requiresNewTx.execute(st -> {
            GenerationJob j = jobRepository.findByIdWithEpisode(jobId).orElseThrow();
            if (!ACTIVE_STATUSES.contains(j.getStatus())) return j;
            LocalDateTime now = LocalDateTime.now();
            j.setStatus("RUNNING");
            j.setStage("PASS_1_WRITER");
            j.setProgress(10);
            j.setStartedAt(now);
            j.setUpdatedAt(now);
            return jobRepository.save(j);
        });
        if (running == null || !"RUNNING".equals(running.getStatus())) return running;
        try {
            CrossModelQuizPipelineService.PipelineResult result = pipelineService.executePipeline(
                    running.getEpisode().getId(), running.getWriterModel(), optionalTranscript,
                    running.isAutoTriggered(), running.isAutoTriggered() ? "AUTO" : "MANUAL",
                    (stage, pct) -> updateJobStage(jobId, stage, pct));
            return requiresNewTx.execute(st -> {
                GenerationJob j = jobRepository.findByIdWithEpisode(jobId).orElseThrow();
                if (cancelledJobIds.contains(jobId) || "FAILED".equals(j.getStatus())) return j;
                LocalDateTime now = LocalDateTime.now();
                j.setStatus("DONE");
                j.setStage("COMPLETED");
                j.setProgress(100);
                j.setSavedCount(result.savedMcqs());
                j.setCompletedAt(now);
                j.setUpdatedAt(now);
                return jobRepository.save(j);
            });
        } catch (Throwable t) {
            String rawMsg = (t.getMessage() != null && !t.getMessage().isBlank())
                    ? t.getMessage() : t.getClass().getSimpleName();
            String sanitized = AiErrorSanitizer.sanitizeText(
                    rawMsg, geminiTextClient.getApiKey(), claudeClient.getApiKey());
            log.warn("Generation job #{} failed: {}", jobId, sanitized);
            return requiresNewTx.execute(st -> {
                GenerationJob j = jobRepository.findByIdWithEpisode(jobId).orElseThrow();
                LocalDateTime now = LocalDateTime.now();
                j.setStatus("FAILED");
                if (!"CANCELLED".equals(j.getStage())) j.setStage("FAILED");
                if (j.getSanitizedError() == null || j.getSanitizedError().isBlank()) {
                    j.setSanitizedError(sanitized.isBlank() ? "Generation job failed." : sanitized);
                }
                j.setCompletedAt(now);
                j.setUpdatedAt(now);
                return jobRepository.save(j);
            });
        } finally {
            jobFutures.remove(jobId);
        }
    }

    public void updateJobStage(Long jobId, String stage, int progress) {
        if (cancelledJobIds.contains(jobId)) return;
        requiresNewTx.executeWithoutResult(st -> {
            jobRepository.findById(jobId).ifPresent(j -> {
                if (!ACTIVE_STATUSES.contains(j.getStatus())) return;
                j.setStage(stage);
                j.setProgress(progress);
                j.setUpdatedAt(LocalDateTime.now());
                jobRepository.save(j);
            });
        });
    }

    public static class DuplicateEpisodeJobException extends RuntimeException {
        private final Long activeJobId;
        public DuplicateEpisodeJobException(String message) { this(message, null); }
        public DuplicateEpisodeJobException(String message, Long activeJobId) {
            super(message);
            this.activeJobId = activeJobId;
        }
        public Long getActiveJobId() { return activeJobId; }
    }
}
