package com.arabicquest.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderEpisodesRequest(
        @NotEmpty(message = "Ordered episode IDs are required")
        List<Long> episodeIds
) {
}
