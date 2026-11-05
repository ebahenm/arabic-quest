package com.arabicquest.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "playlist_bonus_claims", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"student_id", "playlist_id"})
})
public class PlaylistBonusClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    @Column(name = "bonus_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal bonusAmount;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private LocalDateTime claimedAt;

    public PlaylistBonusClaim() {
    }

    public PlaylistBonusClaim(User student, Playlist playlist, BigDecimal bonusAmount) {
        this.student = student;
        this.playlist = playlist;
        this.bonusAmount = bonusAmount;
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

    public Playlist getPlaylist() {
        return playlist;
    }

    public BigDecimal getBonusAmount() {
        return bonusAmount;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }
}
