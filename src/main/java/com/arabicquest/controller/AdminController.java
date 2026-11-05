package com.arabicquest.controller;

import com.arabicquest.dto.*;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.AiQuizService;
import com.arabicquest.service.PayoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Validated
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
    private final WatchSessionRepository watchSessionRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final AiQuizService aiQuizService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminController(
            UserRepository userRepository,
            QuizAttemptRepository quizAttemptRepository,
            PayoutRepository payoutRepository,
            PayoutService payoutService,
            PlaylistRepository playlistRepository,
            EpisodeRepository episodeRepository,
            QuizRepository quizRepository,
            WatchSessionRepository watchSessionRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            AiQuizService aiQuizService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.payoutRepository = payoutRepository;
        this.payoutService = payoutService;
        this.playlistRepository = playlistRepository;
        this.episodeRepository = episodeRepository;
        this.quizRepository = quizRepository;
        this.watchSessionRepository = watchSessionRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.aiQuizService = aiQuizService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/students")
    public ResponseEntity<List<UserSummaryResponse>> getAllStudents() {
        List<UserSummaryResponse> users = userRepository.findAllByOrderByIdAsc()
                .stream()
                .map(UserSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    @PostMapping("/students")
    public ResponseEntity<?> createStudent(@Valid @RequestBody RegisterRequest request) {
        String cleanUsername = request.getUsername().trim().toLowerCase();
        if (userRepository.existsByUsername(cleanUsername)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Username '" + cleanUsername + "' is already taken"));
        }

        User newUser = new User(
                cleanUsername,
                passwordEncoder.encode(request.getPassword()),
                request.getFullName().trim(),
                Role.ROLE_STUDENT
        );
        User saved = userRepository.save(newUser);
        return ResponseEntity.ok(UserSummaryResponse.from(saved));
    }

    @Transactional
    @PostMapping("/students/{studentId}/reset-password")
    public ResponseEntity<Map<String, String>> resetStudentPassword(
            @PathVariable Long studentId,
            @Valid @RequestBody ResetStudentPasswordRequest request) {
        User user = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        if (user.getRole() != Role.ROLE_STUDENT) {
            throw new IllegalArgumentException("Only student accounts can be reset via this endpoint");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.incrementTokenVersion();
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully for " + user.getUsername()));
    }

    @GetMapping("/students/{username}/earnings")
    public ResponseEntity<EarningsSummaryResponse> getStudentEarnings(@PathVariable String username) {
        return ResponseEntity.ok(payoutService.getStudentEarnings(username));
    }

    @Transactional(readOnly = true)
    @GetMapping("/attempts")
    public ResponseEntity<List<AttemptAdminViewResponse>> getAllAttempts() {
        List<AttemptAdminViewResponse> list = quizAttemptRepository
                .findAllWithStudentAndEpisodeOrderByAttemptedAtDesc()
                .stream()
                .map(AttemptAdminViewResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    @Transactional(readOnly = true)
    @GetMapping("/payouts")
    public ResponseEntity<List<PayoutResponse>> getAllPayouts() {
        List<PayoutResponse> list = payoutRepository
                .findAllWithStudentOrderByCreatedAtDesc()
                .stream()
                .map(PayoutResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    @Transactional
    @PostMapping("/payouts")
    public ResponseEntity<PayoutResponse> markPayoutJson(@Valid @RequestBody CreatePayoutRequest request) {
        Payout payout = payoutService.markAsPaid(request.studentId(), request.amount(), request.note());
        return ResponseEntity.ok(PayoutResponse.from(payout));
    }

    @Transactional
    @PostMapping(value = "/payouts", params = {"studentId", "amount"})
    public ResponseEntity<PayoutResponse> markPayoutParams(
            @RequestParam Long studentId,
            @RequestParam @DecimalMin(value = "0.01", message = "Payout amount must be greater than $0.00") BigDecimal amount,
            @RequestParam(required = false, defaultValue = "Cash/Transfer Payout") String note) {
        Payout payout = payoutService.markAsPaid(studentId, amount, note);
        return ResponseEntity.ok(PayoutResponse.from(payout));
    }

    @Transactional(readOnly = true)
    @GetMapping("/episodes")
    public ResponseEntity<List<Map<String, Object>>> getActiveEpisodes() {
        List<Map<String, Object>> list = episodeRepository.findAllActiveOrderByEpisodeNumberAsc()
                .stream()
                .map(ep -> Map.<String, Object>of(
                        "id", ep.getId(),
                        "playlistId", ep.getPlaylist().getId(),
                        "episodeNumber", ep.getEpisodeNumber(),
                        "title", ep.getTitle(),
                        "youtubeVideoId", ep.getYoutubeVideoId(),
                        "durationSeconds", ep.getDurationSeconds()
                ))
                .toList();
        return ResponseEntity.ok(list);
    }

    @Transactional
    @PostMapping("/episodes")
    public ResponseEntity<Map<String, Object>> createEpisode(@Valid @RequestBody CreateEpisodeRequest request) {
        Playlist playlist;
        if (request.getPlaylistId() != null) {
            playlist = playlistRepository.findById(request.getPlaylistId())
                    .or(() -> playlistRepository.findFirstByOrderByIdAsc())
                    .orElseThrow(() -> new IllegalArgumentException("No active playlist found"));
        } else {
            playlist = playlistRepository.findFirstByOrderByIdAsc()
                    .orElseThrow(() -> new IllegalArgumentException("No active playlist found"));
        }

        // Check if an archived episode with the same number exists; if so, restore and update it
        Episode existing = episodeRepository.findByPlaylistIdAndEpisodeNumber(playlist.getId(), request.getEpisodeNumber())
                .orElse(null);

        Episode saved;
        if (existing != null) {
            if (!existing.isArchived()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Episode " + request.getEpisodeNumber() + " already exists in this playlist"));
            }
            existing.setTitle(request.getTitle().trim());
            existing.setYoutubeVideoId(request.getYoutubeVideoId().trim());
            existing.setDurationSeconds(request.getDurationSeconds() != null ? request.getDurationSeconds() : 1800);
            existing.setArchived(false);
            existing.setVocabularyJson(null);
            saved = episodeRepository.save(existing);
        } else {
            Episode episode = new Episode(
                    playlist,
                    request.getEpisodeNumber(),
                    request.getTitle().trim(),
                    request.getYoutubeVideoId().trim(),
                    request.getDurationSeconds() != null ? request.getDurationSeconds() : 1800
            );
            saved = episodeRepository.save(episode);
        }

        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "playlistId", playlist.getId(),
                "episodeNumber", saved.getEpisodeNumber(),
                "title", saved.getTitle(),
                "youtubeVideoId", saved.getYoutubeVideoId(),
                "durationSeconds", saved.getDurationSeconds()
        ));
    }

    @Transactional
    @PostMapping("/episodes/{episodeId}/generate-quiz")
    public ResponseEntity<QuizAdminViewResponse> generateQuizWithAi(@PathVariable Long episodeId) {
        Quiz quiz = aiQuizService.generateQuizForEpisode(episodeId);
        return ResponseEntity.ok(QuizAdminViewResponse.from(quiz, episodeId));
    }

    @Transactional
    @PostMapping("/episodes/{episodeId}/quiz")
    public ResponseEntity<QuizAdminViewResponse> createOrUpdateQuiz(
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
                    List<CreateQuizRequest.CreateOptionRequest> shuffled = new ArrayList<>(qReq.getOptions());
                    Collections.shuffle(shuffled, secureRandom);
                    for (CreateQuizRequest.CreateOptionRequest optReq : shuffled) {
                        QuestionOption opt = new QuestionOption(question, optReq.getOptionText(), Boolean.TRUE.equals(optReq.getIsCorrect()));
                        question.getOptions().add(opt);
                    }
                }
                quiz.getQuestions().add(question);
            }
        }

        Quiz saved = quizRepository.save(quiz);
        return ResponseEntity.ok(QuizAdminViewResponse.from(saved, episodeId));
    }

    @Transactional
    @DeleteMapping("/episodes/{episodeId}")
    public ResponseEntity<Void> deleteEpisode(@PathVariable Long episodeId) {
        Episode episode = episodeRepository.findById(episodeId).orElse(null);
        if (episode == null) {
            return ResponseEntity.notFound().build();
        }

        watchSessionRepository.deleteByEpisodeId(episodeId);

        boolean hasHistory = quizAttemptRepository.existsByEpisodeId(episodeId)
                || episodeRewardClaimRepository.existsByEpisodeId(episodeId);

        if (hasHistory) {
            // Soft-archive to preserve student earnings & audit history without FK violation
            episode.setArchived(true);
            episodeRepository.save(episode);
        } else {
            episodeRepository.delete(episode);
        }
        return ResponseEntity.noContent().build();
    }
}
