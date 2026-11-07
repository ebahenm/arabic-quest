package com.arabicquest.repository;

import com.arabicquest.model.EpisodeRewardClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Set;

@Repository
public interface EpisodeRewardClaimRepository extends JpaRepository<EpisodeRewardClaim, Long> {
    boolean existsByStudentIdAndEpisodeId(Long studentId, Long episodeId);
    boolean existsByEpisodeId(Long episodeId);
    void deleteByStudentId(Long studentId);

    @Query("SELECT DISTINCT erc.episode.id FROM EpisodeRewardClaim erc WHERE erc.student.id = :studentId")
    Set<Long> findClaimedEpisodeIdsByStudentId(@Param("studentId") Long studentId);

    @Query("SELECT COALESCE(SUM(erc.rewardAmount), 0) FROM EpisodeRewardClaim erc WHERE erc.student.id = :studentId")
    BigDecimal calculateTotalEpisodeRewardsByStudent(@Param("studentId") Long studentId);

    @Query("SELECT COALESCE(SUM(erc.rewardAmount), 0) FROM EpisodeRewardClaim erc " +
           "WHERE erc.student.id = :studentId AND erc.episode.playlist.id = :playlistId")
    BigDecimal calculateEpisodeRewardsByStudentAndPlaylist(
            @Param("studentId") Long studentId,
            @Param("playlistId") Long playlistId);

    @Query("SELECT COUNT(DISTINCT erc.episode.id) FROM EpisodeRewardClaim erc " +
           "WHERE erc.student.id = :studentId " +
           "AND erc.episode.playlist.id = :playlistId " +
           "AND (erc.episode.archived IS NULL OR erc.episode.archived = false) " +
           "AND (erc.episode.enabled IS NULL OR erc.episode.enabled = true)")
    long countClaimedActiveEpisodesByStudentAndPlaylist(
            @Param("studentId") Long studentId,
            @Param("playlistId") Long playlistId);
}
