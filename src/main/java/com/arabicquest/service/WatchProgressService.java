// Tracks verified watch time, sequential episode unlocks, retry cooldowns, and background quiz triggers.
package com.arabicquest.service;

import com.arabicquest.dto.WatchHeartbeatRequest;
import com.arabicquest.dto.WatchStatusResponse;
import com.arabicquest.model.Episode;
import com.arabicquest.model.EpisodeProgressState;
import com.arabicquest.model.QuizAttempt;
import com.arabicquest.model.Role;
import com.arabicquest.model.StudentEpisodeOverride;
import com.arabicquest.model.User;
import com.arabicquest.model.WatchMode;
import com.arabicquest.model.WatchSession;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.EpisodeRewardClaimRepository;
import com.arabicquest.repository.QuizAttemptRepository;
import com.arabicquest.repository.StudentEpisodeOverrideRepository;
import com.arabicquest.repository.UserRepository;
import com.arabicquest.repository.WatchSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class WatchProgressService {

    private static final Logger log = LoggerFactory.getLogger(WatchProgressService.class);
    private static final int MAX_HEARTBEAT_CREDIT_SECONDS = 25;
    public static final int DEFAULT_REQUIRED_CAP_SECONDS = 1800; // 30 minutes cap

    private final WatchSessionRepository watchSessionRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final StudentEpisodeOverrideRepository studentEpisodeOverrideRepository;
    private final UserRepository userRepository;
    private final EpisodeRepository episodeRepository;
    private final AutoQuizGenerationService autoQuizGenerationService;
    private final int requiredCapSeconds;
    private final long retryCooldownSeconds;

    public WatchProgressService(
            WatchSessionRepository watchSessionRepository,
            QuizAttemptRepository quizAttemptRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            StudentEpisodeOverrideRepository studentEpisodeOverrideRepository,
            UserRepository userRepository,
            EpisodeRepository episodeRepository,
            int requiredCapSeconds,
            long retryCooldownSeconds) {
        this(watchSessionRepository, quizAttemptRepository, episodeRewardClaimRepository,
                studentEpisodeOverrideRepository, userRepository, episodeRepository,
                requiredCapSeconds, retryCooldownSeconds, null);
    }

    @Autowired
    public WatchProgressService(
            WatchSessionRepository watchSessionRepository,
            QuizAttemptRepository quizAttemptRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            StudentEpisodeOverrideRepository studentEpisodeOverrideRepository,
            UserRepository userRepository,
            EpisodeRepository episodeRepository,
            @Value("${app.watch.required-cap-seconds:1800}") int requiredCapSeconds,
            @Value("${app.quiz.retry-cooldown-seconds:${QUIZ_RETRY_COOLDOWN_SECONDS:600}}") long retryCooldownSeconds,
            @Lazy AutoQuizGenerationService autoQuizGenerationService) {
        this.watchSessionRepository = watchSessionRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.studentEpisodeOverrideRepository = studentEpisodeOverrideRepository;
        this.userRepository = userRepository;
        this.episodeRepository = episodeRepository;
        this.requiredCapSeconds = requiredCapSeconds;
        this.retryCooldownSeconds = retryCooldownSeconds;
        this.autoQuizGenerationService = autoQuizGenerationService;
    }

    public long getConfiguredRetryCooldownSeconds() {
        return Math.max(0L, retryCooldownSeconds);
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
        return evaluateSequentialLock(target, orderedActiveEpisodes, passedEpisodeIds, null, bypassForBeta);
    }

    public EpisodeLockState evaluateSequentialLock(
            Episode target,
            List<Episode> orderedActiveEpisodes,
            Set<Long> passedEpisodeIds,
            StudentEpisodeOverride.OverrideState adminOverride,
            boolean bypassForBeta) {
        if (bypassForBeta) {
            return new EpisodeLockState(false, null);
        }
        if (adminOverride == StudentEpisodeOverride.OverrideState.UNLOCKED) {
            return new EpisodeLockState(false, null);
        }
        if (adminOverride == StudentEpisodeOverride.OverrideState.LOCKED) {
            return new EpisodeLockState(true, null);
        }
        if (passedEpisodeIds.contains(target.getId())) {
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

    public EpisodeProgressState computeEpisodeProgressState(
            EpisodeLockState lockState,
            boolean passed,
            boolean pendingReview,
            boolean mcqPassed,
            WatchStatusResponse watchStatus,
            boolean quizBankReady) {
        if (lockState.locked()) {
            return EpisodeProgressState.LOCKED;
        }
        if (passed) {
            return EpisodeProgressState.PASSED;
        }
        if (pendingReview) {
            return EpisodeProgressState.PENDING_REVIEW;
        }
        boolean watchMet = (watchStatus != null && watchStatus.quizUnlocked()) || mcqPassed;
        if (watchMet) {
            return quizBankReady ? EpisodeProgressState.QUIZ_READY : EpisodeProgressState.QUIZ_NOT_READY;
        }
        if (watchStatus != null && (watchStatus.watchStarted() || watchStatus.verifiedWatchSeconds() > 0 || watchStatus.modeLocked())) {
            return EpisodeProgressState.WATCHING;
        }
        return EpisodeProgressState.AVAILABLE;
    }

    @Transactional(readOnly = true)
    public void assertEpisodeUnlocked(User user, Episode episode, boolean betaUnlockRequested) {
        if (episode.isArchived() || !episode.isEnabled()) {
            throw new SecurityException("Episode is currently unavailable.");
        }
        boolean allowBetaBypass = betaUnlockRequested && user.hasBetaAccess();
        if (episode.getPlaylist() == null
                || episode.getPlaylist().isArchived()
                || (!episode.getPlaylist().isPublished() && !allowBetaBypass)) {
            throw new SecurityException("Course is not available.");
        }
        if (allowBetaBypass) {
            return;
        }
        StudentEpisodeOverride.OverrideState adminOverride = studentEpisodeOverrideRepository
                .findByStudentIdAndEpisodeId(user.getId(), episode.getId())
                .map(StudentEpisodeOverride::getOverrideState)
                .orElse(null);

        Long playlistId = episode.getPlaylist().getId();
        List<Episode> courseActiveEpisodes = episodeRepository.findActiveByPlaylistIdOrderByEpisodeNumberAsc(playlistId);
        Set<Long> passedIds = episodeRewardClaimRepository.findClaimedEpisodeIdsByStudentId(user.getId());
        EpisodeLockState lockState = evaluateSequentialLock(episode, courseActiveEpisodes, passedIds, adminOverride, false);
        if (lockState.locked()) {
            if (lockState.unlockAfterEpisodeNumber() != null) {
                throw new SecurityException("Episode " + episode.getEpisodeNumber()
                        + " is locked. Complete Episode " + lockState.unlockAfterEpisodeNumber() + " in this course first.");
            }
            throw new SecurityException("Episode " + episode.getEpisodeNumber() + " has been locked by the administrator.");
        }
    }

    public record StudentEpisodeOverrideView(
            Long episodeId,
            Long courseId,
            String courseTitle,
            int episodeNumber,
            String episodeTitle,
            boolean passed,
            boolean effectiveLocked,
            String overrideState,
            int verifiedWatchSeconds,
            int requiredWatchSeconds,
            boolean quizUnlocked,
            String watchQuizStatus
    ) {}

    @Transactional(readOnly = true)
    public List<StudentEpisodeOverrideView> listStudentEpisodeOverrides(Long studentId) {
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        if (student.getRole() != Role.ROLE_STUDENT) {
            throw new IllegalArgumentException("Overrides apply to student accounts only");
        }

        List<Episode> activeEpisodes = episodeRepository.findAllActiveOrderByEpisodeNumberAsc();
        Map<Long, List<Episode>> byCourse = new LinkedHashMap<>();
        for (Episode ep : activeEpisodes) {
            byCourse.computeIfAbsent(ep.getPlaylist().getId(), k -> new ArrayList<>()).add(ep);
        }

        Set<Long> passedIds = episodeRewardClaimRepository.findClaimedEpisodeIdsByStudentId(studentId);
        Map<Long, WatchSession> sessionsByEp = new HashMap<>();
        for (WatchSession ws : watchSessionRepository.findByStudentId(studentId)) {
            if (ws.getEpisode() != null) {
                sessionsByEp.put(ws.getEpisode().getId(), ws);
            }
        }
        Map<Long, StudentEpisodeOverride.OverrideState> overridesByEp = new HashMap<>();
        for (StudentEpisodeOverride ov : studentEpisodeOverrideRepository.findByStudentIdWithEpisode(studentId)) {
            if (ov.getOverrideState() != null) {
                overridesByEp.put(ov.getEpisode().getId(), ov.getOverrideState());
            }
        }

        List<StudentEpisodeOverrideView> result = new ArrayList<>(activeEpisodes.size());
        for (Episode ep : activeEpisodes) {
            List<Episode> courseEps = byCourse.getOrDefault(ep.getPlaylist().getId(), List.of(ep));
            StudentEpisodeOverride.OverrideState ov = overridesByEp.get(ep.getId());
            boolean epPassed = passedIds.contains(ep.getId());
            int requiredSecs = computeRequiredWatchSeconds(ep);
            WatchSession session = sessionsByEp.get(ep.getId());
            int verifiedSecs = epPassed ? requiredSecs
                    : (session != null ? Math.min(requiredSecs, session.getVerifiedWatchSeconds()) : 0);
            boolean epQuizUnlocked = epPassed || requiredSecs == 0 || verifiedSecs >= requiredSecs;
            String watchQuizStatus = epPassed ? "PASSED"
                    : (epQuizUnlocked ? "QUIZ_UNLOCKED" : (verifiedSecs > 0 ? "WATCHING" : "NOT_STARTED"));
            EpisodeLockState lockState = evaluateSequentialLock(ep, courseEps, passedIds, ov, false);
            result.add(new StudentEpisodeOverrideView(
                    ep.getId(),
                    ep.getPlaylist().getId(),
                    ep.getPlaylist().getTitle(),
                    ep.getEpisodeNumber(),
                    ep.getTitle(),
                    passedIds.contains(ep.getId()),
                    lockState.locked(),
                    ov != null ? ov.name() : "AUTO",
                    verifiedSecs,
                    requiredSecs,
                    epQuizUnlocked,
                    watchQuizStatus
            ));
        }
        return result;
    }

    @Transactional
    public StudentEpisodeOverride.OverrideState setStudentEpisodeOverride(
            Long studentId,
            Long episodeId,
            StudentEpisodeOverride.OverrideState state) {
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        if (student.getRole() != Role.ROLE_STUDENT) {
            throw new IllegalArgumentException("Overrides apply to student accounts only");
        }
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        StudentEpisodeOverride override = studentEpisodeOverrideRepository
                .findByStudentIdAndEpisodeId(studentId, episodeId)
                .orElseGet(() -> new StudentEpisodeOverride(student, episode));
        override.setOverrideState(state);
        studentEpisodeOverrideRepository.save(override);
        return override.getOverrideState();
    }

    @Transactional
    public Map<String, Object> unlockQuizForStudent(Long studentId, Long episodeId) {
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        if (student.getRole() != Role.ROLE_STUDENT) {
            throw new IllegalArgumentException("Quiz unlock applies to student accounts only");
        }
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        int required = computeRequiredWatchSeconds(episode);
        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(studentId, episodeId)
                .orElseGet(() -> new WatchSession(student, episode, WatchMode.ARABIC_SUB));
        session.setLockedWatchMode(WatchMode.ARABIC_SUB);
        session.setModeLocked(false);
        session.setVerifiedWatchSeconds(required);
        session.setVideoEnded(true);
        watchSessionRepository.save(session);

        StudentEpisodeOverride override = studentEpisodeOverrideRepository
                .findByStudentIdAndEpisodeId(studentId, episodeId).orElse(null);
        StudentEpisodeOverride.OverrideState currentOv = override != null ? override.getOverrideState() : null;
        if (episode.getPlaylist() != null) {
            List<Episode> courseEps = episodeRepository.findActiveByPlaylistIdOrderByEpisodeNumberAsc(episode.getPlaylist().getId());
            Set<Long> passedIds = episodeRewardClaimRepository.findClaimedEpisodeIdsByStudentId(studentId);
            if (evaluateSequentialLock(episode, courseEps, passedIds, currentOv, false).locked()) {
                StudentEpisodeOverride targetOv = override != null ? override : new StudentEpisodeOverride(student, episode);
                targetOv.setOverrideState(StudentEpisodeOverride.OverrideState.UNLOCKED);
                studentEpisodeOverrideRepository.save(targetOv);
                currentOv = StudentEpisodeOverride.OverrideState.UNLOCKED;
            }
        }
        return Map.of(
                "studentId", studentId,
                "episodeId", episodeId,
                "verifiedWatchSeconds", required,
                "requiredWatchSeconds", required,
                "quizUnlocked", true,
                "overrideState", currentOv != null ? currentOv.name() : "AUTO"
        );
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
        LocalDateTime failTime = lastFail.get().getAttemptedAt();
        LocalDateTime resetAt = studentEpisodeOverrideRepository
                .findByStudentIdAndEpisodeId(studentId, episodeId)
                .map(StudentEpisodeOverride::getCooldownResetAt)
                .orElse(null);
        if (resetAt != null && !failTime.isAfter(resetAt)) {
            return 0L;
        }
        long elapsed = Duration.between(failTime, LocalDateTime.now()).getSeconds();
        return Math.max(0L, retryCooldownSeconds - elapsed);
    }

    @Transactional
    public void resetRetryCooldownForStudent(User student, Episode episode) {
        StudentEpisodeOverride override = studentEpisodeOverrideRepository
                .findByStudentIdAndEpisodeId(student.getId(), episode.getId())
                .orElseGet(() -> new StudentEpisodeOverride(student, episode));
        override.setCooldownResetAt(LocalDateTime.now().plusSeconds(1));
        studentEpisodeOverrideRepository.save(override);
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
                    cooldown,
                    alreadyPassed
            );
        }

        WatchSession session = opt.get();
        int verified = Math.min(required, session.getVerifiedWatchSeconds());
        boolean unlocked = alreadyPassed || verified >= required;
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

    @Transactional
    public WatchStatusResponse recordHeartbeat(
            String username,
            Long episodeId,
            WatchHeartbeatRequest req,
            boolean betaUnlockRequested) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        // Never allow client-supplied videoDurationSeconds to mutate Episode.durationSeconds.
        if (req != null && req.videoDurationSeconds() != null
                && Math.abs(episode.getDurationSeconds() - req.videoDurationSeconds()) > 5) {
            log.debug("Ignoring client-reported videoDurationSeconds={} for episodeId={} (authoritative={})",
                    req.videoDurationSeconds(), episodeId, episode.getDurationSeconds());
        }

        boolean alreadyPassed = episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId());
        int required = computeRequiredWatchSeconds(episode);

        // Ignore client-asserted external=true completely; only real player playback counts.
        if (req != null && req.external()) {
            return getWatchStatus(student, episode, alreadyPassed);
        }

        WatchMode effectiveMode = WatchMode.ARABIC_SUB;
        boolean videoEnded = req != null && req.videoEnded();
        int reportedDelta = (req != null && req.watchedDeltaSeconds() != null) ? Math.max(0, req.watchedDeltaSeconds()) : 0;
        boolean isPlaying = req != null && Boolean.TRUE.equals(req.playing());
        boolean hasPlaybackActivity = isPlaying || reportedDelta > 0 || videoEnded;

        if (!hasPlaybackActivity) {
            return getWatchStatus(student, episode, alreadyPassed);
        }

        assertEpisodeUnlocked(student, episode, betaUnlockRequested);

        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), episode.getId())
                .orElseGet(() -> new WatchSession(student, episode, effectiveMode));

        LocalDateTime now = LocalDateTime.now();
        boolean wasFirstHeartbeat = (session.getLastHeartbeatAt() == null);
        int beforeVerified = session.getVerifiedWatchSeconds();

        session.setLockedWatchMode(effectiveMode);
        session.setModeLocked(false);

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
        int quarterMark = required > 0 ? Math.max(1, (int) Math.ceil(required * 0.25)) : 0;
        boolean crossedQuarter = required > 0 && beforeVerified < quarterMark && verified >= quarterMark;
        if (autoQuizGenerationService != null && (wasFirstHeartbeat || beforeVerified == 0 || crossedQuarter)) {
            autoQuizGenerationService.triggerIfEpisodeBankIncomplete(episode.getId());
        }

        long cooldown = alreadyPassed ? 0L : getRetryCooldownRemainingSeconds(student.getId(), episode.getId());
        boolean unlocked = alreadyPassed || verified >= required;

        return new WatchStatusResponse(
                episode.getId(),
                WatchMode.ARABIC_SUB,
                false,
                alreadyPassed ? required : verified,
                required,
                unlocked,
                cooldown,
                true
        );
    }

    @Transactional
    public WatchStatusResponse resetSessionAndSwitchMode(String username, Long episodeId, WatchMode newMode) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        boolean alreadyPassed = episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId());
        boolean mcqPassed = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episode.getId());
        if (alreadyPassed || mcqPassed) {
            throw new IllegalArgumentException("Watch mode cannot be changed after passing the episode quiz.");
        }
        int required = computeRequiredWatchSeconds(episode);
        WatchMode targetMode = WatchMode.ARABIC_SUB;

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
                WatchMode.ARABIC_SUB,
                false,
                alreadyPassed ? required : 0,
                required,
                alreadyPassed || required == 0,
                cooldown,
                alreadyPassed
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
        WatchMode targetMode = WatchMode.ARABIC_SUB;

        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(user.getId(), episode.getId())
                .orElseGet(() -> new WatchSession(user, episode, targetMode));

        session.setLockedWatchMode(targetMode);
        session.setModeLocked(false);
        session.setVerifiedWatchSeconds(required);
        session.setVideoEnded(true);
        session.setLastHeartbeatAt(null);
        watchSessionRepository.save(session);

        long cooldown = getRetryCooldownRemainingSeconds(user.getId(), episode.getId());
        return new WatchStatusResponse(
                episode.getId(),
                WatchMode.ARABIC_SUB,
                false,
                required,
                required,
                true,
                cooldown,
                true
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
        return WatchMode.ARABIC_SUB;
    }
}
