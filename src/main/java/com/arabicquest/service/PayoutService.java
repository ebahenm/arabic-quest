package com.arabicquest.service;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final UserRepository userRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final PlaylistBonusClaimRepository playlistBonusClaimRepository;
    private final OpenAnswerSubmissionRepository openAnswerSubmissionRepository;
    private final PayoutRepository payoutRepository;
    private final BalanceAdjustmentRepository balanceAdjustmentRepository;
    private final EpisodeRepository episodeRepository;
    private final PlaylistRepository playlistRepository;

    public PayoutService(
            UserRepository userRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            PlaylistBonusClaimRepository playlistBonusClaimRepository,
            OpenAnswerSubmissionRepository openAnswerSubmissionRepository,
            PayoutRepository payoutRepository,
            BalanceAdjustmentRepository balanceAdjustmentRepository,
            EpisodeRepository episodeRepository,
            PlaylistRepository playlistRepository) {
        this.userRepository = userRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.playlistBonusClaimRepository = playlistBonusClaimRepository;
        this.openAnswerSubmissionRepository = openAnswerSubmissionRepository;
        this.payoutRepository = payoutRepository;
        this.balanceAdjustmentRepository = balanceAdjustmentRepository;
        this.episodeRepository = episodeRepository;
        this.playlistRepository = playlistRepository;
    }

    @Transactional(readOnly = true)
    public EarningsSummaryResponse getStudentEarnings(String username) {
        User student = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        BigDecimal episodeEarned = episodeRewardClaimRepository.calculateTotalEpisodeRewardsByStudent(student.getId());
        BigDecimal bonusEarned = playlistBonusClaimRepository.calculateTotalBonusesByStudent(student.getId());
        BigDecimal adjustments = balanceAdjustmentRepository.calculateTotalAdjustmentsByStudent(student.getId());
        BigDecimal totalEarned = episodeEarned.add(bonusEarned).add(adjustments);

        // Under Phase B pass rules, written answer submission immediately creates the reward claim.
        BigDecimal pendingReviewAmount = BigDecimal.ZERO;
        long pendingReviewCount = 0L;

        BigDecimal totalPaid = payoutRepository.calculateTotalPaidToStudent(student.getId());
        BigDecimal balanceOwed = totalEarned.subtract(totalPaid);

        List<Playlist> publishedCourses = playlistRepository.findPublishedActiveOrderBySortOrderAsc();
        long totalEpisodes = 0;
        long passedEpisodes = 0;
        boolean bonusUnlocked = false;
        BigDecimal bonusAmount = BrandConstants.DEFAULT_COMPLETION_BONUS;
        for (int i = 0; i < publishedCourses.size(); i++) {
            Playlist c = publishedCourses.get(i);
            totalEpisodes += episodeRepository.countActiveByPlaylistId(c.getId());
            passedEpisodes += episodeRewardClaimRepository
                    .countClaimedActiveEpisodesByStudentAndPlaylist(student.getId(), c.getId());
            if (playlistBonusClaimRepository.existsByStudentIdAndPlaylistId(student.getId(), c.getId())) {
                bonusUnlocked = true;
            }
            if (i == 0 && c.getCompletionBonus() != null) {
                bonusAmount = c.getCompletionBonus();
            }
        }

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

    @Transactional
    public Payout voidPayout(Long payoutId, String rawReason, String adminUsername) {
        if (rawReason == null || rawReason.trim().isEmpty()) {
            throw new IllegalArgumentException("A reason is required to void a payout");
        }
        Payout payout = payoutRepository.findByIdWithStudent(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("Payout not found with id: " + payoutId));

        if (payout.isVoided()) {
            throw new IllegalStateException("Payout #" + payoutId + " is already voided.");
        }

        payout.setVoided(true);
        payout.setVoidReason(rawReason.trim());
        payout.setVoidedBy(adminUsername != null ? adminUsername : "admin");
        payout.setVoidedAt(LocalDateTime.now());

        log.info("Audited: Admin '{}' voided Payout #{} (${}) for student '{}'. Reason: {}",
                adminUsername, payout.getId(), payout.getAmount(), payout.getStudent().getUsername(), rawReason.trim());

        return payoutRepository.save(payout);
    }

    @Transactional
    public BalanceAdjustment createAdjustment(Long studentId, BigDecimal amount, String rawNote, String adminUsername) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Adjustment amount cannot be zero");
        }
        if (rawNote == null || rawNote.trim().isEmpty()) {
            throw new IllegalArgumentException("Adjustment note is required");
        }

        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found with id: " + studentId));

        String note = rawNote.trim();
        BalanceAdjustment adjustment = new BalanceAdjustment(student, amount, note, adminUsername != null ? adminUsername : "admin");
        log.info("Audited: Admin '{}' added balance adjustment of ${} to student '{}'. Note: {}",
                adminUsername, amount, student.getUsername(), note);

        return balanceAdjustmentRepository.save(adjustment);
    }

    @Transactional(readOnly = true)
    public List<BalanceAdjustment> getAdjustmentsByStudent(Long studentId) {
        return balanceAdjustmentRepository.findByStudentIdOrderByTimestampDesc(studentId);
    }

    @Transactional(readOnly = true)
    public List<BalanceAdjustment> getAllAdjustments() {
        return balanceAdjustmentRepository.findAllWithStudentOrderByTimestampDesc();
    }

    @Transactional
    public void reconcileLegacyPaidThirtyNegativeBalances(String adminUsername) {
        String reconciliationNote = "Earnings from the old system, already paid";
        List<User> students = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.ROLE_STUDENT)
                .toList();

        for (User student : students) {
            boolean alreadyReconciled = balanceAdjustmentRepository
                    .existsByStudentIdAndNote(student.getId(), reconciliationNote);
            if (alreadyReconciled) {
                continue;
            }

            BigDecimal paid = payoutRepository.calculateTotalPaidToStudent(student.getId());
            BigDecimal rewards = episodeRewardClaimRepository.calculateTotalEpisodeRewardsByStudent(student.getId());
            BigDecimal bonuses = playlistBonusClaimRepository.calculateTotalBonusesByStudent(student.getId());
            BigDecimal adjustments = balanceAdjustmentRepository.calculateTotalAdjustmentsByStudent(student.getId());
            BigDecimal earnedBefore = rewards.add(bonuses).add(adjustments);
            BigDecimal owedBefore = earnedBefore.subtract(paid);

            // Target condition: Brother's account with $30 genuine payout from old version and $0 earned
            if (paid.compareTo(new BigDecimal("30.00")) == 0 && earnedBefore.compareTo(BigDecimal.ZERO) == 0) {
                BalanceAdjustment adj = new BalanceAdjustment(
                        student,
                        new BigDecimal("30.00"),
                        reconciliationNote,
                        adminUsername != null ? adminUsername : "admin"
                );
                balanceAdjustmentRepository.save(adj);

                BigDecimal earnedAfter = earnedBefore.add(new BigDecimal("30.00"));
                BigDecimal owedAfter = earnedAfter.subtract(paid);
                log.info("Reconciled legacy paid $30 negative balance for student '{}' (id {}): before [Earned=${}, Paid=${}, Owed=${}] -> after [Earned=${}, Paid=${}, Owed=${}]",
                        student.getUsername(), student.getId(), earnedBefore, paid, owedBefore, earnedAfter, paid, owedAfter);
            }
        }
    }
}
