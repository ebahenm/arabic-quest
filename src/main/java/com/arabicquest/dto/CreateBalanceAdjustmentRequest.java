package com.arabicquest.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateBalanceAdjustmentRequest(
        @NotNull(message = "Adjustment amount is required")
        @Digits(integer = 8, fraction = 2, message = "Invalid adjustment amount format")
        BigDecimal amount,

        @NotBlank(message = "Adjustment note is required")
        @Size(max = 500, message = "Adjustment note must not exceed 500 characters")
        String note
) {}
