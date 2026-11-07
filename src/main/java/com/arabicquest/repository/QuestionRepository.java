package com.arabicquest.repository;

import com.arabicquest.model.Question;
import com.arabicquest.model.QuestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuestionRepository extends JpaRepository<Question, Long> {

    @Query("SELECT DISTINCT q FROM Question q LEFT JOIN FETCH q.options WHERE q.quiz.episode.id = :episodeId ORDER BY q.questionOrder ASC, q.id ASC")
    List<Question> findByEpisodeIdWithOptions(@Param("episodeId") Long episodeId);

    @Modifying
    @Query("UPDATE Question q SET q.active = true WHERE q.active IS NULL")
    int backfillNullActiveToTrue();

    @Modifying
    @Query("UPDATE Question q SET q.status = :defaultStatus WHERE q.status IS NULL")
    int backfillNullStatus(@Param("defaultStatus") QuestionStatus defaultStatus);
}
