package com.arabicquest.dto;

import com.arabicquest.model.BalanceAdjustment;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BalanceAdjustmentResponse(
        Long id,
        Long studentId,
        String studentUsername,
        String studentFullName,
        BigDecimal amount,
        String note,
        String admin,
        LocalDateTime timestamp
) {
    public static BalanceAdjustmentResponse from(BalanceAdjustment ba) {
        return new BalanceAdjustmentResponse(
                ba.getId(),
                ba.getStudent().getId(),
                ba.getStudent().getUsername(),
                ba.getStudent().getFullName(),
                ba.getAmount(),
                ba.getNote(),
                ba.getAdmin(),
                ba.getTimestamp()
        );
    }
}
