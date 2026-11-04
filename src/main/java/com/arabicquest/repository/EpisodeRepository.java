package com.arabicquest.repository;

import com.arabicquest.model.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    List<Episode> findByPlaylistIdOrderByEpisodeNumberAsc(Long playlistId);
    Optional<Episode> findByPlaylistIdAndEpisodeNumber(Long playlistId, Integer episodeNumber);
    long countByPlaylistId(Long playlistId);
}
