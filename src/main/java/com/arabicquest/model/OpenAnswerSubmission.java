package com.arabicquest.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "open_answer_submissions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"student_id", "episode_id"})
})
public class OpenAnswerSubmission {

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

    @Column(name = "answer_text", nullable = false, columnDefinition = "TEXT")
    private String answerText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OpenAnswerStatus status = OpenAnswerStatus.PENDING;

    @Column(name = "admin_feedback", columnDefinition = "TEXT")
    private String adminFeedback;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    public OpenAnswerSubmission() {
    }

    public OpenAnswerSubmission(User student, Episode episode, OpenQuestion openQuestion, WatchMode watchMode, String answerText) {
        this.student = student;
        this.episode = episode;
        this.openQuestion = openQuestion;
        this.watchMode = watchMode != null ? watchMode : WatchMode.ARABIC_SUB;
        this.answerText = answerText;
        this.status = OpenAnswerStatus.PENDING;
        this.submittedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.submittedAt == null) {
            this.submittedAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = OpenAnswerStatus.PENDING;
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

    public void setStudent(User student) {
        this.student = student;
    }

    public Episode getEpisode() {
        return episode;
    }

    public void setEpisode(Episode episode) {
        this.episode = episode;
    }

    public OpenQuestion getOpenQuestion() {
        return openQuestion;
    }

    public void setOpenQuestion(OpenQuestion openQuestion) {
        this.openQuestion = openQuestion;
    }

    public WatchMode getWatchMode() {
        return watchMode != null ? watchMode : WatchMode.ARABIC_SUB;
    }

    public void setWatchMode(WatchMode watchMode) {
        this.watchMode = watchMode;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public OpenAnswerStatus getStatus() {
        return status;
    }

    public void setStatus(OpenAnswerStatus status) {
        this.status = status;
    }

    public String getAdminFeedback() {
        return adminFeedback;
    }

    public void setAdminFeedback(String adminFeedback) {
        this.adminFeedback = adminFeedback;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
