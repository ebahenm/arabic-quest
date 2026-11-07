package com.arabicquest.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_open_question_assignments", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"student_id", "episode_id"})
})
public class StudentOpenQuestionAssignment {

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
    @JoinColumn(name = "open_question_id", nullable = false)
    private OpenQuestion openQuestion;

    @Enumerated(EnumType.STRING)
    @Column(name = "watch_mode", nullable = false, length = 30)
    private WatchMode watchMode = WatchMode.ARABIC_SUB;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;

    public StudentOpenQuestionAssignment() {
    }

    public StudentOpenQuestionAssignment(User student, Episode episode, OpenQuestion openQuestion, WatchMode watchMode) {
        this.student = student;
        this.episode = episode;
        this.openQuestion = openQuestion;
        this.watchMode = watchMode != null ? watchMode : WatchMode.ARABIC_SUB;
        this.assignedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.assignedAt == null) {
            this.assignedAt = LocalDateTime.now();
        }
        if (this.watchMode == null) {
            this.watchMode = WatchMode.ARABIC_SUB;
        }
    }

    public Long getId() {
        return id;
    }

    public User getStudent() {
        return student;
    }

    public Episode getEpisode() {
        return episode;
    }

    public OpenQuestion getOpenQuestion() {
        return openQuestion;
    }

    public void setOpenQuestion(OpenQuestion openQuestion) {
        this.openQuestion = openQuestion;
    }

    public WatchMode getWatchMode() {
        return watchMode;
    }

    public void setWatchMode(WatchMode watchMode) {
        this.watchMode = watchMode;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }
}
