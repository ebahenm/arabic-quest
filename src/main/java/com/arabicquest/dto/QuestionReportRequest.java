package com.arabicquest.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

public record QuestionReportRequest(
        UUID sessionId,
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason
) {}
