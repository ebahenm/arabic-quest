package com.arabicquest.repository;

import com.arabicquest.model.QuestionReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuestionReportRepository extends JpaRepository<QuestionReport, Long> {

    @Query("SELECT qr FROM QuestionReport qr JOIN FETCH qr.student JOIN FETCH qr.episode WHERE qr.question.id = :questionId AND qr.resolved = false")
    List<QuestionReport> findUnresolvedByQuestionId(@Param("questionId") Long questionId);

    boolean existsByStudentIdAndQuestionIdAndResolvedFalse(Long studentId, Long questionId);

    void deleteByStudentId(Long studentId);
    void deleteByEpisodeId(Long episodeId);
}
