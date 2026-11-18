// Admin-only endpoints for student password resets, episode unlock/lock-again tools, student overview, and audit log.
package com.arabicquest.controller;

import com.arabicquest.model.AdminAuditEvent;
import com.arabicquest.service.AdminStudentToolsService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminStudentToolsController {

    private final AdminStudentToolsService adminStudentToolsService;

    public AdminStudentToolsController(AdminStudentToolsService adminStudentToolsService) {
        this.adminStudentToolsService = adminStudentToolsService;
    }

    public record PasswordResetToolRequest(String customPassword, Boolean requireChangeOnNextLogin) {}
    public record BulkEpisodeToolRequest(Long courseId) {}

    @PostMapping("/students/{studentId}/password-reset")
    public ResponseEntity<Map<String, Object>> resetStudentPasswordWithTemp(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long studentId,
            @RequestBody(required = false) PasswordResetToolRequest request) {
        String adminUser = userDetails != null ? userDetails.getUsername() : "admin";
        String customPw = request != null ? request.customPassword() : null;
        Boolean requireChange = request != null ? request.requireChangeOnNextLogin() : Boolean.TRUE;
        Map<String, Object> result = adminStudentToolsService.resetStudentPassword(studentId, customPw, requireChange, adminUser);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(result);
    }

    @PostMapping("/students/{studentId}/episodes/{episodeId}/unlock-video")
    public ResponseEntity<Map<String, Object>> unlockEpisodeVideoAccess(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long studentId,
            @PathVariable Long episodeId) {
        String adminUser = userDetails != null ? userDetails.getUsername() : "admin";
        return ResponseEntity.ok(adminStudentToolsService.unlockEpisodeVideoAccess(studentId, episodeId, adminUser));
    }

    @PostMapping("/students/{studentId}/episodes/{episodeId}/lock-again")
    public ResponseEntity<Map<String, Object>> lockEpisodeAgain(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long studentId,
            @PathVariable Long episodeId) {
        String adminUser = userDetails != null ? userDetails.getUsername() : "admin";
        return ResponseEntity.ok(adminStudentToolsService.lockEpisodeAgain(studentId, episodeId, adminUser));
    }

    @PostMapping("/students/{studentId}/episodes/unlock-next")
    public ResponseEntity<Map<String, Object>> unlockNextEpisode(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long studentId,
            @RequestBody(required = false) BulkEpisodeToolRequest request) {
        String adminUser = userDetails != null ? userDetails.getUsername() : "admin";
        Long courseId = request != null ? request.courseId() : null;
        return ResponseEntity.ok(adminStudentToolsService.unlockNextEpisode(studentId, courseId, adminUser));
    }

    @PostMapping("/students/{studentId}/episodes/unlock-all")
    public ResponseEntity<Map<String, Object>> unlockAllEpisodes(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long studentId,
            @RequestBody(required = false) BulkEpisodeToolRequest request) {
        String adminUser = userDetails != null ? userDetails.getUsername() : "admin";
        Long courseId = request != null ? request.courseId() : null;
        return ResponseEntity.ok(adminStudentToolsService.unlockAllEpisodes(studentId, courseId, adminUser));
    }

    @GetMapping("/students/{studentId}/overview")
    public ResponseEntity<Map<String, Object>> getStudentOverview(@PathVariable Long studentId) {
        return ResponseEntity.ok(adminStudentToolsService.getStudentOverview(studentId));
    }

    @GetMapping("/audit-events")
    public ResponseEntity<List<AdminAuditEvent>> listAuditEvents(@RequestParam(required = false) Long studentId) {
        return ResponseEntity.ok(adminStudentToolsService.listAuditEvents(studentId));
    }
}
