package com.arabicquest.repository;

import com.arabicquest.model.OpenQuestion;
import com.arabicquest.model.QuestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OpenQuestionRepository extends JpaRepository<OpenQuestion, Long> {
    List<OpenQuestion> findByEpisodeIdOrderByIdAsc(Long episodeId);
    List<OpenQuestion> findByEpisodeIdAndStatusAndActiveTrueOrderByIdAsc(Long episodeId, QuestionStatus status);
    long countByEpisodeIdAndStatusAndActiveTrue(Long episodeId, QuestionStatus status);
    void deleteByEpisodeId(Long episodeId);

    @Query("SELECT oq.episode.id, COUNT(oq) FROM OpenQuestion oq WHERE oq.status = :status AND oq.active = true GROUP BY oq.episode.id")
    List<Object[]> countActiveByStatusGroupedByEpisode(@Param("status") QuestionStatus status);
}
