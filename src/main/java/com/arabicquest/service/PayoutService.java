package com.arabicquest.service;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class PayoutService {

    private final UserRepository userRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final PlaylistBonusClaimRepository playlistBonusClaimRepository;
    private final OpenAnswerSubmissionRepository openAnswerSubmissionRepository;
    private final PayoutRepository payoutRepository;
    private final EpisodeRepository episodeRepository;
    private final PlaylistRepository playlistRepository;

    public PayoutService(
            UserRepository userRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            PlaylistBonusClaimRepository playlistBonusClaimRepository,
            OpenAnswerSubmissionRepository openAnswerSubmissionRepository,
            PayoutRepository payoutRepository,
            EpisodeRepository episodeRepository,
            PlaylistRepository playlistRepository) {
        this.userRepository = userRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.playlistBonusClaimRepository = playlistBonusClaimRepository;
        this.openAnswerSubmissionRepository = openAnswerSubmissionRepository;
        this.payoutRepository = payoutRepository;
        this.episodeRepository = episodeRepository;
        this.playlistRepository = playlistRepository;
    }

    @Transactional(readOnly = true)
    public EarningsSummaryResponse getStudentEarnings(String username) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        BigDecimal episodeEarned = episodeRewardClaimRepository.calculateTotalEpisodeRewardsByStudent(student.getId());
        BigDecimal bonusEarned = playlistBonusClaimRepository.calculateTotalBonusesByStudent(student.getId());
        BigDecimal totalEarned = episodeEarned.add(bonusEarned);

        Set<Long> claimedEpisodeIds = episodeRewardClaimRepository.findClaimedEpisodeIdsByStudentId(student.getId());
        List<OpenAnswerSubmission> submissions = openAnswerSubmissionRepository
                .findByStudentIdWithEpisodeAndPlaylist(student.getId());

        BigDecimal pendingReviewAmount = BigDecimal.ZERO;
        long pendingReviewCount = 0L;
        for (OpenAnswerSubmission sub : submissions) {
            if (sub.getStatus() == OpenAnswerStatus.PENDING
                    && !claimedEpisodeIds.contains(sub.getEpisode().getId())) {
                Playlist p = sub.getEpisode().getPlaylist();
                BigDecimal rate = sub.getWatchMode() == WatchMode.ENGLISH_DUB
                        ? (p != null && p.getEnglishRewardRate() != null ? p.getEnglishRewardRate() : BrandConstants.DEFAULT_RATE_ENGLISH_DUB)
                        : (p != null && p.getArabicRewardRate() != null ? p.getArabicRewardRate() : BrandConstants.DEFAULT_RATE_ARABIC_SUB);
                pendingReviewAmount = pendingReviewAmount.add(rate);
                pendingReviewCount++;
            }
        }

        BigDecimal totalPaid = payoutRepository.calculateTotalPaidToStudent(student.getId());
        BigDecimal balanceOwed = totalEarned.subtract(totalPaid);

        Playlist playlist = playlistRepository.findFirstByOrderByIdAsc().orElse(null);
        long totalEpisodes = playlist != null ? episodeRepository.countActiveByPlaylistId(playlist.getId()) : 0;
        long passedEpisodes = playlist != null
                ? episodeRewardClaimRepository.countClaimedActiveEpisodesByStudentAndPlaylist(student.getId(), playlist.getId())
                : 0;
        boolean bonusUnlocked = playlist != null
                && playlistBonusClaimRepository.existsByStudentIdAndPlaylistId(student.getId(), playlist.getId());
        BigDecimal bonusAmount = playlist != null ? playlist.getCompletionBonus() : BrandConstants.DEFAULT_COMPLETION_BONUS;

        return new EarningsSummaryResponse(
                student.getUsername(),
                totalEarned,
                pendingReviewAmount,
                pendingReviewCount,
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
