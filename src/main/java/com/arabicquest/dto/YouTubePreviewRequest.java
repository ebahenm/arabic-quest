package com.arabicquest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YouTubePreviewRequest(
        @NotBlank(message = "Playlist URL or ID is required")
        @Size(max = 500, message = "Playlist URL or ID is too long")
        String playlistUrlOrId
) {
}
