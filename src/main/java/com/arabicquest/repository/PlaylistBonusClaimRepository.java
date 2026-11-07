package com.arabicquest.repository;

import com.arabicquest.model.PlaylistBonusClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface PlaylistBonusClaimRepository extends JpaRepository<PlaylistBonusClaim, Long> {
    boolean existsByStudentIdAndPlaylistId(Long studentId, Long playlistId);
    void deleteByStudentId(Long studentId);

    @Query("SELECT COALESCE(SUM(pbc.bonusAmount), 0) FROM PlaylistBonusClaim pbc WHERE pbc.student.id = :studentId")
    BigDecimal calculateTotalBonusesByStudent(@Param("studentId") Long studentId);
}
