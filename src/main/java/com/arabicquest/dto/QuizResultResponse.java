package com.arabicquest.dto;

import java.math.BigDecimal;

public class QuizResultResponse {

    private Double scorePercentage;
    private Integer correctCount;
    private Integer totalQuestions;
    private Boolean passed;
    private BigDecimal rewardAmount;
    private BigDecimal pendingRewardAmount;
    private Long retryCooldownSeconds;
    private String message;
    private OpenQuestionStudentResponse openQuestion;

    public QuizResultResponse() {
    }

    public QuizResultResponse(
            Double scorePercentage,
            Integer correctCount,
            Integer totalQuestions,
            Boolean passed,
            BigDecimal rewardAmount,
            BigDecimal pendingRewardAmount,
            Long retryCooldownSeconds,
            String message,
            OpenQuestionStudentResponse openQuestion) {
        this.scorePercentage = scorePercentage;
        this.correctCount = correctCount;
        this.totalQuestions = totalQuestions;
        this.passed = passed;
        this.rewardAmount = rewardAmount;
        this.pendingRewardAmount = pendingRewardAmount;
        this.retryCooldownSeconds = retryCooldownSeconds;
        this.message = message;
        this.openQuestion = openQuestion;
    }

    public Double getScorePercentage() {
        return scorePercentage;
    }

    public void setScorePercentage(Double scorePercentage) {
        this.scorePercentage = scorePercentage;
    }

    public Integer getCorrectCount() {
        return correctCount;
    }

    public void setCorrectCount(Integer correctCount) {
        this.correctCount = correctCount;
    }

    public Integer getTotalQuestions() {
        return totalQuestions;
    }

    public void setTotalQuestions(Integer totalQuestions) {
        this.totalQuestions = totalQuestions;
    }

    public Boolean getPassed() {
        return passed;
    }

    public void setPassed(Boolean passed) {
        this.passed = passed;
    }

    public BigDecimal getRewardAmount() {
        return rewardAmount;
    }

    public void setRewardAmount(BigDecimal rewardAmount) {
        this.rewardAmount = rewardAmount;
    }

    public BigDecimal getPendingRewardAmount() {
        return pendingRewardAmount;
    }

    public void setPendingRewardAmount(BigDecimal pendingRewardAmount) {
        this.pendingRewardAmount = pendingRewardAmount;
    }

    public Long getRetryCooldownSeconds() {
        return retryCooldownSeconds;
    }

    public void setRetryCooldownSeconds(Long retryCooldownSeconds) {
        this.retryCooldownSeconds = retryCooldownSeconds;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public OpenQuestionStudentResponse getOpenQuestion() {
        return openQuestion;
    }

    public void setOpenQuestion(OpenQuestionStudentResponse openQuestion) {
        this.openQuestion = openQuestion;
    }
}
