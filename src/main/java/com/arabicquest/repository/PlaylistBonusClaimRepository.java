package com.arabicquest.repository;

import com.arabicquest.model.PlaylistBonusClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaylistBonusClaimRepository extends JpaRepository<PlaylistBonusClaim, Long> {
    boolean existsByStudentIdAndPlaylistId(Long studentId, Long playlistId);
    void deleteByStudentId(Long studentId);
}
