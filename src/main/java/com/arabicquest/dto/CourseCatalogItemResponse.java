// Student course catalog summary DTO exposing unified episodeReward and compatibility rate fields.
package com.arabicquest.dto;

import java.math.BigDecimal;

public record CourseCatalogItemResponse(
        Long id,
        String title,
        String description,
        String coverImageUrl,
        String youtubePlaylistId,
        int sortOrder,
        int totalEpisodes,
        int passedEpisodes,
        int progressPercentage,
        BigDecimal episodeReward,
        BigDecimal rateArabicSub,
        BigDecimal rateEnglishDub,
        BigDecimal completionBonus,
        boolean bonusEarned,
        BigDecimal earnedAmount,
        BigDecimal pendingReviewAmount,
        BigDecimal possibleAmount
) {}
