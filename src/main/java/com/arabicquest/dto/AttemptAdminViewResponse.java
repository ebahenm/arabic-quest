package com.arabicquest.dto;

import com.arabicquest.model.QuizAttempt;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AttemptAdminViewResponse(
        Long id,
        Long studentId,
        String studentUsername,
        String studentFullName,
        Long episodeId,
        Integer episodeNumber,
        String episodeTitle,
        String watchMode,
        Double scorePercentage,
        Boolean passed,
        BigDecimal rewardAmount,
        LocalDateTime attemptedAt
) {
    public static AttemptAdminViewResponse from(QuizAttempt qa) {
        return new AttemptAdminViewResponse(
                qa.getId(),
                qa.getStudent().getId(),
                qa.getStudent().getUsername(),
                qa.getStudent().getFullName(),
                qa.getEpisode().getId(),
                qa.getEpisode().getEpisodeNumber(),
                qa.getEpisode().getTitle(),
                qa.getWatchMode().name(),
                qa.getScorePercentage(),
                qa.getPassed(),
                qa.getRewardAmount(),
                qa.getAttemptedAt()
        );
    }
}
