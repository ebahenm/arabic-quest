package com.arabicquest.dto;

import com.arabicquest.model.Payout;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PayoutResponse(
        Long id,
        Long studentId,
        String studentUsername,
        String studentFullName,
        BigDecimal amount,
        Boolean isPaid,
        LocalDateTime paidAt,
        String note,
        boolean voided,
        String voidReason,
        String voidedBy,
        LocalDateTime voidedAt,
        LocalDateTime createdAt
) {
    public static PayoutResponse from(Payout p) {
        return new PayoutResponse(
                p.getId(),
                p.getStudent().getId(),
                p.getStudent().getUsername(),
                p.getStudent().getFullName(),
                p.getAmount(),
                p.getIsPaid(),
                p.getPaidAt(),
                p.getNote(),
                p.isVoided(),
                p.getVoidReason(),
                p.getVoidedBy(),
                p.getVoidedAt(),
                p.getCreatedAt()
        );
    }
}
