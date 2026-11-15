// Repository for querying and updating async quiz GenerationJob entities.
package com.arabicquest.repository;

import com.arabicquest.model.GenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface GenerationJobRepository extends JpaRepository<GenerationJob, Long> {

    @Query("SELECT j FROM GenerationJob j JOIN FETCH j.episode WHERE j.id = :id")
    Optional<GenerationJob> findByIdWithEpisode(@Param("id") Long id);

    boolean existsByEpisodeIdAndStatusIn(Long episodeId, Collection<String> statuses);

    List<GenerationJob> findByStatusIn(Collection<String> statuses);

    List<GenerationJob> findByEpisodeIdOrderByCreatedAtDesc(Long episodeId);

    @Query("SELECT COUNT(j) FROM GenerationJob j WHERE j.episode.id = :episodeId AND j.autoTriggered = true AND j.createdAt >= :since")
    long countAutoAttemptsSince(@Param("episodeId") Long episodeId, @Param("since") LocalDateTime since);
}
