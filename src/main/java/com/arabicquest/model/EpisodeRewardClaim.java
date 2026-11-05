package com.arabicquest.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "episode_reward_claims", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"student_id", "episode_id"})
})
public class EpisodeRewardClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false)
    private Episode episode;

    @Enumerated(EnumType.STRING)
    @Column(name = "watch_mode", nullable = false, length = 20)
    private WatchMode watchMode;

    @Column(name = "reward_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal rewardAmount;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private LocalDateTime claimedAt;

    public EpisodeRewardClaim() {
    }

    public EpisodeRewardClaim(User student, Episode episode, WatchMode watchMode, BigDecimal rewardAmount) {
        this.student = student;
        this.episode = episode;
        this.watchMode = watchMode;
        this.rewardAmount = rewardAmount;
    }

    @PrePersist
    protected void onCreate() {
        this.claimedAt = LocalDateTime.now();
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

    public WatchMode getWatchMode() {
        return watchMode;
    }

    public BigDecimal getRewardAmount() {
        return rewardAmount;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }
}
