package com.arabicquest.repository;

import com.arabicquest.model.StudentQuestionExposure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentQuestionExposureRepository extends JpaRepository<StudentQuestionExposure, Long> {
    List<StudentQuestionExposure> findByStudentIdAndEpisodeId(Long studentId, Long episodeId);
    Optional<StudentQuestionExposure> findByStudentIdAndQuestionId(Long studentId, Long questionId);
    boolean existsByStudentIdAndQuestionId(Long studentId, Long questionId);
    void deleteByStudentId(Long studentId);
    void deleteByEpisodeId(Long episodeId);
}
