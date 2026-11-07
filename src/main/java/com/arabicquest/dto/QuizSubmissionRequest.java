package com.arabicquest.dto;

import com.arabicquest.model.WatchMode;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public class QuizSubmissionRequest {

    @NotNull(message = "Quiz session ID is required")
    private UUID sessionId;

    private Long episodeId;

    private WatchMode watchMode;

    // Map of questionId -> selectedOptionId
    @NotNull(message = "Answers map is required")
    private Map<Long, Long> answers;

    public QuizSubmissionRequest() {
    }

    public QuizSubmissionRequest(UUID sessionId, Long episodeId, WatchMode watchMode, Map<Long, Long> answers) {
        this.sessionId = sessionId;
        this.episodeId = episodeId;
        this.watchMode = watchMode;
        this.answers = answers;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public Long getEpisodeId() {
        return episodeId;
    }

    public void setEpisodeId(Long episodeId) {
        this.episodeId = episodeId;
    }

    public WatchMode getWatchMode() {
        return watchMode;
    }

    public void setWatchMode(WatchMode watchMode) {
        this.watchMode = watchMode;
    }

    public Map<Long, Long> getAnswers() {
        return answers;
    }

    public void setAnswers(Map<Long, Long> answers) {
        this.answers = answers;
    }
}
