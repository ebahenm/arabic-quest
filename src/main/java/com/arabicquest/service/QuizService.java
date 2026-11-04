package com.arabicquest.service;

import com.arabicquest.dto.QuizResultResponse;
import com.arabicquest.dto.QuizSubmissionRequest;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final EpisodeRepository episodeRepository;
    private final UserRepository userRepository;
    private final PayoutRepository payoutRepository;

    public QuizService(QuizRepository quizRepository,
                       QuizAttemptRepository quizAttemptRepository,
                       EpisodeRepository episodeRepository,
                       UserRepository userRepository,
                       PayoutRepository payoutRepository) {
        this.quizRepository = quizRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.episodeRepository = episodeRepository;
        this.userRepository = userRepository;
        this.payoutRepository = payoutRepository;
    }

    @Transactional
    public QuizResultResponse submitQuiz(String username, QuizSubmissionRequest request) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        Episode episode = episodeRepository.findById(request.getEpisodeId())
                .orElseThrow(() -> new IllegalArgumentException("Episode not found with id: " + request.getEpisodeId()));

        Quiz quiz = quizRepository.findByEpisodeId(episode.getId())
                .orElseThrow(() -> new IllegalArgumentException("No quiz configured for episode id: " + episode.getId()));

        List<Question> questions = quiz.getQuestions();
        if (questions == null || questions.isEmpty()) {
            throw new IllegalStateException("Quiz has no questions configured.");
        }

        int totalQuestions = questions.size();
        int correctAnswers = 0;
        Map<Long, Long> submittedAnswers = request.getAnswers();

        for (Question question : questions) {
            Long selectedOptionId = submittedAnswers.get(question.getId());
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

        // Check if student already passed this episode in a previous attempt
        boolean alreadyPassed = quizAttemptRepository.existsByStudentIdAndEpisodeIdAndPassedTrue(student.getId(), episode.getId());

        if (passed) {
            if (!alreadyPassed) {
                // Determine reward rate based on watch mode
                Playlist playlist = episode.getPlaylist();
                if (request.getWatchMode() == WatchMode.ARABIC_SUB) {
                    rewardAmount = playlist != null ? playlist.getArabicRewardRate() : request.getWatchMode().getDefaultReward();
                } else {
                    rewardAmount = playlist != null ? playlist.getEnglishRewardRate() : request.getWatchMode().getDefaultReward();
                }

                message = String.format("Congratulations! You passed with %.1f%% and earned $%s!", scorePercentage, rewardAmount);

                // Check if all episodes in the playlist are now completed for the $100 bonus
                if (playlist != null) {
                    long totalEpisodes = episodeRepository.countByPlaylistId(playlist.getId());
                    long passedEpisodes = quizAttemptRepository.countPassedEpisodesByStudentAndPlaylist(student.getId(), playlist.getId()) + 1; // +1 for current episode

                    if (passedEpisodes >= totalEpisodes && totalEpisodes > 0) {
                        rewardAmount = rewardAmount.add(playlist.getCompletionBonus());
                        message += String.format(" BONUS UNLOCKED! You completed the full playlist and received an extra $%s bonus!", playlist.getCompletionBonus());
                    }
                }
            } else {
                message = String.format("You passed with %.1f%%! (Reward was already collected on a previous attempt).", scorePercentage);
            }
        } else {
            message = String.format("Score: %.1f%%. Passing mark is %.0f%%. You can review the video and try again!",
                    scorePercentage, quiz.getPassingScorePercentage());
        }

        // Save attempt
        QuizAttempt attempt = new QuizAttempt(
                student,
                episode,
                request.getWatchMode(),
                scorePercentage,
                passed,
                rewardAmount
        );
        quizAttemptRepository.save(attempt);

        return new QuizResultResponse(scorePercentage, passed, rewardAmount, message);
    }
}
