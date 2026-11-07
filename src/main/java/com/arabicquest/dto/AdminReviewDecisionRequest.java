package com.arabicquest.dto;

import jakarta.validation.constraints.Size;

public record AdminReviewDecisionRequest(
        @Size(max = 2000, message = "Feedback must not exceed 2,000 characters")
        String feedback
) {}
