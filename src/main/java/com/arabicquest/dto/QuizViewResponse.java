package com.arabicquest.dto;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class QuizViewResponse {
    private Long id;
    private Long episodeId;
    private UUID sessionId;
    private LocalDateTime expiresAt;
    private boolean ready = true;
    private String message;
    private Double passingScorePercentage;
    private List<QuestionViewResponse> questions;
    private OpenQuestionStudentResponse openQuestion;

    public QuizViewResponse() {
    }

    public QuizViewResponse(
            Long id,
            Long episodeId,
            UUID sessionId,
            LocalDateTime expiresAt,
            Double passingScorePercentage,
            List<QuestionViewResponse> questions,
            OpenQuestionStudentResponse openQuestion) {
        this.id = id;
        this.episodeId = episodeId;
        this.sessionId = sessionId;
        this.expiresAt = expiresAt;
        this.ready = true;
        this.message = null;
        this.passingScorePercentage = passingScorePercentage;
        this.questions = questions;
        this.openQuestion = openQuestion;
    }

    public static QuizViewResponse notReady(Long episodeId, String message) {
        QuizViewResponse resp = new QuizViewResponse();
        resp.setId(null);
        resp.setEpisodeId(episodeId);
        resp.setSessionId(null);
        resp.setExpiresAt(null);
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

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
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

    public OpenQuestionStudentResponse getOpenQuestion() {
        return openQuestion;
    }

    public void setOpenQuestion(OpenQuestionStudentResponse openQuestion) {
        this.openQuestion = openQuestion;
    }
}
