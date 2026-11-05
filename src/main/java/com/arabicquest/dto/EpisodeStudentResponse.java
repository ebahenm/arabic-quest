package com.arabicquest.dto;

public record EpisodeStudentResponse(
        Long id,
        Long playlistId,
        Integer episodeNumber,
        String title,
        String youtubeVideoId,
        int durationSeconds,
        boolean passed,
        WatchStatusResponse watchStatus
) {}
