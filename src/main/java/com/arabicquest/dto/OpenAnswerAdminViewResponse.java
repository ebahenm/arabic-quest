package com.arabicquest.dto;
package com.arabicquest.dto;

import com.arabicquest.model.OpenAnswerSubmission;
import com.arabicquest.model.Playlist;
import com.arabicquest.model.WatchMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OpenAnswerAdminViewResponse(
        Long id,
        Long studentId,
        String studentUsername,
        String studentFullName,
        Long episodeId,
        String courseTitle,
        Integer episodeNumber,
        String episodeTitle,
        Long openQuestionId,
        String promptText,
        String evidence,
        WatchMode watchMode,
        BigDecimal potentialRewardAmount,
        String answerText,
        String status,
        boolean readByAdmin,
        String adminFeedback,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt
) {
    public static OpenAnswerAdminViewResponse from(OpenAnswerSubmission s) {
        Playlist p = s.getEpisode().getPlaylist();
        BigDecimal rate = s.getWatchMode() == WatchMode.ARABIC_SUB
                ? p.getArabicRewardRate()
                : p.getEnglishRewardRate();
        return new OpenAnswerAdminViewResponse(
                s.getId(),
                s.getStudent().getId(),
                s.getStudent().getUsername(),
                s.getStudent().getFullName(),
                s.getEpisode().getId(),
                p.getTitle(),
                s.getEpisode().getEpisodeNumber(),
                s.getEpisode().getTitle(),
                s.getOpenQuestion().getId(),
                s.getOpenQuestion().getPromptText(),
                s.getOpenQuestion().getEvidence(),
                s.getWatchMode(),
                rate,
                s.getAnswerText(),
                s.getStatus().name(),
                s.isReadByAdmin(),
                s.getAdminFeedback(),
                s.getSubmittedAt(),
                s.getReviewedAt()
        );
    }
}
import com.arabicquest.model.OpenAnswerSubmission;
import com.arabicquest.model.Playlist;
import com.arabicquest.model.WatchMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OpenAnswerAdminViewResponse(
        Long id,
        Long studentId,
        String studentUsername,
        String studentFullName,
        Long episodeId,
        String courseTitle,
        Integer episodeNumber,
        String episodeTitle,
        Long openQuestionId,
        String promptText,
        String evidence,
        WatchMode watchMode,
        BigDecimal potentialRewardAmount,
        String answerText,
        String status,
        String adminFeedback,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt
) {
    public static OpenAnswerAdminViewResponse from(OpenAnswerSubmission s) {
        Playlist p = s.getEpisode().getPlaylist();
        BigDecimal rate = s.getWatchMode() == WatchMode.ARABIC_SUB
                ? p.getArabicRewardRate()
                : p.getEnglishRewardRate();
        return new OpenAnswerAdminViewResponse(
                s.getId(),
                s.getStudent().getId(),
                s.getStudent().getUsername(),
                s.getStudent().getFullName(),
                s.getEpisode().getId(),
                p.getTitle(),
                s.getEpisode().getEpisodeNumber(),
                s.getEpisode().getTitle(),
                s.getOpenQuestion().getId(),
                s.getOpenQuestion().getPromptText(),
                s.getOpenQuestion().getEvidence(),
                s.getWatchMode(),
                rate,
                s.getAnswerText(),
                s.getStatus().name(),
                s.getAdminFeedback(),
                s.getSubmittedAt(),
                s.getReviewedAt()
        );
    }
}
