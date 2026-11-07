package com.arabicquest.repository;

import com.arabicquest.model.Question;
import com.arabicquest.model.QuestionStatus;
import com.arabicquest.model.QuestionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuestionRepository extends JpaRepository<Question, Long> {

    @Query("SELECT DISTINCT q FROM Question q LEFT JOIN FETCH q.options WHERE q.quiz.episode.id = :episodeId ORDER BY q.questionOrder ASC, q.id ASC")
    List<Question> findByEpisodeIdWithOptions(@Param("episodeId") Long episodeId);

    @Query("SELECT q FROM Question q LEFT JOIN FETCH q.options JOIN FETCH q.quiz qz JOIN FETCH qz.episode WHERE q.id = :questionId")
    Optional<Question> findByIdWithQuizAndEpisode(@Param("questionId") Long questionId);

    long countByQuizEpisodeIdAndStatusAndActiveTrue(Long episodeId, QuestionStatus status);

    long countByQuizEpisodeIdAndActiveTrue(Long episodeId);

    @Modifying
    @Query("UPDATE Question q SET q.active = true WHERE q.active IS NULL")
    int backfillNullActiveToTrue();

    @Modifying
    @Query("UPDATE Question q SET q.status = :defaultStatus WHERE q.status IS NULL")
    int backfillNullStatus(@Param("defaultStatus") QuestionStatus defaultStatus);

    @Modifying
    @Query("UPDATE Question q SET q.type = :defaultType WHERE q.type IS NULL")
    int backfillNullType(@Param("defaultType") QuestionType defaultType);

    @Modifying
    @Query("UPDATE Question q SET q.reportCount = 0 WHERE q.reportCount IS NULL")
    int backfillNullReportCount();
}
