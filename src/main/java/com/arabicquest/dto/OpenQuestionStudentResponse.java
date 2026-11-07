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
        LocalDateTime reviewedAt
) {}
