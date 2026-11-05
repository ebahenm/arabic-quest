package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCredentialsRequest(
        @NotBlank(message = "Current password is required to confirm changes")
        String currentPassword,

        @Size(max = 100, message = "Username must not exceed 100 characters")
        String newUsername,

        @Size(max = 128, message = "New password must not exceed 128 characters")
        String newPassword
) {}
