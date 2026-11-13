// Verifies truncated JSON repair, segment fallbacks, async watch auto-generation, retry top-up, single reward, and admin UI.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.AiQuizService;
import com.arabicquest.service.AutoQuizGenerationService;
import com.arabicquest.service.GeminiJsonRepairSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.arabicquest.Phase5GeminiTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase5_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=phase5admin", "ADMIN_PASSWORD=Phase5AdminPass!2026", "ADMIN_SECRET_PATH=phase5-vault",
        "JWT_SECRET=phase5-jwt-secret-key-with-plenty-of-entropy-over-32-chars",
        "app.watch.required-cap-seconds=120", "app.quiz.retry-cooldown-seconds=600"
})
class Phase5AutoGenAndSingleRewardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiQuizService aiQuizService;
    @Autowired private AutoQuizGenerationService autoQuizGenerationService;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private OpenQuestionRepository openQuestionRepository;
    @Autowired private WatchSessionRepository watchSessionRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private RestTemplate originalRestTemplate;
    private String adminBearer;

    @BeforeEach
    void setup() throws Exception {
        originalRestTemplate = aiQuizService.getRestTemplate();
        aiQuizService.setGeminiApiKey("test-phase5-gemini-key");
        aiQuizService.setRetryBackoffMs(0L);
        aiQuizService.clearRateLimitBuckets();
        autoQuizGenerationService.clearLocks();
        autoQuizGenerationService.setAutoGenerateEnabled(true);
        MvcResult res = mockMvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr("10.50.0.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "XMLHttpRequest")
                        .content("{\"username\":\"phase5admin\",\"password\":\"Phase5AdminPass!2026\"}"))
                .andExpect(status().isOk()).andReturn();
        adminBearer = "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @AfterEach
    void tearDown() {
        if (originalRestTemplate != null) aiQuizService.setRestTemplate(originalRestTemplate);
        aiQuizService.setGeminiApiKey("");
        aiQuizService.clearRateLimitBuckets();
        autoQuizGenerationService.clearLocks();
    }

    @Test
    void jsonRepairParsesTruncatedPayloads_andVerifierFiltersInvalidMcqsWithMaxTokens() throws Exception {
        String truncatedPass1 = "```json\n{\"mcqs\":[{\"questionText\":\"Complete Q1?\",\"options\":["
                + "{\"optionText\":\"A1\",\"isCorrect\":true},{\"optionText\":\"B1\",\"isCorrect\":false},"
                + "{\"optionText\":\"C1\",\"isCorrect\":false},{\"optionText\":\"D1\",\"isCorrect\":false}]},"
                + "{\"questionText\":\"Partial cut mid string";
        JsonNode repairedObj = GeminiJsonRepairSupport.parseLenientJson(objectMapper, truncatedPass1);
        assertThat(repairedObj.path("mcqs")).hasSize(1);
        assertThat(repairedObj.path("mcqs").get(0).path("questionText").asText()).isEqualTo("Complete Q1?");
        assertThat(GeminiJsonRepairSupport.parseLenientJson(objectMapper, "{\"verifiedMcqIndices\":[0,1,2")
                .path("verifiedMcqIndices")).hasSize(3);

        Episode ep = createCourseAndEpisode(transactionTemplate, playlistRepository, episodeRepository, "PL_P5_REPAIR", "Ep Repair", 600, null);
        RestTemplate rt = new RestTemplate();
        aiQuizService.setRestTemplate(rt);
        MockRestServiceServer server = MockRestServiceServer.bindTo(rt).build();
        String bad = "{\"questionText\":\"Bad combo?\",\"type\":\"RECALL\",\"evidence\":\"ev\",\"evidenceTimestamp\":12,\"options\":["
                + "{\"optionText\":\"X\",\"isCorrect\":false},{\"optionText\":\"Y\",\"isCorrect\":false},"
                + "{\"optionText\":\"Z\",\"isCorrect\":false},{\"optionText\":\"All of the above\",\"isCorrect\":true}]},"
                + "{\"questionText\":\"Bad both?\",\"type\":\"RECALL\",\"evidence\":\"ev\",\"evidenceTimestamp\":15,\"options\":["
                + "{\"optionText\":\"A\",\"isCorrect\":false},{\"optionText\":\"B\",\"isCorrect\":false},"
                + "{\"optionText\":\"C\",\"isCorrect\":false},{\"optionText\":\"Both A and B\",\"isCorrect\":true}]},";
        server.expect(once(), MockRestRequestMatchers.requestTo(containsString("gemini-2.5-flash:generateContent")))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.content().string(containsString("\"maxOutputTokens\":16384")))
                .andRespond(withSuccess(wrapGeminiText(objectMapper,
                        buildPass1Json(objectMapper, "Repair", 11, 2).replace("\"mcqs\":[", "\"mcqs\":[" + bad)), MediaType.APPLICATION_JSON));
        server.expect(once(), MockRestRequestMatchers.requestTo(containsString("gemini-2.5-flash:generateContent")))
                .andRespond(withSuccess(wrapGeminiText(objectMapper, buildPass2Json(objectMapper, 11, 2)), MediaType.APPLICATION_JSON));

        AiQuizService.GeneratedBankSummary summary = aiQuizService.generateQuestionBankForEpisode("phase5admin", ep.getId(), null, false, false);
        server.verify();
        assertThat(summary.savedDraftMcqs()).isEqualTo(11);
        assertThat(summary.savedDraftOpenQuestions()).isEqualTo(2);
    }

    @Test
    void segmentVideoFailureFallsBackToTranscript_andRetriesSmallerBatchWithoutTranscript() {
        Episode withTranscript = createCourseAndEpisode(transactionTemplate, playlistRepository, episodeRepository,
                "PL_P5_TRANS", "Ep Transcript Fallback", 600,
                "Historian explains textual chains across Baghdad and Cordoba in detail.");
        RestTemplate rt1 = new RestTemplate();
        aiQuizService.setRestTemplate(rt1);
        MockRestServiceServer s1 = MockRestServiceServer.bindTo(rt1).build();
        s1.expect(once(), MockRestRequestMatchers.requestTo(containsString(":generateContent"))).andRespond(withBadRequest());
        s1.expect(once(), MockRestRequestMatchers.requestTo(containsString(":generateContent")))
                .andExpect(MockRestRequestMatchers.content().string(containsString("Baghdad and Cordoba")))
                .andRespond(withSuccess(wrapGeminiText(objectMapper, buildPass1Json(objectMapper, "TrFallback", 10, 1)), MediaType.APPLICATION_JSON));
        s1.expect(once(), MockRestRequestMatchers.requestTo(containsString(":generateContent")))
                .andRespond(withSuccess(wrapGeminiText(objectMapper, buildPass2Json(objectMapper, 10, 1)), MediaType.APPLICATION_JSON));
        assertThat(aiQuizService.generateQuestionBankForEpisode("phase5admin", withTranscript.getId(), null, false, false).savedDraftMcqs()).isEqualTo(10);
        s1.verify();

        Episode noTranscript = createCourseAndEpisode(transactionTemplate, playlistRepository, episodeRepository, "PL_P5_RETRY", "Ep Smaller Retry", 600, null);
        RestTemplate rt2 = new RestTemplate();
        aiQuizService.setRestTemplate(rt2);
        MockRestServiceServer s2 = MockRestServiceServer.bindTo(rt2).build();
        s2.expect(times(3), MockRestRequestMatchers.requestTo(containsString(":generateContent"))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        s2.expect(once(), MockRestRequestMatchers.requestTo(containsString(":generateContent")))
                .andRespond(withSuccess(wrapGeminiText(objectMapper, buildPass1Json(objectMapper, "Smaller", 10, 1)), MediaType.APPLICATION_JSON));
        s2.expect(once(), MockRestRequestMatchers.requestTo(containsString(":generateContent")))
                .andRespond(withSuccess(wrapGeminiText(objectMapper, buildPass2Json(objectMapper, 10, 1)), MediaType.APPLICATION_JSON));
        assertThat(aiQuizService.generateQuestionBankForEpisode("phase5admin", noTranscript.getId(), null, false, false).savedDraftMcqs()).isEqualTo(10);
        s2.verify();
    }

    @Test
    void autoGenTriggersOnWatchStartAndQuarter_blocksDuplicates_andAutoApproves() throws Exception {
        Episode ep = createCourseAndEpisode(transactionTemplate, playlistRepository, episodeRepository, "PL_P5_AUTO", "Ep Auto Watch", 600, null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(0);
        aiQuizService.setRestTemplate(buildAsyncMockRestTemplate(objectMapper, "Auto", 12, 2, entered, release, calls, null));

        String bearer = createStudentAndLogin(mockMvc, objectMapper, adminBearer, "p5auto", "10.50.1.1");
        mockMvc.perform(post("/api/student/episodes/" + ep.getId() + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playing\":true,\"watchedDeltaSeconds\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchStarted").value(true))
                .andExpect(jsonPath("$.modeLocked").value(false));

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(autoQuizGenerationService.isEpisodeInProgress(ep.getId())).isTrue();
        assertThat(autoQuizGenerationService.triggerIfEpisodeBankIncomplete(ep.getId())).isFalse();
        release.countDown();
        waitUntilEpisodeBankReady(ep.getId(), 10, 1);
        assertThat(calls.get()).isEqualTo(2);

        Episode qEp = createCourseAndEpisode(transactionTemplate, playlistRepository, episodeRepository, "PL_P5_QTR", "Ep Quarter", 600, null);
        User st = userRepository.findByUsername("p5auto").orElseThrow();
        WatchSession ws = new WatchSession(st, qEp, WatchMode.ARABIC_SUB);
        ws.setVerifiedWatchSeconds(20);
        ws.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(20));
        watchSessionRepository.save(ws);
        CountDownLatch qEntered = new CountDownLatch(1);
        aiQuizService.setRestTemplate(buildAsyncMockRestTemplate(objectMapper, "Quarter", 12, 2, qEntered, new CountDownLatch(0), new AtomicInteger(), null));
        mockMvc.perform(post("/api/student/episodes/" + qEp.getId() + "/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"playing\":true,\"watchedDeltaSeconds\":15}"))
                .andExpect(status().isOk());
        assertThat(qEntered.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntilEpisodeBankReady(qEp.getId(), 10, 1);
    }

    @Test
    void quizRetryDrawsFreshUnseenQuestions_andTriggersDeduplicatedTopUp() throws Exception {
        Episode ep = createCourseAndEpisode(transactionTemplate, playlistRepository, episodeRepository, "PL_P5_RETRYQ", "Ep Retry Fresh", 600, null);
        String bearer = createStudentAndLogin(mockMvc, objectMapper, adminBearer, "p5retry", "10.50.1.2");
        User student = userRepository.findByUsername("p5retry").orElseThrow();
        WatchSession ws = new WatchSession(student, ep, WatchMode.ARABIC_SUB);
        ws.setVerifiedWatchSeconds(120);
        watchSessionRepository.save(ws);

        AtomicReference<String> capturedTopUpPrompt = new AtomicReference<>();
        aiQuizService.setRestTemplate(buildAsyncMockRestTemplate(objectMapper, "TopUp", 12, 1,
                new CountDownLatch(1), new CountDownLatch(0), new AtomicInteger(), capturedTopUpPrompt));
        seedApprovedBank(transactionTemplate, episodeRepository, quizRepository, questionRepository, openQuestionRepository, ep.getId(), 12);

        JsonNode quiz1 = objectMapper.readTree(mockMvc.perform(get("/api/student/quizzes/" + ep.getId()).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(quiz1.get("questions")).hasSize(10);
        waitUntilEpisodeBankReady(ep.getId(), 20, 1);
        assertThat(capturedTopUpPrompt.get()).contains("Avoid duplicating or rephrasing").contains("Seed Q1?");

        Set<Long> q1Ids = new HashSet<>();
        Map<Long, Long> wrongAnswers = new LinkedHashMap<>();
        for (JsonNode qn : quiz1.get("questions")) {
            q1Ids.add(qn.get("id").asLong());
            JsonNode opts = qn.get("options");
            wrongAnswers.put(qn.get("id").asLong(), opts.get(0).get("optionText").asText().startsWith("Seed Right")
                    ? opts.get(1).get("id").asLong() : opts.get(0).get("id").asLong());
        }
        mockMvc.perform(post("/api/student/quizzes/submit").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", quiz1.get("sessionId").asText(), "episodeId", ep.getId(), "answers", wrongAnswers))))
                .andExpect(status().isOk());
        quizAttemptRepository.deleteAll();

        JsonNode quiz2 = objectMapper.readTree(mockMvc.perform(get("/api/student/quizzes/" + ep.getId()).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        waitUntilEpisodeBankReady(ep.getId(), 20, 1);
        Set<Long> q2Ids = new HashSet<>();
        quiz2.get("questions").forEach(qn -> q2Ids.add(qn.get("id").asLong()));
        assertThat(q2Ids).hasSize(10).doesNotContainAnyElementsOf(q1Ids);
    }

    @Test
    void singleEpisodeRewardApi_andAdminAndStudentUiVerification() throws Exception {
        mockMvc.perform(post("/api/admin/courses").header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Single Reward Course\",\"episodeReward\":18.50,\"completionBonus\":50.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeReward").value(18.50))
                .andExpect(jsonPath("$.rateArabicSub").value(18.50))
                .andExpect(jsonPath("$.rateEnglishDub").value(18.50));

        assertThat(Files.readString(Path.of("src/main/resources/private-hq/admin.html")))
                .contains("data-tab=\"question-bank\"", "id=\"tab-panel-question-bank\"", "id=\"yt-draft-episode-reward\"", "id=\"edit-course-episode-reward\"")
                .doesNotContain("Arabic Sub Rate", "English Dub Rate");
        assertThat(Files.readString(Path.of("src/main/resources/static/index.html")))
                .contains("id=\"episode-reward-badge\"").doesNotContain("btn-mode-arabic", "btn-mode-english", "Reset Timer &amp; Switch Mode");
        assertThat(Files.readString(Path.of("src/main/resources/static/app.js"))).doesNotContain("innerHTML");
        assertThat(Files.readString(Path.of("src/main/resources/private-hq/admin.js"))).doesNotContain("innerHTML");
        assertThat(Files.readString(Path.of("src/main/resources/private-hq/pages/question-bank-manager.js"))).doesNotContain("innerHTML");
    }

    private void waitUntilEpisodeBankReady(Long epId, long minMcqs, long minOpens) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && (autoQuizGenerationService.isEpisodeInProgress(epId)
                || questionRepository.countByQuizEpisodeIdAndStatusAndActiveTrue(epId, QuestionStatus.APPROVED) < minMcqs
                || openQuestionRepository.countByEpisodeIdAndStatusAndActiveTrue(epId, QuestionStatus.APPROVED) < minOpens)) {
            Thread.sleep(40);
        }
        assertThat(questionRepository.countByQuizEpisodeIdAndStatusAndActiveTrue(epId, QuestionStatus.APPROVED)).isGreaterThanOrEqualTo(minMcqs);
        assertThat(openQuestionRepository.countByEpisodeIdAndStatusAndActiveTrue(epId, QuestionStatus.APPROVED)).isGreaterThanOrEqualTo(minOpens);
    }
}
