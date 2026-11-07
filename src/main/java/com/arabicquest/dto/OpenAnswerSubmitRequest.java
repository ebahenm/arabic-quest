package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OpenAnswerSubmitRequest(
        @NotBlank(message = "Written answer is required")
        @Size(max = 2500, message = "Written answer must not exceed 2,000 characters")
        String answerText
) {}
