package com.arabicquest;

import com.arabicquest.config.DatabaseConfig;
import com.arabicquest.controller.AdminController;
import com.arabicquest.controller.AuthController;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ADMIN_USERNAME=hqadmin",
        "ADMIN_PASSWORD=MasterStrongPass!2026",
        "ADMIN_SECRET_PATH=custom-vault-hq",
        "GOOGLE_CLIENT_ID=test-client-123.apps.googleusercontent.com",
        "JWT_SECRET=unit-test-jwt-secret-key-with-more-than-32-bytes-entropy-999",
        "QUIZ_WATCH_CAP_SECONDS=0",
        "QUIZ_RETRY_COOLDOWN_SECONDS=300",
        "LOGIN_MAX_ATTEMPTS=5",
        "LOGIN_LOCKOUT_SECONDS=600"
})
class SecurityAndAuditIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthController authController;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WatchSessionRepository watchSessionRepository;

    @Autowired
    private QuizAttemptRepository quizAttemptRepository;

    @Autowired
    private EpisodeRewardClaimRepository episodeRewardClaimRepository;

    @Autowired
    private PlaylistBonusClaimRepository playlistBonusClaimRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String adminBearer;
    private Cookie adminCookie;

    @BeforeEach
    void authenticateAdmin() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.0.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .content("{\"username\":\"hqadmin\",\"password\":\"MasterStrongPass!2026\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("turath_session=").contains("HttpOnly").contains("SameSite=Strict");

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        this.adminBearer = "Bearer " + json.get("token").asText();
        this.adminCookie = res.getResponse().getCookie("turath_session");
        assertThat(this.adminCookie).isNotNull();
    }

    @Test
    void audit1_1_and_1_4_secretAdminRoutesWork_publicAdminFilesBlocked_andZeroInnerHtmlOrPrefilledSecrets() throws Exception {
        // Obvious static admin paths are blocked when unauthenticated and 404 even when authenticated
        mockMvc.perform(get("/admin.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin-login.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin.js")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin.html").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());

        // Secret configured admin routes work and contain no pre-filled passwords
        String adminLoginHtml = mockMvc.perform(get("/custom-vault-hq-login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(adminLoginHtml)
                .doesNotContain("admin123")
                .doesNotContain("value=\"admin\"")
                .contains("/custom-vault-hq");

        String studentLoginHtml = mockMvc.perform(get("/login.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(studentLoginHtml)
                .doesNotContain("learn123")
                .doesNotContain("YOUR_GOOGLE_CLIENT_ID")
                .doesNotContain("GooglePassword123!");

        // Verify XSS safety: zero .innerHTML usage in app.js and private-hq/admin.js
        String appJs = StreamUtils.copyToString(new ClassPathResource("static/app.js").getInputStream(), StandardCharsets.UTF_8);
        String adminJs = StreamUtils.copyToString(new ClassPathResource("private-hq/admin.js").getInputStream(), StandardCharsets.UTF_8);
        assertThat(appJs).doesNotContain(".innerHTML");
        assertThat(adminJs).doesNotContain(".innerHTML");
    }

    @Test
    void audit1_2_and_1_3_googleAuthVerifiesAudEmailAndNoSelfReg_neverOverwritesPassword_andRegisterEndpointDeleted() throws Exception {
        // 1.3: /api/auth/register is completely removed
        mockMvc.perform(post("/api/auth/register")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hacker\",\"password\":\"Password123!\",\"fullName\":\"Hacker\"}"))
                .andExpect(status().is4xxClientError());

        // Create legitimate student via Admin API with a Gmail username
        mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"brother.ahmad@gmail.com\",\"password\":\"OriginalSecret#88\",\"fullName\":\"Ahmad\"}"))
                .andExpect(status().isOk());

        // Mock Google tokeninfo endpoint
        Map<String, Map<String, Object>> tokenFixtures = new HashMap<>();
        long futureExp = Instant.now().getEpochSecond() + 3600;

        tokenFixtures.put("wrong-aud-token", Map.of(
                "aud", "some-other-app.apps.googleusercontent.com",
                "iss", "https://accounts.google.com",
                "email_verified", "true",
                "exp", String.valueOf(futureExp),
                "email", "brother.ahmad@gmail.com"
        ));
        tokenFixtures.put("unverified-email-token", Map.of(
                "aud", "test-client-123.apps.googleusercontent.com",
                "iss", "https://accounts.google.com",
                "email_verified", "false",
                "exp", String.valueOf(futureExp),
                "email", "brother.ahmad@gmail.com"
        ));
        tokenFixtures.put("stranger-token", Map.of(
                "aud", "test-client-123.apps.googleusercontent.com",
                "iss", "https://accounts.google.com",
                "email_verified", "true",
                "exp", String.valueOf(futureExp),
                "email", "stranger@gmail.com"
        ));
        tokenFixtures.put("valid-brother-token", Map.of(
                "aud", "test-client-123.apps.googleusercontent.com",
                "iss", "https://accounts.google.com",
                "email_verified", "true",
                "exp", String.valueOf(futureExp),
                "email", "brother.ahmad@gmail.com"
        ));

        authController.setRestTemplate(new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> getForEntity(URI url, Class<T> responseType) {
                String query = url.getQuery();
                String idToken = query.substring(query.indexOf("id_token=") + "id_token=".length());
                Map<String, Object> payload = tokenFixtures.get(idToken);
                return new ResponseEntity<>((T) payload, HttpStatus.OK);
            }
        });

        // Wrong aud -> 401
        mockMvc.perform(post("/api/auth/google")
                        .with(req -> { req.setRemoteAddr("10.0.1.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"wrong-aud-token\"}"))
                .andExpect(status().isUnauthorized());

        // Unverified email -> 401
        mockMvc.perform(post("/api/auth/google")
                        .with(req -> { req.setRemoteAddr("10.0.1.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"unverified-email-token\"}"))
                .andExpect(status().isUnauthorized());

        // Unknown stranger -> 403 Forbidden (no self-registration!)
        mockMvc.perform(post("/api/auth/google")
                        .with(req -> { req.setRemoteAddr("10.0.1.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"stranger-token\"}"))
                .andExpect(status().isForbidden());
        assertThat(userRepository.existsByUsername("stranger@gmail.com")).isFalse();

        // Pre-registered student -> 200 OK via Google
        mockMvc.perform(post("/api/auth/google")
                        .with(req -> { req.setRemoteAddr("10.0.1.4"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"valid-brother-token\"}"))
                .andExpect(status().isOk());

        // Password login STILL works after Google login (password was NOT overwritten!)
        mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.1.5"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"brother.ahmad@gmail.com\",\"password\":\"OriginalSecret#88\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void audit1_4_and_1_6_csrfCookieGuard_passwordChangeRevokesOldJwt_corsLocked_frameOptions_andRateLimit() throws Exception {
        // Cookie POST without X-Requested-With or JSON content-type is ignored by JwtAuthenticationFilter -> 401
        mockMvc.perform(post("/api/admin/episodes/1/generate-quiz")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));

        // Same cookie POST WITH X-Requested-With succeeds -> 200 (also verifies 3.1 no lazy proxy 500!)
        mockMvc.perform(post("/api/admin/episodes/1/generate-quiz")
                        .cookie(adminCookie)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk());

        // CORS: untrusted origin rejected, turathtona.site allowed
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil-site.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());

        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://turathtona.site")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://turathtona.site"));

        // Brute-force login rate limit from dedicated attacker IP
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .with(req -> { req.setRemoteAddr("192.0.2.99"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"nobody\",\"password\":\"wrongpass\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("192.0.2.99"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Password change revokes older JWTs via tokenVersion
        mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"rotatetest\",\"password\":\"InitialPass#11\",\"fullName\":\"Rotate Test\"}"))
                .andExpect(status().isOk());

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.2.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"rotatetest\",\"password\":\"InitialPass#11\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String oldToken = objectMapper.readTree(loginBody).get("token").asText();

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"InitialPass#11\",\"newPassword\":\"RotatedPass#22\"}"))
                .andExpect(status().isOk());

        // Old token is now immediately invalid!
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void audit1_5_2_1_and_3_2_realYoutubeIds_sortedEpisodes_shuffledOptions_andNoPasswordOrAnswerLeaks() throws Exception {
        List<Episode> active = episodeRepository.findAllActiveOrderByEpisodeNumberAsc();
        assertThat(active).hasSize(15);
        assertThat(active.get(0).getEpisodeNumber()).isEqualTo(1);
        assertThat(active.get(0).getYoutubeVideoId()).isEqualTo("EXrpLeyeSeU");
        assertThat(active.get(14).getEpisodeNumber()).isEqualTo(15);
        assertThat(active.get(14).getYoutubeVideoId()).isEqualTo("BDgk-IRFi3c");

        // Verify episode-specific vocabulary differs across episodes
        assertThat(active.get(0).getVocabularyJson()).isNotBlank();
        assertThat(active.get(0).getVocabularyJson()).isNotEqualTo(active.get(1).getVocabularyJson());

        // Verify all 15 episodes have 20 questions, 100% pass requirement, and shuffled options
        int[] stats = transactionTemplate.execute(status -> {
            int nonFirst = 0;
            int total = 0;
            for (Episode ep : active) {
                Quiz qz = quizRepository.findByEpisodeId(ep.getId()).orElseThrow();
                assertThat(qz.getPassingScorePercentage()).isEqualTo(100.0);
                assertThat(qz.getQuestions()).hasSize(20);
                for (Question q : qz.getQuestions()) {
                    total++;
                    if (!Boolean.TRUE.equals(q.getOptions().get(0).getIsCorrect())) {
                        nonFirst++;
                    }
                }
            }
            return new int[]{total, nonFirst};
        });
        assertThat(stats[0]).isEqualTo(300);
        assertThat(stats[1]).isGreaterThan(150);

        // Verify mode buttons ("Arabic + English Subs" / "English Dub") were removed from index.html
        String indexHtml = StreamUtils.copyToString(new ClassPathResource("static/index.html").getInputStream(), StandardCharsets.UTF_8);
        assertThat(indexHtml).doesNotContain("Arabic + English Subs").doesNotContain("English Dub");

        // Verify no password hashes or isCorrect flags leak in API responses
        String studentsJson = mockMvc.perform(get("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(studentsJson).doesNotContain("password").doesNotContain("$2a$");

        String studentQuizJson = mockMvc.perform(get("/api/student/quizzes/" + active.get(0).getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(studentQuizJson).doesNotContain("isCorrect");
    }

    @Test
    void audit2_2_2_3_2_4_3_1_and_3_4_watchVerification_modeLock_cooldown_singleBonus_payoutValidation_andSoftArchive() throws Exception {
        // Create student
        String createdJson = mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"earner1\",\"password\":\"StrongStudent#1\",\"fullName\":\"Earner One\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long studentId = objectMapper.readTree(createdJson).get("id").asLong();

        String studentLogin = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.5.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"earner1\",\"password\":\"StrongStudent#1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String studentBearer = "Bearer " + objectMapper.readTree(studentLogin).get("token").asText();

        // 2.3: Student quiz generation endpoint is removed
        List<Episode> episodes = episodeRepository.findAllActiveOrderByEpisodeNumberAsc();
        Episode ep1 = episodes.get(0);
        Episode ep2 = episodes.get(1);

        mockMvc.perform(post("/api/student/quizzes/generate/" + ep1.getId())
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().is4xxClientError());

        // 2.2: Submitting quiz BEFORE 10 minutes (e.g. 599s) is blocked with 403 Forbidden
        User earner = userRepository.findById(studentId).orElseThrow();
        WatchSession ws1 = new WatchSession(earner, ep1, WatchMode.ENGLISH_DUB);
        ws1.setModeLocked(true);
        ws1.setVerifiedWatchSeconds(599);
        watchSessionRepository.save(ws1);

        Map<String, Object> perfectEp1Payload = buildWinningSubmission(ep1.getId(), "ARABIC_SUB");
        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(perfectEp1Payload)))
                .andExpect(status().isForbidden());

        // Reach exact 10-minute (600s) watch requirement!
        ws1.setVerifiedWatchSeconds(600);
        watchSessionRepository.save(ws1);

        // Answering 19 out of 20 (95%) MUST FAIL because pass requirement is strictly 100% (20/20)!
        Map<String, Object> nineteenOfTwentyPayload = buildAlmostWinningSubmission(ep1.getId(), "ARABIC_SUB");
        MvcResult ep1AlmostRes = mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nineteenOfTwentyPayload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ep1AlmostJson = objectMapper.readTree(ep1AlmostRes.getResponse().getContentAsString());
        assertThat(ep1AlmostJson.get("passed").asBoolean()).isFalse();
        assertThat(ep1AlmostJson.get("scorePercentage").asDouble()).isEqualTo(95.0);
        assertThat(new BigDecimal(ep1AlmostJson.get("rewardAmount").asText())).isEqualByComparingTo("0.00");

        // Clear cooldown from the 95% failed attempt so we can submit 20/20 (100%)
        QuizAttempt ep1Fail = quizAttemptRepository
                .findTopByStudentIdAndEpisodeIdAndPassedFalseOrderByAttemptedAtDesc(studentId, ep1.getId())
                .orElseThrow();
        quizAttemptRepository.delete(ep1Fail);

        MvcResult ep1PassRes = mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(perfectEp1Payload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ep1Result = objectMapper.readTree(ep1PassRes.getResponse().getContentAsString());
        assertThat(ep1Result.get("passed").asBoolean()).isTrue();
        // Must pay $10.00 (locked ENGLISH_DUB), NOT spoofed $15.00!
        assertThat(new BigDecimal(ep1Result.get("rewardAmount").asText())).isEqualByComparingTo("10.00");

        // 2.4: Submitting Episode 1 a second time pays $0.00
        MvcResult ep1DupRes = mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(perfectEp1Payload)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new BigDecimal(objectMapper.readTree(ep1DupRes.getResponse().getContentAsString())
                .get("rewardAmount").asText())).isEqualByComparingTo("0.00");

        // 2.2: Failing Episode 2 triggers retry cooldown (429 on immediate retry)
        WatchSession ws2 = new WatchSession(earner, ep2, WatchMode.ARABIC_SUB);
        ws2.setModeLocked(true);
        ws2.setVerifiedWatchSeconds(600);
        watchSessionRepository.save(ws2);

        Map<String, Object> failingEp2Payload = Map.of(
                "episodeId", ep2.getId(),
                "watchMode", "ARABIC_SUB",
                "answers", Map.of()
        );
        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failingEp2Payload)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildWinningSubmission(ep2.getId(), "ARABIC_SUB"))))
                .andExpect(status().isTooManyRequests());

        // Clear the failed attempt in test DB after verifying 429 cooldown (attempted_at is immutable updatable=false)
        QuizAttempt failedAttempt = quizAttemptRepository
                .findTopByStudentIdAndEpisodeIdAndPassedFalseOrderByAttemptedAtDesc(studentId, ep2.getId())
                .orElseThrow();
        quizAttemptRepository.delete(failedAttempt);

        for (int i = 1; i < episodes.size(); i++) {
            Episode ep = episodes.get(i);
            WatchSession ws = watchSessionRepository.findByStudentIdAndEpisodeId(studentId, ep.getId())
                    .orElseGet(() -> new WatchSession(earner, ep, WatchMode.ARABIC_SUB));
            ws.setLockedWatchMode(WatchMode.ARABIC_SUB);
            ws.setModeLocked(true);
            ws.setVerifiedWatchSeconds(600);
            watchSessionRepository.save(ws);

            MvcResult passRes = mockMvc.perform(post("/api/student/quizzes/submit")
                            .header(HttpHeaders.AUTHORIZATION, studentBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildWinningSubmission(ep.getId(), "ARABIC_SUB"))))
                    .andExpect(status().isOk())
                    .andReturn();
            BigDecimal earned = new BigDecimal(objectMapper.readTree(passRes.getResponse().getContentAsString())
                    .get("rewardAmount").asText());

            if (i < episodes.size() - 1) {
                assertThat(earned).isEqualByComparingTo("15.00");
            } else {
                // Final episode (#15) awards $15.00 + $100.00 one-time playlist completion bonus = $115.00!
                assertThat(earned).isEqualByComparingTo("115.00");
            }
        }

        // 2.4: Admin adds Episode 16; student watches & passes Episode 16 -> earns ONLY $15.00 (NO second $100 bonus!)
        String ep16Json = mockMvc.perform(post("/api/admin/episodes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"episodeNumber\":16,\"title\":\"الحلقة 16 الاختبارية\",\"youtubeVideoId\":\"EXrpLeyeSeU\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long ep16Id = objectMapper.readTree(ep16Json).get("id").asLong();

        // Trigger quiz creation for Ep 16 via student view
        mockMvc.perform(get("/api/student/quizzes/" + ep16Id)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk());

        Episode ep16 = episodeRepository.findById(ep16Id).orElseThrow();
        WatchSession ws16 = new WatchSession(earner, ep16, WatchMode.ARABIC_SUB);
        ws16.setModeLocked(true);
        ws16.setVerifiedWatchSeconds(600);
        watchSessionRepository.save(ws16);

        MvcResult ep16PassRes = mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildWinningSubmission(ep16Id, "ARABIC_SUB"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new BigDecimal(objectMapper.readTree(ep16PassRes.getResponse().getContentAsString())
                .get("rewardAmount").asText())).isEqualByComparingTo("15.00");

        // 3.4: Negative and zero payouts are rejected with 400 Bad Request
        mockMvc.perform(post("/api/admin/payouts")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":" + studentId + ",\"amount\":-50,\"note\":\"hack\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/payouts")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":" + studentId + ",\"amount\":0,\"note\":\"zero\"}"))
                .andExpect(status().isBadRequest());

        // Valid payout succeeds -> 200 OK
        mockMvc.perform(post("/api/admin/payouts")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":" + studentId + ",\"amount\":25.00,\"note\":\"Zelle\"}"))
                .andExpect(status().isOk());

        // 3.1 & 1.5: /api/admin/attempts, /api/admin/payouts, /api/student/payouts all return 200 OK with zero lazy proxy 500s or password leaks
        String attemptsBody = mockMvc.perform(get("/api/admin/attempts").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(attemptsBody).doesNotContain("password").contains("Earner One");

        String adminPayoutsBody = mockMvc.perform(get("/api/admin/payouts").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(adminPayoutsBody).doesNotContain("password").contains("25.00");

        String studentPayoutsBody = mockMvc.perform(get("/api/student/payouts").header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(studentPayoutsBody).doesNotContain("password").contains("25.00");

        // 3.4: Deleting Episode 16 (which has a passed attempt & reward claim) soft-archives it (204) without FK crash and preserves balance!
        mockMvc.perform(delete("/api/admin/episodes/" + ep16Id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        // Total earned: $10 (ep1) + 14 * $15 ($210) + $100 bonus + $15 (ep16) = $335.00; paid $25.00 -> owed $310.00
        String earningsJson = mockMvc.perform(get("/api/student/earnings")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode earnings = objectMapper.readTree(earningsJson);
        assertThat(new BigDecimal(earnings.get("totalEarned").asText())).isEqualByComparingTo("335.00");
        assertThat(new BigDecimal(earnings.get("balanceOwed").asText())).isEqualByComparingTo("310.00");
    }

    @Test
    void audit3_5_databaseConfigPreservesPlusPercentEncodedCharsAndNeonQueryParameters() {
        DatabaseConfig.ParsedJdbcConfig parsed = DatabaseConfig.parseDatabaseUrl(
                "postgresql://neondb_owner:p%40ss+w%3Ard!@ep-cool-sky-12345.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require",
                null,
                null
        );
        assertThat(parsed.username()).isEqualTo("neondb_owner");
        assertThat(parsed.password()).isEqualTo("p@ss+w:rd!");
        assertThat(parsed.jdbcUrl())
                .isEqualTo("jdbc:postgresql://ep-cool-sky-12345.us-east-2.aws.neon.tech:5432/neondb?sslmode=require&channel_binding=require");
    }

    @Test
    void adminAndStudentUsernameUpdates_andYoutubeUrlExtractionWorkSeamlessly() throws Exception {
        // Verify full YouTube URLs extract cleanly
        assertThat(AdminController.extractYoutubeVideoId("https://www.youtube.com/watch?v=EXrpLeyeSeU&list=PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB"))
                .isEqualTo("EXrpLeyeSeU");
        assertThat(AdminController.extractYoutubeVideoId("https://youtu.be/4eLlSGg2ToY?si=abc123"))
                .isEqualTo("4eLlSGg2ToY");

        // Create student & rename student username via Admin endpoint
        String createdJson = mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tempbrother\",\"password\":\"BrotherPass#99\",\"fullName\":\"Brother\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long studentId = objectMapper.readTree(createdJson).get("id").asLong();

        mockMvc.perform(post("/api/admin/students/" + studentId + "/update-account")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUsername\":\"ahmed.renamed\",\"fullName\":\"Ahmed Renamed\",\"newPassword\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ahmed.renamed"))
                .andExpect(jsonPath("$.fullName").value("Ahmed Renamed"));

        // Student can log in with the new username and original password
        mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.9.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ahmed.renamed\",\"password\":\"BrotherPass#99\"}"))
                .andExpect(status().isOk());

        // Rename Admin master username without changing password
        MvcResult renameRes = mockMvc.perform(post("/api/auth/update-credentials")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"MasterStrongPass!2026\",\"newUsername\":\"mohamed_master\",\"newPassword\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("mohamed_master"))
                .andReturn();

        String newAdminToken = "Bearer " + objectMapper.readTree(renameRes.getResponse().getContentAsString()).get("token").asText();
        assertThat(userRepository.findByUsername("mohamed_master").orElseThrow().isCredentialsCustomized()).isTrue();

        // Old admin token is immediately revoked; new admin username logs in cleanly
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.9.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mohamed_master\",\"password\":\"MasterStrongPass!2026\"}"))
                .andExpect(status().isOk());

        // Restore username to hqadmin so @BeforeEach on other tests remains consistent
        mockMvc.perform(post("/api/auth/update-credentials")
                        .header(HttpHeaders.AUTHORIZATION, newAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"MasterStrongPass!2026\",\"newUsername\":\"hqadmin\",\"newPassword\":\"\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void sequentialEpisodeLocking_realDurationWatchCalibration_andBetaSandboxControlsWork() throws Exception {
        // 1. Create a standard student
        String createdJson = mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"seqstudent\",\"password\":\"SeqStudent#88\",\"fullName\":\"Sequential Learner\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long studentId = objectMapper.readTree(createdJson).get("id").asLong();

        String studentLogin = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.12.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"seqstudent\",\"password\":\"SeqStudent#88\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String studentBearer = "Bearer " + objectMapper.readTree(studentLogin).get("token").asText();

        // 2. Episode 1 is unlocked, Episode 2 is locked until Episode 1 is passed
        String epListJson = mockMvc.perform(get("/api/student/episodes")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode epNodes = objectMapper.readTree(epListJson);
        long ep1Id = epNodes.get(0).get("id").asLong();
        long ep2Id = epNodes.get(1).get("id").asLong();
        assertThat(epNodes.get(0).get("locked").asBoolean()).isFalse();
        assertThat(epNodes.get(0).get("watchStatus").get("requiredWatchSeconds").asInt()).isEqualTo(600);
        assertThat(epNodes.get(1).get("locked").asBoolean()).isTrue();
        assertThat(epNodes.get(1).get("unlockAfterEpisodeNumber").asInt()).isEqualTo(1);

        // Even if standard student passes ?betaUnlock=true, Episode 2 remains locked!
        String spoofBetaList = mockMvc.perform(get("/api/student/episodes?betaUnlock=true")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(spoofBetaList).get(1).get("locked").asBoolean()).isTrue();

        // Standard student cannot fetch or submit Episode 2 quiz before passing Episode 1 -> 403 Forbidden
        mockMvc.perform(get("/api/student/quizzes/" + ep2Id)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildWinningSubmission(ep2Id, "ARABIC_SUB"))))
                .andExpect(status().isForbidden());

        // 3. Standard student is blocked from Beta Sandbox endpoints -> 403 Forbidden
        mockMvc.perform(post("/api/beta/episodes/" + ep1Id + "/unlock-watch")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/beta/reset-progress")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        // 4. Metadata duration calibration updates Episode durationSeconds from YouTube player
        mockMvc.perform(post("/api/student/episodes/" + ep1Id + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"external\":false,\"videoEnded\":false,\"watchedDeltaSeconds\":0,\"videoDurationSeconds\":1440,\"playing\":false}"))
                .andExpect(status().isOk());
        assertThat(episodeRepository.findById(ep1Id).orElseThrow().getDurationSeconds()).isEqualTo(1440);

        // 5. Admin grants betaTester=true to seqstudent -> Beta Sandbox routes & unlock tools work!
        mockMvc.perform(get("/beta")).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/students/" + studentId + "/update-account")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUsername\":\"seqstudent\",\"fullName\":\"Sequential Learner\",\"newPassword\":\"\",\"betaTester\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.betaTester").value(true));

        // Beta tester can instant-unlock Episode 1 watch progress, pass Episode 1, and see Episode 2 unlock sequentially!
        mockMvc.perform(post("/api/beta/episodes/" + ep1Id + "/unlock-watch?watchMode=ARABIC_SUB")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizUnlocked").value(true));

        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildWinningSubmission(ep1Id, "ARABIC_SUB"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        // Episode 2 is now unlocked even without betaUnlock=true!
        String afterEp1List = mockMvc.perform(get("/api/student/episodes")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(afterEp1List).get(1).get("locked").asBoolean()).isFalse();
        assertThat(objectMapper.readTree(afterEp1List).get(2).get("locked").asBoolean()).isTrue();

        // With ?betaUnlock=true, Beta Tester can unlock all episodes for sandbox testing
        String betaUnlockedAll = mockMvc.perform(get("/api/student/episodes?betaUnlock=true")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(betaUnlockedAll).get(2).get("locked").asBoolean()).isFalse();

        // Reset beta progress wipes test earnings back to $0.00
        mockMvc.perform(post("/api/beta/reset-progress")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/student/earnings")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned").value(0.0));
    }

    private Map<String, Object> buildAlmostWinningSubmission(Long episodeId, String watchMode) {
        return transactionTemplate.execute(status -> {
            Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElseThrow();
            Map<String, Long> answers = new HashMap<>();
            List<Question> questions = quiz.getQuestions();
            for (int i = 0; i < questions.size(); i++) {
                Question q = questions.get(i);
                final boolean pickCorrect = (i > 0); // miss exactly 1 out of 20 -> 19/20 = 95%
                QuestionOption chosen = q.getOptions().stream()
                        .filter(opt -> Boolean.TRUE.equals(opt.getIsCorrect()) == pickCorrect)
                        .findFirst()
                        .orElseThrow();
                answers.put(String.valueOf(q.getId()), chosen.getId());
            }
            return Map.of(
                    "episodeId", episodeId,
                    "watchMode", watchMode,
                    "answers", answers
            );
        });
    }

    private Map<String, Object> buildWinningSubmission(Long episodeId, String watchMode) {
        return transactionTemplate.execute(status -> {
            Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElseThrow();
            Map<String, Long> answers = new HashMap<>();
            for (Question q : quiz.getQuestions()) {
                QuestionOption correct = q.getOptions().stream()
                        .filter(opt -> Boolean.TRUE.equals(opt.getIsCorrect()))
                        .findFirst()
                        .orElseThrow();
                answers.put(String.valueOf(q.getId()), correct.getId());
            }
            return Map.of(
                    "episodeId", episodeId,
                    "watchMode", watchMode,
                    "answers", answers
            );
        });
    }
}
