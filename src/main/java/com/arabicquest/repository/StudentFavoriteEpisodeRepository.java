package com.arabicquest.repository;

import com.arabicquest.model.StudentFavoriteEpisode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;

public interface StudentFavoriteEpisodeRepository extends JpaRepository<StudentFavoriteEpisode, Long> {

    boolean existsByStudentIdAndEpisodeId(Long studentId, Long episodeId);

    Optional<StudentFavoriteEpisode> findByStudentIdAndEpisodeId(Long studentId, Long episodeId);

    @Query("SELECT sfe.episode.id FROM StudentFavoriteEpisode sfe WHERE sfe.student.id = :studentId")
    Set<Long> findFavoriteEpisodeIdsByStudentId(@Param("studentId") Long studentId);

    void deleteByStudentIdAndEpisodeId(Long studentId, Long episodeId);

    void deleteByStudentId(Long studentId);

    void deleteByEpisodeId(Long episodeId);
}
