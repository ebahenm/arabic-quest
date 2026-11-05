package com.arabicquest.service;

import com.arabicquest.dto.WatchHeartbeatRequest;
import com.arabicquest.dto.WatchStatusResponse;
import com.arabicquest.model.Episode;
import com.arabicquest.model.QuizAttempt;
import com.arabicquest.model.User;
import com.arabicquest.model.WatchMode;
import com.arabicquest.model.WatchSession;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.QuizAttemptRepository;
import com.arabicquest.repository.UserRepository;
import com.arabicquest.repository.WatchSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class WatchProgressService {

    private static final int MAX_HEARTBEAT_CREDIT_SECONDS = 25;

    private final WatchSessionRepository watchSessionRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final UserRepository userRepository;
    private final EpisodeRepository episodeRepository;
    private final int configuredMinWatchSeconds;
    private final long retryCooldownSeconds;

    public WatchProgressService(
            WatchSessionRepository watchSessionRepository,
            QuizAttemptRepository quizAttemptRepository,
            UserRepository userRepository,
            EpisodeRepository episodeRepository,
            @Value("${QUIZ_MIN_WATCH_SECONDS:600}") int configuredMinWatchSeconds,
            @Value("${QUIZ_RETRY_COOLDOWN_SECONDS:600}") long retryCooldownSeconds) {
        this.watchSessionRepository = watchSessionRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.userRepository = userRepository;
        this.episodeRepository = episodeRepository;
        this.configuredMinWatchSeconds = configuredMinWatchSeconds;
        this.retryCooldownSeconds = retryCooldownSeconds;
    }

    public int computeRequiredWatchSeconds(Episode episode) {
        if (configuredMinWatchSeconds <= 0) {
            return 0;
        }
        int sixtyPercent = (int) Math.round(episode.getDurationSeconds() * 0.60);
        return Math.min(configuredMinWatchSeconds, Math.max(15, sixtyPercent));
    }

    public long getRetryCooldownRemainingSeconds(Long studentId, Long episodeId) {
        if (retryCooldownSeconds <= 0) {
            return 0L;
        }
        Optional<QuizAttempt> lastFail = quizAttemptRepository
                .findTopByStudentIdAndEpisodeIdAndPassedFalseOrderByAttemptedAtDesc(studentId, episodeId);
        if (lastFail.isEmpty() || lastFail.get().getAttemptedAt() == null) {
            return 0L;
        }
        long elapsed = Duration.between(lastFail.get().getAttemptedAt(), LocalDateTime.now()).getSeconds();
        return Math.max(0L, retryCooldownSeconds - elapsed);
    }

    @Transactional(readOnly = true)
    public WatchStatusResponse getWatchStatus(User student, Episode episode, boolean alreadyPassed) {
        int required = computeRequiredWatchSeconds(episode);
        long cooldown = alreadyPassed ? 0L : getRetryCooldownRemainingSeconds(student.getId(), episode.getId());

        Optional<WatchSession> opt = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), episode.getId());
        if (opt.isEmpty()) {
            return new WatchStatusResponse(
                    episode.getId(),
                    WatchMode.ARABIC_SUB,
                    false,
                    alreadyPassed ? required : 0,
                    required,
                    alreadyPassed || required == 0,
                    cooldown
            );
        }

        WatchSession session = opt.get();
        int effectiveVerified = calculateEffectiveWatchSeconds(session, required);
        boolean unlocked = alreadyPassed || effectiveVerified >= required;

        return new WatchStatusResponse(
                episode.getId(),
                session.getLockedWatchMode(),
                session.isModeLocked(),
                effectiveVerified,
                required,
                unlocked,
                cooldown
        );
    }

    @Transactional
    public WatchStatusResponse recordHeartbeat(String username, Long episodeId, WatchHeartbeatRequest req) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        boolean alreadyPassed = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episode.getId());
        int required = computeRequiredWatchSeconds(episode);

        WatchMode requestedMode = (req != null && req.watchMode() != null) ? req.watchMode() : WatchMode.ARABIC_SUB;
        boolean isExternal = req != null && req.external();
        boolean videoEnded = req != null && req.videoEnded();

        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), episode.getId())
                .orElseGet(() -> new WatchSession(student, episode, requestedMode));

        LocalDateTime now = LocalDateTime.now();

        if (!session.isModeLocked()) {
            session.setLockedWatchMode(requestedMode);
            session.setModeLocked(true);
        }

        if (isExternal) {
            if (session.getExternalWatchStartedAt() == null) {
                session.setExternalWatchStartedAt(now);
            } else {
                long extElapsed = Math.max(0L, Duration.between(session.getExternalWatchStartedAt(), now).getSeconds());
                int updated = Math.max(session.getVerifiedWatchSeconds(), (int) Math.min(required, extElapsed));
                session.setVerifiedWatchSeconds(updated);
            }
        } else {
            if (session.getLastHeartbeatAt() != null) {
                long delta = Duration.between(session.getLastHeartbeatAt(), now).getSeconds();
                if (delta > 0) {
                    int credit = (int) Math.min(MAX_HEARTBEAT_CREDIT_SECONDS, delta);
                    session.setVerifiedWatchSeconds(session.getVerifiedWatchSeconds() + credit);
                }
            }
            session.setLastHeartbeatAt(now);
        }

        if (videoEnded) {
            session.setVideoEnded(true);
        }

        int effectiveVerified = calculateEffectiveWatchSeconds(session, required);
        session.setVerifiedWatchSeconds(effectiveVerified);
        watchSessionRepository.save(session);

        long cooldown = alreadyPassed ? 0L : getRetryCooldownRemainingSeconds(student.getId(), episode.getId());
        boolean unlocked = alreadyPassed || effectiveVerified >= required;

        return new WatchStatusResponse(
                episode.getId(),
                session.getLockedWatchMode(),
                session.isModeLocked(),
                effectiveVerified,
                required,
                unlocked,
                cooldown
        );
    }

    @Transactional
    public WatchStatusResponse resetSessionAndSwitchMode(String username, Long episodeId, WatchMode newMode) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        boolean alreadyPassed = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episode.getId());
        int required = computeRequiredWatchSeconds(episode);
        WatchMode targetMode = newMode != null ? newMode : WatchMode.ARABIC_SUB;

        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), episode.getId())
                .orElseGet(() -> new WatchSession(student, episode, targetMode));

        session.setLockedWatchMode(targetMode);
        session.setModeLocked(false);
        session.setVerifiedWatchSeconds(0);
        session.setExternalWatchStartedAt(null);
        session.setLastHeartbeatAt(null);
        session.setVideoEnded(false);
        watchSessionRepository.save(session);

        long cooldown = alreadyPassed ? 0L : getRetryCooldownRemainingSeconds(student.getId(), episode.getId());

        return new WatchStatusResponse(
                episode.getId(),
                session.getLockedWatchMode(),
                false,
                alreadyPassed ? required : 0,
                required,
                alreadyPassed || required == 0,
                cooldown
        );
    }

    public boolean isQuizUnlockedForSubmission(Long studentId, Episode episode) {
        int required = computeRequiredWatchSeconds(episode);
        if (required <= 0) {
            return true;
        }
        Optional<WatchSession> opt = watchSessionRepository.findByStudentIdAndEpisodeId(studentId, episode.getId());
        if (opt.isEmpty()) {
            return false;
        }
        return calculateEffectiveWatchSeconds(opt.get(), required) >= required;
    }

    public WatchMode resolveEffectiveWatchMode(Long studentId, Long episodeId, WatchMode fallbackMode) {
        return watchSessionRepository.findByStudentIdAndEpisodeId(studentId, episodeId)
                .filter(WatchSession::isModeLocked)
                .map(WatchSession::getLockedWatchMode)
                .orElse(fallbackMode != null ? fallbackMode : WatchMode.ARABIC_SUB);
    }

    private int calculateEffectiveWatchSeconds(WatchSession session, int required) {
        int verified = session.getVerifiedWatchSeconds();
        if (session.getExternalWatchStartedAt() != null) {
            long extElapsed = Math.max(0L, Duration.between(session.getExternalWatchStartedAt(), LocalDateTime.now()).getSeconds());
            verified = Math.max(verified, (int) Math.min(required, extElapsed));
        }
        return verified;
    }
}
