package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase3_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase3admin",
        "ADMIN_PASSWORD=Phase3AdminPass!2026",
        "ADMIN_SECRET_PATH=phase3-vault",
        "JWT_SECRET=phase3-jwt-secret-key-with-plenty-of-entropy-over-32-chars",
        "app.watch.required-cap-seconds=1800",
        "app.quiz.retry-cooldown-seconds=600"
})
class Phase3LockingStatesAndModeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private OpenQuestionRepository openQuestionRepository;

    @Autowired
    private WatchSessionRepository watchSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String adminBearer;

    @BeforeEach
    void loginAdmin() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr("10.30.0.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .content("{\"username\":\"phase3admin\",\"password\":\"Phase3AdminPass!2026\"}"))
                .andExpect(status().isOk())
                .andReturn();
        this.adminBearer = "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void sequentialLockingIsPerCourse_twoCoursesUnlockIndependently() throws Exception {
        Long[] courseA = createCourseWithEpisodes("PL_P3_COURSE_A", "Umayyad History", 2,
                new BigDecimal("15.00"), new BigDecimal("10.00"));
        Long[] courseB = createCourseWithEpisodes("PL_P3_COURSE_B", "Abbasid Golden Age", 2,
                new BigDecimal("18.00"), new BigDecimal("12.00"));

        String studentBearer = createAndLoginStudent("twocoursebro", "TwoCourse!123", "Two Course Brother", "10.30.1.1");

        Map<Long, JsonNode> allEps = getStudentEpisodesById(studentBearer);

        // Course A Ep 1 AND Course B Ep 1 must BOTH be AVAILABLE (Course B Ep 1 is NOT blocked by unpassed Course A!)
        assertThat(allEps.get(courseA[1]).get("locked").asBoolean()).isFalse();
        assertThat(allEps.get(courseA[1]).get("state").asText()).isEqualTo("AVAILABLE");
        assertThat(allEps.get(courseA[2]).get("locked").asBoolean()).isTrue();
        assertThat(allEps.get(courseA[2]).get("state").asText()).isEqualTo("LOCKED");
        assertThat(allEps.get(courseA[2]).get("unlockAfterEpisodeNumber").asInt()).isEqualTo(1);

        assertThat(allEps.get(courseB[1]).get("locked").asBoolean()).isFalse();
        assertThat(allEps.get(courseB[1]).get("state").asText()).isEqualTo("AVAILABLE");
        assertThat(allEps.get(courseB[2]).get("locked").asBoolean()).isTrue();
        assertThat(allEps.get(courseB[2]).get("state").asText()).isEqualTo("LOCKED");
        assertThat(allEps.get(courseB[2]).get("unlockAfterEpisodeNumber").asInt()).isEqualTo(1);

        // Student can start watching Course B Episode 1 immediately while Course A is completely untouched
        mockMvc.perform(post("/api/student/episodes/" + courseB[1] + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"playing\":true,\"watchedDeltaSeconds\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modeLocked").value(true));

        // Course B Episode 2 remains locked (403) until Course B Episode 1 is passed
        mockMvc.perform(post("/api/student/episodes/" + courseB[2] + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"playing\":true,\"watchedDeltaSeconds\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allSevenStudentStates_andAdminPerStudentUnlockAndLockOverrides() throws Exception {
        Long[] course = createCourseWithEpisodes("PL_P3_STATES", "Andalusian Chronicles", 3,
                new BigDecimal("15.00"), new BigDecimal("10.00"));
        long ep1Id = course[1];
        long ep2Id = course[2];
        long ep3Id = course[3];

        String studentBearer = createAndLoginStudent("statesbrother", "StatesPass!123", "States Brother", "10.30.1.2");
        User student = userRepository.findByUsername("statesbrother").orElseThrow();

        // 1. Initial states: Ep 1 = AVAILABLE ("Available"), Ep 2 & Ep 3 = LOCKED ("Locked")
        Map<Long, JsonNode> eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep1Id).get("state").asText()).isEqualTo("AVAILABLE");
        assertThat(eps.get(ep1Id).get("stateLabel").asText()).isEqualTo("Available");
        assertThat(eps.get(ep2Id).get("state").asText()).isEqualTo("LOCKED");
        assertThat(eps.get(ep2Id).get("stateLabel").asText()).isEqualTo("Locked");

        // 2. Start playback on Ep 1 -> WATCHING ("Watching")
        mockMvc.perform(post("/api/student/episodes/" + ep1Id + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"playing\":true,\"watchedDeltaSeconds\":0}"))
                .andExpect(status().isOk());

        eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep1Id).get("state").asText()).isEqualTo("WATCHING");
        assertThat(eps.get(ep1Id).get("stateLabel").asText()).isEqualTo("Watching");

        // 3. Complete watch requirement on Ep 1 BEFORE question bank is ready -> QUIZ_NOT_READY ("Quiz not ready (coming soon)")
        completeWatchInDb(student, ep1Id, WatchMode.ARABIC_SUB);
        eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep1Id).get("state").asText()).isEqualTo("QUIZ_NOT_READY");
        assertThat(eps.get(ep1Id).get("stateLabel").asText()).isEqualTo("Quiz not ready (coming soon)");

        // 4. Seed 10 approved MCQs + 1 approved OpenQuestion on Ep 1 -> QUIZ_READY ("Quiz ready")
        Map<String, String> correctByQuestionText = seedApprovedBank(ep1Id, "P3_EP1");
        eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep1Id).get("state").asText()).isEqualTo("QUIZ_READY");
        assertThat(eps.get(ep1Id).get("stateLabel").asText()).isEqualTo("Quiz ready");

        // 5. Pass 10/10 MCQ and submit written answer -> PENDING_REVIEW ("Pending review")
        passMcqAndSubmitWrittenAnswer(studentBearer, ep1Id, WatchMode.ARABIC_SUB, correctByQuestionText);
        eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep1Id).get("state").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(eps.get(ep1Id).get("stateLabel").asText()).isEqualTo("Pending review");
        // Ep 2 is STILL locked while Ep 1 is only pending review!
        assertThat(eps.get(ep2Id).get("state").asText()).isEqualTo("LOCKED");

        // 6. Admin approves written answer -> Ep 1 becomes PASSED ("Passed") and Ep 2 unlocks (AVAILABLE)
        String queueBody = mockMvc.perform(get("/api/admin/open-answers?status=PENDING")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long subId = objectMapper.readTree(queueBody).get(0).get("id").asLong();

        mockMvc.perform(post("/api/admin/open-answers/" + subId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Well argued.\"}"))
                .andExpect(status().isOk());

        eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep1Id).get("state").asText()).isEqualTo("PASSED");
        assertThat(eps.get(ep1Id).get("stateLabel").asText()).isEqualTo("Passed");
        assertThat(eps.get(ep2Id).get("state").asText()).isEqualTo("AVAILABLE");
        assertThat(eps.get(ep3Id).get("state").asText()).isEqualTo("LOCKED");

        // 7. Student cannot call admin override endpoints (403)
        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/episodes/" + ep3Id + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());

        // 8. Admin unlocks Episode 3 directly for this student -> Ep 3 becomes AVAILABLE and accessible
        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/episodes/" + ep3Id + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideState").value("UNLOCKED"));

        eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep3Id).get("locked").asBoolean()).isFalse();
        assertThat(eps.get(ep3Id).get("state").asText()).isEqualTo("AVAILABLE");

        mockMvc.perform(post("/api/student/episodes/" + ep3Id + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"playing\":true,\"watchedDeltaSeconds\":0}"))
                .andExpect(status().isOk());

        // 9. Admin locks Episode 3 (and Episode 2) again -> student is blocked with 403
        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/episodes/" + ep3Id + "/lock")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideState").value("LOCKED"));

        mockMvc.perform(post("/api/admin/students/" + student.getId() + "/episodes/" + ep2Id + "/lock")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk());

        eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep2Id).get("locked").asBoolean()).isTrue();
        assertThat(eps.get(ep3Id).get("locked").asBoolean()).isTrue();

        mockMvc.perform(post("/api/student/episodes/" + ep3Id + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"playing\":true,\"watchedDeltaSeconds\":5}"))
                .andExpect(status().isForbidden());

        // 10. Clearing Ep 2 override restores sequential auto-unlock (AVAILABLE since Ep 1 is passed)
        mockMvc.perform(delete("/api/admin/students/" + student.getId() + "/episodes/" + ep2Id + "/override")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideState").value("AUTO"));

        eps = getStudentEpisodesById(studentBearer);
        assertThat(eps.get(ep2Id).get("locked").asBoolean()).isFalse();
    }

    @Test
    void modeLocksOncePlaybackStarts_andPaysCourseRateForChosenMode() throws Exception {
        Long[] course = createCourseWithEpisodes("PL_P3_MODES", "Levantine History", 1,
                new BigDecimal("22.50"), new BigDecimal("13.50"));
        long epId = course[1];
        Map<String, String> correctByQuestionText = seedApprovedBank(epId, "P3_MODE_EP1");

        String studentBearer = createAndLoginStudent("modebrother", "ModePass!123", "Mode Brother", "10.30.1.3");
        User student = userRepository.findByUsername("modebrother").orElseThrow();

        // Start playback in ENGLISH_DUB -> mode locks to ENGLISH_DUB
        mockMvc.perform(post("/api/student/episodes/" + epId + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ENGLISH_DUB\",\"playing\":true,\"watchedDeltaSeconds\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modeLocked").value(true))
                .andExpect(jsonPath("$.lockedWatchMode").value("ENGLISH_DUB"));

        // Subsequent heartbeat attempting ARABIC_SUB without resetting session stays locked to ENGLISH_DUB
        mockMvc.perform(post("/api/student/episodes/" + epId + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchMode\":\"ARABIC_SUB\",\"playing\":true,\"watchedDeltaSeconds\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modeLocked").value(true))
                .andExpect(jsonPath("$.lockedWatchMode").value("ENGLISH_DUB"));

        // Complete watch in locked ENGLISH_DUB mode
        completeWatchInDb(student, epId, WatchMode.ENGLISH_DUB);

        // Even if student spoofs watchMode="ARABIC_SUB" on quiz submit, server enforces locked ENGLISH_DUB ($13.50)
        passMcqAndSubmitWrittenAnswer(studentBearer, epId, WatchMode.ARABIC_SUB, correctByQuestionText);

        // After passing MCQ, resetting watch session to switch mode is rejected (400)
        mockMvc.perform(post("/api/student/episodes/" + epId + "/reset-session?watchMode=ARABIC_SUB")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isBadRequest());

        // Pending review shows $13.50 (the course's ENGLISH_DUB rate)
        mockMvc.perform(get("/api/student/earnings")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned").value(0.0))
                .andExpect(jsonPath("$.pendingReviewAmount").value(13.50));

        // Admin approves -> student earns $13.50 (+ $100.00 course bonus if 1-episode minEpisodesForBonus=1; here minEpisodesForBonus=12 so $13.50)
        String queueBody = mockMvc.perform(get("/api/admin/open-answers?status=PENDING")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long subId = objectMapper.readTree(queueBody).get(0).get("id").asLong();

        mockMvc.perform(post("/api/admin/open-answers/" + subId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Approved in English Dub mode.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeRewardAmount").value(13.50));

        mockMvc.perform(get("/api/student/earnings")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEarned").value(13.50))
                .andExpect(jsonPath("$.pendingReviewAmount").value(0.0));
    }

    // --- Helpers ---

    private Long[] createCourseWithEpisodes(
            String ytId,
            String title,
            int episodeCount,
            BigDecimal rateAr,
            BigDecimal rateEn) {
        return transactionTemplate.execute(status -> {
            Playlist p = new Playlist(title, ytId, "Phase 3 test course");
            p.setPublished(true);
            p.setArabicRewardRate(rateAr);
            p.setEnglishRewardRate(rateEn);
            p.setCompletionBonus(new BigDecimal("100.00"));
            p.setMinEpisodesForBonus(12);
            p = playlistRepository.save(p);

            Long[] ids = new Long[episodeCount + 1];
            ids[0] = p.getId();
            for (int i = 1; i <= episodeCount; i++) {
                Episode ep = episodeRepository.save(new Episode(
                        p,
                        i,
                        title + " - Episode " + i,
                        "vid" + ytId.substring(Math.max(0, ytId.length() - 4)) + "0" + i,
                        1800
                ));
                ids[i] = ep.getId();
            }
            return ids;
        });
    }

    private Map<String, String> seedApprovedBank(long episodeId, String prefix) {
        return transactionTemplate.execute(status -> {
            Episode ep = episodeRepository.findById(episodeId).orElseThrow();
            Quiz quiz = quizRepository.findByEpisodeId(episodeId).orElseGet(() -> quizRepository.save(new Quiz(ep)));
            Map<String, String> correctByQ = new HashMap<>();
            for (int i = 1; i <= 12; i++) {
                String qText = prefix + " Question #" + i + "?";
                String correctText = prefix + " Correct #" + i;
                Question q = new Question(quiz, qText, i);
                q.setStatus(QuestionStatus.APPROVED);
                q.setActive(true);
                q.setType(QuestionType.RECALL);
                q.setEvidence("Transcript evidence #" + i);
                q.getOptions().add(new QuestionOption(q, correctText, true));
                q.getOptions().add(new QuestionOption(q, prefix + " Wrong A #" + i, false));
                q.getOptions().add(new QuestionOption(q, prefix + " Wrong B #" + i, false));
                q.getOptions().add(new QuestionOption(q, prefix + " Wrong C #" + i, false));
                quiz.getQuestions().add(q);
                correctByQ.put(qText, correctText);
            }
            quizRepository.save(quiz);

            OpenQuestion oq = new OpenQuestion(
                    ep,
                    "Explain the historical significance of " + prefix + " in your own words.",
                    "Open question transcript evidence",
                    QuestionStatus.APPROVED
            );
            openQuestionRepository.save(oq);
            return correctByQ;
        });
    }

    private void completeWatchInDb(User student, long episodeId, WatchMode mode) {
        transactionTemplate.executeWithoutResult(status -> {
            Episode ep = episodeRepository.findById(episodeId).orElseThrow();
            WatchSession ws = watchSessionRepository.findByStudentIdAndEpisodeId(student.getId(), episodeId)
                    .orElseGet(() -> new WatchSession(student, ep, mode));
            ws.setLockedWatchMode(mode);
            ws.setModeLocked(true);
            ws.setVerifiedWatchSeconds(1800);
            ws.setVideoEnded(true);
            watchSessionRepository.save(ws);
        });
    }

    private void passMcqAndSubmitWrittenAnswer(
            String studentBearer,
            long episodeId,
            WatchMode submittedModeInPayload,
            Map<String, String> correctByQuestionText) throws Exception {
        String viewJson = mockMvc.perform(get("/api/student/quizzes/" + episodeId)
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode view = objectMapper.readTree(viewJson);
        String sessionId = view.get("sessionId").asText();

        Map<Long, Long> answers = new HashMap<>();
        for (JsonNode qNode : view.get("questions")) {
            long qId = qNode.get("id").asLong();
            String expectedCorrect = correctByQuestionText.get(qNode.get("questionText").asText());
            for (JsonNode optNode : qNode.get("options")) {
                if (expectedCorrect.equals(optNode.get("optionText").asText())) {
                    answers.put(qId, optNode.get("id").asLong());
                }
            }
        }

        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId,
                                "episodeId", episodeId,
                                "watchMode", submittedModeInPayload.name(),
                                "answers", answers
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        mockMvc.perform(post("/api/student/episodes/" + episodeId + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "answerText", "This episode illustrates how governance, trade routes, and scholarship strengthened historical continuity across generations."
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("PENDING"));
    }

    private Map<Long, JsonNode> getStudentEpisodesById(String studentBearer) throws Exception {
        String body = mockMvc.perform(get("/api/student/episodes")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = objectMapper.readTree(body);
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode ep : arr) {
            byId.put(ep.get("id").asLong(), ep);
        }
        return byId;
    }

    private String createAndLoginStudent(String username, String password, String fullName, String ip) throws Exception {
        mockMvc.perform(post("/api/admin/students")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "fullName", fullName
                        ))))
                .andExpect(status().isOk());

        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .with(req -> { req.setRemoteAddr(ip); return req; })
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + objectMapper.readTree(loginRes.getResponse().getContentAsString()).get("token").asText();
    }
}
