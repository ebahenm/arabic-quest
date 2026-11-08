package com.arabicquest.service;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.dto.GradeSuggestionResponse;
import com.arabicquest.dto.OpenAnswerAdminViewResponse;
import com.arabicquest.dto.OpenQuestionStudentResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class OpenAnswerReviewService {

    private static final Logger log = LoggerFactory.getLogger(OpenAnswerReviewService.class);

    public static final int MIN_ANSWER_WORDS = 40;
    public static final int MAX_ANSWER_CHARS = 2000;

    private static final Pattern REPEATED_CHARS = Pattern.compile("(.)\\1{6,}");

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

        boolean rewardClaimed = episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId());

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
                submission != null ? submission.getReviewedAt() : null,
                rewardClaimed,
                rewardClaimed ? rate : BigDecimal.ZERO,
                false,
                BigDecimal.ZERO
        );
    }

    @Transactional
    public OpenQuestionStudentResponse getStudentOpenQuestionState(String username, Long episodeId) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Episode episode = episodeRepository.findByIdWithPlaylist(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        if (episode.isArchived() || !episode.isEnabled()
                || episode.getPlaylist() == null
                || episode.getPlaylist().isArchived()
                || !episode.getPlaylist().isPublished()) {
            throw new SecurityException("Course is not available.");
        }

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

        if (episode.isArchived() || !episode.isEnabled()
                || episode.getPlaylist() == null
                || episode.getPlaylist().isArchived()
                || !episode.getPlaylist().isPublished()) {
            throw new SecurityException("Course is not available.");
        }

        boolean passedMcq = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episodeId);
        if (!passedMcq) {
            throw new SecurityException("You must pass the 10-question quiz (10/10) before submitting the written answer.");
        }

        String trimmed = rawAnswerText == null ? "" : rawAnswerText.trim();
        validateWrittenAnswer(trimmed, episode.getId(), student.getId());

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

        OpenQuestion openQ = assignment.getOpenQuestion();
        validateAnswerNotCopyingPrompt(trimmed, openQ.getPromptText());

        OpenAnswerSubmission submission = openAnswerSubmissionRepository
                .findByStudentIdAndEpisodeId(student.getId(), episodeId)
                .orElse(null);

        if (submission != null) {
            submission.setAnswerText(trimmed);
            submission.setStatus(OpenAnswerStatus.APPROVED);
            submission.setReadByAdmin(false);
            submission.setSubmittedAt(LocalDateTime.now());
        } else {
            submission = new OpenAnswerSubmission(student, episode, openQ, assignment.getWatchMode(), trimmed);
            submission.setStatus(OpenAnswerStatus.APPROVED);
            submission.setReadByAdmin(false);
        }

        submission = openAnswerSubmissionRepository.save(submission);

        // Immediate and idempotent reward claim creation
        boolean rewardCreated = false;
        BigDecimal episodeRate = resolveEpisodeRewardRate(episode, submission.getWatchMode());
        if (!episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId())) {
            try {
                episodeRewardClaimRepository.save(new EpisodeRewardClaim(
                        student,
                        episode,
                        submission.getWatchMode(),
                        episodeRate
                ));
                episodeRewardClaimRepository.flush();
                rewardCreated = true;
            } catch (Exception ex) {
                log.info("Concurrent reward claim creation handled idempotently for student {} ep {}",
                        student.getId(), episode.getId());
            }
        }

        // Check course completion bonus
        Map<String, Object> bonusResult = checkCourseCompletionBonus(student, episode.getPlaylist());
        boolean bonusAwarded = (boolean) bonusResult.getOrDefault("bonusAwarded", false);
        BigDecimal bonusAmount = (BigDecimal) bonusResult.getOrDefault("bonusAmount", BigDecimal.ZERO);

        return new OpenQuestionStudentResponse(
                episode.getId(),
                openQ.getId(),
                openQ.getPromptText(),
                submission.getWatchMode(),
                episodeRate,
                submission.getStatus().name(),
                submission.getAnswerText(),
                submission.getAdminFeedback(),
                submission.getSubmittedAt(),
                submission.getReviewedAt(),
                rewardCreated || episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId()),
                episodeRate,
                bonusAwarded,
                bonusAmount
        );
    }

    private void validateWrittenAnswer(String text, Long episodeId, Long studentId) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Your written answer cannot be blank.");
        }
        if (text.length() > MAX_ANSWER_CHARS) {
            throw new IllegalArgumentException("Your written answer exceeds the 2,000 character maximum.");
        }

        String[] words = text.split("\\s+");
        if (words.length < MIN_ANSWER_WORDS) {
            throw new IllegalArgumentException("Your written answer must contain at least " + MIN_ANSWER_WORDS + " words (you wrote " + words.length + ").");
        }

        if (!text.matches("(?s).*[A-Za-z]{3,}.*")) {
            throw new IllegalArgumentException("Please write your answer in English.");
        }

        if (REPEATED_CHARS.matcher(text).find()) {
            throw new IllegalArgumentException("Answer rejected: contains excessively repeated characters.");
        }

        // Check for repetitive word spam (e.g., repeating the same word 20 times)
        Map<String, Integer> wordCounts = new HashMap<>();
        for (String w : words) {
            String lower = w.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (!lower.isBlank()) {
                wordCounts.put(lower, wordCounts.getOrDefault(lower, 0) + 1);
            }
        }
        int maxWordFreq = wordCounts.values().stream().max(Integer::compareTo).orElse(0);
        if (maxWordFreq > words.length * 0.40 && words.length >= 20) {
            throw new IllegalArgumentException("Answer rejected: contains mostly repetitive words.");
        }
    }

    private void validateAnswerNotCopyingPrompt(String answer, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        String cleanAnswer = answer.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        String cleanPrompt = prompt.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (cleanAnswer.equals(cleanPrompt) || cleanAnswer.replace(cleanPrompt, "").trim().length() < 20) {
            throw new IllegalArgumentException("Answer rejected: copying or slightly rewording the question prompt is not allowed.");
        }
    }

    private Map<String, Object> checkCourseCompletionBonus(User student, Playlist playlist) {
        if (playlist == null) {
            return Map.of("bonusAwarded", false, "bonusAmount", BigDecimal.ZERO);
        }

        boolean bonusAwarded = false;
        BigDecimal bonusAmount = BigDecimal.ZERO;

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
            try {
                playlistBonusClaimRepository.save(new PlaylistBonusClaim(student, playlist, bonusAmount));
                bonusAwarded = true;
            } catch (Exception ex) {
                log.info("Bonus already claimed concurrently for student {} course {}", student.getId(), playlist.getId());
            }
        }

        return Map.of("bonusAwarded", bonusAwarded, "bonusAmount", bonusAmount);
    }

    @Transactional(readOnly = true)
    public List<OpenAnswerAdminViewResponse> listSubmissionsForAdmin(String filter) {
        List<OpenAnswerSubmission> list;
        if ("UNREAD".equalsIgnoreCase(filter)) {
            list = openAnswerSubmissionRepository.findUnreadWithDetailsOrderBySubmittedAtAsc();
        } else if ("READ".equalsIgnoreCase(filter)) {
            list = openAnswerSubmissionRepository.findReadWithDetailsOrderBySubmittedAtDesc();
        } else {
            list = openAnswerSubmissionRepository.findAllWithDetailsOrderBySubmittedAtDesc();
        }
        return list.stream().map(OpenAnswerAdminViewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long getUnreadCount() {
        return openAnswerSubmissionRepository.countUnread();
    }

    @Transactional
    public OpenAnswerAdminViewResponse markAsRead(Long submissionId, String optionalFeedback) {
        OpenAnswerSubmission submission = openAnswerSubmissionRepository.findByIdWithDetails(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Written answer submission not found"));

        submission.setReadByAdmin(true);
        if (optionalFeedback != null && !optionalFeedback.isBlank()) {
            submission.setAdminFeedback(optionalFeedback.trim());
        }
        submission.setReviewedAt(LocalDateTime.now());
        OpenAnswerSubmission saved = openAnswerSubmissionRepository.save(submission);
        return OpenAnswerAdminViewResponse.from(saved);
    }

    @Transactional
    public Map<String, Object> approveSubmission(Long submissionId, String rawFeedback) {
        // Kept for backward compatibility with existing tests; marks as read and returns details
        OpenAnswerSubmission submission = openAnswerSubmissionRepository.findByIdWithDetails(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Written answer submission not found"));

        User student = userRepository.findByIdForUpdate(submission.getStudent().getId())
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        Episode episode = submission.getEpisode();
        submission.setStatus(OpenAnswerStatus.APPROVED);
        submission.setReadByAdmin(true);
        if (rawFeedback != null && !rawFeedback.isBlank()) {
            submission.setAdminFeedback(rawFeedback.trim());
        }
        submission.setReviewedAt(LocalDateTime.now());
        openAnswerSubmissionRepository.save(submission);

        boolean rewardCreated = false;
        BigDecimal episodeRewardAmount = resolveEpisodeRewardRate(episode, submission.getWatchMode());
        if (!episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId())) {
            try {
                episodeRewardClaimRepository.save(new EpisodeRewardClaim(
                        student,
                        episode,
                        submission.getWatchMode(),
                        episodeRewardAmount
                ));
                episodeRewardClaimRepository.flush();
                rewardCreated = true;
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> bonus = checkCourseCompletionBonus(student, episode.getPlaylist());
        boolean bonusAwarded = (boolean) bonus.getOrDefault("bonusAwarded", false);
        boolean alreadyClaimed = episode.getPlaylist() != null && playlistBonusClaimRepository.existsByStudentIdAndPlaylistId(student.getId(), episode.getPlaylist().getId());
        BigDecimal bonusAmt = (bonusAwarded || alreadyClaimed)
                ? (episode.getPlaylist() != null && episode.getPlaylist().getCompletionBonus() != null ? episode.getPlaylist().getCompletionBonus() : BrandConstants.DEFAULT_COMPLETION_BONUS)
                : BigDecimal.ZERO;
        return Map.of(
                "submission", OpenAnswerAdminViewResponse.from(submission),
                "rewardCreated", rewardCreated,
                "episodeRewardAmount", episodeRewardAmount,
                "bonusAwarded", bonusAwarded,
                "bonusAmount", bonusAmt
        );
    }

    @Transactional
    public OpenAnswerAdminViewResponse requestRevision(Long submissionId, String rawFeedback) {
        // Optional feedback note setter
        OpenAnswerSubmission submission = openAnswerSubmissionRepository.findByIdWithDetails(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Written answer submission not found"));

        String cleanFeedback = (rawFeedback != null && !rawFeedback.isBlank())
                ? rawFeedback.trim()
                : "Good effort! Check the historical details in the episode.";

        submission.setAdminFeedback(cleanFeedback);
        submission.setReadByAdmin(true);
        submission.setReviewedAt(LocalDateTime.now());
        openAnswerSubmissionRepository.save(submission);

        return OpenAnswerAdminViewResponse.from(submission);
    }

    @Transactional
    public void convertExistingPendingSubmissionsOnStartup() {
        openAnswerSubmissionRepository.backfillNullReadByAdminForApproved();
        openAnswerSubmissionRepository.backfillNullReadByAdminToFalse();

        List<OpenAnswerSubmission> pendings = openAnswerSubmissionRepository
                .findByStatusWithDetailsOrderBySubmittedAtAsc(OpenAnswerStatus.PENDING);
        for (OpenAnswerSubmission sub : pendings) {
            sub.setStatus(OpenAnswerStatus.APPROVED);
            sub.setReadByAdmin(true);
            sub.setReviewedAt(LocalDateTime.now());
            openAnswerSubmissionRepository.save(sub);

            User student = sub.getStudent();
            Episode episode = sub.getEpisode();
            if (!episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId())) {
                BigDecimal rate = resolveEpisodeRewardRate(episode, sub.getWatchMode());
                try {
                    episodeRewardClaimRepository.save(new EpisodeRewardClaim(
                            student,
                            episode,
                            sub.getWatchMode(),
                            rate
                    ));
                    checkCourseCompletionBonus(student, episode.getPlaylist());
                } catch (Exception ex) {
                    log.info("Reward already exists for student {} episode {}", student.getId(), episode.getId());
                }
            }
        }
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
