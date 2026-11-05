package com.arabicquest.service;

import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.model.Payout;
import com.arabicquest.model.Playlist;
import com.arabicquest.model.User;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.PayoutRepository;
import com.arabicquest.repository.PlaylistBonusClaimRepository;
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
    private final PlaylistBonusClaimRepository playlistBonusClaimRepository;

    public PayoutService(
            UserRepository userRepository,
            QuizAttemptRepository quizAttemptRepository,
            PayoutRepository payoutRepository,
            EpisodeRepository episodeRepository,
            PlaylistRepository playlistRepository,
            PlaylistBonusClaimRepository playlistBonusClaimRepository) {
        this.userRepository = userRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.payoutRepository = payoutRepository;
        this.episodeRepository = episodeRepository;
        this.playlistRepository = playlistRepository;
        this.playlistBonusClaimRepository = playlistBonusClaimRepository;
    }

    @Transactional(readOnly = true)
    public EarningsSummaryResponse getStudentEarnings(String username) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        BigDecimal totalEarned = quizAttemptRepository.calculateTotalEarnedByStudent(student.getId());
        BigDecimal totalPaid = payoutRepository.calculateTotalPaidToStudent(student.getId());
        BigDecimal balanceOwed = totalEarned.subtract(totalPaid);

        Playlist playlist = playlistRepository.findFirstByOrderByIdAsc().orElse(null);
        long totalEpisodes = playlist != null ? episodeRepository.countActiveByPlaylistId(playlist.getId()) : 0;
        long passedEpisodes = playlist != null ? quizAttemptRepository.countPassedEpisodesByStudentAndPlaylist(student.getId(), playlist.getId()) : 0;
        boolean bonusUnlocked = playlist != null && (
                playlistBonusClaimRepository.existsByStudentIdAndPlaylistId(student.getId(), playlist.getId())
                || (totalEpisodes >= playlist.getMinEpisodesForBonus() && passedEpisodes >= totalEpisodes)
        );
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
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payout amount must be greater than $0.00");
        }

        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found with id: " + studentId));

        String cleanNote = (note != null && !note.isBlank()) ? note.trim() : "Cash/Transfer Payout";
        Payout payout = new Payout(student, amount, cleanNote);
        payout.setIsPaid(true);
        payout.setPaidAt(LocalDateTime.now());

        return payoutRepository.save(payout);
    }
}
