// Admin service for student password resets, episode unlock/lock-again tools, overview summaries, and audit logging.
package com.arabicquest.service;

import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.service.WatchProgressService.StudentEpisodeOverrideView;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;

@Service
public class AdminStudentToolsService {

    private static final Logger log = LoggerFactory.getLogger(AdminStudentToolsService.class);
    private static final String TEMP_PW_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%*";
    private static final int GENERATED_PW_LENGTH = 18;
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "admin123", "learn123", "password", "12345678", "123456789", "qwerty123", "student123"
    );

    private final SecureRandom secureRandom = new SecureRandom();
    private final UserRepository userRepository;
    private final EpisodeRepository episodeRepository;
    private final StudentEpisodeOverrideRepository studentEpisodeOverrideRepository;
    private final WatchSessionRepository watchSessionRepository;
    private final WatchProgressService watchProgressService;
    private final PayoutService payoutService;
    private final AdminAuditEventRepository adminAuditEventRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminStudentToolsService(
            UserRepository userRepository,
            EpisodeRepository episodeRepository,
            StudentEpisodeOverrideRepository studentEpisodeOverrideRepository,
            WatchSessionRepository watchSessionRepository,
            WatchProgressService watchProgressService,
            PayoutService payoutService,
            AdminAuditEventRepository adminAuditEventRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.episodeRepository = episodeRepository;
        this.studentEpisodeOverrideRepository = studentEpisodeOverrideRepository;
        this.watchSessionRepository = watchSessionRepository;
        this.watchProgressService = watchProgressService;
        this.payoutService = payoutService;
        this.adminAuditEventRepository = adminAuditEventRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Map<String, Object> resetStudentPassword(Long studentId, String customPassword, Boolean requireChangeOnNextLogin, String adminUsername) {
        User student = requireStudent(studentId);
        String cleanAdmin = normalizeAdmin(adminUsername);

        String temporaryPassword;
        if (customPassword != null && !customPassword.isBlank()) {
            String trimmed = customPassword.trim();
            validateCustomPassword(trimmed, student.getUsername());
            temporaryPassword = trimmed;
        } else {
            temporaryPassword = generateStrongTemporaryPassword();
        }

        boolean mustChange = requireChangeOnNextLogin == null || Boolean.TRUE.equals(requireChangeOnNextLogin);
        student.setPassword(passwordEncoder.encode(temporaryPassword));
        student.setMustChangePassword(mustChange);
        student.setCredentialsCustomized(false);
        student.incrementTokenVersion();
        userRepository.save(student);

        // Never log or store the temporary password in logs or audit rows
        log.info("AUDIT [PASSWORD_RESET] studentId={}, studentUsername={}, admin={}, mustChangePassword={}",
                student.getId(), student.getUsername(), cleanAdmin, mustChange);
        adminAuditEventRepository.save(new AdminAuditEvent(
                student.getId(), student.getUsername(), cleanAdmin, "PASSWORD_RESET",
                "Reset password for " + student.getUsername() + (mustChange ? " (must change on next login)" : "")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Password reset for " + student.getUsername() + ". All active sessions invalidated.");
        response.put("studentId", student.getId());
        response.put("username", student.getUsername());
        response.put("temporaryPassword", temporaryPassword);
        response.put("mustChangePassword", mustChange);
        return response;
    }

    @Transactional
    public Map<String, Object> unlockEpisodeVideoAccess(Long studentId, Long episodeId, String adminUsername) {
        User student = requireStudent(studentId);
        Episode ep = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        String cleanAdmin = normalizeAdmin(adminUsername);

        watchProgressService.setStudentEpisodeOverride(studentId, episodeId, StudentEpisodeOverride.OverrideState.UNLOCKED);
        adminAuditEventRepository.save(new AdminAuditEvent(
                student.getId(), student.getUsername(), cleanAdmin, "UNLOCK_VIDEO",
                "Unlocked video access for Episode #" + ep.getEpisodeNumber() + " (" + ep.getTitle() + ")"));

        return Map.of("studentId", studentId, "episodeId", episodeId,
                "overrideState", "UNLOCKED", "message", "Unlocked video access for Episode #" + ep.getEpisodeNumber());
    }

    @Transactional
    public Map<String, Object> unlockEpisodeQuiz(Long studentId, Long episodeId, String adminUsername, String reason) {
        User student = requireStudent(studentId);
        Episode ep = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        String cleanAdmin = normalizeAdmin(adminUsername);

        Map<String, Object> res = watchProgressService.unlockQuizForStudent(studentId, episodeId, cleanAdmin, reason);
        adminAuditEventRepository.save(new AdminAuditEvent(
                student.getId(), student.getUsername(), cleanAdmin, "UNLOCK_QUIZ",
                "Unlocked quiz for Episode #" + ep.getEpisodeNumber() + " (" + ep.getTitle() + ")"));
        return res;
    }

    @Transactional
    public Map<String, Object> lockEpisodeAgain(Long studentId, Long episodeId, String adminUsername) {
        User student = requireStudent(studentId);
        Episode ep = episodeRepository.findById(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("Episode not found"));
        String cleanAdmin = normalizeAdmin(adminUsername);

        // Removes ONLY overrides (StudentEpisodeOverride and WatchSession.adminUnlocked* fields).
        // Never deletes reward claims, passed episodes, or payouts.
        studentEpisodeOverrideRepository.findByStudentIdAndEpisodeId(studentId, episodeId)
                .ifPresent(studentEpisodeOverrideRepository::delete);

        watchSessionRepository.findByStudentIdAndEpisodeId(studentId, episodeId).ifPresent(ws -> {
            ws.setAdminUnlockedAt(null);
            ws.setAdminUnlockedBy(null);
            ws.setAdminUnlockReason(null);
            watchSessionRepository.save(ws);
        });

        adminAuditEventRepository.save(new AdminAuditEvent(
                student.getId(),
                student.getUsername(),
                cleanAdmin,
                "LOCK_AGAIN",
                "Removed access/quiz overrides for Episode #" + ep.getEpisodeNumber() + " (returned to sequential rules)"
        ));

        return Map.of(
                "studentId", studentId,
                "episodeId", episodeId,
                "overrideState", "AUTO",
                "message", "Overrides removed for Episode #" + ep.getEpisodeNumber() + " (returned to sequential rules)."
        );
    }

    @Transactional
    public Map<String, Object> unlockNextEpisode(Long studentId, Long courseId, String adminUsername) {
        User student = requireStudent(studentId);
        String cleanAdmin = normalizeAdmin(adminUsername);
        List<StudentEpisodeOverrideView> views = watchProgressService.listStudentEpisodeOverrides(studentId);

        StudentEpisodeOverrideView target = null;
        for (StudentEpisodeOverrideView v : views) {
            if (courseId != null && !courseId.equals(v.courseId())) {
                continue;
            }
            if (v.effectiveLocked()) {
                target = v;
                break;
            }
        }

        if (target == null) {
            return Map.of("studentId", studentId, "unlockedCount", 0,
                    "message", "All episodes are already unlocked for " + student.getUsername() + ".");
        }

        watchProgressService.setStudentEpisodeOverride(studentId, target.episodeId(), StudentEpisodeOverride.OverrideState.UNLOCKED);
        adminAuditEventRepository.save(new AdminAuditEvent(
                student.getId(),
                student.getUsername(),
                cleanAdmin,
                "UNLOCK_NEXT",
                "Unlocked next episode: Episode #" + target.episodeNumber() + " (" + target.episodeTitle() + ")"
        ));

        return Map.of(
                "studentId", studentId,
                "episodeId", target.episodeId(),
                "episodeNumber", target.episodeNumber(),
                "unlockedCount", 1,
                "message", "Unlocked Episode #" + target.episodeNumber() + " for " + student.getUsername()
        );
    }

    @Transactional
    public Map<String, Object> unlockAllEpisodes(Long studentId, Long courseId, String adminUsername) {
        User student = requireStudent(studentId);
        String cleanAdmin = normalizeAdmin(adminUsername);
        List<StudentEpisodeOverrideView> views = watchProgressService.listStudentEpisodeOverrides(studentId);

        int count = 0;
        for (StudentEpisodeOverrideView v : views) {
            if (courseId != null && !courseId.equals(v.courseId())) {
                continue;
            }
            if (v.effectiveLocked() || !"UNLOCKED".equals(v.overrideState())) {
                watchProgressService.setStudentEpisodeOverride(studentId, v.episodeId(), StudentEpisodeOverride.OverrideState.UNLOCKED);
                count++;
            }
        }

        adminAuditEventRepository.save(new AdminAuditEvent(
                student.getId(),
                student.getUsername(),
                cleanAdmin,
                "UNLOCK_ALL",
                "Unlocked all episodes (" + count + " updated) for " + student.getUsername()
        ));

        return Map.of(
                "studentId", studentId,
                "unlockedCount", count,
                "message", "Unlocked all episodes for " + student.getUsername() + " (" + count + " episode overrides applied)."
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStudentOverview(Long studentId) {
        User student = requireStudent(studentId);
        EarningsSummaryResponse earnings = payoutService.getStudentEarnings(student.getUsername());
        BigDecimal balanceOwed = earnings.getBalanceOwed();
        BigDecimal prepaidCredit = balanceOwed.compareTo(BigDecimal.ZERO) < 0 ? balanceOwed.abs() : BigDecimal.ZERO;

        List<AdminAuditEvent> recentActivity = adminAuditEventRepository
                .findTop50ByStudentIdOrderByCreatedAtDescIdDesc(studentId);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("studentId", student.getId());
        overview.put("username", student.getUsername());
        overview.put("fullName", student.getFullName());
        overview.put("enabled", student.isEnabled());
        overview.put("mustChangePassword", student.isMustChangePassword());
        overview.put("lastLoginAt", student.getLastLoginAt());
        overview.put("totalEarned", earnings.getTotalEarned());
        overview.put("totalPaid", earnings.getTotalPaid());
        overview.put("balanceOwed", balanceOwed);
        overview.put("prepaidCredit", prepaidCredit);
        overview.put("passedEpisodes", earnings.getPassedEpisodesCount());
        overview.put("totalEpisodes", earnings.getTotalEpisodesCount());
        overview.put("recentActivity", recentActivity);
        return overview;
    }

    @Transactional(readOnly = true)
    public List<AdminAuditEvent> listAuditEvents(Long studentId) {
        if (studentId != null) {
            return adminAuditEventRepository.findTop50ByStudentIdOrderByCreatedAtDescIdDesc(studentId);
        }
        return adminAuditEventRepository.findTop100ByOrderByCreatedAtDescIdDesc();
    }

    private User requireStudent(Long studentId) {
        User user = userRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found with id: " + studentId));
        if (user.getRole() != Role.ROLE_STUDENT) {
            throw new IllegalArgumentException("This operation applies to student accounts only");
        }
        return user;
    }

    private String normalizeAdmin(String adminUsername) {
        return (adminUsername != null && !adminUsername.isBlank()) ? adminUsername.trim() : "admin";
    }

    private void validateCustomPassword(String password, String username) {
        if (password.length() < 10) {
            throw new IllegalArgumentException("Password must be at least 10 characters");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (WEAK_PASSWORDS.contains(lower) || (username != null && lower.equals(username.toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("Password is too weak. Please choose a stronger password or generate one.");
        }
    }

    private String generateStrongTemporaryPassword() {
        StringBuilder sb = new StringBuilder(GENERATED_PW_LENGTH);
        for (int i = 0; i < GENERATED_PW_LENGTH; i++) {
            int idx = secureRandom.nextInt(TEMP_PW_ALPHABET.length());
            sb.append(TEMP_PW_ALPHABET.charAt(idx));
        }
        return sb.toString();
    }
}
