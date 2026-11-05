package com.arabicquest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreatePayoutRequest(
        @NotNull(message = "Student ID is required")
        Long studentId,

        @NotNull(message = "Payout amount is required")
        @DecimalMin(value = "0.01", message = "Payout amount must be at least $0.01")
        @Digits(integer = 8, fraction = 2, message = "Invalid payout amount format")
        BigDecimal amount,

        @Size(max = 255, message = "Note cannot exceed 255 characters")
        String note
) {}
