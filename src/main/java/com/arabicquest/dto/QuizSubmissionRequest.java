package com.arabicquest.dto;

import com.arabicquest.model.WatchMode;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public class QuizSubmissionRequest {

    @NotNull
    private Long episodeId;

    @NotNull
    private WatchMode watchMode;

    // Map of questionId -> selectedOptionId
    @NotNull
    private Map<Long, Long> answers;

    public QuizSubmissionRequest() {
    }

    public QuizSubmissionRequest(Long episodeId, WatchMode watchMode, Map<Long, Long> answers) {
        this.episodeId = episodeId;
        this.watchMode = watchMode;
        this.answers = answers;
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
