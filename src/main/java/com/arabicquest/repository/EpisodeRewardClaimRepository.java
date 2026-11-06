package com.arabicquest.repository;

import com.arabicquest.model.EpisodeRewardClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EpisodeRewardClaimRepository extends JpaRepository<EpisodeRewardClaim, Long> {
    boolean existsByStudentIdAndEpisodeId(Long studentId, Long episodeId);
    boolean existsByEpisodeId(Long episodeId);
    void deleteByStudentId(Long studentId);
}
