// Runs @Async quiz bank generation jobs on the background executor thread pool.
package com.arabicquest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class AutoQuizAsyncWorker {

    private static final Logger log = LoggerFactory.getLogger(AutoQuizAsyncWorker.class);

    private final AiQuizService aiQuizService;

    public AutoQuizAsyncWorker(@Lazy AiQuizService aiQuizService) {
        this.aiQuizService = aiQuizService;
    }

    @Async("quizAutoGenExecutor")
    public CompletableFuture<AiQuizService.GeneratedBankSummary> executeAutoGeneration(
            Long episodeId,
            boolean topUpMode,
            Runnable releaseLock) {
        try {
            AiQuizService.GeneratedBankSummary summary = aiQuizService.generateQuestionBankForEpisode(
                    null,
                    episodeId,
                    null,
                    true,
                    topUpMode
            );
            return CompletableFuture.completedFuture(summary);
        } catch (Exception ex) {
            log.warn("Background auto-generation skipped/failed for episode {}: {}", episodeId, ex.getMessage());
            return CompletableFuture.completedFuture(null);
        } finally {
            if (releaseLock != null) {
                releaseLock.run();
            }
        }
    }
}
