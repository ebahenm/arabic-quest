package com.arabicquest.dto;

import java.math.BigDecimal;

public record EpisodeStudentResponse(
        Long id,
        Long playlistId,
        String courseTitle,
        Integer episodeNumber,
        String title,
        String youtubeVideoId,
        int durationSeconds,
        boolean passed,
        boolean locked,
        Integer unlockAfterEpisodeNumber,
        String state,
        String stateLabel,
        boolean quizReady,
        BigDecimal rateArabicSub,
        BigDecimal rateEnglishDub,
        WatchStatusResponse watchStatus
) {}
