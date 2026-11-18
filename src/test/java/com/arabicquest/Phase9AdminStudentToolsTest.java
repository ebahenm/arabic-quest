// Integration tests for Phase 9 Admin Student Tools: unlock isolation, lock-again safety, advance payouts, password reset & mustChangePassword 403 enforcement.
package com.arabicquest;

import com.arabicquest.dto.ChangePasswordRequest;
import com.arabicquest.dto.CreatePayoutRequest;
import com.arabicquest.dto.EarningsSummaryResponse;
import com.arabicquest.dto.PayoutResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.JwtService;
import com.arabicquest.service.PayoutService;
import com.arabicquest.service.WatchProgressService;
import com.arabicquest.service.WatchProgressService.StudentEpisodeOverrideView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase9_tools_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase9admin",
        "ADMIN_PASSWORD=Phase9StrongPassword!99",
        "ADMIN_SECRET_PATH=phase9-secret-vault",
        "JWT_SECRET=phase9-test-jwt-secret-key-with-well-over-32-chars-entropy"
})
class Phase9AdminStudentToolsTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;

    private String url(String path) { return "http://localhost:" + port + path; }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Requested-With", "XMLHttpRequest");
        return h;
    }
    @Autowired private UserRepository userRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private StudentEpisodeOverrideRepository overrideRepository;
    @Autowired private WatchSessionRepository watchSessionRepository;
    @Autowired private EpisodeRewardClaimRepository rewardClaimRepository;
    @Autowired private PayoutRepository payoutRepository;
    @Autowired private AdminAuditEventRepository auditEventRepository;
    @Autowired private WatchProgressService watchProgressService;
    @Autowired private PayoutService payoutService;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User admin;
    private User studentA;
    private User studentB;
    private String adminToken;
    private String studentAToken;
    private String studentBToken;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("phase9admin").orElseGet(() ->
                userRepository.save(new User("phase9admin", passwordEncoder.encode("Phase9StrongPassword!99"), "Admin", Role.ROLE_ADMIN)));

        studentA = userRepository.findByUsername("phase9_student_a").orElseGet(() ->
                userRepository.save(new User("phase9_student_a", passwordEncoder.encode("InitialPassA!123"), "Student A", Role.ROLE_STUDENT)));
        studentA.setMustChangePassword(false);
        studentA = userRepository.save(studentA);

        studentB = userRepository.findByUsername("phase9_student_b").orElseGet(() ->
                userRepository.save(new User("phase9_student_b", passwordEncoder.encode("InitialPassB!123"), "Student B", Role.ROLE_STUDENT)));
        studentB.setMustChangePassword(false);
        studentB = userRepository.save(studentB);

        if (episodeRepository.findAllActiveOrderByEpisodeNumberAsc().size() < 3) {
            Playlist course = playlistRepository.save(new Playlist("PL_PHASE9_COURSE", "Phase 9 Test Course", "Test course"));
            course.setPublished(true);
            course = playlistRepository.save(course);
            for (int i = 1; i <= 3; i++) {
                Episode ep = new Episode(course, i, "Episode " + i, "vid_p9_" + i, 1200);
                ep.setEnabled(true);
                ep.setArchived(false);
                episodeRepository.save(ep);
            }
        }

        adminToken = jwtService.generateToken(admin);
        studentAToken = jwtService.generateToken(studentA);
        studentBToken = jwtService.generateToken(studentB);
    }

    @Test
    void test1_twoStudentUnlockIsolation_andLockAgainNeverDeletesRewardsOrPayouts() {
        overrideRepository.deleteByStudentId(studentA.getId());
        overrideRepository.deleteByStudentId(studentB.getId());
        watchSessionRepository.deleteByStudentId(studentA.getId());
        watchSessionRepository.deleteByStudentId(studentB.getId());

        List<Episode> activeEps = episodeRepository.findAllActiveOrderByEpisodeNumberAsc();
        assertThat(activeEps.size()).isGreaterThanOrEqualTo(3);
        Episode ep1 = activeEps.get(0);
        Episode ep2 = activeEps.get(1);
        Episode ep3 = activeEps.get(2);

        // Unlock Ep 2 video and quiz for Student A only
        ResponseEntity<Map> unlockVideoRes = rest.exchange(
                url("/api/admin/students/" + studentA.getId() + "/episodes/" + ep2.getId() + "/unlock-video"),
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(adminToken)), Map.class);
        assertThat(unlockVideoRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> unlockQuizRes = rest.exchange(
                url("/api/admin/students/" + studentA.getId() + "/episodes/" + ep2.getId() + "/unlock-quiz"),
                HttpMethod.POST, new HttpEntity<>(Map.of("reason", "Isolation test"), authHeaders(adminToken)), Map.class);
        assertThat(unlockQuizRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Unlock Next Episode for Student A (should unlock Ep 3)
        ResponseEntity<Map> unlockNextRes = rest.exchange(
                url("/api/admin/students/" + studentA.getId() + "/episodes/unlock-next"),
                HttpMethod.POST, new HttpEntity<>(Map.of(), authHeaders(adminToken)), Map.class);
        assertThat(unlockNextRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify Student A has Ep 2 & Ep 3 unlocked and NO rewards were created by unlocking
        List<StudentEpisodeOverrideView> viewsA = watchProgressService.listStudentEpisodeOverrides(studentA.getId());
        StudentEpisodeOverrideView ep2A = viewsA.stream().filter(v -> v.episodeId().equals(ep2.getId())).findFirst().orElseThrow();
        StudentEpisodeOverrideView ep3A = viewsA.stream().filter(v -> v.episodeId().equals(ep3.getId())).findFirst().orElseThrow();
        assertThat(ep2A.effectiveLocked()).isFalse();
        assertThat(ep2A.quizUnlocked()).isTrue();
        assertThat(ep3A.effectiveLocked()).isFalse();
        assertThat(rewardClaimRepository.findClaimedEpisodeIdsByStudentId(studentA.getId())).isEmpty();

        // Verify Student B is completely untouched (Ep 2 & Ep 3 remain locked, quiz locked)
        List<StudentEpisodeOverrideView> viewsB = watchProgressService.listStudentEpisodeOverrides(studentB.getId());
        StudentEpisodeOverrideView ep2B = viewsB.stream().filter(v -> v.episodeId().equals(ep2.getId())).findFirst().orElseThrow();
        StudentEpisodeOverrideView ep3B = viewsB.stream().filter(v -> v.episodeId().equals(ep3.getId())).findFirst().orElseThrow();
        assertThat(ep2B.effectiveLocked()).isTrue();
        assertThat(ep2B.quizUnlocked()).isFalse();
        assertThat(ep3B.effectiveLocked()).isTrue();

        // Record a real reward claim on Ep 1 and a payout for Student A, then call "Lock again" on Ep 1 & Ep 2
        if (!rewardClaimRepository.existsByStudentIdAndEpisodeId(studentA.getId(), ep1.getId())) {
            rewardClaimRepository.save(new EpisodeRewardClaim(studentA, ep1, WatchMode.ARABIC_SUB, new BigDecimal("15.00")));
        }
        Payout savedPayout = payoutService.markAsPaid(studentA.getId(), new BigDecimal("15.00"), "Ep 1 reward payout");

        ResponseEntity<Map> lockAgainEp2 = rest.exchange(
                url("/api/admin/students/" + studentA.getId() + "/episodes/" + ep2.getId() + "/lock-again"),
                HttpMethod.POST, new HttpEntity<>(null, authHeaders(adminToken)), Map.class);
        assertThat(lockAgainEp2.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Lock again on Ep 2 must remove only overrides (overrideState returns to AUTO, quizUnlocked false)
        // while preserving Student A's reward claim and payout
        List<StudentEpisodeOverrideView> afterLockA = watchProgressService.listStudentEpisodeOverrides(studentA.getId());
        StudentEpisodeOverrideView ep2After = afterLockA.stream().filter(v -> v.episodeId().equals(ep2.getId())).findFirst().orElseThrow();
        assertThat(ep2After.overrideState()).isEqualTo("AUTO");
        assertThat(ep2After.quizUnlocked()).isFalse();
        assertThat(rewardClaimRepository.existsByStudentIdAndEpisodeId(studentA.getId(), ep1.getId())).isTrue();
        assertThat(payoutRepository.findById(savedPayout.getId())).isPresent();
    }

    @Test
    void test2_advancePayoutFlow_existingNullPayoutTypeReadsAsRegular_andNoAutoAddedEarnings() {
        User advStudent = userRepository.findByUsername("phase9_adv_student").orElseGet(() ->
                userRepository.save(new User("phase9_adv_student", passwordEncoder.encode("AdvPass!123"), "Advance Student", Role.ROLE_STUDENT)));

        // Rejects advance payout when advanceConfirmed is explicitly false
        CreatePayoutRequest unconfirmed = new CreatePayoutRequest(advStudent.getId(), new BigDecimal("30.00"), "Prepaid", "REGULAR", false);
        ResponseEntity<Map> rejectedRes = rest.exchange(
                url("/api/admin/payouts"), HttpMethod.POST, new HttpEntity<>(unconfirmed, authHeaders(adminToken)), Map.class);
        assertThat(rejectedRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Succeeds when advanceConfirmed is true and stores ADVANCE payoutType
        CreatePayoutRequest confirmed = new CreatePayoutRequest(advStudent.getId(), new BigDecimal("30.00"), "Prepaid credit for 2 episodes", "ADVANCE", true);
        ResponseEntity<PayoutResponse> okRes = rest.exchange(
                url("/api/admin/payouts"), HttpMethod.POST, new HttpEntity<>(confirmed, authHeaders(adminToken)), PayoutResponse.class);
        assertThat(okRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(okRes.getBody()).isNotNull();
        assertThat(okRes.getBody().payoutType()).isEqualTo("ADVANCE");

        // Verify legacy reconciliation never auto-adds earnings for an advance payout
        payoutService.reconcileLegacyPaidThirtyNegativeBalances("phase9admin");
        EarningsSummaryResponse summary = payoutService.getStudentEarnings(advStudent.getUsername());
        assertThat(summary.getTotalEarned()).isEqualByComparingTo("0.00");
        assertThat(summary.getTotalPaid()).isEqualByComparingTo("30.00");
        assertThat(summary.getBalanceOwed()).isEqualByComparingTo("-30.00");

        // Verify existing rows with NULL payout_type read as REGULAR
        Long payoutId = okRes.getBody().id();
        jdbcTemplate.update("UPDATE payouts SET payout_type = NULL WHERE id = ?", payoutId);
        Payout reloaded = payoutRepository.findByIdWithStudent(payoutId).orElseThrow();
        assertThat(reloaded.getPayoutType()).isEqualTo("REGULAR");
        assertThat(PayoutResponse.from(reloaded).payoutType()).isEqualTo("REGULAR");
    }

    @Test
    void test3_passwordReset_oneTimeCacheNoStore_andMustChangePasswordEnforcedServerSide() {
        // Weak custom password rejected
        ResponseEntity<Map> weakRes = rest.exchange(url("/api/admin/students/" + studentA.getId() + "/password-reset"),
                HttpMethod.POST, new HttpEntity<>(Map.of("customPassword", "password", "requireChangeOnNextLogin", true), authHeaders(adminToken)), Map.class);
        assertThat(weakRes.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Auto-generate 16+ char SecureRandom temporary password with mustChangePassword=true
        String oldToken = jwtService.generateToken(studentA);
        ResponseEntity<Map> resetRes = rest.exchange(url("/api/admin/students/" + studentA.getId() + "/password-reset"),
                HttpMethod.POST, new HttpEntity<>(Map.of("requireChangeOnNextLogin", true), authHeaders(adminToken)), Map.class);
        assertThat(resetRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resetRes.getHeaders().getCacheControl()).contains("no-store");

        String tempPw = String.valueOf(resetRes.getBody().get("temporaryPassword"));
        assertThat(tempPw.length()).isGreaterThanOrEqualTo(16);

        // Never store temporary password in audit events or plaintext in User entity
        User refreshed = userRepository.findById(studentA.getId()).orElseThrow();
        assertThat(refreshed.getPassword()).doesNotContain(tempPw);
        assertThat(passwordEncoder.matches(tempPw, refreshed.getPassword())).isTrue();
        assertThat(refreshed.isMustChangePassword()).isTrue();
        List<AdminAuditEvent> audits = auditEventRepository.findTop50ByStudentIdOrderByCreatedAtDescIdDesc(studentA.getId());
        assertThat(audits).isNotEmpty();
        assertThat(audits.get(0).getSummary()).doesNotContain(tempPw);

        // Old JWT token immediately stops working (tokenVersion bumped)
        ResponseEntity<Map> oldTokenRes = rest.exchange(
                url("/api/student/earnings"), HttpMethod.GET, new HttpEntity<>(null, authHeaders(oldToken)), Map.class);
        assertThat(oldTokenRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // New token with mustChangePassword=true gets 403 on student APIs (/api/student/courses, /api/student/earnings)
        String tempSessionToken = jwtService.generateToken(refreshed);
        ResponseEntity<Map> blockedCourses = rest.exchange(
                url("/api/student/courses"), HttpMethod.GET, new HttpEntity<>(null, authHeaders(tempSessionToken)), Map.class);
        assertThat(blockedCourses.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blockedCourses.getBody().get("mustChangePassword")).isEqualTo(true);

        ResponseEntity<Map> blockedEarnings = rest.exchange(
                url("/api/student/earnings"), HttpMethod.GET, new HttpEntity<>(null, authHeaders(tempSessionToken)), Map.class);
        assertThat(blockedEarnings.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // /api/auth/me and /api/auth/change-password remain accessible
        ResponseEntity<Map> meRes = rest.exchange(
                url("/api/auth/me"), HttpMethod.GET, new HttpEntity<>(null, authHeaders(tempSessionToken)), Map.class);
        assertThat(meRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meRes.getBody().get("mustChangePassword")).isEqualTo(true);

        // Student changes their own password via /api/auth/change-password
        ChangePasswordRequest cpReq = new ChangePasswordRequest(tempPw, "NewPersonalStrong!2026");
        ResponseEntity<Map> cpRes = rest.exchange(
                url("/api/auth/change-password"), HttpMethod.POST, new HttpEntity<>(cpReq, authHeaders(tempSessionToken)), Map.class);
        assertThat(cpRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        User afterChange = userRepository.findById(studentA.getId()).orElseThrow();
        assertThat(afterChange.isMustChangePassword()).isFalse();
        String freshStudentToken = jwtService.generateToken(afterChange);

        // Now /api/student/earnings succeeds with 200 OK
        ResponseEntity<Map> unblockedEarnings = rest.exchange(
                url("/api/student/earnings"), HttpMethod.GET, new HttpEntity<>(null, authHeaders(freshStudentToken)), Map.class);
        assertThat(unblockedEarnings.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test4_studentsReceive403OnEveryNewAdminEndpoint() {
        List<String> postPaths = List.of(
                "/api/admin/students/" + studentA.getId() + "/password-reset",
                "/api/admin/students/" + studentA.getId() + "/episodes/1/unlock-video",
                "/api/admin/students/" + studentA.getId() + "/episodes/1/lock-again",
                "/api/admin/students/" + studentA.getId() + "/episodes/unlock-next",
                "/api/admin/students/" + studentA.getId() + "/episodes/unlock-all"
        );
        for (String path : postPaths) {
            ResponseEntity<String> res = rest.exchange(
                    url(path), HttpMethod.POST, new HttpEntity<>(Map.of(), authHeaders(studentBToken)), String.class);
            assertThat(res.getStatusCode()).as("POST %s should return 403 for student", path).isEqualTo(HttpStatus.FORBIDDEN);
        }

        List<String> getPaths = List.of(
                "/api/admin/students/" + studentA.getId() + "/overview",
                "/api/admin/audit-events"
        );
        for (String path : getPaths) {
            ResponseEntity<String> res = rest.exchange(
                    url(path), HttpMethod.GET, new HttpEntity<>(null, authHeaders(studentBToken)), String.class);
            assertThat(res.getStatusCode()).as("GET %s should return 403 for student", path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
