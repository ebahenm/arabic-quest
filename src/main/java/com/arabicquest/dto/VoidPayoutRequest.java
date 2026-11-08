package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VoidPayoutRequest(
        @NotBlank(message = "A reason is required to void a payout")
        @Size(max = 500, message = "Void reason must not exceed 500 characters")
        String reason
) {}
