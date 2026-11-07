package com.arabicquest.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record CourseAdminResponse(
        Long id,
        String title,
        String description,
        String coverImageUrl,
        String youtubePlaylistId,
        boolean published,
        boolean archived,
        int sortOrder,
        BigDecimal rateArabicSub,
        BigDecimal rateEnglishDub,
        BigDecimal completionBonus,
        int minEpisodesForBonus,
        int activeEpisodeCount,
        int totalEpisodeCount,
        long totalDurationSeconds,
        LocalDateTime createdAt,
        List<EpisodeAdminItem> episodes
) {
    public record EpisodeAdminItem(
            Long id,
            Long courseId,
            int episodeNumber,
            int sortOrder,
            String title,
            String youtubeVideoId,
            int durationSeconds,
            boolean enabled,
            boolean archived,
            long approvedMcqCount,
            long draftMcqCount,
            long approvedOpenCount,
            long draftOpenCount,
            boolean quizReady,
            String quizReadinessStatus,
            String quizReadinessLabel
    ) {
    }
}
