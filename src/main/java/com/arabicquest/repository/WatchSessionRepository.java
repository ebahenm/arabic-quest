package com.arabicquest.repository;

import com.arabicquest.model.WatchSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchSessionRepository extends JpaRepository<WatchSession, Long> {
    Optional<WatchSession> findByStudentIdAndEpisodeId(Long studentId, Long episodeId);
    List<WatchSession> findByStudentId(Long studentId);

    @Query("SELECT ws FROM WatchSession ws JOIN FETCH ws.episode WHERE ws.student.id = :studentId")
    List<WatchSession> findByStudentIdWithEpisode(@Param("studentId") Long studentId);

    void deleteByEpisodeId(Long episodeId);
    void deleteByStudentId(Long studentId);
}
