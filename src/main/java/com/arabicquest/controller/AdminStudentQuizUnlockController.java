// Admin-only controller to unlock an episode's quiz for a student without faking watch seconds.
package com.arabicquest.controller;

import com.arabicquest.service.WatchProgressService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminStudentQuizUnlockController {

    private final WatchProgressService watchProgressService;

    public AdminStudentQuizUnlockController(WatchProgressService watchProgressService) {
        this.watchProgressService = watchProgressService;
    }

    public record UnlockQuizRequest(String reason) {}

    @PostMapping("/students/{studentId}/episodes/{episodeId}/unlock-quiz")
    public ResponseEntity<Map<String, Object>> unlockQuizForStudent(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long studentId,
            @PathVariable Long episodeId,
            @RequestBody(required = false) UnlockQuizRequest request) {
        String adminUser = userDetails != null ? userDetails.getUsername() : "admin";
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(watchProgressService.unlockQuizForStudent(studentId, episodeId, adminUser, reason));
    }
}
