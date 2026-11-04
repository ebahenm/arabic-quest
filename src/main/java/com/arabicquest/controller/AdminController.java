package com.arabicquest.controller;

import com.arabicquest.dto.CreateEpisodeRequest;
import com.arabicquest.dto.CreateQuizRequest;
import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.PayoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutService payoutService;
    private final PlaylistRepository playlistRepository;
    private final EpisodeRepository episodeRepository;
    private final QuizRepository quizRepository;

    public AdminController(UserRepository userRepository,
                           QuizAttemptRepository quizAttemptRepository,
                           PayoutRepository payoutRepository,
                           PayoutService payoutService,
                           PlaylistRepository playlistRepository,
                           EpisodeRepository episodeRepository,
                           QuizRepository quizRepository) {
        this.userRepository = userRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.payoutRepository = payoutRepository;
        this.payoutService = payoutService;
        this.playlistRepository = playlistRepository;
        this.episodeRepository = episodeRepository;
        this.quizRepository = quizRepository;
    }

    @GetMapping("/students")
    public ResponseEntity<List<User>> getAllStudents() {
        return ResponseEntity.ok(userRepository.findAll());
    }

    @GetMapping("/students/{username}/earnings")
    public ResponseEntity<EarningsSummaryResponse> getStudentEarnings(@PathVariable String username) {
        return ResponseEntity.ok(payoutService.getStudentEarnings(username));
    }

    @GetMapping("/attempts")
    public ResponseEntity<List<QuizAttempt>> getAllAttempts() {
        return ResponseEntity.ok(quizAttemptRepository.findAll());
    }

    @GetMapping("/payouts")
    public ResponseEntity<List<Payout>> getAllPayouts() {
        return ResponseEntity.ok(payoutRepository.findAll());
    }

    @PostMapping("/payouts")
    public ResponseEntity<Payout> markPayout(
            @RequestParam Long studentId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false, defaultValue = "Cash/Transfer Payout") String note) {
        Payout payout = payoutService.markAsPaid(studentId, amount, note);
        return ResponseEntity.ok(payout);
    }

    @PostMapping("/episodes")
    public ResponseEntity<Episode> createEpisode(@Valid @RequestBody CreateEpisodeRequest request) {
        Playlist playlist = playlistRepository.findById(request.getPlaylistId())
                .orElseThrow(() -> new IllegalArgumentException("Playlist not found"));
        Episode episode = new Episode(playlist, request.getEpisodeNumber(), request.getTitle(), request.getYoutubeVideoId());
        return ResponseEntity.ok(episodeRepository.save(episode));
    }

    @Transactional
    @PostMapping("/episodes/{episodeId}/quiz")
    public ResponseEntity<Quiz> createOrUpdateQuiz(
            @PathVariable Long episodeId,
            @Valid @RequestBody CreateQuizRequest request) {
        Episode episode = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));

        Quiz quiz = quizRepository.findByEpisodeId(episodeId)
                .orElse(new Quiz(episode));

        quiz.setPassingScorePercentage(request.getPassingScorePercentage() != null ? request.getPassingScorePercentage() : 80.0);
        quiz.getQuestions().clear();

        if (request.getQuestions() != null) {
            int order = 1;
            for (CreateQuizRequest.CreateQuestionRequest qReq : request.getQuestions()) {
                Question question = new Question(quiz, qReq.getQuestionText(), order++);
                if (qReq.getOptions() != null) {
                    for (CreateQuizRequest.CreateOptionRequest optReq : qReq.getOptions()) {
                        QuestionOption opt = new QuestionOption(question, optReq.getOptionText(), Boolean.TRUE.equals(optReq.getIsCorrect()));
                        question.getOptions().add(opt);
                    }
                }
                quiz.getQuestions().add(question);
            }
        }

        return ResponseEntity.ok(quizRepository.save(quiz));
    }

    @DeleteMapping("/episodes/{episodeId}")
    public ResponseEntity<Void> deleteEpisode(@PathVariable Long episodeId) {
        if (episodeRepository.existsById(episodeId)) {
            episodeRepository.deleteById(episodeId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
