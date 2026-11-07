package com.arabicquest.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "student_favorite_episode",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "episode_id"})
)
public class StudentFavoriteEpisode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false)
    private Episode episode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public StudentFavoriteEpisode() {
    }

    public StudentFavoriteEpisode(User student, Episode episode) {
        this.student = student;
        this.episode = episode;
        this.createdAt = LocalDateTime.now();
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
