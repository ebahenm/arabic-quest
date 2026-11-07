package com.arabicquest.service;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.dto.CourseCatalogItemResponse;
import com.arabicquest.dto.CourseDetailStudentResponse;
import com.arabicquest.dto.EpisodeStudentResponse;
import com.arabicquest.dto.WatchStatusResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class CourseService {

    private final PlaylistRepository playlistRepository;
    private final EpisodeRepository episodeRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final PlaylistBonusClaimRepository playlistBonusClaimRepository;
    private final OpenAnswerSubmissionRepository openAnswerSubmissionRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final QuestionRepository questionRepository;
    private final OpenQuestionRepository openQuestionRepository;
    private final StudentEpisodeOverrideRepository studentEpisodeOverrideRepository;
    private final UserRepository userRepository;
    private final WatchProgressService watchProgressService;

    public CourseService(
            PlaylistRepository playlistRepository,
            EpisodeRepository episodeRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            PlaylistBonusClaimRepository playlistBonusClaimRepository,
            OpenAnswerSubmissionRepository openAnswerSubmissionRepository,
            QuizAttemptRepository quizAttemptRepository,
            QuestionRepository questionRepository,
            OpenQuestionRepository openQuestionRepository,
            StudentEpisodeOverrideRepository studentEpisodeOverrideRepository,
            UserRepository userRepository,
            WatchProgressService watchProgressService) {
        this.playlistRepository = playlistRepository;
        this.episodeRepository = episodeRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.playlistBonusClaimRepository = playlistBonusClaimRepository;
        this.openAnswerSubmissionRepository = openAnswerSubmissionRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.studentEpisodeOverrideRepository = studentEpisodeOverrideRepository;
        this.userRepository = userRepository;
        this.watchProgressService = watchProgressService;
    }

    @Transactional(readOnly = true)
    public List<CourseCatalogItemResponse> getPublishedCourseCatalog(String username) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<Playlist> courses = playlistRepository.findPublishedActiveOrderBySortOrderAsc();
        Set<Long> claimedEpisodeIds = episodeRewardClaimRepository.findClaimedEpisodeIdsByStudentId(student.getId());
        List<OpenAnswerSubmission> submissions = openAnswerSubmissionRepository
                .findByStudentIdWithEpisodeAndPlaylist(student.getId());

        Map<Long, BigDecimal> pendingByCourse = new HashMap<>();
        for (OpenAnswerSubmission sub : submissions) {
            if (sub.getStatus() == OpenAnswerStatus.PENDING
                    && !claimedEpisodeIds.contains(sub.getEpisode().getId())) {
                Playlist p = sub.getEpisode().getPlaylist();
                if (p != null) {
                    BigDecimal rate = sub.getWatchMode() == WatchMode.ENGLISH_DUB
                            ? resolveRateEnglishDub(p)
                            : resolveRateArabicSub(p);
                    pendingByCourse.merge(p.getId(), rate, BigDecimal::add);
                }
            }
        }

        List<CourseCatalogItemResponse> catalog = new ArrayList<>(courses.size());
        for (Playlist course : courses) {
            catalog.add(buildCatalogItem(student, course, pendingByCourse.getOrDefault(course.getId(), BigDecimal.ZERO)));
        }
        return catalog;
    }

    @Transactional(readOnly = true)
    public CourseDetailStudentResponse getStudentCourseDetail(String username, Long courseId, boolean betaUnlockRequested) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Playlist course = playlistRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found"));

        boolean allowBetaBypass = betaUnlockRequested && student.hasBetaAccess();
        if (course.isArchived() || (!course.isPublished() && !allowBetaBypass)) {
            throw new SecurityException("Course is not available.");
        }

        Set<Long> claimedEpisodeIds = episodeRewardClaimRepository.findClaimedEpisodeIdsByStudentId(student.getId());
        BigDecimal pendingForCourse = BigDecimal.ZERO;
        for (OpenAnswerSubmission sub : openAnswerSubmissionRepository.findByStudentIdWithEpisodeAndPlaylist(student.getId())) {
            if (sub.getStatus() == OpenAnswerStatus.PENDING
                    && sub.getEpisode().getPlaylist().getId().equals(course.getId())
                    && !claimedEpisodeIds.contains(sub.getEpisode().getId())) {
                BigDecimal rate = sub.getWatchMode() == WatchMode.ENGLISH_DUB
                        ? resolveRateEnglishDub(course)
                        : resolveRateArabicSub(course);
                pendingForCourse = pendingForCourse.add(rate);
            }
        }

        CourseCatalogItemResponse summary = buildCatalogItem(student, course, pendingForCourse);
        List<Episode> activeEpisodes = episodeRepository.findActiveByPlaylistIdOrderByEpisodeNumberAsc(course.getId());
        List<EpisodeStudentResponse> episodeResponses = buildEpisodeStudentResponses(student, activeEpisodes, allowBetaBypass);

        return new CourseDetailStudentResponse(summary, episodeResponses);
    }

    @Transactional(readOnly = true)
    public List<EpisodeStudentResponse> getStudentEpisodes(String username, Long courseId, boolean betaUnlockRequested) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        boolean allowBetaBypass = betaUnlockRequested && student.hasBetaAccess();

        List<Episode> episodes;
        if (courseId != null) {
            Playlist course = playlistRepository.findById(courseId)
                    .orElseThrow(() -> new IllegalArgumentException("Course not found"));
            if (course.isArchived() || (!course.isPublished() && !allowBetaBypass)) {
                throw new SecurityException("Course is not available.");
            }
            episodes = episodeRepository.findActiveByPlaylistIdOrderByEpisodeNumberAsc(courseId);
        } else {
            episodes = episodeRepository.findAllActiveOrderByEpisodeNumberAsc().stream()
                    .filter(ep -> ep.getPlaylist() != null
                            && !ep.getPlaylist().isArchived()
                            && (ep.getPlaylist().isPublished() || allowBetaBypass))
                    .toList();
        }

        return buildEpisodeStudentResponses(student, episodes, allowBetaBypass);
    }

    public List<EpisodeStudentResponse> buildEpisodeStudentResponses(
            User student,
            List<Episode> episodes,
            boolean allowBetaBypass) {

        Map<Long, List<Episode>> courseEpisodesByPlaylistId = new LinkedHashMap<>();
        for (Episode ep : episodes) {
            courseEpisodesByPlaylistId
                    .computeIfAbsent(ep.getPlaylist().getId(), k -> new ArrayList<>())
                    .add(ep);
        }

        Set<Long> passedIds = episodeRewardClaimRepository.findClaimedEpisodeIdsByStudentId(student.getId());
        Set<Long> mcqPassedIds = quizAttemptRepository.findPassedEpisodeIdsByStudentId(student.getId());

        Set<Long> pendingReviewEpisodeIds = new HashSet<>();
        for (OpenAnswerSubmission sub : openAnswerSubmissionRepository.findByStudentIdWithEpisodeAndPlaylist(student.getId())) {
            if (sub.getStatus() == OpenAnswerStatus.PENDING) {
                pendingReviewEpisodeIds.add(sub.getEpisode().getId());
            }
        }

        Map<Long, Long> approvedMcqCounts = new HashMap<>();
        for (Object[] row : questionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.APPROVED)) {
            approvedMcqCounts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        Map<Long, Long> approvedOpenCounts = new HashMap<>();
        for (Object[] row : openQuestionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.APPROVED)) {
            approvedOpenCounts.put((Long) row[0], ((Number) row[1]).longValue());
        }

        Map<Long, StudentEpisodeOverride.OverrideState> overridesByEp = new HashMap<>();
        for (StudentEpisodeOverride ov : studentEpisodeOverrideRepository.findByStudentIdWithEpisode(student.getId())) {
            if (ov.getOverrideState() != null) {
                overridesByEp.put(ov.getEpisode().getId(), ov.getOverrideState());
            }
        }

        return episodes.stream().map(ep -> {
            Playlist course = ep.getPlaylist();
            List<Episode> sameCourseEpisodes = courseEpisodesByPlaylistId.getOrDefault(course.getId(), List.of(ep));
            boolean passed = passedIds.contains(ep.getId());
            boolean pendingReview = pendingReviewEpisodeIds.contains(ep.getId());
            boolean mcqPassed = mcqPassedIds.contains(ep.getId());
            boolean quizBankReady = approvedMcqCounts.getOrDefault(ep.getId(), 0L) >= QuizService.SESSION_QUESTION_COUNT
                    && approvedOpenCounts.getOrDefault(ep.getId(), 0L) >= 1L;

            WatchProgressService.EpisodeLockState lockState =
                    watchProgressService.evaluateSequentialLock(
                            ep, sameCourseEpisodes, passedIds, overridesByEp.get(ep.getId()), allowBetaBypass);
            WatchStatusResponse watchStatus = watchProgressService.getWatchStatus(student, ep, passed);
            EpisodeProgressState progressState = watchProgressService.computeEpisodeProgressState(
                    lockState, passed, pendingReview, mcqPassed, watchStatus, quizBankReady);

            BigDecimal rateAr = resolveRateArabicSub(course);
            BigDecimal rateEn = resolveRateEnglishDub(course);

            return new EpisodeStudentResponse(
                    ep.getId(),
                    course.getId(),
                    course.getTitle(),
                    ep.getEpisodeNumber(),
                    ep.getTitle(),
                    ep.getYoutubeVideoId(),
                    ep.getDurationSeconds(),
                    passed && !lockState.locked(),
                    lockState.locked(),
                    lockState.unlockAfterEpisodeNumber(),
                    progressState.name(),
                    progressState.getLabel(),
                    quizBankReady,
                    rateAr,
                    rateEn,
                    watchStatus
            );
        }).toList();
    }

    private CourseCatalogItemResponse buildCatalogItem(User student, Playlist course, BigDecimal pendingReviewAmount) {
        int totalEpisodes = (int) episodeRepository.countActiveByPlaylistId(course.getId());
        int passedEpisodes = (int) episodeRewardClaimRepository
                .countClaimedActiveEpisodesByStudentAndPlaylist(student.getId(), course.getId());

        int progressPct = totalEpisodes > 0
                ? Math.min(100, (int) Math.round((passedEpisodes * 100.0) / totalEpisodes))
                : 0;

        BigDecimal rateAr = resolveRateArabicSub(course);
        BigDecimal rateEn = resolveRateEnglishDub(course);
        BigDecimal bonus = resolveCompletionBonus(course);

        BigDecimal episodeRewardsEarned = episodeRewardClaimRepository
                .calculateEpisodeRewardsByStudentAndPlaylist(student.getId(), course.getId());
        BigDecimal bonusRewardsEarned = playlistBonusClaimRepository
                .calculateBonusesByStudentAndPlaylist(student.getId(), course.getId());
        BigDecimal earnedAmount = episodeRewardsEarned.add(bonusRewardsEarned);
        boolean bonusEarned = playlistBonusClaimRepository.existsByStudentIdAndPlaylistId(student.getId(), course.getId());

        int minForBonus = Math.max(1, course.getMinEpisodesForBonus());
        boolean bonusReachable = totalEpisodes > 0 && totalEpisodes >= minForBonus;
        BigDecimal possibleAmount = rateAr.multiply(BigDecimal.valueOf(totalEpisodes))
                .add(bonusReachable ? bonus : BigDecimal.ZERO);

        return new CourseCatalogItemResponse(
                course.getId(),
                course.getTitle(),
                course.getDescription(),
                course.getCoverImageUrl(),
                course.getYoutubePlaylistId(),
                course.getSortOrder(),
                totalEpisodes,
                passedEpisodes,
                progressPct,
                rateAr,
                rateEn,
                bonus,
                bonusEarned,
                earnedAmount,
                pendingReviewAmount,
                possibleAmount
        );
    }

    public static BigDecimal resolveRateArabicSub(Playlist course) {
        return (course != null && course.getArabicRewardRate() != null)
                ? course.getArabicRewardRate()
                : BrandConstants.DEFAULT_RATE_ARABIC_SUB;
    }

    public static BigDecimal resolveRateEnglishDub(Playlist course) {
        return (course != null && course.getEnglishRewardRate() != null)
                ? course.getEnglishRewardRate()
                : BrandConstants.DEFAULT_RATE_ENGLISH_DUB;
    }

    public static BigDecimal resolveCompletionBonus(Playlist course) {
        return (course != null && course.getCompletionBonus() != null)
                ? course.getCompletionBonus()
                : BrandConstants.DEFAULT_COMPLETION_BONUS;
    }
}
