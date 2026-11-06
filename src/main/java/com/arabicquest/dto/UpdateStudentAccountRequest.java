package com.arabicquest.dto;

import jakarta.validation.constraints.Size;

public record UpdateStudentAccountRequest(
        @Size(max = 100, message = "Username must not exceed 100 characters")
        String newUsername,

        @Size(max = 100, message = "Full name must not exceed 100 characters")
        String fullName,

        @Size(max = 128, message = "Password must not exceed 128 characters")
        String newPassword,

        Boolean betaTester
) {}
