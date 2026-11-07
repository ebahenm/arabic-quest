package com.arabicquest.dto;

import java.math.BigDecimal;

public class EarningsSummaryResponse {

    private String studentUsername;
    private BigDecimal totalEarned;
    private BigDecimal pendingReviewAmount;
    private long pendingReviewCount;
    private BigDecimal totalPaid;
    private BigDecimal balanceOwed;
    private long passedEpisodesCount;
    private long totalEpisodesCount;
    private boolean bonusUnlocked;
    private BigDecimal completionBonusAmount;

    public EarningsSummaryResponse() {
    }

    public EarningsSummaryResponse(
            String studentUsername,
            BigDecimal totalEarned,
            BigDecimal pendingReviewAmount,
            long pendingReviewCount,
            BigDecimal totalPaid,
            BigDecimal balanceOwed,
            long passedEpisodesCount,
            long totalEpisodesCount,
            boolean bonusUnlocked,
            BigDecimal completionBonusAmount) {
        this.studentUsername = studentUsername;
        this.totalEarned = totalEarned;
        this.pendingReviewAmount = pendingReviewAmount != null ? pendingReviewAmount : BigDecimal.ZERO;
        this.pendingReviewCount = pendingReviewCount;
        this.totalPaid = totalPaid;
        this.balanceOwed = balanceOwed;
        this.passedEpisodesCount = passedEpisodesCount;
        this.totalEpisodesCount = totalEpisodesCount;
        this.bonusUnlocked = bonusUnlocked;
        this.completionBonusAmount = completionBonusAmount;
    }

    public String getStudentUsername() {
        return studentUsername;
    }

    public void setStudentUsername(String studentUsername) {
        this.studentUsername = studentUsername;
    }

    public BigDecimal getTotalEarned() {
        return totalEarned;
    }

    public void setTotalEarned(BigDecimal totalEarned) {
        this.totalEarned = totalEarned;
    }

    public BigDecimal getPendingReviewAmount() {
        return pendingReviewAmount;
    }

    public void setPendingReviewAmount(BigDecimal pendingReviewAmount) {
        this.pendingReviewAmount = pendingReviewAmount;
    }

    public long getPendingReviewCount() {
        return pendingReviewCount;
    }

    public void setPendingReviewCount(long pendingReviewCount) {
        this.pendingReviewCount = pendingReviewCount;
    }

    public BigDecimal getTotalPaid() {
        return totalPaid;
    }

    public void setTotalPaid(BigDecimal totalPaid) {
        this.totalPaid = totalPaid;
    }

    public BigDecimal getBalanceOwed() {
        return balanceOwed;
    }

    public void setBalanceOwed(BigDecimal balanceOwed) {
        this.balanceOwed = balanceOwed;
    }

    public long getPassedEpisodesCount() {
        return passedEpisodesCount;
    }

    public void setPassedEpisodesCount(long passedEpisodesCount) {
        this.passedEpisodesCount = passedEpisodesCount;
    }

    public long getTotalEpisodesCount() {
        return totalEpisodesCount;
    }

    public void setTotalEpisodesCount(long totalEpisodesCount) {
        this.totalEpisodesCount = totalEpisodesCount;
    }

    public boolean isBonusUnlocked() {
        return bonusUnlocked;
    }

    public void setBonusUnlocked(boolean bonusUnlocked) {
        this.bonusUnlocked = bonusUnlocked;
    }

    public BigDecimal getCompletionBonusAmount() {
        return completionBonusAmount;
    }

    public void setCompletionBonusAmount(BigDecimal completionBonusAmount) {
        this.completionBonusAmount = completionBonusAmount;
    }
}
