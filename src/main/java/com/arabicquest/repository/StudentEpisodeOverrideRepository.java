package com.arabicquest.repository;

import com.arabicquest.model.StudentEpisodeOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentEpisodeOverrideRepository extends JpaRepository<StudentEpisodeOverride, Long> {
    Optional<StudentEpisodeOverride> findByStudentIdAndEpisodeId(Long studentId, Long episodeId);
    List<StudentEpisodeOverride> findByStudentId(Long studentId);
    void deleteByStudentId(Long studentId);
    void deleteByEpisodeId(Long episodeId);
}
