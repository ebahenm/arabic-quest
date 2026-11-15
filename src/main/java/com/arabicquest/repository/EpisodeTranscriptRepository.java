// Repository for accessing admin-only EpisodeTranscript records by episode ID.
package com.arabicquest.repository;

import com.arabicquest.model.EpisodeTranscript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EpisodeTranscriptRepository extends JpaRepository<EpisodeTranscript, Long> {
    Optional<EpisodeTranscript> findByEpisodeId(Long episodeId);
    void deleteByEpisodeId(Long episodeId);
}
