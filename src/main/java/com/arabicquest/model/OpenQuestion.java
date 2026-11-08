package com.arabicquest.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "open_questions")
public class OpenQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false)
    @JsonIgnore
    private Episode episode;

    @Column(name = "prompt_text", nullable = false, columnDefinition = "TEXT")
    private String promptText;

    @JsonIgnore
    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

    @JsonIgnore
    @Column(name = "evidence_timestamp")
    private Integer evidenceTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuestionStatus status = QuestionStatus.DRAFT;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public OpenQuestion() {
    }

    public OpenQuestion(Episode episode, String promptText, String evidence, QuestionStatus status) {
        this(episode, promptText, evidence, null, status);
    }

    public OpenQuestion(Episode episode, String promptText, String evidence, Integer evidenceTimestamp, QuestionStatus status) {
        this.episode = episode;
        this.promptText = promptText;
        this.evidence = evidence;
        this.evidenceTimestamp = evidenceTimestamp;
        this.status = status != null ? status : QuestionStatus.DRAFT;
        this.active = true;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = QuestionStatus.DRAFT;
        }
        if (this.active == null) {
            this.active = true;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Episode getEpisode() {
        return episode;
    }

    public void setEpisode(Episode episode) {
        this.episode = episode;
    }

    public String getPromptText() {
        return promptText;
    }

    public void setPromptText(String promptText) {
        this.promptText = promptText;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public Integer getEvidenceTimestamp() {
        return evidenceTimestamp;
    }

    public void setEvidenceTimestamp(Integer evidenceTimestamp) {
        this.evidenceTimestamp = evidenceTimestamp;
    }

    public QuestionStatus getStatus() {
        return status == null ? QuestionStatus.DRAFT : status;
    }

    public void setStatus(QuestionStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return active == null || active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
