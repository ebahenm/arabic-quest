package com.arabicquest.repository;

import com.arabicquest.model.QuizAttemptSession;
import com.arabicquest.model.QuizSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizAttemptSessionRepository extends JpaRepository<QuizAttemptSession, UUID> {
    Optional<QuizAttemptSession> findTopByStudentIdAndEpisodeIdAndStatusOrderByCreatedAtDesc(
            Long studentId, Long episodeId, QuizSessionStatus status);

    List<QuizAttemptSession> findByStudentIdAndEpisodeIdOrderByCreatedAtDesc(Long studentId, Long episodeId);

    void deleteByStudentId(Long studentId);
    void deleteByEpisodeId(Long episodeId);
}
