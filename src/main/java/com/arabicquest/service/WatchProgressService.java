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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class WatchProgressService {

    private static final Logger log = LoggerFactory.getLogger(WatchProgressService.class);
    private static final int MAX_HEARTBEAT_CREDIT_SECONDS = 25;
    public static final int DEFAULT_REQUIRED_CAP_SECONDS = 1800; // 30 minutes cap

    private final WatchSessionRepository watchSessionRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final UserRepository userRepository;
    private final EpisodeRepository episodeRepository;
    private final int requiredCapSeconds;
    private final long retryCooldownSeconds;

    public WatchProgressService(
            WatchSessionRepository watchSessionRepository,
            QuizAttemptRepository quizAttemptRepository,
            UserRepository userRepository,
            EpisodeRepository episodeRepository,
            @Value("${app.watch.required-cap-seconds:1800}") int requiredCapSeconds,
            @Value("${app.quiz.retry-cooldown-seconds:${QUIZ_RETRY_COOLDOWN_SECONDS:600}}") long retryCooldownSeconds) {
        this.watchSessionRepository = watchSessionRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.userRepository = userRepository;
        this.episodeRepository = episodeRepository;
        this.requiredCapSeconds = requiredCapSeconds;
        this.retryCooldownSeconds = retryCooldownSeconds;
    }

    public int computeRequiredWatchSeconds(Episode episode) {
        if (requiredCapSeconds <= 0) {
            return 0;
        }
        int durationSeconds = Math.max(1, episode.getDurationSeconds());
        return Math.min(requiredCapSeconds, durationSeconds);
    }

    public record EpisodeLockState(boolean locked, Integer unlockAfterEpisodeNumber) {}

    public EpisodeLockState evaluateSequentialLock(
            Episode target,
            List<Episode> orderedActiveEpisodes,
            Set<Long> passedEpisodeIds,
            boolean bypassForBeta) {
        if (bypassForBeta || passedEpisodeIds.contains(target.getId())) {
            return new EpisodeLockState(false, null);
        }
        for (int i = 0; i < orderedActiveEpisodes.size(); i++) {
            if (orderedActiveEpisodes.get(i).getId().equals(target.getId())) {
                if (i == 0) {
                    return new EpisodeLockState(false, null);
                }
                Episode prev = orderedActiveEpisodes.get(i - 1);
                boolean prevPassed = passedEpisodeIds.contains(prev.getId());
                return prevPassed
                        ? new EpisodeLockState(false, null)
                        : new EpisodeLockState(true, prev.getEpisodeNumber());
            }
        }
        return new EpisodeLockState(false, null);
    }

    @Transactional(readOnly = true)
    public void assertEpisodeUnlocked(User user, Episode episode, boolean betaUnlockRequested) {
        boolean allowBetaBypass = betaUnlockRequested && user.hasBetaAccess();
        if (allowBetaBypass) {
            return;
        }
        List<Episode> active = episodeRepository.findAllActiveOrderByEpisodeNumberAsc();
        Set<Long> passedIds = quizAttemptRepository.findPassedEpisodeIdsByStudentId(user.getId());
        EpisodeLockState lockState = evaluateSequentialLock(episode, active, passedIds, false);
        if (lockState.locked()) {
            throw new SecurityException("Episode " + episode.getEpisodeNumber()
                    + " is locked. Pass Episode " + lockState.unlockAfterEpisodeNumber() + " first.");
        }
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
        int verified = Math.min(required, session.getVerifiedWatchSeconds());
        boolean unlocked = alreadyPassed || verified >= required;

        return new WatchStatusResponse(
                episode.getId(),
                session.getLockedWatchMode(),
                session.isModeLocked(),
                alreadyPassed ? required : verified,
                required,
                unlocked,
                cooldown
        );
    }

    @Transactional
    public WatchStatusResponse recordHeartbeat(
            String username,
            Long episodeId,
            WatchHeartbeatRequest req,
            boolean betaUnlockRequested) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        // Never allow client-supplied videoDurationSeconds to mutate Episode.durationSeconds.
        if (req != null && req.videoDurationSeconds() != null
                && Math.abs(episode.getDurationSeconds() - req.videoDurationSeconds()) > 5) {
            log.debug("Ignoring client-reported videoDurationSeconds={} for episodeId={} (authoritative={})",
                    req.videoDurationSeconds(), episodeId, episode.getDurationSeconds());
        }

        boolean alreadyPassed = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episode.getId());
        int required = computeRequiredWatchSeconds(episode);

        // Ignore client-asserted external=true completely; only real player playback counts.
        if (req != null && req.external()) {
            return getWatchStatus(student, episode, alreadyPassed);
        }

        WatchMode requestedMode = (req != null && req.watchMode() != null) ? req.watchMode() : WatchMode.ARABIC_SUB;
        boolean videoEnded = req != null && req.videoEnded();
        int reportedDelta = (req != null && req.watchedDeltaSeconds() != null) ? Math.max(0, req.watchedDeltaSeconds()) : 0;
        boolean isPlaying = req != null && Boolean.TRUE.equals(req.playing());
        boolean hasPlaybackActivity = isPlaying || reportedDelta > 0 || videoEnded;

        if (!hasPlaybackActivity) {
            return getWatchStatus(student, episode, alreadyPassed);
        }

        assertEpisodeUnlocked(student, episode, betaUnlockRequested);

        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), episode.getId())
                .orElseGet(() -> new WatchSession(student, episode, requestedMode));

        LocalDateTime now = LocalDateTime.now();

        if (!session.isModeLocked()) {
            session.setLockedWatchMode(requestedMode);
            session.setModeLocked(true);
        }

        if (session.getLastHeartbeatAt() == null) {
            session.setLastHeartbeatAt(now);
        } else {
            long elapsedMillis = Math.max(0L, Duration.between(session.getLastHeartbeatAt(), now).toMillis());
            int elapsedWholeSeconds = (int) Math.min(Integer.MAX_VALUE, elapsedMillis / 1000L);
            int wallCap = Math.min(MAX_HEARTBEAT_CREDIT_SECONDS, elapsedWholeSeconds);
            int credit = (req != null && req.watchedDeltaSeconds() != null)
                    ? Math.min(reportedDelta, wallCap)
                    : 0;

            if (credit > 0) {
                session.setVerifiedWatchSeconds(Math.min(required, session.getVerifiedWatchSeconds() + credit));
            }
            if (reportedDelta == 0 || elapsedWholeSeconds >= 1) {
                session.setLastHeartbeatAt(now);
            }
        }

        if (videoEnded) {
            session.setVideoEnded(true);
        }

        watchSessionRepository.save(session);

        int verified = Math.min(required, session.getVerifiedWatchSeconds());
        long cooldown = alreadyPassed ? 0L : getRetryCooldownRemainingSeconds(student.getId(), episode.getId());
        boolean unlocked = alreadyPassed || verified >= required;

        return new WatchStatusResponse(
                episode.getId(),
                session.getLockedWatchMode(),
                session.isModeLocked(),
                alreadyPassed ? required : verified,
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

    @Transactional
    public WatchStatusResponse betaInstantUnlockWatch(User user, Long episodeId, WatchMode mode) {
        if (!user.hasBetaAccess()) {
            throw new SecurityException("Beta test controls are restricted to administrators and authorized beta testers.");
        }
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        int required = computeRequiredWatchSeconds(episode);
        WatchMode targetMode = mode != null ? mode : WatchMode.ARABIC_SUB;

        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(user.getId(), episode.getId())
                .orElseGet(() -> new WatchSession(user, episode, targetMode));

        session.setLockedWatchMode(targetMode);
        session.setModeLocked(true);
        session.setVerifiedWatchSeconds(required);
        session.setVideoEnded(true);
        session.setLastHeartbeatAt(null);
        watchSessionRepository.save(session);

        long cooldown = getRetryCooldownRemainingSeconds(user.getId(), episode.getId());
        return new WatchStatusResponse(
                episode.getId(),
                session.getLockedWatchMode(),
                true,
                required,
                required,
                true,
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
        return opt.get().getVerifiedWatchSeconds() >= required;
    }

    public WatchMode resolveEffectiveWatchMode(Long studentId, Long episodeId, WatchMode fallbackMode) {
        return watchSessionRepository.findByStudentIdAndEpisodeId(studentId, episodeId)
                .filter(WatchSession::isModeLocked)
                .map(WatchSession::getLockedWatchMode)
                .orElse(fallbackMode != null ? fallbackMode : WatchMode.ARABIC_SUB);
    }
}
