package com.arabicquest.dto;

import java.util.List;

public class QuizViewResponse {
    private Long id;
    private Long episodeId;
    private Double passingScorePercentage;
    private List<QuestionViewResponse> questions;

    public QuizViewResponse() {
    }

    public QuizViewResponse(Long id, Long episodeId, Double passingScorePercentage, List<QuestionViewResponse> questions) {
        this.id = id;
        this.episodeId = episodeId;
        this.passingScorePercentage = passingScorePercentage;
        this.questions = questions;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEpisodeId() {
        return episodeId;
    }

    public void setEpisodeId(Long episodeId) {
        this.episodeId = episodeId;
    }

    public Double getPassingScorePercentage() {
        return passingScorePercentage;
    }

    public void setPassingScorePercentage(Double passingScorePercentage) {
        this.passingScorePercentage = passingScorePercentage;
    }

    public List<QuestionViewResponse> getQuestions() {
        return questions;
    }

    public void setQuestions(List<QuestionViewResponse> questions) {
        this.questions = questions;
    }
}
