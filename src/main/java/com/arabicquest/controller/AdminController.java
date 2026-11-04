package com.arabicquest.controller;

import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.PayoutService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final PayoutRepository payoutRepository;
    private final PayoutService payoutService;
    private final PlaylistRepository playlistRepository;
    private final EpisodeRepository episodeRepository;

    public AdminController(UserRepository userRepository,
                           QuizAttemptRepository quizAttemptRepository,
                           PayoutRepository payoutRepository,
                           PayoutService payoutService,
                           PlaylistRepository playlistRepository,
                           EpisodeRepository episodeRepository) {
        this.userRepository = userRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.payoutRepository = payoutRepository;
        this.payoutService = payoutService;
        this.playlistRepository = playlistRepository;
        this.episodeRepository = episodeRepository;
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
}
