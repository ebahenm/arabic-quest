// JPA entity tracking asynchronous quiz generation jobs per episode across restarts.
package com.arabicquest.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "generation_job")
public class GenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false)
    @JsonIgnore
    private Episode episode;

    @Column(name = "writer_model", length = 80)
    private String writerModel;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "QUEUED";

    @Column(name = "stage", length = 60)
    private String stage = "QUEUED";

    @Column(name = "progress")
    private Integer progress = 0;

    @Column(name = "auto_triggered")
    private Boolean autoTriggered = false;

    @Column(name = "saved_count")
    private Integer savedCount = 0;

    @Column(name = "sanitized_error", columnDefinition = "TEXT")
    private String sanitizedError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public GenerationJob() {
    }

    public GenerationJob(Episode episode, String writerModel, boolean autoTriggered) {
        this.episode = episode;
        this.writerModel = writerModel != null ? writerModel : "gemini-3.8-flash";
        this.status = "QUEUED";
        this.stage = "QUEUED";
        this.progress = 0;
        this.autoTriggered = autoTriggered;
        this.savedCount = 0;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "QUEUED";
        }
        if (this.progress == null) {
            this.progress = 0;
        }
        if (this.autoTriggered == null) {
            this.autoTriggered = false;
        }
    }

    public Long getId() { return id; }
    public Episode getEpisode() { return episode; }
    public void setEpisode(Episode episode) { this.episode = episode; }
    public String getWriterModel() { return writerModel; }
    public void setWriterModel(String writerModel) { this.writerModel = writerModel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public int getProgress() { return progress == null ? 0 : progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public boolean isAutoTriggered() { return Boolean.TRUE.equals(autoTriggered); }
    public void setAutoTriggered(Boolean autoTriggered) { this.autoTriggered = autoTriggered; }
    public int getSavedCount() { return savedCount == null ? 0 : savedCount; }
    public void setSavedCount(Integer savedCount) { this.savedCount = savedCount; }
    public String getSanitizedError() { return sanitizedError; }
    public void setSanitizedError(String sanitizedError) { this.sanitizedError = sanitizedError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
