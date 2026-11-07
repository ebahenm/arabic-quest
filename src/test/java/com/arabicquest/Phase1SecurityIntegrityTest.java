package com.arabicquest;

import com.arabicquest.config.BrandConstants;
import com.arabicquest.config.ProdStartupValidator;
import com.arabicquest.dto.WatchHeartbeatRequest;
import com.arabicquest.dto.WatchStatusResponse;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.AiQuizService;
import com.arabicquest.service.WatchProgressService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase1_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase1admin",
        "ADMIN_PASSWORD=Phase1StrongPassword!99",
        "ADMIN_SECRET_PATH=phase1-secret-console",
        "JWT_SECRET=phase1-test-jwt-secret-key-with-well-over-32-chars-entropy",
        "app.watch.required-cap-seconds=1800",
        "LOGIN_MAX_ATTEMPTS=4",
        "LOGIN_LOCKOUT_SECONDS=600"
})
class Phase1SecurityIntegrityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private WatchProgressService watchProgressService;

    @Autowired
    private AiQuizService aiQuizService;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WatchSessionRepository watchSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void heartbeatCannotChangeDuration_externalGivesZeroCredit_requiredIsMin1800AndWallClockBounded() {
        User student = userRepository.findByUsername("p1watchstudent").orElseGet(() ->
                userRepository.save(new User("p1watchstudent", passwordEncoder.encode("StudentPass!123"), "Watch Student", Role.ROLE_STUDENT)));

        Playlist course = playlistRepository.findByYoutubePlaylistId("PL_PHASE1_WATCH")
                .orElseGet(() -> {
                    Playlist p = new Playlist("Watch Test Course", "PL_PHASE1_WATCH", "");
                    p.setPublished(true);
                    return playlistRepository.save(p);
                });

        Episode longEp = episodeRepository.findByPlaylistIdAndEpisodeNumber(course.getId(), 1)
                .orElseGet(() -> episodeRepository.save(new Episode(course, 1, "45-Min Episode", "VIDLONG0001", 2700)));
        Episode shortEp = episodeRepository.findByPlaylistIdAndEpisodeNumber(course.getId(), 2)
                .orElseGet(() -> episodeRepository.save(new Episode(course, 2, "12-Min Episode", "VIDSHRT0002", 720)));

        // Required watch seconds = min(1800, duration)
        assertThat(watchProgressService.computeRequiredWatchSeconds(longEp)).isEqualTo(1800);
        assertThat(watchProgressService.computeRequiredWatchSeconds(shortEp)).isEqualTo(720);

        // Client-supplied videoDurationSeconds MUST NOT mutate Episode.durationSeconds
        WatchStatusResponse afterSpoofedDuration = watchProgressService.recordHeartbeat(
                student.getUsername(),
                longEp.getId(),
                new WatchHeartbeatRequest(WatchMode.ARABIC_SUB, false, false, 10, 10, 90, true),
                false
        );
        assertThat(episodeRepository.findById(longEp.getId()).orElseThrow().getDurationSeconds()).isEqualTo(2700);
        assertThat(afterSpoofedDuration.requiredWatchSeconds()).isEqualTo(1800);
        // Initial anchor heartbeat grants 0 credit
        assertThat(afterSpoofedDuration.verifiedWatchSeconds()).isEqualTo(0);

        // Immediate second heartbeat (< 1s elapsed) claiming 25s watched must grant 0s (bounded by wall clock)
        WatchStatusResponse spamResp = watchProgressService.recordHeartbeat(
                student.getUsername(),
                longEp.getId(),
                new WatchHeartbeatRequest(WatchMode.ARABIC_SUB, false, false, 25, 35, 90, true),
                false
        );
        assertThat(spamResp.verifiedWatchSeconds()).isEqualTo(0);

        // Simulate 6 real seconds elapsed -> claiming 25s must only credit 6s!
        WatchSession session = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), longEp.getId()).orElseThrow();
        session.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(6));
        watchSessionRepository.save(session);

        WatchStatusResponse boundedResp = watchProgressService.recordHeartbeat(
                student.getUsername(),
                longEp.getId(),
                new WatchHeartbeatRequest(WatchMode.ARABIC_SUB, false, false, 25, 35, null, true),
                false
        );
        assertThat(boundedResp.verifiedWatchSeconds()).isBetween(6, 7);

        // Client-asserted external=true MUST grant zero additional credit even if time passed
        int beforeExternal = boundedResp.verifiedWatchSeconds();
        session = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), longEp.getId()).orElseThrow();
        session.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(20));
        watchSessionRepository.save(session);

        WatchStatusResponse externalResp = watchProgressService.recordHeartbeat(
                student.getUsername(),
                longEp.getId(),
                new WatchHeartbeatRequest(WatchMode.ARABIC_SUB, true, false, 20, 55, null, true),
                false
        );
        assertThat(externalResp.verifiedWatchSeconds()).isEqualTo(beforeExternal);
    }

    @Test
    void nativeForwardHeadersStrategyResolvesXForwardedFor_soDistinctClientIpsDoNotLockEachOtherOut() {
        userRepository.findByUsername("realbrother").orElseGet(() ->
                userRepository.save(new User("realbrother", passwordEncoder.encode("BrotherValid!88"), "Brother", Role.ROLE_STUDENT)));

        String loginUrl = "http://localhost:" + port + "/api/auth/login";

        // Client IP A (198.51.100.10) exhausts 4 failed attempts targeting a different username
        HttpHeaders ipAHeaders = new HttpHeaders();
        ipAHeaders.setContentType(MediaType.APPLICATION_JSON);
        ipAHeaders.set("X-Forwarded-For", "198.51.100.10");

        for (int i = 0; i < 4; i++) {
            ResponseEntity<String> failRes = testRestTemplate.postForEntity(
                    loginUrl,
                    new HttpEntity<>("{\"username\":\"attacker_guess\",\"password\":\"wrongpass\"}", ipAHeaders),
                    String.class
            );
            assertThat(failRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // 5th attempt from IP A is locked out with 429 Too Many Requests
        ResponseEntity<String> lockedRes = testRestTemplate.postForEntity(
                loginUrl,
                new HttpEntity<>("{\"username\":\"realbrother\",\"password\":\"BrotherValid!88\"}", ipAHeaders),
                String.class
        );
        assertThat(lockedRes.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Meanwhile Client IP B (203.0.113.77) behind the same proxy is completely unaffected and logs in -> 200 OK!
        HttpHeaders ipBHeaders = new HttpHeaders();
        ipBHeaders.setContentType(MediaType.APPLICATION_JSON);
        ipBHeaders.set("X-Forwarded-For", "203.0.113.77");

        ResponseEntity<String> ipBSuccess = testRestTemplate.postForEntity(
                loginUrl,
                new HttpEntity<>("{\"username\":\"realbrother\",\"password\":\"BrotherValid!88\"}", ipBHeaders),
                String.class
        );
        assertThat(ipBSuccess.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unreadyEpisodeQuizNeverCallsGemini_andQuestionBankExportImportWorksEndToEnd() throws Exception {
        AtomicInteger geminiCalls = new AtomicInteger(0);
        RestTemplate prev = aiQuizService.getRestTemplate();
        aiQuizService.setRestTemplate(new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                geminiCalls.incrementAndGet();
                throw new AssertionError("Gemini must never be called on student quiz view!");
            }
        });

        try {
            HttpHeaders jsonHeaders = new HttpHeaders();
            jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
            jsonHeaders.set("X-Requested-With", "XMLHttpRequest");
            jsonHeaders.set("X-Forwarded-For", "203.0.113.88");

            ResponseEntity<String> adminLogin = testRestTemplate.postForEntity(
                    "http://localhost:" + port + "/api/auth/login",
                    new HttpEntity<>("{\"username\":\"phase1admin\",\"password\":\"Phase1StrongPassword!99\"}", jsonHeaders),
                    String.class
            );
            assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
            String adminToken = objectMapper.readTree(adminLogin.getBody()).get("token").asText();

            HttpHeaders adminAuth = new HttpHeaders();
            adminAuth.setContentType(MediaType.APPLICATION_JSON);
            adminAuth.setBearerAuth(adminToken);

            // Import a course with 1 ready episode and 1 empty episode via Question Bank JSON Import
            String importPayload = """
                    {
                      "schemaVersion": 1,
                      "courses": [
                        {
                          "title": "Imported History Course",
                          "youtubePlaylistId": "PL_IMPORT_ROUNDTRIP_1",
                          "description": "Private backup course",
                          "rateArabicSub": 15.00,
                          "rateEnglishDub": 10.00,
                          "completionBonus": 100.00,
                          "episodes": [
                            {
                              "episodeNumber": 1,
                              "title": "Unready Episode",
                              "youtubeVideoId": "VIDUNREADY1",
                              "durationSeconds": 1500,
                              "questions": []
                            }
                          ]
                        }
                      ]
                    }
                    """;

            ResponseEntity<String> importRes = testRestTemplate.postForEntity(
                    "http://localhost:" + port + "/api/admin/question-bank/import",
                    new HttpEntity<>(importPayload, adminAuth),
                    String.class
            );
            assertThat(importRes.getStatusCode()).isEqualTo(HttpStatus.OK);

            Playlist importedCourse = playlistRepository.findByYoutubePlaylistId("PL_IMPORT_ROUNDTRIP_1").orElseThrow();
            Episode unreadyEp = episodeRepository.findByPlaylistIdAndEpisodeNumber(importedCourse.getId(), 1).orElseThrow();

            // Student / Admin fetching quiz for unready episode gets ready=false and 0 Gemini calls
            ResponseEntity<String> quizViewRes = testRestTemplate.exchange(
                    "http://localhost:" + port + "/api/student/quizzes/" + unreadyEp.getId() + "?betaUnlock=true",
                    HttpMethod.GET,
                    new HttpEntity<>(adminAuth),
                    String.class
            );
            assertThat(quizViewRes.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode quizNode = objectMapper.readTree(quizViewRes.getBody());
            assertThat(quizNode.get("ready").asBoolean()).isFalse();
            assertThat(quizNode.get("message").asText()).isEqualTo("Quiz not ready yet");
            assertThat(geminiCalls.get()).isZero();

            // Export question bank JSON and verify imported course is included
            ResponseEntity<String> exportRes = testRestTemplate.exchange(
                    "http://localhost:" + port + "/api/admin/question-bank/export",
                    HttpMethod.GET,
                    new HttpEntity<>(adminAuth),
                    String.class
            );
            assertThat(exportRes.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(exportRes.getBody()).contains("PL_IMPORT_ROUNDTRIP_1").contains("Imported History Course");

            // Verify centralized BrandConstants in /api/public/config and Actuator exposure limited to health
            ResponseEntity<String> pubConfig = testRestTemplate.getForEntity(
                    "http://localhost:" + port + "/api/public/config", String.class);
            assertThat(pubConfig.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(pubConfig.getBody()).contains(BrandConstants.BRAND_NAME_LATIN).contains(BrandConstants.TAGLINE);

            assertThat(testRestTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(testRestTemplate.getForEntity("http://localhost:" + port + "/actuator/env", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            aiQuizService.setRestTemplate(prev);
        }
    }

    @Test
    void prodStartupValidatorFailsFastWhenRequiredVarsMissingOrEqualDefaults() {
        // Missing all required vars in prod -> clear error listing all four
        MockEnvironment emptyProd = new MockEnvironment();
        emptyProd.setActiveProfiles("prod");

        assertThatThrownBy(() -> ProdStartupValidator.validateProdEnvironment(emptyProd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("ADMIN_PASSWORD")
                .hasMessageContaining("ADMIN_SECRET_PATH")
                .hasMessageContaining("DATABASE_URL");

        // Known defaults (admin123, turath-hq, localhost postgres/postgres) -> rejected!
        MockEnvironment defaultProd = new MockEnvironment()
                .withProperty("JWT_SECRET", "changeme")
                .withProperty("ADMIN_PASSWORD", "admin123")
                .withProperty("ADMIN_SECRET_PATH", "turath-hq")
                .withProperty("DATABASE_URL", "jdbc:postgresql://localhost:5432/arabicquest");
        defaultProd.setActiveProfiles("prod");

        assertThatThrownBy(() -> ProdStartupValidator.validateProdEnvironment(defaultProd))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("ADMIN_PASSWORD")
                .hasMessageContaining("ADMIN_SECRET_PATH")
                .hasMessageContaining("DATABASE_URL");

        // Valid strong prod configuration -> passes cleanly
        MockEnvironment validProd = new MockEnvironment()
                .withProperty("JWT_SECRET", "9f8e7d6c5b4a392817263544536271809a8b7c6d5e4f3a2b")
                .withProperty("ADMIN_PASSWORD", "Str0ngUnique#ProdPass2026!")
                .withProperty("ADMIN_SECRET_PATH", "vault-9c4f2a8b")
                .withProperty("DATABASE_URL", "postgresql://neondb_owner:realSecret99@ep-divine-wind.us-east-2.aws.neon.tech/neondb?sslmode=require");
        validProd.setActiveProfiles("prod");

        ProdStartupValidator.validateProdEnvironment(validProd);
    }
}
