// Batch-loads a student's watch sessions, retry cooldowns, reward claims, overrides, and question counts in fixed SQL queries.
package com.arabicquest.service;

import com.arabicquest.dto.WatchStatusResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class EpisodeStatusBatchLoader {

    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final StudentFavoriteEpisodeRepository studentFavoriteEpisodeRepository;
    private final QuestionRepository questionRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final StudentEpisodeOverrideRepository studentEpisodeOverrideRepository;
    private final WatchSessionRepository watchSessionRepository;
    private final WatchProgressService watchProgressService;

    public EpisodeStatusBatchLoader(
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            QuizAttemptRepository quizAttemptRepository,
            StudentFavoriteEpisodeRepository studentFavoriteEpisodeRepository,
            QuestionRepository questionRepository,
            OpenQuestionRepository openQuestionRepository,
            StudentEpisodeOverrideRepository studentEpisodeOverrideRepository,
            WatchSessionRepository watchSessionRepository,
            WatchProgressService watchProgressService) {
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.studentFavoriteEpisodeRepository = studentFavoriteEpisodeRepository;
        this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.studentEpisodeOverrideRepository = studentEpisodeOverrideRepository;
        this.watchSessionRepository = watchSessionRepository;
        this.watchProgressService = watchProgressService;
    }

    public record BatchData(
            Set<Long> passedIds,
            Set<Long> mcqPassedIds,
            Set<Long> favoriteIds,
            Map<Long, Long> approvedMcqCounts,
            Map<Long, Long> approvedOpenCounts,
            Map<Long, StudentEpisodeOverride.OverrideState> overridesByEpisodeId,
            Map<Long, LocalDateTime> cooldownResetAtByEpisodeId,
            Map<Long, WatchSession> watchSessionsByEpisodeId,
            Map<Long, LocalDateTime> latestFailTimeByEpisodeId
    ) {}

    @Transactional(readOnly = true)
    public BatchData loadForStudent(Long studentId) {
        Set<Long> passedIds = episodeRewardClaimRepository.findClaimedEpisodeIdsByStudentId(studentId);
        Set<Long> mcqPassedIds = quizAttemptRepository.findPassedEpisodeIdsByStudentId(studentId);
        Set<Long> favoriteIds = studentFavoriteEpisodeRepository.findFavoriteEpisodeIdsByStudentId(studentId);

        Map<Long, Long> approvedMcqCounts = toCountMap(questionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.APPROVED));
        Map<Long, Long> approvedOpenCounts = toCountMap(openQuestionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.APPROVED));

        Map<Long, StudentEpisodeOverride.OverrideState> overridesByEp = new HashMap<>();
        Map<Long, LocalDateTime> cooldownResetAtByEp = new HashMap<>();
        for (StudentEpisodeOverride ov : studentEpisodeOverrideRepository.findByStudentIdWithEpisode(studentId)) {
            if (ov.getEpisode() == null) continue;
            Long epId = ov.getEpisode().getId();
            if (ov.getOverrideState() != null) {
                overridesByEp.put(epId, ov.getOverrideState());
            }
            if (ov.getCooldownResetAt() != null) {
                cooldownResetAtByEp.put(epId, ov.getCooldownResetAt());
            }
        }

        Map<Long, WatchSession> sessionsByEp = new HashMap<>();
        for (WatchSession ws : watchSessionRepository.findByStudentIdWithEpisode(studentId)) {
            if (ws.getEpisode() != null) {
                sessionsByEp.put(ws.getEpisode().getId(), ws);
            }
        }

        Map<Long, LocalDateTime> latestFailByEp = new HashMap<>();
        if (watchProgressService.getConfiguredRetryCooldownSeconds() > 0) {
            for (Object[] row : quizAttemptRepository.findLatestFailedAttemptTimesByStudentId(studentId)) {
                if (row != null && row.length >= 2 && row[0] instanceof Number n && row[1] instanceof LocalDateTime dt) {
                    latestFailByEp.put(n.longValue(), dt);
                }
            }
        }

        return new BatchData(
                passedIds,
                mcqPassedIds,
                favoriteIds,
                approvedMcqCounts,
                approvedOpenCounts,
                overridesByEp,
                cooldownResetAtByEp,
                sessionsByEp,
                latestFailByEp
        );
    }

    public WatchStatusResponse buildWatchStatus(Episode episode, boolean alreadyPassed, BatchData batch) {
        int required = watchProgressService.computeRequiredWatchSeconds(episode);
        long cooldown = alreadyPassed ? 0L : computeCooldownRemaining(episode.getId(), batch);
        WatchSession session = batch.watchSessionsByEpisodeId().get(episode.getId());

        if (session == null) {
            return new WatchStatusResponse(
                    episode.getId(),
                    WatchMode.ARABIC_SUB,
                    false,
                    alreadyPassed ? required : 0,
                    required,
                    alreadyPassed || required == 0,
                    cooldown,
                    alreadyPassed
            );
        }

        int verified = Math.min(required, session.getVerifiedWatchSeconds());
        boolean unlocked = alreadyPassed || verified >= required || session.isAdminUnlocked();
        boolean watchStarted = alreadyPassed || session.getLastHeartbeatAt() != null || verified > 0;

        return new WatchStatusResponse(
                episode.getId(),
                WatchMode.ARABIC_SUB,
                false,
                alreadyPassed ? required : verified,
                required,
                unlocked,
                cooldown,
                watchStarted
        );
    }

    private long computeCooldownRemaining(Long episodeId, BatchData batch) {
        long configured = watchProgressService.getConfiguredRetryCooldownSeconds();
        if (configured <= 0) {
            return 0L;
        }
        LocalDateTime failTime = batch.latestFailTimeByEpisodeId().get(episodeId);
        if (failTime == null) {
            return 0L;
        }
        LocalDateTime resetAt = batch.cooldownResetAtByEpisodeId().get(episodeId);
        if (resetAt != null && !failTime.isAfter(resetAt)) {
            return 0L;
        }
        long elapsed = Duration.between(failTime, LocalDateTime.now()).getSeconds();
        return Math.max(0L, configured - elapsed);
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        if (rows == null) return map;
        for (Object[] row : rows) {
            if (row != null && row.length >= 2 && row[0] instanceof Number id && row[1] instanceof Number cnt) {
                map.put(id.longValue(), cnt.longValue());
            }
        }
        return map;
    }
}
