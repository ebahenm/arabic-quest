package com.arabicquest.dto;

import com.arabicquest.model.User;
import java.time.LocalDateTime;

public record UserSummaryResponse(
        Long id,
        String username,
        String fullName,
        String role,
        boolean betaTester,
        boolean enabled,
        LocalDateTime createdAt
) {
    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole().name(),
                user.hasBetaAccess(),
                user.isEnabled(),
                user.getCreatedAt()
        );
    }
}
