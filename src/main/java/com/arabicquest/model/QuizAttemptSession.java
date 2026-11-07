package com.arabicquest.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "quiz_attempt_sessions", indexes = {
        @Index(name = "idx_quiz_session_student_ep", columnList = "student_id, episode_id")
})
public class QuizAttemptSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false)
    private Episode episode;

    @Column(name = "question_ids_csv", nullable = false, length = 600)
    private String questionIdsCsv;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuizSessionStatus status = QuizSessionStatus.OPEN;

    public QuizAttemptSession() {
    }

    public QuizAttemptSession(User student, Episode episode, List<Long> questionIds, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.id = UUID.randomUUID();
        this.student = student;
        this.episode = episode;
        setQuestionIds(questionIds);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = QuizSessionStatus.OPEN;
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.expiresAt == null) {
            this.expiresAt = this.createdAt.plusMinutes(60);
        }
        if (this.status == null) {
            this.status = QuizSessionStatus.OPEN;
        }
    }

    public List<Long> getQuestionIds() {
        if (questionIdsCsv == null || questionIdsCsv.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String token : questionIdsCsv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                ids.add(Long.parseLong(trimmed));
            }
        }
        return ids;
    }

    public void setQuestionIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            this.questionIdsCsv = "";
        } else {
            this.questionIdsCsv = ids.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getStudent() {
        return student;
    }

    public void setStudent(User student) {
        this.student = student;
    }

    public Episode getEpisode() {
        return episode;
    }

    public void setEpisode(Episode episode) {
        this.episode = episode;
    }

    public String getQuestionIdsCsv() {
        return questionIdsCsv;
    }

    public void setQuestionIdsCsv(String questionIdsCsv) {
        this.questionIdsCsv = questionIdsCsv;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public QuizSessionStatus getStatus() {
        return status;
    }

    public void setStatus(QuizSessionStatus status) {
        this.status = status;
    }
}
