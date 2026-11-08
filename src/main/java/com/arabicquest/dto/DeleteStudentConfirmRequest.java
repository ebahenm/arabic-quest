package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteStudentConfirmRequest(
        @NotBlank(message = "Confirmation username is required")
        String confirmUsername
) {}
