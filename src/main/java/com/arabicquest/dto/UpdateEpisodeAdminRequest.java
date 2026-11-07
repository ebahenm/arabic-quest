package com.arabicquest.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateEpisodeAdminRequest(
        @Size(max = 250, message = "Episode title must not exceed 250 characters")
        String title,

        @Min(value = 1, message = "Duration must be at least 1 second")
        Integer durationSeconds,

        Boolean enabled,

        Integer sortOrder
) {
}
