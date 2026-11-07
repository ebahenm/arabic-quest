package com.arabicquest.repository;

import com.arabicquest.model.StudentOpenQuestionAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StudentOpenQuestionAssignmentRepository extends JpaRepository<StudentOpenQuestionAssignment, Long> {

    @Query("SELECT a FROM StudentOpenQuestionAssignment a JOIN FETCH a.openQuestion WHERE a.student.id = :studentId AND a.episode.id = :episodeId")
    Optional<StudentOpenQuestionAssignment> findByStudentIdAndEpisodeIdWithOpenQuestion(
            @Param("studentId") Long studentId,
            @Param("episodeId") Long episodeId);

    boolean existsByEpisodeId(Long episodeId);
    void deleteByStudentId(Long studentId);
}
