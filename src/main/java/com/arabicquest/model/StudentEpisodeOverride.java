package com.arabicquest.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_episode_overrides", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"student_id", "episode_id"})
})
public class StudentEpisodeOverride {

    public enum OverrideState {
        UNLOCKED,
        LOCKED
    }

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
    @Column(name = "override_state", length = 20)
    private OverrideState overrideState;

    @Column(name = "cooldown_reset_at")
    private LocalDateTime cooldownResetAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public StudentEpisodeOverride() {
    }

    public StudentEpisodeOverride(User student, Episode episode) {
        this.student = student;
        this.episode = episode;
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    @PreUpdate
    protected void touch() {
        this.updatedAt = LocalDateTime.now();
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

    public OverrideState getOverrideState() {
        return overrideState;
    }

    public void setOverrideState(OverrideState overrideState) {
        this.overrideState = overrideState;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCooldownResetAt() {
        return cooldownResetAt;
    }

    public void setCooldownResetAt(LocalDateTime cooldownResetAt) {
        this.cooldownResetAt = cooldownResetAt;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
