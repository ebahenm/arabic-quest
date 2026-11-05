package com.arabicquest.repository;

import com.arabicquest.model.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    List<Episode> findByPlaylistIdOrderByEpisodeNumberAsc(Long playlistId);
    Optional<Episode> findByPlaylistIdAndEpisodeNumber(Long playlistId, Integer episodeNumber);
    long countByPlaylistId(Long playlistId);

    @Query("SELECT e FROM Episode e JOIN FETCH e.playlist WHERE (e.archived IS NULL OR e.archived = false) ORDER BY e.episodeNumber ASC, e.id ASC")
    List<Episode> findAllActiveOrderByEpisodeNumberAsc();

    @Query("SELECT e FROM Episode e JOIN FETCH e.playlist WHERE e.id = :id")
    Optional<Episode> findByIdWithPlaylist(@Param("id") Long id);

    @Query("SELECT COUNT(e) FROM Episode e WHERE e.playlist.id = :playlistId AND (e.archived IS NULL OR e.archived = false)")
    long countActiveByPlaylistId(@Param("playlistId") Long playlistId);
}
