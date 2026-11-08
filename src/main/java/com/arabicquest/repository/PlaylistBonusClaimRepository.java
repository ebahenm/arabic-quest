package com.arabicquest.repository;

import com.arabicquest.model.PlaylistBonusClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface PlaylistBonusClaimRepository extends JpaRepository<PlaylistBonusClaim, Long> {
    boolean existsByStudentIdAndPlaylistId(Long studentId, Long playlistId);
    List<PlaylistBonusClaim> findByStudentIdOrderByClaimedAtAsc(Long studentId);
    void deleteByStudentId(Long studentId);

    @Query("SELECT COALESCE(SUM(pbc.bonusAmount), 0) FROM PlaylistBonusClaim pbc WHERE pbc.student.id = :studentId")
    BigDecimal calculateTotalBonusesByStudent(@Param("studentId") Long studentId);

    @Query("SELECT COALESCE(SUM(pbc.bonusAmount), 0) FROM PlaylistBonusClaim pbc " +
           "WHERE pbc.student.id = :studentId AND pbc.playlist.id = :playlistId")
    BigDecimal calculateBonusesByStudentAndPlaylist(
            @Param("studentId") Long studentId,
            @Param("playlistId") Long playlistId);
}
