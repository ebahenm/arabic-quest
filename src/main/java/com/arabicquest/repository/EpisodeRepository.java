package com.arabicquest.repository;

import com.arabicquest.model.Episode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    List<Episode> findByPlaylistIdOrderByEpisodeNumberAsc(Long playlistId);
    Optional<Episode> findByPlaylistIdAndEpisodeNumber(Long playlistId, Integer episodeNumber);
    Optional<Episode> findByPlaylistIdAndYoutubeVideoId(Long playlistId, String youtubeVideoId);
    long countByPlaylistId(Long playlistId);

    @Query("SELECT e FROM Episode e JOIN FETCH e.playlist " +
           "WHERE (e.archived IS NULL OR e.archived = false) " +
           "AND (e.enabled IS NULL OR e.enabled = true) " +
           "ORDER BY e.playlist.id ASC, COALESCE(e.sortOrder, e.episodeNumber) ASC, e.episodeNumber ASC, e.id ASC")
    List<Episode> findAllActiveOrderByEpisodeNumberAsc();

    @Query("SELECT e FROM Episode e JOIN FETCH e.playlist " +
           "WHERE e.playlist.id = :playlistId " +
           "AND (e.archived IS NULL OR e.archived = false) " +
           "AND (e.enabled IS NULL OR e.enabled = true) " +
           "ORDER BY COALESCE(e.sortOrder, e.episodeNumber) ASC, e.episodeNumber ASC, e.id ASC")
    List<Episode> findActiveByPlaylistIdOrderByEpisodeNumberAsc(@Param("playlistId") Long playlistId);

    @Query("SELECT e FROM Episode e JOIN FETCH e.playlist " +
           "WHERE e.playlist.id = :playlistId " +
           "AND (e.archived IS NULL OR e.archived = false) " +
           "ORDER BY COALESCE(e.sortOrder, e.episodeNumber) ASC, e.episodeNumber ASC, e.id ASC")
    List<Episode> findNonArchivedByPlaylistIdOrderBySortOrderAsc(@Param("playlistId") Long playlistId);

    @Query("SELECT e FROM Episode e JOIN FETCH e.playlist " +
           "WHERE e.playlist.id = :playlistId " +
           "ORDER BY COALESCE(e.sortOrder, e.episodeNumber) ASC, e.episodeNumber ASC, e.id ASC")
    List<Episode> findAllByPlaylistIdOrderBySortOrderAsc(@Param("playlistId") Long playlistId);

    @Query("SELECT e FROM Episode e JOIN FETCH e.playlist " +
           "WHERE (e.archived IS NULL OR e.archived = false) " +
           "ORDER BY COALESCE(e.playlist.sortOrder, 0) ASC, e.playlist.id ASC, COALESCE(e.sortOrder, e.episodeNumber) ASC, e.episodeNumber ASC, e.id ASC")
    List<Episode> findAllNonArchivedOrderByCourseAndSortOrderAsc();

    @Query("SELECT e FROM Episode e JOIN FETCH e.playlist WHERE e.id = :id")
    Optional<Episode> findByIdWithPlaylist(@Param("id") Long id);

    @Query("SELECT COUNT(e) FROM Episode e " +
           "WHERE e.playlist.id = :playlistId " +
           "AND (e.archived IS NULL OR e.archived = false) " +
           "AND (e.enabled IS NULL OR e.enabled = true)")
    long countActiveByPlaylistId(@Param("playlistId") Long playlistId);

    @Modifying
    @Query("UPDATE Episode e SET e.enabled = true WHERE e.enabled IS NULL")
    int backfillNullEnabledToTrue();

    @Modifying
    @Query("UPDATE Episode e SET e.sortOrder = e.episodeNumber WHERE e.sortOrder IS NULL")
    int backfillNullSortOrder();
}
