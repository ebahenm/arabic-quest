package com.arabicquest.service;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.dto.GradeSuggestionResponse;
import com.arabicquest.dto.OpenAnswerAdminViewResponse;
import com.arabicquest.dto.OpenQuestionStudentResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OpenAnswerReviewService {

    public static final int MIN_ANSWER_CHARS = 40;
    public static final int MAX_ANSWER_CHARS = 2000;

    private final OpenQuestionRepository openQuestionRepository;
    private final OpenAnswerSubmissionRepository openAnswerSubmissionRepository;
    private final StudentOpenQuestionAssignmentRepository assignmentRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final PlaylistBonusClaimRepository playlistBonusClaimRepository;
    private final EpisodeRepository episodeRepository;
    private final UserRepository userRepository;
    private final WatchProgressService watchProgressService;
    private final AiQuizService aiQuizService;
    private final SecureRandom secureRandom = new SecureRandom();

    public OpenAnswerReviewService(
            OpenQuestionRepository openQuestionRepository,
            OpenAnswerSubmissionRepository openAnswerSubmissionRepository,
            StudentOpenQuestionAssignmentRepository assignmentRepository,
            QuizAttemptRepository quizAttemptRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            PlaylistBonusClaimRepository playlistBonusClaimRepository,
            EpisodeRepository episodeRepository,
            UserRepository userRepository,
            WatchProgressService watchProgressService,
            AiQuizService aiQuizService) {
        this.openQuestionRepository = openQuestionRepository;
        this.openAnswerSubmissionRepository = openAnswerSubmissionRepository;
        this.assignmentRepository = assignmentRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.playlistBonusClaimRepository = playlistBonusClaimRepository;
        this.episodeRepository = episodeRepository;
        this.userRepository = userRepository;
        this.watchProgressService = watchProgressService;
        this.aiQuizService = aiQuizService;
    }

    public BigDecimal resolveEpisodeRewardRate(Episode episode, WatchMode mode) {
        Playlist p = episode.getPlaylist();
        if (mode == WatchMode.ENGLISH_DUB) {
            return (p != null && p.getEnglishRewardRate() != null)
                    ? p.getEnglishRewardRate()
                    : BrandConstants.DEFAULT_RATE_ENGLISH_DUB;
        }
        return (p != null && p.getArabicRewardRate() != null)
                ? p.getArabicRewardRate()
                : BrandConstants.DEFAULT_RATE_ARABIC_SUB;
    }

    @Transactional
    public OpenQuestionStudentResponse assignOrGetOpenQuestion(User student, Episode episode, WatchMode effectiveMode) {
        StudentOpenQuestionAssignment assignment = assignmentRepository
                .findByStudentIdAndEpisodeIdWithOpenQuestion(student.getId(), episode.getId())
                .orElseGet(() -> {
                    List<OpenQuestion> approvedOpens = openQuestionRepository
                            .findByEpisodeIdAndStatusAndActiveTrueOrderByIdAsc(episode.getId(), QuestionStatus.APPROVED);
                    if (approvedOpens.isEmpty()) {
                        throw new IllegalStateException("Quiz not ready yet");
                    }
                    OpenQuestion picked = approvedOpens.get(secureRandom.nextInt(approvedOpens.size()));
                    return assignmentRepository.save(new StudentOpenQuestionAssignment(student, episode, picked, effectiveMode));
                });

        OpenQuestion openQ = assignment.getOpenQuestion();
        OpenAnswerSubmission submission = openAnswerSubmissionRepository
                .findByStudentIdAndEpisodeId(student.getId(), episode.getId())
                .orElse(null);

        WatchMode mode = submission != null ? submission.getWatchMode() : assignment.getWatchMode();
        BigDecimal rate = resolveEpisodeRewardRate(episode, mode);

        return new OpenQuestionStudentResponse(
                episode.getId(),
                openQ.getId(),
                openQ.getPromptText(),
                mode,
                rate,
                submission != null ? submission.getStatus().name() : "AWAITING_SUBMISSION",
                submission != null ? submission.getAnswerText() : null,
                submission != null ? submission.getAdminFeedback() : null,
                submission != null ? submission.getSubmittedAt() : null,
                submission != null ? submission.getReviewedAt() : null
        );
    }

    @Transactional
    public OpenQuestionStudentResponse getStudentOpenQuestionState(String username, Long episodeId) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        boolean passedMcq = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episodeId)
                || episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episodeId);
        if (!passedMcq) {
            throw new SecurityException("Pass the 10-question quiz with 100% first to unlock the written reflection question.");
        }

        WatchMode effectiveMode = watchProgressService.resolveEffectiveWatchMode(student.getId(), episodeId, WatchMode.ARABIC_SUB);
        return assignOrGetOpenQuestion(student, episode, effectiveMode);
    }

    @Transactional
    public OpenQuestionStudentResponse submitStudentOpenAnswer(String username, Long episodeId, String rawAnswerText) {
        User student = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        if (episode.isArchived()) {
            throw new IllegalArgumentException("Episode is archived and no longer accepting submissions.");
        }

        boolean passedMcq = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episodeId);
        if (!passedMcq) {
            throw new SecurityException("You must pass the 10-question quiz (10/10) before submitting the written answer.");
        }

        String trimmed = rawAnswerText == null ? "" : rawAnswerText.trim();
        if (trimmed.length() < MIN_ANSWER_CHARS || trimmed.length() > MAX_ANSWER_CHARS) {
            throw new IllegalArgumentException("Your written answer must be between 40 and 2,000 characters in English.");
        }
        if (!trimmed.matches("(?s).*[A-Za-z]{3,}.*")) {
            throw new IllegalArgumentException("Please write your answer in English (40 to 2,000 characters).");
        }

        WatchMode effectiveMode = watchProgressService.resolveEffectiveWatchMode(student.getId(), episodeId, WatchMode.ARABIC_SUB);

        StudentOpenQuestionAssignment assignment = assignmentRepository
                .findByStudentIdAndEpisodeIdWithOpenQuestion(student.getId(), episodeId)
                .orElseGet(() -> {
                    List<OpenQuestion> approvedOpens = openQuestionRepository
                            .findByEpisodeIdAndStatusAndActiveTrueOrderByIdAsc(episodeId, QuestionStatus.APPROVED);
                    if (approvedOpens.isEmpty()) {
                        throw new IllegalStateException("No approved open question available for this episode.");
                    }
                    OpenQuestion picked = approvedOpens.get(secureRandom.nextInt(approvedOpens.size()));
                    return assignmentRepository.save(new StudentOpenQuestionAssignment(student, episode, picked, effectiveMode));
                });

        OpenAnswerSubmission submission = openAnswerSubmissionRepository
                .findByStudentIdAndEpisodeId(student.getId(), episodeId)
                .orElse(null);

        if (submission != null) {
            if (submission.getStatus() == OpenAnswerStatus.APPROVED
                    || episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episodeId)) {
                throw new IllegalArgumentException("Your written answer for this episode has already been approved.");
            }
            submission.setAnswerText(trimmed);
            submission.setStatus(OpenAnswerStatus.PENDING);
            submission.setSubmittedAt(LocalDateTime.now());
        } else {
            submission = new OpenAnswerSubmission(student, episode, assignment.getOpenQuestion(), assignment.getWatchMode(), trimmed);
        }

        submission = openAnswerSubmissionRepository.save(submission);
        BigDecimal rate = resolveEpisodeRewardRate(episode, submission.getWatchMode());

        return new OpenQuestionStudentResponse(
                episode.getId(),
                submission.getOpenQuestion().getId(),
                submission.getOpenQuestion().getPromptText(),
                submission.getWatchMode(),
                rate,
                submission.getStatus().name(),
                submission.getAnswerText(),
                submission.getAdminFeedback(),
                submission.getSubmittedAt(),
                submission.getReviewedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<OpenAnswerAdminViewResponse> listSubmissionsForAdmin(String statusFilter) {
        List<OpenAnswerSubmission> list;
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter.trim())) {
            OpenAnswerStatus status = OpenAnswerStatus.valueOf(statusFilter.trim().toUpperCase(Locale.ROOT));
            list = openAnswerSubmissionRepository.findByStatusWithDetailsOrderBySubmittedAtAsc(status);
        } else {
            list = openAnswerSubmissionRepository.findAllWithDetailsOrderBySubmittedAtDesc();
        }
        return list.stream().map(OpenAnswerAdminViewResponse::from).toList();
    }

    @Transactional
    public Map<String, Object> approveSubmission(Long submissionId, String rawFeedback) {
        OpenAnswerSubmission submission = openAnswerSubmissionRepository.findByIdWithDetails(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Written answer submission not found"));

        // Serialize reward & bonus claim creation per student
        User student = userRepository.findByIdForUpdate(submission.getStudent().getId())
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        Episode episode = submission.getEpisode();
        Playlist playlist = episode.getPlaylist();

        String cleanFeedback = (rawFeedback != null && !rawFeedback.isBlank()) ? rawFeedback.trim() : null;
        submission.setStatus(OpenAnswerStatus.APPROVED);
        if (cleanFeedback != null) {
            submission.setAdminFeedback(cleanFeedback);
        }
        submission.setReviewedAt(LocalDateTime.now());
        openAnswerSubmissionRepository.save(submission);

        boolean rewardCreated = false;
        BigDecimal episodeRewardAmount = BigDecimal.ZERO;
        boolean bonusAwarded = false;
        BigDecimal bonusAmount = BigDecimal.ZERO;

        if (!episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId())) {
            episodeRewardAmount = resolveEpisodeRewardRate(episode, submission.getWatchMode());
            episodeRewardClaimRepository.save(new EpisodeRewardClaim(
                    student,
                    episode,
                    submission.getWatchMode(),
                    episodeRewardAmount
            ));
            episodeRewardClaimRepository.flush();
            rewardCreated = true;

            if (playlist != null) {
                long totalActiveEpisodes = episodeRepository.countActiveByPlaylistId(playlist.getId());
                long claimedActiveEpisodes = episodeRewardClaimRepository
                        .countClaimedActiveEpisodesByStudentAndPlaylist(student.getId(), playlist.getId());
                boolean bonusAlreadyClaimed = playlistBonusClaimRepository
                        .existsByStudentIdAndPlaylistId(student.getId(), playlist.getId());

                int minEpisodes = Math.max(1, playlist.getMinEpisodesForBonus());
                if (!bonusAlreadyClaimed
                        && totalActiveEpisodes > 0
                        && totalActiveEpisodes >= minEpisodes
                        && claimedActiveEpisodes >= totalActiveEpisodes) {
                    bonusAmount = playlist.getCompletionBonus() != null
                            ? playlist.getCompletionBonus()
                            : BrandConstants.DEFAULT_COMPLETION_BONUS;
                    playlistBonusClaimRepository.save(new PlaylistBonusClaim(student, playlist, bonusAmount));
                    bonusAwarded = true;
                }
            }
        }

        return Map.of(
                "submission", OpenAnswerAdminViewResponse.from(submission),
                "rewardCreated", rewardCreated,
                "episodeRewardAmount", episodeRewardAmount,
                "bonusAwarded", bonusAwarded,
                "bonusAmount", bonusAmount
        );
    }

    @Transactional
    public OpenAnswerAdminViewResponse requestRevision(Long submissionId, String rawFeedback) {
        OpenAnswerSubmission submission = openAnswerSubmissionRepository.findByIdWithDetails(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Written answer submission not found"));

        if (submission.getStatus() == OpenAnswerStatus.APPROVED
                || episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(submission.getStudent().getId(), submission.getEpisode().getId())) {
            throw new IllegalArgumentException("Cannot request revision on an already-approved submission.");
        }

        String cleanFeedback = (rawFeedback != null && !rawFeedback.isBlank())
                ? rawFeedback.trim()
                : "Please expand your explanation in English using specific details from the episode.";

        submission.setStatus(OpenAnswerStatus.REVISION_REQUESTED);
        submission.setAdminFeedback(cleanFeedback);
        submission.setReviewedAt(LocalDateTime.now());
        openAnswerSubmissionRepository.save(submission);

        return OpenAnswerAdminViewResponse.from(submission);
    }

    @Transactional(readOnly = true)
    public GradeSuggestionResponse suggestGrade(String adminUsername, Long submissionId) {
        OpenAnswerSubmission submission = openAnswerSubmissionRepository.findByIdWithDetails(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Written answer submission not found"));
        return aiQuizService.suggestGradeForOpenAnswer(
                adminUsername,
                submission.getOpenQuestion(),
                submission.getAnswerText()
        );
    }
}
