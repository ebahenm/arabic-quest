package com.arabicquest.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_question_exposures", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"student_id", "question_id"})
})
public class StudentQuestionExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false)
    private Episode episode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "last_shown_at", nullable = false)
    private LocalDateTime lastShownAt;

    @Column(name = "exposure_count", nullable = false)
    private Integer exposureCount = 1;

    public StudentQuestionExposure() {
    }

    public StudentQuestionExposure(User student, Episode episode, Question question, LocalDateTime lastShownAt) {
        this.student = student;
        this.episode = episode;
        this.question = question;
        this.lastShownAt = lastShownAt;
        this.exposureCount = 1;
    }

    public Long getId() {
        return id;
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

    public Question getQuestion() {
        return question;
    }

    public void setQuestion(Question question) {
        this.question = question;
    }

    public LocalDateTime getLastShownAt() {
        return lastShownAt;
    }

    public void setLastShownAt(LocalDateTime lastShownAt) {
        this.lastShownAt = lastShownAt;
    }

    public int getExposureCount() {
        return exposureCount == null ? 0 : exposureCount;
    }

    public void setExposureCount(Integer exposureCount) {
        this.exposureCount = exposureCount;
    }
}
