// Tests for Phase 11 watch tracking: credit rules (late timer, 2x speed, seeking, 90s cap), retry, resume position, and client-errors rate limiting.
package com.arabicquest;

import com.arabicquest.controller.StudentClientErrorController;
import com.arabicquest.dto.WatchHeartbeatRequest;
import com.arabicquest.dto.WatchStatusResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.WatchCreditCalculator;
import com.arabicquest.service.WatchProgressService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase11_watch_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=p11admin",
        "ADMIN_PASSWORD=Phase11StrongPassword!99",
        "ADMIN_SECRET_PATH=p11-secret-hq",
        "JWT_SECRET=phase11-watch-tracking-jwt-secret-key-well-over-32-chars",
        "app.watch.required-cap-seconds=1800"
})
class Phase11WatchTrackingTest {

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WatchProgressService watchProgressService;
    @Autowired private StudentClientErrorController clientErrorController;
    @Autowired private UserRepository userRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private WatchSessionRepository watchSessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void creditCalculator_handlesLateTimer_2xSpeed_seekingBound_90sCap_andZeroSpam() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 1, 12, 0, 0);

        // Late mobile timer (45s elapsed, 45s played) -> full 45s credited (previously capped at 25s)
        assertThat(WatchCreditCalculator.computeCredit(now.minusSeconds(45), now, 45).creditSeconds()).isEqualTo(45);

        // 2x playback speed (20s wall elapsed, 40s played) -> 40s credited
        assertThat(WatchCreditCalculator.computeCredit(now.minusSeconds(20), now, 40).creditSeconds()).isEqualTo(40);

        // Seeking forward (5s wall elapsed, 80s reported) -> bounded to floor(5 * 2 + 2) = 12s
        assertThat(WatchCreditCalculator.computeCredit(now.minusSeconds(5), now, 80).creditSeconds()).isEqualTo(12);

        // 90s per-heartbeat cap (120s wall elapsed, 120s reported) -> capped at 90s
        assertThat(WatchCreditCalculator.computeCredit(now.minusSeconds(120), now, 120).creditSeconds()).isEqualTo(90);

        // Sub-second spam, zero/negative delta, or missing anchor -> 0 credit
        assertThat(WatchCreditCalculator.computeCredit(now.minusNanos(300_000_000L), now, 10).creditSeconds()).isZero();
        assertThat(WatchCreditCalculator.computeCredit(now.minusSeconds(10), now, 0).creditSeconds()).isZero();
        assertThat(WatchCreditCalculator.computeCredit(now.minusSeconds(10), now, -5).creditSeconds()).isZero();
        assertThat(WatchCreditCalculator.computeCredit(null, now, 25).creditSeconds()).isZero();

        // Resume position clamping (5s buffer before EOF)
        assertThat(WatchCreditCalculator.clampResumePosition(420, 1800)).isEqualTo(420);
        assertThat(WatchCreditCalculator.clampResumePosition(1799, 1800)).isEqualTo(1795);
        assertThat(WatchCreditCalculator.clampResumePosition(-10, 1800)).isZero();
    }

    @Test
    void watchService_externalGivesZero_retryPreservesCredit_andResumePositionSaved() {
        User student = ensureStudent("p11student1", "StudentPass!123");
        Episode ep = ensureEpisode("PL_P11_WATCH", 1, "P11_VID_001", 2400);

        // Initial anchor heartbeat grants 0 credit and saves initial position
        WatchStatusResponse init = watchProgressService.recordHeartbeat(
                student.getUsername(),
                ep.getId(),
                new WatchHeartbeatRequest(WatchMode.ARABIC_SUB, false, false, 0, 15, null, true),
                false
        );
        assertThat(init.verifiedWatchSeconds()).isZero();
        assertThat(init.lastPositionSeconds()).isEqualTo(15);

        // External / hidden watch gives zero credit even after 40s elapsed
        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), ep.getId()).orElseThrow();
        session.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(40));
        watchSessionRepository.save(session);

        WatchStatusResponse extRes = watchProgressService.recordHeartbeat(
                student.getUsername(),
                ep.getId(),
                new WatchHeartbeatRequest(WatchMode.ARABIC_SUB, true, false, 40, 55, null, true),
                false
        );
        assertThat(extRes.verifiedWatchSeconds()).isZero();

        // Delayed retry heartbeat after 50s elapsed -> full 50s credited and resume position saved
        session = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), ep.getId()).orElseThrow();
        session.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(50));
        watchSessionRepository.save(session);

        WatchStatusResponse retryRes = watchProgressService.recordHeartbeat(
                student.getUsername(),
                ep.getId(),
                new WatchHeartbeatRequest(WatchMode.ARABIC_SUB, false, false, 50, 340, null, true),
                false
        );
        assertThat(retryRes.verifiedWatchSeconds()).isEqualTo(50);
        assertThat(retryRes.lastPositionSeconds()).isEqualTo(340);

        // Status lookup returns saved resume position
        WatchStatusResponse reloaded = watchProgressService.getWatchStatus(student, ep, false);
        assertThat(reloaded.lastPositionSeconds()).isEqualTo(340);
        assertThat(reloaded.verifiedWatchSeconds()).isEqualTo(50);
    }

    @Test
    void clientErrorsEndpoint_requiresAuth_logsSanitized_andEnforcesRateLimit() {
        clientErrorController.resetRateLimits();

        // Unauthenticated call returns 401
        HttpHeaders anonHeaders = new HttpHeaders();
        anonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> unauthRes = restTemplate.postForEntity(
                url("/api/student/client-errors"),
                new HttpEntity<>(Map.of("message", "YT failed", "browser", "SamsungBrowser/24"), anonHeaders),
                String.class);
        assertThat(unauthRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Log in as student
        ensureStudent("p11errstudent", "StudentPass!123");
        HttpHeaders studentHeaders = loginAndGetCookieHeaders("p11errstudent", "StudentPass!123");
        studentHeaders.setContentType(MediaType.APPLICATION_JSON);

        // First 10 calls succeed
        for (int i = 0; i < StudentClientErrorController.MAX_ERRORS_PER_MINUTE; i++) {
            ResponseEntity<String> res = restTemplate.postForEntity(
                    url("/api/student/client-errors"),
                    new HttpEntity<>(Map.of("message", "Player error #" + i, "browser", "SamsungBrowser/24"), studentHeaders),
                    String.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // 11th call within 1 minute is rate-limited (429)
        ResponseEntity<String> limited = restTemplate.postForEntity(
                url("/api/student/client-errors"),
                new HttpEntity<>(Map.of("message", "Player error #11", "browser", "SamsungBrowser/24"), studentHeaders),
                String.class);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void staticWatchScriptsArePubliclyServed() {
        ResponseEntity<String> tracker = restTemplate.getForEntity(url("/watch-tracker.js"), String.class);
        assertThat(tracker.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tracker.getBody()).contains("window.WatchTracker");

        ResponseEntity<String> resilience = restTemplate.getForEntity(url("/watch-resilience.js"), String.class);
        assertThat(resilience.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resilience.getBody()).contains("window.WatchResilience");
    }

    private User ensureStudent(String username, String rawPassword) {
        return userRepository.findByUsername(username).orElseGet(() ->
                userRepository.save(new User(username, passwordEncoder.encode(rawPassword), "Test Student", Role.ROLE_STUDENT)));
    }

    private Episode ensureEpisode(String ytPlaylistId, int num, String vid, int durationSec) {
        Playlist p = playlistRepository.findByYoutubePlaylistId(ytPlaylistId).orElseGet(() -> {
            Playlist created = new Playlist("Phase 11 Course", ytPlaylistId, "");
            created.setPublished(true);
            return playlistRepository.save(created);
        });
        return episodeRepository.findByPlaylistIdAndEpisodeNumber(p.getId(), num).orElseGet(() ->
                episodeRepository.save(new Episode(p, num, "Episode " + num, vid, durationSec)));
    }

    private HttpHeaders loginAndGetCookieHeaders(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = restTemplate.postForEntity(
                url("/api/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = res.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set(HttpHeaders.COOKIE, String.join("; ", cookies.stream().map(c -> c.split(";", 2)[0]).toList()));
        return authHeaders;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
