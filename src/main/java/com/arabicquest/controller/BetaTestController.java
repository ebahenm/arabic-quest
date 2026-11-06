package com.arabicquest.controller;

import com.arabicquest.dto.WatchStatusResponse;
import com.arabicquest.model.User;
import com.arabicquest.model.WatchMode;
import com.arabicquest.repository.*;
import com.arabicquest.service.WatchProgressService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/beta")
public class BetaTestController {

    private final UserRepository userRepository;
    private final WatchProgressService watchProgressService;
    private final WatchSessionRepository watchSessionRepository;
    private final EpisodeRewardClaimRepository episodeRewardClaimRepository;
    private final PlaylistBonusClaimRepository playlistBonusClaimRepository;
    private final PayoutRepository payoutRepository;
    private final QuizAttemptRepository quizAttemptRepository;

    public BetaTestController(
            UserRepository userRepository,
            WatchProgressService watchProgressService,
            WatchSessionRepository watchSessionRepository,
            EpisodeRewardClaimRepository episodeRewardClaimRepository,
            PlaylistBonusClaimRepository playlistBonusClaimRepository,
            PayoutRepository payoutRepository,
            QuizAttemptRepository quizAttemptRepository) {
        this.userRepository = userRepository;
        this.watchProgressService = watchProgressService;
        this.watchSessionRepository = watchSessionRepository;
        this.episodeRewardClaimRepository = episodeRewardClaimRepository;
        this.playlistBonusClaimRepository = playlistBonusClaimRepository;
        this.payoutRepository = payoutRepository;
        this.quizAttemptRepository = quizAttemptRepository;
    }

    @Transactional
    @PostMapping("/episodes/{episodeId}/unlock-watch")
    public ResponseEntity<WatchStatusResponse> instantUnlockWatch(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId,
            @RequestParam(defaultValue = "ARABIC_SUB") WatchMode watchMode) {
        User user = requireBetaAccess(userDetails);
        WatchStatusResponse status = watchProgressService.betaInstantUnlockWatch(user, episodeId, watchMode);
        return ResponseEntity.ok(status);
    }

    @Transactional
    @PostMapping("/episodes/{episodeId}/clear-cooldown")
    public ResponseEntity<Map<String, String>> clearRetryCooldown(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long episodeId) {
        User user = requireBetaAccess(userDetails);
        quizAttemptRepository.deleteByStudentIdAndEpisodeIdAndPassedFalse(user.getId(), episodeId);
        return ResponseEntity.ok(Map.of("message", "Retry cooldown cleared for episode."));
    }

    @Transactional
    @PostMapping("/reset-progress")
    public ResponseEntity<Map<String, String>> resetMyBetaProgress(
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = requireBetaAccess(userDetails);
        Long uid = user.getId();
        watchSessionRepository.deleteByStudentId(uid);
        episodeRewardClaimRepository.deleteByStudentId(uid);
        playlistBonusClaimRepository.deleteByStudentId(uid);
        payoutRepository.deleteByStudentId(uid);
        quizAttemptRepository.deleteByStudentId(uid);
        return ResponseEntity.ok(Map.of(
                "message", "Your beta test progress, watch timers, and $0.00 test balance have been reset."
        ));
    }

    private User requireBetaAccess(UserDetails userDetails) {
        if (userDetails == null) {
            throw new SecurityException("Authentication required");
        }
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!user.hasBetaAccess()) {
            throw new SecurityException("Beta test portal is restricted to administrators and authorized beta testers.");
        }
        return user;
    }
}
