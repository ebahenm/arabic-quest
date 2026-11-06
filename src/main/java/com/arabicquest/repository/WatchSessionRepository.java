package com.arabicquest.repository;

import com.arabicquest.model.WatchSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchSessionRepository extends JpaRepository<WatchSession, Long> {
    Optional<WatchSession> findByStudentIdAndEpisodeId(Long studentId, Long episodeId);
    List<WatchSession> findByStudentId(Long studentId);
    void deleteByEpisodeId(Long episodeId);
    void deleteByStudentId(Long studentId);
}
