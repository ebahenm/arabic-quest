package com.arabicquest.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "episode_watch_sessions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"student_id", "episode_id"})
})
public class WatchSession {

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
    @Column(name = "locked_watch_mode", nullable = false, length = 20)
    private WatchMode lockedWatchMode = WatchMode.ARABIC_SUB;

    @Column(name = "mode_locked", nullable = false)
    private Boolean modeLocked = false;

    @Column(name = "verified_watch_seconds", nullable = false)
    private Integer verifiedWatchSeconds = 0;

    @Column(name = "external_watch_started_at")
    private LocalDateTime externalWatchStartedAt;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "video_ended", nullable = false)
    private Boolean videoEnded = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WatchSession() {
    }

    public WatchSession(User student, Episode episode, WatchMode watchMode) {
        this.student = student;
        this.episode = episode;
        this.lockedWatchMode = watchMode != null ? watchMode : WatchMode.ARABIC_SUB;
        this.modeLocked = false;
        this.verifiedWatchSeconds = 0;
        this.videoEnded = false;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
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

    public WatchMode getLockedWatchMode() {
        return lockedWatchMode;
    }

    public void setLockedWatchMode(WatchMode lockedWatchMode) {
        this.lockedWatchMode = lockedWatchMode;
    }

    public boolean isModeLocked() {
        return Boolean.TRUE.equals(modeLocked);
    }

    public void setModeLocked(boolean modeLocked) {
        this.modeLocked = modeLocked;
    }

    public int getVerifiedWatchSeconds() {
        return verifiedWatchSeconds == null ? 0 : verifiedWatchSeconds;
    }

    public void setVerifiedWatchSeconds(int verifiedWatchSeconds) {
        this.verifiedWatchSeconds = verifiedWatchSeconds;
    }

    public LocalDateTime getExternalWatchStartedAt() {
        return externalWatchStartedAt;
    }

    public void setExternalWatchStartedAt(LocalDateTime externalWatchStartedAt) {
        this.externalWatchStartedAt = externalWatchStartedAt;
    }

    public LocalDateTime getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public boolean isVideoEnded() {
        return Boolean.TRUE.equals(videoEnded);
    }

    public void setVideoEnded(boolean videoEnded) {
        this.videoEnded = videoEnded;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
