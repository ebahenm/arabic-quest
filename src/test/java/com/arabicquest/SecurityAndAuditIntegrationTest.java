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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sec_audit_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=hqadmin",
        "ADMIN_PASSWORD=MasterStrongPass!2026",
        "ADMIN_SECRET_PATH=custom-vault-hq",
        "GOOGLE_CLIENT_ID=test-client-123.apps.googleusercontent.com",
        "JWT_SECRET=unit-test-jwt-secret-key-with-more-than-32-bytes-entropy-999",
        "app.watch.required-cap-seconds=1800",
        "app.quiz.retry-cooldown-seconds=300",
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
    private QuestionRepository questionRepository;

    @Autowired
    private OpenQuestionRepository openQuestionRepository;

    @Autowired
    private QuizAttemptSessionRepository quizAttemptSessionRepository;

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

    private final SecureRandom secureRandom = new SecureRandom();
    private String adminBearer;
    private Cookie adminCookie;

    @BeforeEach
    void authenticateAdminAndEnsureSyntheticFixtures() throws Exception {
        ensureSyntheticCourseFixtures();

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

    private void ensureSyntheticCourseFixtures() {
        transactionTemplate.executeWithoutResult(status -> {
            if (playlistRepository.findByYoutubePlaylistId("PL_SYNTHETIC_TEST_01").isPresent()) {
                return;
            }
            Playlist course = new Playlist("Synthetic Test Course", "PL_SYNTHETIC_TEST_01", "Test-only fixture course");
            course.setPublished(true);
            course.setArabicRewardRate(new BigDecimal("15.00"));
            course.setEnglishRewardRate(new BigDecimal("10.00"));
            course.setCompletionBonus(new BigDecimal("100.00"));
            course.setMinEpisodesForBonus(3);
            course = playlistRepository.save(course);

            for (int epNum = 1; epNum <= 3; epNum++) {
                Episode ep = new Episode(course, epNum, "Synthetic Episode " + epNum, "SYNTHVIDEO" + epNum, 2400);
                ep = episodeRepository.save(ep);
                seedSyntheticQuizForEpisode(ep, 15);
            }
        });
    }

    private void seedSyntheticQuizForEpisode(Episode ep, int questionCount) {
        Quiz quiz = new Quiz(ep);
        quiz.setPassingScorePercentage(100.0);
        for (int qIdx = 1; qIdx <= questionCount; qIdx++) {
            Question q = new Question(quiz, "Synthetic Ep" + ep.getEpisodeNumber() + " Question " + qIdx + "?", qIdx);
            q.setStatus(QuestionStatus.APPROVED);
            q.setActive(true);
            q.setType(QuestionType.values()[qIdx % QuestionType.values().length]);
            q.setEvidence("Admin-only transcript quote for Q" + qIdx);
            List<QuestionOption> opts = new ArrayList<>();
            opts.add(new QuestionOption(q, "Correct synthetic answer " + qIdx, true));
            opts.add(new QuestionOption(q, "Distractor A " + qIdx, false));
            opts.add(new QuestionOption(q, "Distractor B " + qIdx, false));
            opts.add(new QuestionOption(q, "Distractor C " + qIdx, false));
            Collections.shuffle(opts, secureRandom);
            q.getOptions().addAll(opts);
            quiz.getQuestions().add(q);
        }
        quizRepository.save(quiz);

        for (int oIdx = 1; oIdx <= 3; oIdx++) {
            openQuestionRepository.save(new OpenQuestion(
                    ep,
                    "Explain the historical reasoning in Episode " + ep.getEpisodeNumber() + " (Prompt " + oIdx + ").",
                    "Admin-only rubric evidence " + oIdx,
                    QuestionStatus.APPROVED
            ));
        }
    }

    @Test
    void audit1_1_and_1_4_secretAdminRoutesWork_publicAdminFilesBlocked_andZeroInnerHtmlOrPrefilledSecrets() throws Exception {
        mockMvc.perform(get("/admin.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin-login.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin.js")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin.html").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNotFound());

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

        String appJs = StreamUtils.copyToString(new ClassPathResource("static/app.js").getInputStream(), StandardCharsets.UTF_8);
        String adminJs = StreamUtils.copyToString(new ClassPathResource("private-hq/admin.js").getInputStream(), StandardCharsets.UTF_8);
        assertThat(appJs).doesNotContain(".innerHTML");
        assertThat(adminJs).doesNotContain(".innerHTML");
    }

    @Test
    void audit1_2_and_1_3_googleAuthVerifiesAudEmailAndNoSelfReg_neverOverwritesPassword_andRegisterEndpointDeleted() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hacker\",\"password\":\"Password123!\",\"fullName\":\"Hacker\"}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"brother.ahmad@gmail.com\",\"password\":\"OriginalSecret#88\",\"fullName\":\"Ahmad\"}"))
                .andExpect(status().isOk());

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

        mockMvc.perform(post("/api/auth/google")
                        .with(req -> { req.setRemoteAddr("10.0.1.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"wrong-aud-token\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/google")
                        .with(req -> { req.setRemoteAddr("10.0.1.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"unverified-email-token\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/google")
                        .with(req -> { req.setRemoteAddr("10.0.1.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"stranger-token\"}"))
                .andExpect(status().isForbidden());
        assertThat(userRepository.existsByUsername("stranger@gmail.com")).isFalse();

        mockMvc.perform(post("/api/auth/google")
                        .with(req -> { req.setRemoteAddr("10.0.1.4"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"valid-brother-token\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.1.5"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"brother.ahmad@gmail.com\",\"password\":\"OriginalSecret#88\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void audit1_4_and_1_6_csrfCookieGuard_passwordChangeRevokesOldJwt_corsLocked_frameOptions_andRateLimit() throws Exception {
        // Cookie POST without X-Requested-With or JSON content-type is ignored by JwtAuthenticationFilter -> 401
        mockMvc.perform(post("/api/admin/question-bank/import")
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));

        // Same cookie POST WITH X-Requested-With and JSON succeeds -> 200
        mockMvc.perform(post("/api/admin/question-bank/import")
                        .cookie(adminCookie)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courses\":[]}"))
                .andExpect(status().isOk());

        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil-site.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());

        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://turathtona.site")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://turathtona.site"));

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

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void audit1_5_2_1_and_3_2_sortedEpisodes_shuffledOptions_andNoPasswordOrAnswerLeaks() throws Exception {
        List<Episode> active = episodeRepository.findAllActiveOrderByEpisodeNumberAsc();
        assertThat(active).hasSizeGreaterThanOrEqualTo(3);
        assertThat(active.get(0).getEpisodeNumber()).isEqualTo(1);

        int[] stats = transactionTemplate.execute(status -> {
            int nonFirst = 0;
            int total = 0;
            for (Episode ep : active) {
                Optional<Quiz> qzOpt = quizRepository.findByEpisodeId(ep.getId());
                if (qzOpt.isEmpty()) continue;
                Quiz qz = qzOpt.get();
                assertThat(qz.getPassingScorePercentage()).isEqualTo(100.0);
                for (Question q : qz.getQuestions()) {
                    total++;
                    if (!Boolean.TRUE.equals(q.getOptions().get(0).getIsCorrect())) {
                        nonFirst++;
                    }
                }
            }
            return new int[]{total, nonFirst};
        });
        assertThat(stats[0]).isGreaterThanOrEqualTo(30);
        assertThat(stats[1]).isGreaterThan(5);

        String studentsJson = mockMvc.perform(get("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(studentsJson).doesNotContain("password").doesNotContain("$2a$");

        String studentQuizJson = mockMvc.perform(get("/api/student/quizzes/" + active.get(0).getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(studentQuizJson).doesNotContain("isCorrect").doesNotContain("evidence");
    }

    @Test
    void audit2_2_2_3_2_4_3_1_and_3_4_watchVerification_modeLock_cooldown_singleBonus_payoutValidation_andSoftArchive() throws Exception {
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

        List<Episode> episodes = episodeRepository.findAllActiveOrderByEpisodeNumberAsc();
        Episode ep1 = episodes.get(0);
        Episode ep2 = episodes.get(1);

        mockMvc.perform(post("/api/student/quizzes/generate/" + ep1.getId())
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().is4xxClientError());

        // Submitting quiz BEFORE 30 minutes (1799s < 1800s) is blocked with 403 Forbidden
        User earner = userRepository.findById(studentId).orElseThrow();
        WatchSession ws1 = new WatchSession(earner, ep1, WatchMode.ENGLISH_DUB);
        ws1.setModeLocked(true);
        ws1.setVerifiedWatchSeconds(1799);
        watchSessionRepository.save(ws1);

        Map<String, Object> earlyEp1Payload = buildSessionSubmission(studentBearer, ep1.getId(), "ARABIC_SUB", 10);
        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(earlyEp1Payload)))
                .andExpect(status().isForbidden());

        // Reach exact 30-minute (1800s) watch requirement
        ws1.setVerifiedWatchSeconds(1800);
        watchSessionRepository.save(ws1);

        // Answering 9 out of 10 (90%) MUST FAIL because pass requirement is strictly 100%
        Map<String, Object> nineOfTenPayload = buildSessionSubmission(studentBearer, ep1.getId(), "ARABIC_SUB", 9);
        MvcResult ep1AlmostRes = mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nineOfTenPayload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ep1AlmostJson = objectMapper.readTree(ep1AlmostRes.getResponse().getContentAsString());
        assertThat(ep1AlmostJson.get("passed").asBoolean()).isFalse();
        assertThat(ep1AlmostJson.get("scorePercentage").asDouble()).isEqualTo(90.0);
        assertThat(new BigDecimal(ep1AlmostJson.get("rewardAmount").asText())).isEqualByComparingTo("0.00");

        QuizAttempt ep1Fail = quizAttemptRepository
                .findTopByStudentIdAndEpisodeIdAndPassedFalseOrderByAttemptedAtDesc(studentId, ep1.getId())
                .orElseThrow();
        quizAttemptRepository.delete(ep1Fail);

        // Pass 10/10 on new session -> passed=true, rewardAmount=0.00 until written answer is approved!
        Map<String, Object> perfectEp1Payload = buildSessionSubmission(studentBearer, ep1.getId(), "ARABIC_SUB", 10);
        MvcResult ep1PassRes = mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(perfectEp1Payload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode ep1Result = objectMapper.readTree(ep1PassRes.getResponse().getContentAsString());
        assertThat(ep1Result.get("passed").asBoolean()).isTrue();
        assertThat(new BigDecimal(ep1Result.get("rewardAmount").asText())).isEqualByComparingTo("0.00");
        assertThat(ep1Result.get("openQuestion")).isNotNull();

        // Re-submitting the SAME session ID is rejected with 400 Bad Request
        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(perfectEp1Payload)))
                .andExpect(status().isBadRequest());

        // Submit written answer and approve -> awards $10.00 (locked ENGLISH_DUB mode on ws1)
        JsonNode ep1Approval = submitAndApproveWrittenAnswer(studentBearer, ep1.getId(),
                "The lecturer explained the key historical turning points and primary sources in detail.");
        assertThat(new BigDecimal(ep1Approval.get("episodeRewardAmount").asText())).isEqualByComparingTo("10.00");

        WatchSession ws2 = new WatchSession(earner, ep2, WatchMode.ARABIC_SUB);
        ws2.setModeLocked(true);
        ws2.setVerifiedWatchSeconds(1800);
        watchSessionRepository.save(ws2);

        Map<String, Object> failingEp2Payload = buildSessionSubmission(studentBearer, ep2.getId(), "ARABIC_SUB", 0);
        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failingEp2Payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false));

        // Immediate retry with a fresh session is blocked by the 300s retry cooldown -> 429
        Map<String, Object> cooldownBlockedPayload = buildSessionSubmission(studentBearer, ep2.getId(), "ARABIC_SUB", 10);
        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cooldownBlockedPayload)))
                .andExpect(status().isTooManyRequests());

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
            ws.setVerifiedWatchSeconds(1800);
            watchSessionRepository.save(ws);

            mockMvc.perform(post("/api/student/quizzes/submit")
                            .header(HttpHeaders.AUTHORIZATION, studentBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSessionSubmission(studentBearer, ep.getId(), "ARABIC_SUB", 10))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passed").value(true));

            JsonNode approval = submitAndApproveWrittenAnswer(studentBearer, ep.getId(),
                    "In this episode, the historian analyzes the primary causes, consequences, and lessons for understanding this era.");
            BigDecimal epEarned = new BigDecimal(approval.get("episodeRewardAmount").asText());
            assertThat(epEarned).isEqualByComparingTo("15.00");

            if (i < episodes.size() - 1) {
                assertThat(approval.get("bonusAwarded").asBoolean()).isFalse();
            } else {
                assertThat(approval.get("bonusAwarded").asBoolean()).isTrue();
                assertThat(new BigDecimal(approval.get("bonusAmount").asText())).isEqualByComparingTo("100.00");
            }
        }

        // Admin adds Episode 4; student GET on Episode 4 returns ready=false ("Quiz not ready yet") without calling Gemini!
        String ep4Json = mockMvc.perform(post("/api/admin/episodes")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"episodeNumber\":4,\"title\":\"Synthetic Bonus Episode 4\",\"youtubeVideoId\":\"EXrpLeyeSeU\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long ep4Id = objectMapper.readTree(ep4Json).get("id").asLong();

        mockMvc.perform(get("/api/student/quizzes/" + ep4Id)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.message").value("Quiz not ready yet"));

        Episode ep4 = episodeRepository.findById(ep4Id).orElseThrow();
        transactionTemplate.executeWithoutResult(st -> seedSyntheticQuizForEpisode(ep4, 15));

        WatchSession ws4 = new WatchSession(earner, ep4, WatchMode.ARABIC_SUB);
        ws4.setModeLocked(true);
        ws4.setVerifiedWatchSeconds(1800);
        watchSessionRepository.save(ws4);

        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildSessionSubmission(studentBearer, ep4Id, "ARABIC_SUB", 10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        JsonNode ep4Approval = submitAndApproveWrittenAnswer(studentBearer, ep4Id,
                "Episode 4 deepens the historical narrative and connects the institutional reforms to long-term outcomes.");
        assertThat(new BigDecimal(ep4Approval.get("episodeRewardAmount").asText())).isEqualByComparingTo("15.00");
        assertThat(ep4Approval.get("bonusAwarded").asBoolean()).isFalse();

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

        mockMvc.perform(post("/api/admin/payouts")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":" + studentId + ",\"amount\":25.00,\"note\":\"Zelle\"}"))
                .andExpect(status().isOk());

        String attemptsBody = mockMvc.perform(get("/api/admin/attempts").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(attemptsBody).doesNotContain("password").contains("Earner One");

        String adminPayoutsBody = mockMvc.perform(get("/api/admin/payouts").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(adminPayoutsBody).doesNotContain("password").contains("25.00");

        String studentPayoutsBody = mockMvc.perform(get("/api/student/payouts").header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(studentPayoutsBody).doesNotContain("password").contains("25.00");

        mockMvc.perform(delete("/api/admin/episodes/" + ep4Id)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isNoContent());

        // Total earned: $10 (ep1) + 2 * $15 ($30) + $100 bonus + $15 (ep4) = $155.00; paid $25.00 -> owed $130.00
        String earningsJson = mockMvc.perform(get("/api/student/earnings")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode earnings = objectMapper.readTree(earningsJson);
        assertThat(new BigDecimal(earnings.get("totalEarned").asText())).isEqualByComparingTo("155.00");
        assertThat(new BigDecimal(earnings.get("balanceOwed").asText())).isEqualByComparingTo("130.00");
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

        DatabaseConfig.HostAndDatabase redacted = DatabaseConfig.extractHostAndDatabase(parsed.jdbcUrl());
        assertThat(redacted.host()).isEqualTo("ep-cool-sky-12345.us-east-2.aws.neon.tech");
        assertThat(redacted.databaseName()).isEqualTo("neondb");
    }

    @Test
    void adminAndStudentUsernameUpdates_andYoutubeUrlExtractionWorkSeamlessly() throws Exception {
        assertThat(AdminController.extractYoutubeVideoId("https://www.youtube.com/watch?v=EXrpLeyeSeU&list=PLVVfonyXVJI3BXB9Wd2C5yXMr63w3SGXB"))
                .isEqualTo("EXrpLeyeSeU");
        assertThat(AdminController.extractYoutubeVideoId("https://youtu.be/4eLlSGg2ToY?si=abc123"))
                .isEqualTo("4eLlSGg2ToY");

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

        mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.9.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ahmed.renamed\",\"password\":\"BrotherPass#99\"}"))
                .andExpect(status().isOk());

        MvcResult renameRes = mockMvc.perform(post("/api/auth/update-credentials")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"MasterStrongPass!2026\",\"newUsername\":\"mohamed_master\",\"newPassword\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("mohamed_master"))
                .andReturn();

        String newAdminToken = "Bearer " + objectMapper.readTree(renameRes.getResponse().getContentAsString()).get("token").asText();
        assertThat(userRepository.findByUsername("mohamed_master").orElseThrow().isCredentialsCustomized()).isTrue();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.0.9.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mohamed_master\",\"password\":\"MasterStrongPass!2026\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/update-credentials")
                        .header(HttpHeaders.AUTHORIZATION, newAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"MasterStrongPass!2026\",\"newUsername\":\"hqadmin\",\"newPassword\":\"\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void sequentialEpisodeLocking_immutableDuration_andBetaSandboxControlsWork() throws Exception {
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

        String epListJson = mockMvc.perform(get("/api/student/episodes")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode epNodes = objectMapper.readTree(epListJson);
        long ep1Id = epNodes.get(0).get("id").asLong();
        long ep2Id = epNodes.get(1).get("id").asLong();
        assertThat(epNodes.get(0).get("locked").asBoolean()).isFalse();
        assertThat(epNodes.get(0).get("watchStatus").get("requiredWatchSeconds").asInt()).isEqualTo(1800);
        assertThat(epNodes.get(1).get("locked").asBoolean()).isTrue();
        assertThat(epNodes.get(1).get("unlockAfterEpisodeNumber").asInt()).isEqualTo(1);

        String spoofBetaList = mockMvc.perform(get("/api/student/episodes?betaUnlock=true")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(spoofBetaList).get(1).get("locked").asBoolean()).isTrue();

        mockMvc.perform(get("/api/student/quizzes/" + ep2Id)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        // Even if a session existed for locked ep2, submitQuiz enforces sequential locking -> 403 Forbidden
        UUID lockedSessionId = transactionTemplate.execute(st -> {
            User stu = userRepository.findById(studentId).orElseThrow();
            Episode e2 = episodeRepository.findById(ep2Id).orElseThrow();
            List<Long> first10 = questionRepository.findByEpisodeIdWithOptions(ep2Id)
                    .stream().limit(10).map(Question::getId).toList();
            return quizAttemptSessionRepository.save(new QuizAttemptSession(
                    stu, e2, first10, java.time.LocalDateTime.now(), java.time.LocalDateTime.now().plusMinutes(60)
            )).getId();
        });
        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", lockedSessionId.toString(),
                                "episodeId", ep2Id,
                                "watchMode", "ARABIC_SUB",
                                "answers", Map.of("1", 1L)
                        ))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/beta/episodes/" + ep1Id + "/unlock-watch")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/beta/reset-progress")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        // Client heartbeat MUST NOT change Episode.durationSeconds!
        int originalDuration = episodeRepository.findById(ep1Id).orElseThrow().getDurationSeconds();
        mockMvc.perform(post("/api/student/episodes/" + ep1Id + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"external\":false,\"videoEnded\":false,\"watchedDeltaSeconds\":0,\"videoDurationSeconds\":65,\"playing\":false}"))
                .andExpect(status().isOk());
        assertThat(episodeRepository.findById(ep1Id).orElseThrow().getDurationSeconds()).isEqualTo(originalDuration);

        mockMvc.perform(get("/beta")).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/students/" + studentId + "/update-account")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUsername\":\"seqstudent\",\"fullName\":\"Sequential Learner Renamed\",\"newPassword\":\"\",\"betaTester\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.betaTester").value(false));

        mockMvc.perform(post("/api/admin/students/" + studentId + "/episodes/" + ep1Id + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideState").value("UNLOCKED"));

        transactionTemplate.executeWithoutResult(st -> {
            User stu = userRepository.findById(studentId).orElseThrow();
            Episode e1 = episodeRepository.findById(ep1Id).orElseThrow();
            WatchSession ws = watchSessionRepository.findByStudentIdAndEpisodeId(studentId, ep1Id)
                    .orElseGet(() -> new WatchSession(stu, e1, WatchMode.ARABIC_SUB));
            ws.setModeLocked(true);
            ws.setVerifiedWatchSeconds(1800);
            watchSessionRepository.save(ws);
        });

        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildSessionSubmission(studentBearer, ep1Id, "ARABIC_SUB", 10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        // Before written answer approval, ep2 remains locked!
        String beforeApprovalList = mockMvc.perform(get("/api/student/episodes")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(beforeApprovalList).get(1).get("locked").asBoolean()).isTrue();

        // Submit written answer and approve -> ep1 passed & ep2 unlocks!
        submitAndApproveWrittenAnswer(studentBearer, ep1Id,
                "Sequential learner explains the core historical argument and key takeaways from Episode 1.");

        String afterEp1List = mockMvc.perform(get("/api/student/episodes")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(afterEp1List).get(1).get("locked").asBoolean()).isFalse();
        assertThat(objectMapper.readTree(afterEp1List).get(2).get("locked").asBoolean()).isTrue();

        // Student attempting ?betaUnlock=true is ignored (beta tools are admin-only)
        String betaUnlockedAll = mockMvc.perform(get("/api/student/episodes?betaUnlock=true")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(betaUnlockedAll).get(2).get("locked").asBoolean()).isTrue();

        // Student reset-progress is forbidden
        mockMvc.perform(post("/api/beta/reset-progress")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isForbidden());

        // Admin call to reset-progress succeeds
        mockMvc.perform(post("/api/beta/reset-progress")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk());
    }

    private JsonNode submitAndApproveWrittenAnswer(String studentBearer, Long episodeId, String englishAnswer) throws Exception {
        String contentText = englishAnswer.length() >= 250 ? englishAnswer : (englishAnswer + " Furthermore the historical record demonstrates how scholarly transmission judicial reasoning and cultural institutions supported civil governance throughout this era maintaining consistent documentation and administrative coherence across multiple centuries while facilitating regional trade routes.");
        String submitJson = mockMvc.perform(post("/api/student/episodes/" + episodeId + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answerText", contentText))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();

        String queueJson = mockMvc.perform(get("/api/admin/open-answers?status=ALL")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode queue = objectMapper.readTree(queueJson);
        long subId = -1L;
        for (JsonNode item : queue) {
            if (item.get("episodeId").asLong() == episodeId) {
                subId = item.get("id").asLong();
                break;
            }
        }
        assertThat(subId).isPositive();

        mockMvc.perform(post("/api/admin/open-answers/" + subId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Well explained!\"}"))
                .andExpect(status().isOk());
        return objectMapper.readTree(submitJson);
    }

    private Map<String, Object> buildSessionSubmission(String bearer, Long episodeId, String watchMode, int targetCorrectCount) throws Exception {
        String quizViewJson = mockMvc.perform(get("/api/student/quizzes/" + episodeId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode viewNode = objectMapper.readTree(quizViewJson);
        assertThat(viewNode.get("ready").asBoolean()).isTrue();
        String sessionId = viewNode.get("sessionId").asText();
        JsonNode sessionQuestions = viewNode.get("questions");
        assertThat(sessionQuestions).hasSize(10);

        return transactionTemplate.execute(status -> {
            List<Question> allQuestions = questionRepository.findByEpisodeIdWithOptions(episodeId);
            Map<Long, Question> qMap = new HashMap<>();
            for (Question q : allQuestions) {
                qMap.put(q.getId(), q);
            }

            Map<String, Long> answers = new HashMap<>();
            for (int i = 0; i < sessionQuestions.size(); i++) {
                long qId = sessionQuestions.get(i).get("id").asLong();
                Question dbQ = qMap.get(qId);
                boolean wantCorrect = i < targetCorrectCount;
                QuestionOption chosen = dbQ.getOptions().stream()
                        .filter(opt -> Boolean.TRUE.equals(opt.getIsCorrect()) == wantCorrect)
                        .findFirst()
                        .orElseThrow();
                answers.put(String.valueOf(qId), chosen.getId());
            }
            return Map.of(
                    "sessionId", sessionId,
                    "episodeId", episodeId,
                    "watchMode", watchMode,
                    "answers", answers
            );
        });
    }
}
