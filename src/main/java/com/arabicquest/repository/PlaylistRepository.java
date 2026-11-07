package com.arabicquest.repository;

import com.arabicquest.model.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaylistRepository extends JpaRepository<Playlist, Long> {
    Optional<Playlist> findByYoutubePlaylistId(String youtubePlaylistId);
    Optional<Playlist> findFirstByOrderByIdAsc();

    @Query("SELECT p FROM Playlist p " +
           "WHERE p.published = true AND (p.archived IS NULL OR p.archived = false) " +
           "ORDER BY COALESCE(p.sortOrder, 0) ASC, p.id ASC")
    List<Playlist> findPublishedActiveOrderBySortOrderAsc();

    @Query("SELECT p FROM Playlist p " +
           "ORDER BY COALESCE(p.archived, false) ASC, COALESCE(p.sortOrder, 0) ASC, p.id ASC")
    List<Playlist> findAllOrderBySortOrderAsc();

    @Modifying
    @Query("UPDATE Playlist p SET p.published = true WHERE p.published IS NULL")
    int backfillNullPublishedToTrue();

    @Modifying
    @Query("UPDATE Playlist p SET p.archived = false WHERE p.archived IS NULL")
    int backfillNullArchivedToFalse();

    @Modifying
    @Query("UPDATE Playlist p SET p.sortOrder = CAST(p.id AS integer) WHERE p.sortOrder IS NULL")
    int backfillNullSortOrder();

    @Modifying
    @Query("UPDATE Playlist p SET " +
           "p.arabicRewardRate = COALESCE(p.arabicRewardRate, 15.00), " +
           "p.englishRewardRate = COALESCE(p.englishRewardRate, 10.00), " +
           "p.completionBonus = COALESCE(p.completionBonus, 100.00) " +
           "WHERE p.arabicRewardRate IS NULL OR p.englishRewardRate IS NULL OR p.completionBonus IS NULL")
    int backfillNullRatesAndBonus();
}
