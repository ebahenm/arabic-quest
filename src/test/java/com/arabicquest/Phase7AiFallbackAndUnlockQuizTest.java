// Verifies Gemini 400/403 recovery via YouTube captions & OpenAI fallback, challenging prompts, and admin unlock-quiz.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.arabicquest.Phase5GeminiTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase7_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_PASSWORD=Phase7Admin!Password99",
        "ADMIN_USERNAME=phase7admin",
        "ADMIN_SECRET_PATH=phase7-vault",
        "JWT_SECRET=phase7-super-secret-jwt-key-32chars!",
        "GEMINI_API_KEY=AIzaSyMockGeminiSecretKey9999999",
        "GEMINI_MODEL=gemini-2.5-flash",
        "FALLBACK_AI_API_KEY=",
        "app.ai.retry-backoff-ms=0"
})
class Phase7AiFallbackAndUnlockQuizTest {

    private static final String GEMINI_SECRET = "AIzaSyMockGeminiSecretKey9999999";
    private static final String FALLBACK_SECRET = "sk-fallback-secret-key-987654321";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate tx;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private OpenQuestionRepository openQuestionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AiQuizService aiQuizService;
    @Autowired private YouTubeCaptionService youTubeCaptionService;
    @Autowired private FallbackAiClientService fallbackAiClientService;

    private String adminBearer;
    private RestTemplate origGeminiRt;
    private RestTemplate origCaptionRt;
    private RestTemplate origFallbackRt;

    @BeforeEach
    void setup() throws Exception {
        origGeminiRt = aiQuizService.getRestTemplate();
        origCaptionRt = youTubeCaptionService.getRestTemplate();
        origFallbackRt = fallbackAiClientService.getRestTemplate();
        aiQuizService.setGeminiApiKey(GEMINI_SECRET);
        aiQuizService.setRetryBackoffMs(0);
        aiQuizService.clearRateLimitBuckets();
        fallbackAiClientService.setApiKey("");
        fallbackAiClientService.setBaseUrl("https://mock-openai.example/v1");
        fallbackAiClientService.setModel("gpt-4.1");
        fallbackAiClientService.setRetryBackoffMs(0);
        if (adminBearer == null) {
            String body = mockMvc.perform(post("/api/auth/login")
                            .header("X-Requested-With", "XMLHttpRequest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"phase7admin\",\"password\":\"Phase7Admin!Password99\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            adminBearer = "Bearer " + objectMapper.readTree(body).get("token").asText();
        }
    }

    @AfterEach
    void restoreDefaults() {
        aiQuizService.setRestTemplate(origGeminiRt);
        aiQuizService.setGeminiApiKey(GEMINI_SECRET);
        youTubeCaptionService.setRestTemplate(origCaptionRt);
        fallbackAiClientService.setRestTemplate(origFallbackRt);
        fallbackAiClientService.setApiKey("");
    }

    @Test
    void gemini403SurfacesSanitizedDetail_andRecoversViaYouTubeCaptionsAndOpenAiFallback() throws Exception {
        String leakedSample = "Denied at https://x.test?key=abc123 with " + GEMINI_SECRET + " and Bearer tok99 " + FALLBACK_SECRET;
        String sanitized = AiErrorSanitizer.sanitizeText(leakedSample, GEMINI_SECRET, FALLBACK_SECRET);
        assertThat(sanitized).doesNotContain(GEMINI_SECRET, FALLBACK_SECRET, "tok99", "abc123").contains("[REDACTED]");

        String permDeniedBody = "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\","
                + "\"message\":\"API key " + GEMINI_SECRET + " blocked for Video API\"}}";

        Episode noCapEp = createCourseAndEpisode(tx, playlistRepository, episodeRepository, "PL_P7_403A", "Ep Blocked NoCap", 600, null);
        RestTemplate gRt1 = new RestTemplate(), cRt1 = new RestTemplate();
        aiQuizService.setRestTemplate(gRt1);
        youTubeCaptionService.setRestTemplate(cRt1);
        MockRestServiceServer.bindTo(gRt1).build().expect(once(), MockRestRequestMatchers.requestTo(containsString(":generateContent")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body(permDeniedBody).contentType(MediaType.APPLICATION_JSON));
        MockRestServiceServer.bindTo(cRt1).build().expect(once(), MockRestRequestMatchers.requestTo(containsString("youtube.com/watch")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        mockMvc.perform(post("/api/admin/episodes/" + noCapEp.getId() + "/generate-bank")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("PERMISSION_DENIED")))
                .andExpect(jsonPath("$.error", not(containsString(GEMINI_SECRET))));

        Episode capEp = createCourseAndEpisode(tx, playlistRepository, episodeRepository, "PL_P7_403B", "Ep Captions OpenAI", 600, null);
        fallbackAiClientService.setApiKey(FALLBACK_SECRET);
        RestTemplate gRt2 = new RestTemplate(), cRt2 = new RestTemplate(), fRt2 = new RestTemplate();
        aiQuizService.setRestTemplate(gRt2);
        youTubeCaptionService.setRestTemplate(cRt2);
        fallbackAiClientService.setRestTemplate(fRt2);

        MockRestServiceServer gSrv2 = MockRestServiceServer.bindTo(gRt2).build();
        gSrv2.expect(times(2), MockRestRequestMatchers.requestTo(containsString(":generateContent")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body(permDeniedBody).contentType(MediaType.APPLICATION_JSON));

        String watchHtml = "<html><script>var p={\"captionTracks\":[{\"baseUrl\":\"https://www.youtube.com/api/timedtext?v=test&lang=en\",\"languageCode\":\"en\"}]};</script></html>";
        String json3Caps = "{\"events\":[{\"tStartMs\":1000,\"segs\":[{\"utf8\":\"Bayt al-Hikma coordinated translations in Baghdad.\"}]},"
                + "{\"tStartMs\":35000,\"segs\":[{\"utf8\":\"Scholars verified planetary models across Cordoba.\"}]}]}";
        MockRestServiceServer cSrv2 = MockRestServiceServer.bindTo(cRt2).build();
        cSrv2.expect(once(), MockRestRequestMatchers.requestTo(containsString("youtube.com/watch")))
                .andRespond(withSuccess(watchHtml, MediaType.TEXT_HTML));
        cSrv2.expect(once(), MockRestRequestMatchers.requestTo(containsString("youtube.com/api/timedtext")))
                .andRespond(withSuccess(json3Caps, MediaType.APPLICATION_JSON));

        String openaiP1 = objectMapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", buildPass1Json(objectMapper, "Fallback", 11, 1))))));
        String openaiP2 = objectMapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", buildPass2Json(objectMapper, 11, 1))))));
        MockRestServiceServer fSrv2 = MockRestServiceServer.bindTo(fRt2).build();
        fSrv2.expect(once(), MockRestRequestMatchers.requestTo("https://mock-openai.example/v1/chat/completions"))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer " + FALLBACK_SECRET))
                .andExpect(MockRestRequestMatchers.content().string(containsString("gpt-4.1")))
                .andExpect(MockRestRequestMatchers.content().string(containsString("Bayt al-Hikma")))
                .andRespond(withSuccess(openaiP1, MediaType.APPLICATION_JSON));
        fSrv2.expect(once(), MockRestRequestMatchers.requestTo("https://mock-openai.example/v1/chat/completions"))
                .andExpect(MockRestRequestMatchers.content().string(containsString("Candidate MCQs")))
                .andRespond(withSuccess(openaiP2, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/admin/episodes/" + capEp.getId() + "/generate-bank")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedDraftMcqs").value(11))
                .andExpect(jsonPath("$.savedDraftOpenQuestions").value(1));

        gSrv2.verify();
        cSrv2.verify();
        fSrv2.verify();
        Episode reloaded = episodeRepository.findById(capEp.getId()).orElseThrow();
        assertThat(reloaded.getTranscriptText()).contains("Bayt al-Hikma", "[0:35]");
    }

    @Test
    void savedTranscriptSkipsFileData_usesChallengingPromptsAndAutoApprovesBank() {
        Episode ep = createCourseAndEpisode(tx, playlistRepository, episodeRepository, "PL_P7_TR",
                "Ep Toledo Astronomy", 900, "Toledo and Cordoba astronomers refined astronomical tables and optics.");
        RestTemplate rt = new RestTemplate();
        aiQuizService.setRestTemplate(rt);
        MockRestServiceServer srv = MockRestServiceServer.bindTo(rt).build();
        srv.expect(once(), MockRestRequestMatchers.requestTo(containsString(":generateContent")))
                .andExpect(MockRestRequestMatchers.content().string(not(containsString("file_data"))))
                .andExpect(MockRestRequestMatchers.content().string(containsString("60%")))
                .andExpect(MockRestRequestMatchers.content().string(containsString("cause-and-effect")))
                .andExpect(MockRestRequestMatchers.content().string(containsString("chronology")))
                .andExpect(MockRestRequestMatchers.content().string(containsString("plausible distractors")))
                .andExpect(MockRestRequestMatchers.content().string(containsString("multi-point")))
                .andRespond(withSuccess(wrapGeminiText(objectMapper, buildPass1Json(objectMapper, "Challenging", 10, 1)), MediaType.APPLICATION_JSON));
        srv.expect(once(), MockRestRequestMatchers.requestTo(containsString(":generateContent")))
                .andExpect(MockRestRequestMatchers.content().string(not(containsString("file_data"))))
                .andExpect(MockRestRequestMatchers.content().string(containsString("multi-point")))
                .andRespond(withSuccess(wrapGeminiText(objectMapper, buildPass2Json(objectMapper, 10, 1)), MediaType.APPLICATION_JSON));

        AiQuizService.GeneratedBankSummary summary = aiQuizService.generateQuestionBankForEpisode(ep.getId());
        srv.verify();
        assertThat(summary.savedDraftMcqs()).isGreaterThanOrEqualTo(10);
        assertThat(questionRepository.findByEpisodeIdWithOptions(ep.getId()))
                .hasSize(10).allMatch(q -> q.getStatus() == QuestionStatus.APPROVED && q.isActive());
    }

    @Test
    void adminUnlockQuizUnlocksEpisodeAndAllowsImmediateSubmission() throws Exception {
        Episode[] eps = tx.execute(st -> {
            Playlist p = new Playlist("Unlock Course", "PL_P7_UNLOCK", "Unlock test");
            p.setPublished(true);
            p = playlistRepository.save(p);
            Episode e1 = episodeRepository.save(new Episode(p, 1, "Ep One", "vidP7One11", 1800));
            Episode e2 = episodeRepository.save(new Episode(p, 2, "Ep Two", "vidP7Two22", 1800));
            return new Episode[]{e1, e2};
        });
        seedApprovedBank(tx, episodeRepository, quizRepository, questionRepository, openQuestionRepository, eps[0].getId(), 10);
        seedApprovedBank(tx, episodeRepository, quizRepository, questionRepository, openQuestionRepository, eps[1].getId(), 10);

        String studentBearer = createStudentAndLogin(mockMvc, objectMapper, adminBearer, "p7student", "10.70.1.1");
        Long studentId = userRepository.findByUsername("p7student").orElseThrow().getId();

        mockMvc.perform(get("/api/student/quizzes/" + eps[1].getId()).header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/admin/students/" + studentId + "/episodes/" + eps[1].getId() + "/unlock-quiz")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/students/" + studentId + "/episodes/" + eps[1].getId() + "/unlock-quiz")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeId").value(eps[1].getId()))
                .andExpect(jsonPath("$.verifiedWatchSeconds").value(1800))
                .andExpect(jsonPath("$.requiredWatchSeconds").value(1800))
                .andExpect(jsonPath("$.quizUnlocked").value(true))
                .andExpect(jsonPath("$.overrideState").value("UNLOCKED"));

        mockMvc.perform(get("/api/admin/students/" + studentId + "/episode-overrides").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.episodeId==" + eps[1].getId() + ")].quizUnlocked").value(contains(true)))
                .andExpect(jsonPath("$[?(@.episodeId==" + eps[1].getId() + ")].effectiveLocked").value(contains(false)))
                .andExpect(jsonPath("$[?(@.episodeId==" + eps[1].getId() + ")].watchQuizStatus").value(contains("QUIZ_UNLOCKED")));

        String quizBody = mockMvc.perform(get("/api/student/quizzes/" + eps[1].getId()).header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.questions.length()").value(10)).andReturn().getResponse().getContentAsString();
        JsonNode quizJson = objectMapper.readTree(quizBody);
        Map<Long, Long> correctOptByQ = new HashMap<>();
        questionRepository.findByEpisodeIdWithOptions(eps[1].getId()).forEach(q -> q.getOptions().stream()
                .filter(o -> Boolean.TRUE.equals(o.getIsCorrect())).findFirst().ifPresent(o -> correctOptByQ.put(q.getId(), o.getId())));
        Map<Long, Long> answers = new LinkedHashMap<>();
        for (JsonNode qNode : quizJson.get("questions")) {
            long qId = qNode.get("id").asLong();
            answers.put(qId, correctOptByQ.get(qId));
        }

        mockMvc.perform(post("/api/student/quizzes/submit")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", quizJson.get("sessionId").asText(),
                                "episodeId", eps[1].getId(),
                                "answers", answers))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.scorePercentage").value(100.0))
                .andExpect(jsonPath("$.correctCount").value(10));

        mockMvc.perform(get("/phase7-vault").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Watch / Quiz Status")))
                .andExpect(content().string(not(containsString("innerHTML"))));
        mockMvc.perform(get("/phase7-vault.js").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unlock-quiz")))
                .andExpect(content().string(containsString("Unlock Quiz")))
                .andExpect(content().string(not(containsString("innerHTML"))));
    }
}
