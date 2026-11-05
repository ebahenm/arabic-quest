package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetStudentPasswordRequest(
        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 128, message = "Password must be at least 8 characters")
        String newPassword
) {}
