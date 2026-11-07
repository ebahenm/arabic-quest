package com.arabicquest.dto;

import java.util.Collections;
import java.util.List;

public class QuizViewResponse {
    private Long id;
    private Long episodeId;
    private boolean ready = true;
    private String message;
    private Double passingScorePercentage;
    private List<QuestionViewResponse> questions;

    public QuizViewResponse() {
    }

    public QuizViewResponse(Long id, Long episodeId, Double passingScorePercentage, List<QuestionViewResponse> questions) {
        this.id = id;
        this.episodeId = episodeId;
        this.ready = true;
        this.message = null;
        this.passingScorePercentage = passingScorePercentage;
        this.questions = questions;
    }

    public static QuizViewResponse notReady(Long episodeId, String message) {
        QuizViewResponse resp = new QuizViewResponse();
        resp.setId(null);
        resp.setEpisodeId(episodeId);
        resp.setReady(false);
        resp.setMessage(message != null ? message : "Quiz not ready yet");
        resp.setPassingScorePercentage(100.0);
        resp.setQuestions(Collections.emptyList());
        return resp;
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

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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
