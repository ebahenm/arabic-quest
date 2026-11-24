package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetStudentPasswordRequest(
        @NotBlank(message = "New password is required")
        @Size(min = 10, max = 128, message = "Password must be at least 10 characters")
        String newPassword
) {}
