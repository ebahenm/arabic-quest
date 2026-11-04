package com.arabicquest.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "playlists")
public class Playlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "youtube_playlist_id", nullable = false, unique = true, length = 100)
    private String youtubePlaylistId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "arabic_reward_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal arabicRewardRate = new BigDecimal("15.00");

    @Column(name = "english_reward_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal englishRewardRate = new BigDecimal("10.00");

    @Column(name = "completion_bonus", nullable = false, precision = 10, scale = 2)
    private BigDecimal completionBonus = new BigDecimal("100.00");

    @OneToMany(mappedBy = "playlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("episodeNumber ASC")
    private List<Episode> episodes = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Playlist() {
    }

    public Playlist(String title, String youtubePlaylistId, String description) {
        this.title = title;
        this.youtubePlaylistId = youtubePlaylistId;
        this.description = description;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getYoutubePlaylistId() {
        return youtubePlaylistId;
    }

    public void setYoutubePlaylistId(String youtubePlaylistId) {
        this.youtubePlaylistId = youtubePlaylistId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getArabicRewardRate() {
        return arabicRewardRate;
    }

    public void setArabicRewardRate(BigDecimal arabicRewardRate) {
        this.arabicRewardRate = arabicRewardRate;
    }

    public BigDecimal getEnglishRewardRate() {
        return englishRewardRate;
    }

    public void setEnglishRewardRate(BigDecimal englishRewardRate) {
        this.englishRewardRate = englishRewardRate;
    }

    public BigDecimal getCompletionBonus() {
        return completionBonus;
    }

    public void setCompletionBonus(BigDecimal completionBonus) {
        this.completionBonus = completionBonus;
    }

    public List<Episode> getEpisodes() {
        return episodes;
    }

    public void setEpisodes(List<Episode> episodes) {
        this.episodes = episodes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
