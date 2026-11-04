package com.arabicquest.service;

import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.model.Payout;
import com.arabicquest.model.Playlist;
import com.arabicquest.model.User;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.PayoutRepository;
import com.arabicquest.repository.PlaylistRepository;
import com.arabicquest.repository.QuizAttemptRepository;
import com.arabicquest.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class PayoutService {

    private final UserRepository userRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final PayoutRepository payoutRepository;
    private final EpisodeRepository episodeRepository;
    private final PlaylistRepository playlistRepository;

    public PayoutService(UserRepository userRepository,
                         QuizAttemptRepository quizAttemptRepository,
                         PayoutRepository payoutRepository,
                         EpisodeRepository episodeRepository,
                         PlaylistRepository playlistRepository) {
        this.userRepository = userRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.payoutRepository = payoutRepository;
        this.episodeRepository = episodeRepository;
        this.playlistRepository = playlistRepository;
    }

    public EarningsSummaryResponse getStudentEarnings(String username) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        BigDecimal totalEarned = quizAttemptRepository.calculateTotalEarnedByStudent(student.getId());
        BigDecimal totalPaid = payoutRepository.calculateTotalPaidToStudent(student.getId());
        BigDecimal balanceOwed = totalEarned.subtract(totalPaid);

        // Fetch counts for the primary active playlist if available
        Playlist playlist = playlistRepository.findAll().stream().findFirst().orElse(null);
        long totalEpisodes = playlist != null ? episodeRepository.countByPlaylistId(playlist.getId()) : 0;
        long passedEpisodes = playlist != null ? quizAttemptRepository.countPassedEpisodesByStudentAndPlaylist(student.getId(), playlist.getId()) : 0;
        boolean bonusUnlocked = totalEpisodes > 0 && passedEpisodes >= totalEpisodes;
        BigDecimal bonusAmount = playlist != null ? playlist.getCompletionBonus() : new BigDecimal("100.00");

        return new EarningsSummaryResponse(
                student.getUsername(),
                totalEarned,
                totalPaid,
                balanceOwed,
                passedEpisodes,
                totalEpisodes,
                bonusUnlocked,
                bonusAmount
        );
    }

    @Transactional
    public Payout markAsPaid(Long studentId, BigDecimal amount, String note) {
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found with id: " + studentId));

        Payout payout = new Payout(student, amount, note);
        payout.setIsPaid(true);
        payout.setPaidAt(LocalDateTime.now());

        return payoutRepository.save(payout);
    }
}
