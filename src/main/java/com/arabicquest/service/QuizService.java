package com.arabicquest.service;

import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.dto.*;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final EpisodeRepository episodeRepository;
    private final UserRepository userRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final PlaylistBonusClaimRepository playlistBonusClaimRepository;
    private final WatchProgressService watchProgressService;
    private final AiQuizService aiQuizService;
    private final SecureRandom secureRandom = new SecureRandom();

    public QuizService(
            QuizRepository quizRepository,
            QuizAttemptRepository quizAttemptRepository,
            EpisodeRepository episodeRepository,
            UserRepository userRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            PlaylistBonusClaimRepository playlistBonusClaimRepository,
            WatchProgressService watchProgressService,
            AiQuizService aiQuizService) {
        this.quizRepository = quizRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.episodeRepository = episodeRepository;
        this.userRepository = userRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.playlistBonusClaimRepository = playlistBonusClaimRepository;
        this.watchProgressService = watchProgressService;
        this.aiQuizService = aiQuizService;
    }

    @Transactional
    public QuizViewResponse getStudentQuizView(Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found with id: " + episodeId));
        if (episode.isArchived()) {
            throw new IllegalArgumentException("Episode not found");
        }

        Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElse(null);
        if (quiz == null || quiz.getQuestions().isEmpty()) {
            quiz = aiQuizService.generateQuizForEpisode(episodeId);
        }

        List<QuestionViewResponse> questionViews = quiz.getQuestions().stream().map(q -> {
            List<OptionViewResponse> optionViews = new ArrayList<>(
                    q.getOptions().stream()
                            .map(opt -> new OptionViewResponse(opt.getId(), opt.getOptionText()))
                            .toList()
            );
            // Shuffle options on every fetch so button positions cannot be memorized
            Collections.shuffle(optionViews, secureRandom);
            return new QuestionViewResponse(q.getId(), q.getQuestionText(), q.getQuestionOrder(), optionViews);
        }).toList();

        return new QuizViewResponse(quiz.getId(), episodeId, quiz.getPassingScorePercentage(), questionViews);
    }

    @Transactional
    public QuizResultResponse submitQuiz(String username, QuizSubmissionRequest request) {
        // Acquire PESSIMISTIC_WRITE row lock on User to serialize concurrent submissions
        User student = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        Episode episode = episodeRepository.findByIdWithPlaylist(request.getEpisodeId())
                .orElseThrow(() -> new IllegalArgumentException("Episode not found with id: " + request.getEpisodeId()));

        if (episode.isArchived()) {
            throw new IllegalArgumentException("Episode is archived and no longer accepting submissions");
        }

        Quiz quiz = quizRepository.findByEpisodeId(episode.getId())
                .orElseThrow(() -> new IllegalArgumentException("No quiz configured for episode id: " + episode.getId()));

        List<Question> questions = quiz.getQuestions();
        if (questions == null || questions.isEmpty()) {
            throw new IllegalStateException("Quiz has no questions configured.");
        }

        boolean alreadyPassed = episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), episode.getId())
                || quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episode.getId());

        if (!alreadyPassed) {
            long cooldownRemaining = watchProgressService.getRetryCooldownRemainingSeconds(student.getId(), episode.getId());
            if (cooldownRemaining > 0) {
                long mins = cooldownRemaining / 60;
                long secs = cooldownRemaining % 60;
                throw new TooManyRequestsException(
                        String.format("Cooldown active after previous attempt. Please review the episode and retry in %dm %02ds.", mins, secs),
                        cooldownRemaining
                );
            }

            if (!watchProgressService.isQuizUnlockedForSubmission(student.getId(), episode)) {
                throw new SecurityException("Quiz is locked. Please watch the episode before submitting the quiz.");
            }
        }

        // Always enforce the server-locked WatchMode so students cannot spoof ARABIC_SUB at submission time
        WatchMode effectiveWatchMode = watchProgressService.resolveEffectiveWatchMode(
                student.getId(), episode.getId(), request.getWatchMode());

        int totalQuestions = questions.size();
        int correctAnswers = 0;
        Map<Long, Long> submittedAnswers = request.getAnswers();

        for (Question question : questions) {
            Long selectedOptionId = submittedAnswers != null ? submittedAnswers.get(question.getId()) : null;
            if (selectedOptionId != null) {
                boolean isCorrect = question.getOptions().stream()
                        .anyMatch(opt -> opt.getId().equals(selectedOptionId) && Boolean.TRUE.equals(opt.getIsCorrect()));
                if (isCorrect) {
                    correctAnswers++;
                }
            }
        }

        double scorePercentage = ((double) correctAnswers / totalQuestions) * 100.0;
        boolean passed = scorePercentage >= quiz.getPassingScorePercentage();

        BigDecimal rewardAmount = BigDecimal.ZERO;
        String message;

        if (passed) {
            if (!alreadyPassed) {
                Playlist playlist = episode.getPlaylist();
                if (effectiveWatchMode == WatchMode.ARABIC_SUB) {
                    rewardAmount = playlist != null ? playlist.getArabicRewardRate() : effectiveWatchMode.getDefaultReward();
                } else {
                    rewardAmount = playlist != null ? playlist.getEnglishRewardRate() : effectiveWatchMode.getDefaultReward();
                }

                // Persist unique episode reward claim
                episodeRewardClaimRepository.save(new EpisodeRewardClaim(student, episode, effectiveWatchMode, rewardAmount));

                message = String.format("Congratulations! You passed with %.0f%% (%d/%d) and earned $%s!",
                        scorePercentage, correctAnswers, totalQuestions, rewardAmount);

                // Check one-time playlist completion bonus (guarded by UNIQUE(student_id, playlist_id))
                if (playlist != null) {
                    long totalActiveEpisodes = episodeRepository.countActiveByPlaylistId(playlist.getId());
                    long previouslyPassed = quizAttemptRepository.countPassedEpisodesByStudentAndPlaylist(student.getId(), playlist.getId());
                    long nowPassed = previouslyPassed + 1;
                    boolean bonusAlreadyClaimed = playlistBonusClaimRepository.existsByStudentIdAndPlaylistId(student.getId(), playlist.getId());

                    if (!bonusAlreadyClaimed
                            && totalActiveEpisodes >= playlist.getMinEpisodesForBonus()
                            && nowPassed >= totalActiveEpisodes) {
                        BigDecimal bonus = playlist.getCompletionBonus();
                        playlistBonusClaimRepository.save(new PlaylistBonusClaim(student, playlist, bonus));
                        rewardAmount = rewardAmount.add(bonus);
                        message += String.format(" 🏆 BONUS UNLOCKED! You completed all %d episodes and earned the one-time $%s completion bonus!",
                                totalActiveEpisodes, bonus);
                    }
                }
            } else {
                message = String.format("You passed with %.0f%% (%d/%d)! (Reward for this episode was already claimed).",
                        scorePercentage, correctAnswers, totalQuestions);
            }
        } else {
            message = String.format("Score: %.0f%% (%d/%d). Passing mark is %.0f%%. Review the lesson and try again after the cooldown!",
                    scorePercentage, correctAnswers, totalQuestions, quiz.getPassingScorePercentage());
        }

        QuizAttempt attempt = new QuizAttempt(
                student,
                episode,
                effectiveWatchMode,
                scorePercentage,
                passed,
                rewardAmount
        );
        quizAttemptRepository.save(attempt);

        return new QuizResultResponse(scorePercentage, passed, rewardAmount, message);
    }
}
