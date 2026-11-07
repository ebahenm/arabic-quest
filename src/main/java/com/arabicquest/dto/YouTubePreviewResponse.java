package com.arabicquest.dto;

import com.arabicquest.service.YouTubePlaylistService;

import java.util.List;

public record YouTubePreviewResponse(
        String youtubePlaylistId,
        String title,
        String description,
        String thumbnailUrl,
        int episodeCount,
        long totalDurationSeconds,
        int skippedCount,
        boolean alreadyImported,
        Long existingCourseId,
        List<YouTubePlaylistService.YouTubeEpisodeItem> episodes
) {
}
