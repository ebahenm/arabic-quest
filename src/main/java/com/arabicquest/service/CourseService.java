// Manages published courses, episode locks/unlock states, YouTube playlist sync, and favorites.
package com.arabicquest.service;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.dto.*;
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
    private final StudentFavoriteEpisodeRepository studentFavoriteEpisodeRepository;
    private final UserRepository userRepository;
    private final WatchProgressService watchProgressService;
    private final YouTubePlaylistService youTubePlaylistService;

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
            StudentFavoriteEpisodeRepository studentFavoriteEpisodeRepository,
            UserRepository userRepository,
            WatchProgressService watchProgressService,
            YouTubePlaylistService youTubePlaylistService) {
        this.playlistRepository = playlistRepository;
        this.episodeRepository = episodeRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.playlistBonusClaimRepository = playlistBonusClaimRepository;
        this.openAnswerSubmissionRepository = openAnswerSubmissionRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.questionRepository = questionRepository;
        this.openQuestionRepository = openQuestionRepository;
        this.studentEpisodeOverrideRepository = studentEpisodeOverrideRepository;
        this.studentFavoriteEpisodeRepository = studentFavoriteEpisodeRepository;
        this.userRepository = userRepository;
        this.watchProgressService = watchProgressService;
        this.youTubePlaylistService = youTubePlaylistService;
    }

    @Transactional(readOnly = true)
    public List<CourseCatalogItemResponse> getPublishedCourseCatalog(String username) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<Playlist> courses = playlistRepository.findPublishedActiveOrderBySortOrderAsc();
        List<CourseCatalogItemResponse> catalog = new ArrayList<>(courses.size());
        for (Playlist course : courses) {
            catalog.add(buildCatalogItem(student, course, BigDecimal.ZERO));
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

        CourseCatalogItemResponse summary = buildCatalogItem(student, course, BigDecimal.ZERO);
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
        Set<Long> favoriteIds = studentFavoriteEpisodeRepository.findFavoriteEpisodeIdsByStudentId(student.getId());

        Set<Long> pendingReviewEpisodeIds = Collections.emptySet();

        Map<Long, Long> approvedMcqCounts = toCountMap(questionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.APPROVED));
        Map<Long, Long> approvedOpenCounts = toCountMap(openQuestionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.APPROVED));

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
                    favoriteIds.contains(ep.getId()),
                    rateAr,
                    rateEn,
                    watchStatus
            );
        }).toList();
    }

    @Transactional
    public Map<String, Object> favoriteEpisode(String username, Long episodeId, boolean betaUnlockRequested) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        boolean allowBetaBypass = betaUnlockRequested && student.hasBetaAccess();
        Playlist course = episode.getPlaylist();
        if (episode.isArchived()
                || !episode.isEnabled()
                || course == null
                || course.isArchived()
                || (!course.isPublished() && !allowBetaBypass)) {
            throw new SecurityException("Course is not available.");
        }

        if (!studentFavoriteEpisodeRepository.existsByStudentIdAndEpisodeId(student.getId(), episodeId)) {
            studentFavoriteEpisodeRepository.save(new StudentFavoriteEpisode(student, episode));
        }

        return Map.of("episodeId", episodeId, "favorited", true);
    }

    @Transactional
    public Map<String, Object> unfavoriteEpisode(String username, Long episodeId) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!episodeRepository.existsById(episodeId)) {
            throw new IllegalArgumentException("Episode not found");
        }
        studentFavoriteEpisodeRepository.deleteByStudentIdAndEpisodeId(student.getId(), episodeId);
        return Map.of("episodeId", episodeId, "favorited", false);
    }

    @Transactional(readOnly = true)
    public List<CourseAdminResponse> listAllCoursesForAdmin() {
        List<Playlist> courses = playlistRepository.findAllOrderBySortOrderAsc();
        QuestionCountsByEpisode counts = loadQuestionCountsByEpisode();
        List<CourseAdminResponse> responses = new ArrayList<>(courses.size());
        for (Playlist course : courses) {
            List<Episode> courseEpisodes = episodeRepository.findNonArchivedByPlaylistIdOrderBySortOrderAsc(course.getId());
            responses.add(buildCourseAdminResponse(course, courseEpisodes, counts));
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public CourseAdminResponse getCourseForAdmin(Long courseId) {
        Playlist course = playlistRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found"));
        List<Episode> courseEpisodes = episodeRepository.findNonArchivedByPlaylistIdOrderBySortOrderAsc(course.getId());
        QuestionCountsByEpisode counts = loadQuestionCountsByEpisode();
        return buildCourseAdminResponse(course, courseEpisodes, counts);
    }

    @Transactional(readOnly = true)
    public YouTubePreviewResponse previewYouTubePlaylist(String playlistUrlOrId) {
        YouTubePlaylistService.YouTubePlaylistPreview preview = youTubePlaylistService.fetchPlaylist(playlistUrlOrId);
        Optional<Playlist> existing = playlistRepository.findByYoutubePlaylistId(preview.youtubePlaylistId());
        return new YouTubePreviewResponse(
                preview.youtubePlaylistId(),
                preview.title(),
                preview.description(),
                preview.thumbnailUrl(),
                preview.episodeCount(),
                preview.totalDurationSeconds(),
                preview.skippedCount(),
                existing.isPresent(),
                existing.map(Playlist::getId).orElse(null),
                preview.episodes()
        );
    }

    @Transactional
    public CourseAdminResponse importCourseFromYouTube(YouTubeImportCourseRequest request) {
        YouTubePlaylistService.YouTubePlaylistPreview preview =
                youTubePlaylistService.fetchPlaylist(request.playlistUrlOrId());

        if (playlistRepository.findByYoutubePlaylistId(preview.youtubePlaylistId()).isPresent()) {
            throw new IllegalStateException(
                    "A course for YouTube playlist '" + preview.youtubePlaylistId() + "' already exists. Use Re-sync instead.");
        }

        String title = (request.title() != null && !request.title().isBlank())
                ? truncate(request.title().trim(), 200)
                : truncate(preview.title(), 200);
        String description = (request.description() != null && !request.description().isBlank())
                ? request.description().trim()
                : preview.description();
        String coverUrl = (request.coverImageUrl() != null && !request.coverImageUrl().isBlank())
                ? truncate(request.coverImageUrl().trim(), 1000)
                : truncate(preview.thumbnailUrl(), 1000);

        Playlist course = new Playlist(title, preview.youtubePlaylistId(), description);
        course.setCoverImageUrl(coverUrl);
        course.setPublished(false);
        course.setArchived(false);
        course.setSortOrder((int) playlistRepository.count() + 1);
        course.setRateArabicSub(request.rateArabicSub() != null ? request.rateArabicSub() : BrandConstants.DEFAULT_RATE_ARABIC_SUB);
        course.setRateEnglishDub(request.rateEnglishDub() != null ? request.rateEnglishDub() : BrandConstants.DEFAULT_RATE_ENGLISH_DUB);
        course.setCompletionBonus(request.completionBonus() != null ? request.completionBonus() : BrandConstants.DEFAULT_COMPLETION_BONUS);
        course.setMinEpisodesForBonus(1);
        Playlist savedCourse = playlistRepository.save(course);

        int epNum = 1;
        for (YouTubePlaylistService.YouTubeEpisodeItem item : preview.episodes()) {
            Episode ep = new Episode(
                    savedCourse,
                    epNum,
                    truncate(item.title(), 250),
                    item.youtubeVideoId(),
                    item.durationSeconds()
            );
            ep.setSortOrder(epNum);
            ep.setEnabled(true);
            ep.setArchived(false);
            episodeRepository.save(ep);
            epNum++;
        }
        episodeRepository.flush();

        return getCourseForAdmin(savedCourse.getId());
    }

    @Transactional
    public CourseResyncResponse resyncCourseFromYouTube(Long courseId) {
        Playlist course = playlistRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found"));

        String ytPlaylistId = course.getYoutubePlaylistId();
        if (ytPlaylistId == null || ytPlaylistId.isBlank() || ytPlaylistId.startsWith("manual-")) {
            throw new IllegalArgumentException("This course does not have a linked YouTube playlist ID to re-sync.");
        }

        YouTubePlaylistService.YouTubePlaylistPreview preview = youTubePlaylistService.fetchPlaylist(ytPlaylistId);
        List<Episode> allCourseEpisodes = episodeRepository.findAllByPlaylistIdOrderBySortOrderAsc(course.getId());

        Map<String, Episode> existingByVideoId = new LinkedHashMap<>();
        int maxEpisodeNumber = 0;
        int maxSortOrder = 0;
        for (Episode ep : allCourseEpisodes) {
            existingByVideoId.putIfAbsent(ep.getYoutubeVideoId(), ep);
            if (ep.getEpisodeNumber() != null && ep.getEpisodeNumber() > maxEpisodeNumber) {
                maxEpisodeNumber = ep.getEpisodeNumber();
            }
            if (ep.getSortOrder() != null && ep.getSortOrder() > maxSortOrder) {
                maxSortOrder = ep.getSortOrder();
            }
        }

        Set<String> presentVideoIds = new HashSet<>();
        int added = 0;
        int updated = 0;
        int deactivated = 0;

        for (YouTubePlaylistService.YouTubeEpisodeItem item : preview.episodes()) {
            presentVideoIds.add(item.youtubeVideoId());
            Episode existing = existingByVideoId.get(item.youtubeVideoId());
            String cleanTitle = truncate(item.title(), 250);
            int cleanDuration = item.durationSeconds();

            if (existing != null) {
                boolean changed = !Objects.equals(existing.getTitle(), cleanTitle)
                        || existing.getDurationSeconds() != cleanDuration;
                existing.setTitle(cleanTitle);
                existing.setDurationSeconds(cleanDuration);
                if (changed) {
                    episodeRepository.save(existing);
                    updated++;
                }
            } else {
                maxEpisodeNumber++;
                maxSortOrder++;
                Episode newEp = new Episode(
                        course,
                        maxEpisodeNumber,
                        cleanTitle,
                        item.youtubeVideoId(),
                        cleanDuration
                );
                newEp.setSortOrder(maxSortOrder);
                newEp.setEnabled(true);
                newEp.setArchived(false);
                episodeRepository.save(newEp);
                added++;
            }
        }

        for (Episode existing : allCourseEpisodes) {
            if (!presentVideoIds.contains(existing.getYoutubeVideoId())
                    && !existing.isArchived()
                    && existing.isEnabled()) {
                existing.setEnabled(false);
                episodeRepository.save(existing);
                deactivated++;
            }
        }

        if ((course.getCoverImageUrl() == null || course.getCoverImageUrl().isBlank())
                && preview.thumbnailUrl() != null && !preview.thumbnailUrl().isBlank()) {
            course.setCoverImageUrl(truncate(preview.thumbnailUrl(), 1000));
            playlistRepository.save(course);
        }

        episodeRepository.flush();
        return new CourseResyncResponse(
                getCourseForAdmin(course.getId()),
                added,
                updated,
                deactivated,
                preview.skippedCount()
        );
    }

    @Transactional
    public CourseAdminResponse createDraftCourse(UpsertCourseRequest request) {
        String ytId;
        if (request.youtubePlaylistId() != null && !request.youtubePlaylistId().isBlank()) {
            ytId = YouTubePlaylistService.extractPlaylistId(request.youtubePlaylistId());
            if (playlistRepository.findByYoutubePlaylistId(ytId).isPresent()) {
                throw new IllegalStateException("A course with YouTube playlist ID '" + ytId + "' already exists.");
            }
        } else {
            ytId = "manual-" + UUID.randomUUID().toString().substring(0, 12);
        }

        Playlist course = new Playlist(
                truncate(request.title().trim(), 200),
                ytId,
                request.description() != null ? request.description().trim() : ""
        );
        course.setCoverImageUrl(request.coverImageUrl() != null && !request.coverImageUrl().isBlank()
                ? truncate(request.coverImageUrl().trim(), 1000)
                : null);
        course.setPublished(false);
        course.setArchived(false);
        course.setSortOrder(request.sortOrder() != null ? request.sortOrder() : (int) playlistRepository.count() + 1);
        course.setRateArabicSub(request.rateArabicSub() != null ? request.rateArabicSub() : BrandConstants.DEFAULT_RATE_ARABIC_SUB);
        course.setRateEnglishDub(request.rateEnglishDub() != null ? request.rateEnglishDub() : BrandConstants.DEFAULT_RATE_ENGLISH_DUB);
        course.setCompletionBonus(request.completionBonus() != null ? request.completionBonus() : BrandConstants.DEFAULT_COMPLETION_BONUS);
        course.setMinEpisodesForBonus(1);

        Playlist saved = playlistRepository.save(course);
        return getCourseForAdmin(saved.getId());
    }

    @Transactional
    public CourseAdminResponse updateCourse(Long courseId, UpsertCourseRequest request) {
        Playlist course = playlistRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found"));

        course.setTitle(truncate(request.title().trim(), 200));
        course.setDescription(request.description() != null ? request.description().trim() : "");
        course.setCoverImageUrl(request.coverImageUrl() != null && !request.coverImageUrl().isBlank()
                ? truncate(request.coverImageUrl().trim(), 1000)
                : null);

        if (request.youtubePlaylistId() != null && !request.youtubePlaylistId().isBlank()) {
            String trimmedYt = request.youtubePlaylistId().trim();
            if (!trimmedYt.equals(course.getYoutubePlaylistId())) {
                String cleanYt = trimmedYt.startsWith("manual-")
                        ? trimmedYt
                        : YouTubePlaylistService.extractPlaylistId(trimmedYt);
                playlistRepository.findByYoutubePlaylistId(cleanYt).ifPresent(other -> {
                    if (!other.getId().equals(courseId)) {
                        throw new IllegalStateException("Another course already uses YouTube playlist ID '" + cleanYt + "'.");
                    }
                });
                course.setYoutubePlaylistId(cleanYt);
            }
        }

        if (request.rateArabicSub() != null) {
            course.setRateArabicSub(request.rateArabicSub());
        }
        if (request.rateEnglishDub() != null) {
            course.setRateEnglishDub(request.rateEnglishDub());
        }
        if (request.completionBonus() != null) {
            course.setCompletionBonus(request.completionBonus());
        }
        if (request.sortOrder() != null) {
            course.setSortOrder(request.sortOrder());
        }

        playlistRepository.save(course);
        return getCourseForAdmin(courseId);
    }

    @Transactional
    public CourseAdminResponse setCoursePublished(Long courseId, boolean published) {
        Playlist course = playlistRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found"));
        course.setPublished(published);
        if (published && course.isArchived()) {
            course.setArchived(false);
        }
        playlistRepository.save(course);
        return getCourseForAdmin(courseId);
    }

    @Transactional
    public CourseAdminResponse setCourseArchived(Long courseId, boolean archived) {
        Playlist course = playlistRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found"));
        course.setArchived(archived);
        if (archived) {
            course.setPublished(false);
        }
        playlistRepository.save(course);
        return getCourseForAdmin(courseId);
    }

    @Transactional
    public CourseAdminResponse reorderCourseEpisodes(Long courseId, List<Long> orderedEpisodeIds) {
        Playlist course = playlistRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("Course not found"));

        if (orderedEpisodeIds == null || orderedEpisodeIds.isEmpty()) {
            throw new IllegalArgumentException("Ordered episode IDs are required");
        }

        List<Episode> allCourseEpisodes = episodeRepository.findAllByPlaylistIdOrderBySortOrderAsc(course.getId());
        Map<Long, Episode> byId = new LinkedHashMap<>();
        for (Episode ep : allCourseEpisodes) {
            byId.put(ep.getId(), ep);
        }

        List<Episode> finalOrder = new ArrayList<>(allCourseEpisodes.size());
        Set<Long> seen = new HashSet<>();
        for (Long epId : orderedEpisodeIds) {
            Episode ep = byId.get(epId);
            if (ep == null) {
                throw new IllegalArgumentException("Episode " + epId + " does not belong to course " + courseId);
            }
            if (seen.add(epId)) {
                finalOrder.add(ep);
            }
        }
        for (Episode ep : allCourseEpisodes) {
            if (seen.add(ep.getId())) {
                finalOrder.add(ep);
            }
        }

        int tempBase = 100_000;
        for (int i = 0; i < finalOrder.size(); i++) {
            finalOrder.get(i).setEpisodeNumber(tempBase + i + 1);
        }
        episodeRepository.saveAll(finalOrder);
        episodeRepository.flush();

        for (int i = 0; i < finalOrder.size(); i++) {
            Episode ep = finalOrder.get(i);
            ep.setEpisodeNumber(i + 1);
            ep.setSortOrder(i + 1);
        }
        episodeRepository.saveAll(finalOrder);
        episodeRepository.flush();

        return getCourseForAdmin(courseId);
    }

    @Transactional
    public CourseAdminResponse.EpisodeAdminItem updateEpisodeAdmin(Long episodeId, UpdateEpisodeAdminRequest request) {
        Episode ep = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        if (request.title() != null && !request.title().isBlank()) {
            ep.setTitle(truncate(request.title().trim(), 250));
        }
        if (request.durationSeconds() != null) {
            if (request.durationSeconds() < 1) {
                throw new IllegalArgumentException("Duration must be at least 1 second");
            }
            ep.setDurationSeconds(request.durationSeconds());
        }
        if (request.enabled() != null) {
            ep.setEnabled(request.enabled());
        }
        if (request.sortOrder() != null) {
            ep.setSortOrder(request.sortOrder());
        }

        Episode saved = episodeRepository.save(ep);
        episodeRepository.flush();
        QuestionCountsByEpisode counts = loadQuestionCountsByEpisode();
        return buildEpisodeAdminItem(saved, counts);
    }

    private record QuestionCountsByEpisode(
            Map<Long, Long> approvedMcq,
            Map<Long, Long> draftMcq,
            Map<Long, Long> approvedOpen,
            Map<Long, Long> draftOpen
    ) {
    }

    private QuestionCountsByEpisode loadQuestionCountsByEpisode() {
        return new QuestionCountsByEpisode(
                toCountMap(questionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.APPROVED)),
                toCountMap(questionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.DRAFT)),
                toCountMap(openQuestionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.APPROVED)),
                toCountMap(openQuestionRepository.countActiveByStatusGroupedByEpisode(QuestionStatus.DRAFT))
        );
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        if (rows != null) {
            for (Object[] row : rows) {
                map.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }
        return map;
    }

    private CourseAdminResponse buildCourseAdminResponse(
            Playlist course,
            List<Episode> courseEpisodes,
            QuestionCountsByEpisode counts) {

        List<CourseAdminResponse.EpisodeAdminItem> episodeItems = new ArrayList<>(courseEpisodes.size());
        int activeCount = 0;
        long totalDuration = 0L;

        for (Episode ep : courseEpisodes) {
            if (ep.isEnabled() && !ep.isArchived()) {
                activeCount++;
                totalDuration += ep.getDurationSeconds();
            }
            episodeItems.add(buildEpisodeAdminItem(ep, counts));
        }

        return new CourseAdminResponse(
                course.getId(),
                course.getTitle(),
                course.getDescription(),
                course.getCoverImageUrl(),
                course.getYoutubePlaylistId(),
                course.isPublished(),
                course.isArchived(),
                course.getSortOrder(),
                resolveRateArabicSub(course),
                resolveRateEnglishDub(course),
                resolveCompletionBonus(course),
                course.getMinEpisodesForBonus(),
                activeCount,
                courseEpisodes.size(),
                totalDuration,
                course.getCreatedAt(),
                episodeItems
        );
    }

    private CourseAdminResponse.EpisodeAdminItem buildEpisodeAdminItem(Episode ep, QuestionCountsByEpisode counts) {
        long approvedMcq = counts.approvedMcq().getOrDefault(ep.getId(), 0L);
        long draftMcq = counts.draftMcq().getOrDefault(ep.getId(), 0L);
        long approvedOpen = counts.approvedOpen().getOrDefault(ep.getId(), 0L);
        long draftOpen = counts.draftOpen().getOrDefault(ep.getId(), 0L);

        boolean quizReady = approvedMcq >= QuizService.SESSION_QUESTION_COUNT && approvedOpen >= 1L;
        String readinessStatus;
        String readinessLabel;

        if (approvedMcq == 0 && draftMcq == 0 && approvedOpen == 0 && draftOpen == 0) {
            readinessStatus = "NO_BANK";
            readinessLabel = "No bank";
        } else if (approvedMcq == 0 && approvedOpen == 0) {
            readinessStatus = "DRAFTS_ONLY";
            readinessLabel = "Drafts only (" + draftMcq + " MCQ, " + draftOpen + " open)";
        } else if (quizReady) {
            readinessStatus = "READY";
            readinessLabel = approvedMcq + " approved (" + approvedOpen + " open) • Ready";
        } else {
            readinessStatus = "PARTIAL";
            readinessLabel = approvedMcq + " approved (" + approvedOpen + " open) • Needs 10 MCQ + 1 open";
        }

        return new CourseAdminResponse.EpisodeAdminItem(
                ep.getId(),
                ep.getPlaylist().getId(),
                ep.getEpisodeNumber(),
                ep.getSortOrder(),
                ep.getTitle(),
                ep.getYoutubeVideoId(),
                ep.getDurationSeconds(),
                ep.isEnabled(),
                ep.isArchived(),
                approvedMcq,
                draftMcq,
                approvedOpen,
                draftOpen,
                quizReady,
                readinessStatus,
                readinessLabel
        );
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

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
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
