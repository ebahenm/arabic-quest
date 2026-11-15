// Stores uploaded or auto-fetched episode transcripts, parsed cues, and quality gate results.
package com.arabicquest.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "episode_transcript")
public class EpisodeTranscript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false, unique = true)
    @JsonIgnore
    private Episode episode;

    @Column(name = "source", nullable = false, length = 30)
    private String source = "UPLOAD";

    @JsonIgnore
    @Column(name = "cues_json", columnDefinition = "TEXT")
    private String cuesJson;

    @JsonIgnore
    @Column(name = "plain_text", columnDefinition = "TEXT")
    private String plainText;

    @Column(name = "word_count")
    private Integer wordCount = 0;

    @Column(name = "quality_status", nullable = false, length = 20)
    private String qualityStatus = "FAIL";

    @Column(name = "quality_report", columnDefinition = "TEXT")
    private String qualityReport;

    @Column(name = "use_anyway", nullable = false)
    private Boolean useAnyway = false;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    public EpisodeTranscript() {
    }

    public EpisodeTranscript(Episode episode, String source, String cuesJson, String plainText,
                             Integer wordCount, String qualityStatus, String qualityReport) {
        this.episode = episode;
        this.source = source != null ? source : "UPLOAD";
        this.cuesJson = cuesJson;
        this.plainText = plainText;
        this.wordCount = wordCount != null ? wordCount : 0;
        this.qualityStatus = qualityStatus != null ? qualityStatus : "FAIL";
        this.qualityReport = qualityReport;
        this.useAnyway = false;
        this.uploadedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.uploadedAt == null) {
            this.uploadedAt = LocalDateTime.now();
        }
        if (this.useAnyway == null) {
            this.useAnyway = false;
        }
        if (this.source == null) {
            this.source = "UPLOAD";
        }
        if (this.qualityStatus == null) {
            this.qualityStatus = "FAIL";
        }
    }

    public boolean isUsableForGeneration() {
        return "PASS".equalsIgnoreCase(qualityStatus) || Boolean.TRUE.equals(useAnyway);
    }

    public Long getId() { return id; }
    public Episode getEpisode() { return episode; }
    public void setEpisode(Episode episode) { this.episode = episode; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCuesJson() { return cuesJson; }
    public void setCuesJson(String cuesJson) { this.cuesJson = cuesJson; }
    public String getPlainText() { return plainText; }
    public void setPlainText(String plainText) { this.plainText = plainText; }
    public int getWordCount() { return wordCount == null ? 0 : wordCount; }
    public void setWordCount(Integer wordCount) { this.wordCount = wordCount; }
    public String getQualityStatus() { return qualityStatus; }
    public void setQualityStatus(String qualityStatus) { this.qualityStatus = qualityStatus; }
    public String getQualityReport() { return qualityReport; }
    public void setQualityReport(String qualityReport) { this.qualityReport = qualityReport; }
    public boolean isUseAnyway() { return Boolean.TRUE.equals(useAnyway); }
    public void setUseAnyway(Boolean useAnyway) { this.useAnyway = useAnyway; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}
