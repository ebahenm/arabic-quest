package com.arabicquest.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "episodes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"playlist_id", "episode_number"})
})
public class Episode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id", nullable = false)
    @JsonIgnore
    private Playlist playlist;

    @Column(name = "episode_number", nullable = false)
    private Integer episodeNumber;

    @Column(nullable = false, length = 250)
    private String title;

    @Column(name = "youtube_video_id", nullable = false, length = 100)
    private String youtubeVideoId;

    @Column(name = "duration_seconds")
    private Integer durationSeconds = 1800;

    @JsonIgnore
    @Column(name = "vocabulary_json", columnDefinition = "TEXT")
    private String vocabularyJson;

    @JsonIgnore
    @Column(name = "transcript_text", columnDefinition = "TEXT")
    private String transcriptText;

    @Column(name = "archived")
    private Boolean archived = false;

    @OneToOne(mappedBy = "episode", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Quiz quiz;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Episode() {
    }

    public Episode(Playlist playlist, Integer episodeNumber, String title, String youtubeVideoId) {
        this(playlist, episodeNumber, title, youtubeVideoId, 1800);
    }

    public Episode(Playlist playlist, Integer episodeNumber, String title, String youtubeVideoId, Integer durationSeconds) {
        this.playlist = playlist;
        this.episodeNumber = episodeNumber;
        this.title = title;
        this.youtubeVideoId = youtubeVideoId;
        this.durationSeconds = durationSeconds != null ? durationSeconds : 1800;
        this.archived = false;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.durationSeconds == null) {
            this.durationSeconds = 1800;
        }
        if (this.archived == null) {
            this.archived = false;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Playlist getPlaylist() {
        return playlist;
    }

    public void setPlaylist(Playlist playlist) {
        this.playlist = playlist;
    }

    public Integer getEpisodeNumber() {
        return episodeNumber;
    }

    public void setEpisodeNumber(Integer episodeNumber) {
        this.episodeNumber = episodeNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getYoutubeVideoId() {
        return youtubeVideoId;
    }

    public void setYoutubeVideoId(String youtubeVideoId) {
        this.youtubeVideoId = youtubeVideoId;
    }

    public int getDurationSeconds() {
        return durationSeconds != null && durationSeconds > 0 ? durationSeconds : 1800;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getVocabularyJson() {
        return vocabularyJson;
    }

    public void setVocabularyJson(String vocabularyJson) {
        this.vocabularyJson = vocabularyJson;
    }

    public String getTranscriptText() {
        return transcriptText;
    }

    public void setTranscriptText(String transcriptText) {
        this.transcriptText = transcriptText;
    }

    public boolean isArchived() {
        return Boolean.TRUE.equals(archived);
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
