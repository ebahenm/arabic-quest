package com.arabicquest.dto;

import java.math.BigDecimal;

public class QuizResultResponse {

    private Double scorePercentage;
    private Boolean passed;
    private BigDecimal rewardAmount;
    private String message;

    public QuizResultResponse() {
    }

    public QuizResultResponse(Double scorePercentage, Boolean passed, BigDecimal rewardAmount, String message) {
        this.scorePercentage = scorePercentage;
        this.passed = passed;
        this.rewardAmount = rewardAmount;
        this.message = message;
    }

    public Double getScorePercentage() {
        return scorePercentage;
    }

    public void setScorePercentage(Double scorePercentage) {
        this.scorePercentage = scorePercentage;
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
