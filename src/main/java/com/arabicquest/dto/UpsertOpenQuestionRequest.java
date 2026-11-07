package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertOpenQuestionRequest(
        @NotBlank(message = "Open question prompt is required")
        @Size(max = 2000)
        String promptText,

        @Size(max = 3000)
        String evidence,

        String status,
        Boolean active
) {}
