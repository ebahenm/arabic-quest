package com.arabicquest.repository;

import com.arabicquest.model.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    @Query("SELECT qa FROM QuizAttempt qa JOIN FETCH qa.student JOIN FETCH qa.episode ORDER BY qa.attemptedAt DESC, qa.id DESC")
    List<QuizAttempt> findAllWithStudentAndEpisodeOrderByAttemptedAtDesc();

    @Query("SELECT qa FROM QuizAttempt qa JOIN FETCH qa.student JOIN FETCH qa.episode WHERE qa.student.id = :studentId ORDER BY qa.attemptedAt DESC")
    List<QuizAttempt> findByStudentIdOrderByAttemptedAtDesc(@Param("studentId") Long studentId);

    @Query("SELECT DISTINCT qa.episode.id FROM QuizAttempt qa WHERE qa.student.id = :studentId AND qa.passed = true")
    Set<Long> findPassedEpisodeIdsByStudentId(@Param("studentId") Long studentId);

    List<QuizAttempt> findByStudentIdAndEpisodeId(Long studentId, Long episodeId);
    Optional<QuizAttempt> findFirstByStudentIdAndEpisodeIdAndPassedTrue(Long studentId, Long episodeId);
    Optional<QuizAttempt> findTopByStudentIdAndEpisodeIdAndPassedFalseOrderByAttemptedAtDesc(Long studentId, Long episodeId);
    boolean existsByStudentIdAndEpisodeIdAndPassedTrue(Long studentId, Long episodeId);
    boolean existsByEpisodeId(Long episodeId);
    void deleteByStudentId(Long studentId);
    void deleteByStudentIdAndEpisodeIdAndPassedFalse(Long studentId, Long episodeId);

    @Query("SELECT COALESCE(SUM(qa.rewardAmount), 0) FROM QuizAttempt qa WHERE qa.student.id = :studentId AND qa.passed = true")
    BigDecimal calculateTotalEarnedByStudent(@Param("studentId") Long studentId);

    @Query("SELECT COUNT(DISTINCT qa.episode.id) FROM QuizAttempt qa WHERE qa.student.id = :studentId AND qa.episode.playlist.id = :playlistId AND (qa.episode.archived IS NULL OR qa.episode.archived = false) AND qa.passed = true")
    long countPassedEpisodesByStudentAndPlaylist(@Param("studentId") Long studentId, @Param("playlistId") Long playlistId);
}
