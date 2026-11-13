// Coordinates automatic background quiz generation on watch start/25% and low-unseen retry states with per-episode locks.
package com.arabicquest.service;

import com.arabicquest.model.QuestionStatus;
import com.arabicquest.repository.OpenQuestionRepository;
import com.arabicquest.repository.QuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AutoQuizGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AutoQuizGenerationService.class);

    private final QuestionRepository questionRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final AiQuizService aiQuizService;
    private final AutoQuizAsyncWorker asyncWorker;
    private final ConcurrentHashMap<Long, AtomicBoolean> episodeLocks = new ConcurrentHashMap<>();

    @Value("${app.quiz.auto-generate-enabled:true}")
    private boolean autoGenerateEnabled = true;

    public AutoQuizGenerationService(
            QuestionRepository questionRepository,
            OpenQuestionRepository openQuestionRepository,
            @Lazy AiQuizService aiQuizService,
            AutoQuizAsyncWorker asyncWorker) {
        this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.aiQuizService = aiQuizService;
        this.asyncWorker = asyncWorker;
    }

    public void setAutoGenerateEnabled(boolean enabled) {
        this.autoGenerateEnabled = enabled;
    }

    public boolean isEpisodeInProgress(Long episodeId) {
        AtomicBoolean lock = episodeLocks.get(episodeId);
        return lock != null && lock.get();
    }

    public void clearLocks() {
        episodeLocks.clear();
    }

    public boolean triggerIfEpisodeBankIncomplete(Long episodeId) {
        if (!canAttemptAutoGeneration(episodeId)) {
            return false;
        }
        long approvedMcqs = questionRepository.countByQuizEpisodeIdAndStatusAndActiveTrue(
                episodeId, QuestionStatus.APPROVED);
        long approvedOpens = openQuestionRepository.countByEpisodeIdAndStatusAndActiveTrue(
                episodeId, QuestionStatus.APPROVED);
        if (approvedMcqs >= QuizService.SESSION_QUESTION_COUNT && approvedOpens >= 1L) {
            return false;
        }
        return scheduleAsync(episodeId, false) != null;
    }

    public boolean triggerTopUpIfUnseenLow(Long episodeId, int unseenApprovedCount) {
        if (unseenApprovedCount >= QuizService.SESSION_QUESTION_COUNT) {
            return false;
        }
        if (!canAttemptAutoGeneration(episodeId)) {
            return false;
        }
        return scheduleAsync(episodeId, true) != null;
    }

    public CompletableFuture<AiQuizService.GeneratedBankSummary> triggerAndReturnFuture(
            Long episodeId,
            boolean topUpMode) {
        if (!canAttemptAutoGeneration(episodeId)) {
            return null;
        }
        return scheduleAsync(episodeId, topUpMode);
    }

    private boolean canAttemptAutoGeneration(Long episodeId) {
        return autoGenerateEnabled && episodeId != null && aiQuizService.isAiConfigured();
    }

    private CompletableFuture<AiQuizService.GeneratedBankSummary> scheduleAsync(Long episodeId, boolean topUpMode) {
        AtomicBoolean lock = episodeLocks.computeIfAbsent(episodeId, id -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            log.debug("Auto-generation already in progress for episode {}; skipping duplicate trigger.", episodeId);
            return null;
        }

        Runnable release = () -> lock.set(false);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            final CompletableFuture<AiQuizService.GeneratedBankSummary> bridge = new CompletableFuture<>();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncWorker.executeAutoGeneration(episodeId, topUpMode, release)
                            .whenComplete((res, ex) -> {
                                if (ex != null) bridge.completeExceptionally(ex);
                                else bridge.complete(res);
                            });
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        release.run();
                        bridge.complete(null);
                    }
                }
            });
            return bridge;
        }

        return asyncWorker.executeAutoGeneration(episodeId, topUpMode, release);
    }
}
