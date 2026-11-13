// Validates admin course create/update payloads with a unified episode reward (plus legacy field fallback).
package com.arabicquest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpsertCourseRequest(
        @NotBlank(message = "Course title is required")
        @Size(max = 200, message = "Course title must not exceed 200 characters")
        String title,

        @Size(max = 100, message = "YouTube playlist ID must not exceed 100 characters")
        String youtubePlaylistId,

        @Size(max = 5000, message = "Course description is too long")
        String description,

        @Size(max = 1000, message = "Cover image URL must not exceed 1000 characters")
        String coverImageUrl,

        @DecimalMin(value = "0.00", message = "Episode reward must be >= 0")
        BigDecimal episodeReward,

        @DecimalMin(value = "0.00", message = "Arabic subtitle rate must be >= 0")
        BigDecimal rateArabicSub,

        @DecimalMin(value = "0.00", message = "English dub rate must be >= 0")
        BigDecimal rateEnglishDub,

        @DecimalMin(value = "0.00", message = "Completion bonus must be >= 0")
        BigDecimal completionBonus,

        Integer sortOrder
) {
    public BigDecimal resolveEpisodeReward() {
        if (episodeReward != null) {
            return episodeReward;
        }
        if (rateArabicSub != null) {
            return rateArabicSub;
        }
        return rateEnglishDub;
    }
}
