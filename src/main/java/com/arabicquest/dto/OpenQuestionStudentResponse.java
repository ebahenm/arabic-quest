package com.arabicquest.dto;

import com.arabicquest.model.WatchMode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OpenQuestionStudentResponse(
        Long episodeId,
        Long openQuestionId,
        String promptText,
        WatchMode watchMode,
        BigDecimal potentialRewardAmount,
        String submissionStatus,
        String submittedAnswerText,
        String adminFeedback,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        boolean rewardCreated,
        BigDecimal episodeRewardAmount,
        boolean bonusAwarded,
        BigDecimal bonusAmount
) {
    public OpenQuestionStudentResponse(
            Long episodeId,
            Long openQuestionId,
            String promptText,
            WatchMode watchMode,
            BigDecimal potentialRewardAmount,
            String submissionStatus,
            String submittedAnswerText,
            String adminFeedback,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt) {
        this(
                episodeId,
                openQuestionId,
                promptText,
                watchMode,
                potentialRewardAmount,
                submissionStatus,
                submittedAnswerText,
                adminFeedback,
                submittedAt,
                reviewedAt,
                false,
                BigDecimal.ZERO,
                false,
                BigDecimal.ZERO
        );
    }
}
