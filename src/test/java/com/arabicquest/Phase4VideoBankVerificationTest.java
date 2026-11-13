// Verifies Phase 4 video-bank segmentation, timestamp/vocab/concept guards, retries/errors, and Question Bank UI assets.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.AiQuizService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:p4_bank_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=p4_admin",
        "ADMIN_PASSWORD=MasterStrongPass!2026",
        "ADMIN_SECRET_PATH=p4-vault-hq",
        "JWT_SECRET=phase4-video-jwt-secret-key-with-at-least-32-chars-ok",
        "GEMINI_API_KEY=phase4-mock-gemini-key",
        "app.ai.retry-backoff-ms=0"
})
class Phase4VideoBankVerificationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private OpenQuestionRepository openQuestionRepository;
    @Autowired private AiQuizService aiQuizService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminBearer;
    private Episode ep5000s;

    @BeforeEach
    void setup() throws Exception {
        openQuestionRepository.deleteAll();
        questionRepository.deleteAll();
        quizRepository.deleteAll();
        episodeRepository.deleteAll();
        playlistRepository.deleteAll();
        userRepository.deleteAll();
        aiQuizService.clearRateLimitBuckets();
        aiQuizService.setRetryBackoffMs(0L);

        userRepository.save(new User("p4_admin", passwordEncoder.encode("MasterStrongPass!2026"), "P4 Admin", Role.ROLE_ADMIN));
        Playlist course = new Playlist("PL_P4_TEST", "Long Form Course", "Multi-segment course");
        course.setPublished(true);
        playlistRepository.save(course);

        ep5000s = new Episode(course, 1, "Long 83 Min Documentary", "abc123XYZ99");
        ep5000s.setDurationSeconds(5000);
        ep5000s.setEnabled(true);
        episodeRepository.save(ep5000s);

        String loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"p4_admin\",\"password\":\"MasterStrongPass!2026\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        adminBearer = "Bearer " + objectMapper.readTree(loginRes).get("token").asText();
    }

    private String wrapGeminiJson(Object payloadObj) throws Exception {
        String innerJson = objectMapper.writeValueAsString(payloadObj);
        Map<String, Object> env = Map.of("candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", innerJson))))));
        return objectMapper.writeValueAsString(env);
    }

    private Map<String, Object> mcq(String stem, String type, Integer ts, String evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questionText", stem);
        m.put("type", type);
        if (ts != null) m.put("evidenceTimestamp", ts);
        m.put("evidence", evidence);
        m.put("options", List.of(
                Map.of("optionText", "Correct Option", "isCorrect", true),
                Map.of("optionText", "Distractor One", "isCorrect", false),
                Map.of("optionText", "Distractor Two", "isCorrect", false),
                Map.of("optionText", "Distractor Three", "isCorrect", false)
        ));
        return m;
    }

    @Test
    void segmentsLongVideoEnforcesGuardsKeepsApprovedUntouchedAndSupportsBulkApprove() throws Exception {
        Quiz existingQuiz = quizRepository.save(new Quiz(ep5000s));
        Question preexistingApproved = new Question(existingQuiz, "Preexisting Approved Fact?", 1);
        preexistingApproved.setStatus(QuestionStatus.APPROVED);
        preexistingApproved.setActive(true);
        preexistingApproved.setType(QuestionType.RECALL);
        preexistingApproved.setEvidence("Preexisting note");
        preexistingApproved.setEvidenceTimestamp(42);
        preexistingApproved.getOptions().addAll(List.of(
                new QuestionOption(preexistingApproved, "A", true),
                new QuestionOption(preexistingApproved, "B", false),
                new QuestionOption(preexistingApproved, "C", false),
                new QuestionOption(preexistingApproved, "D", false)
        ));
        existingQuiz.getQuestions().add(preexistingApproved);
        existingQuiz = quizRepository.save(existingQuiz);
        Long preexistingId = existingQuiz.getQuestions().get(0).getId();

        OpenQuestion preexistingOpen = new OpenQuestion(ep5000s, "Preexisting Open Prompt?", "Rubric", QuestionStatus.APPROVED);
        preexistingOpen.setActive(true);
        preexistingOpen.setEvidenceTimestamp(88);
        openQuestionRepository.save(preexistingOpen);

        List<String> pass1Bodies = new ArrayList<>();
        RestTemplate prev = aiQuizService.getRestTemplate();
        aiQuizService.setRestTemplate(new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                try {
                    String body = objectMapper.writeValueAsString(((HttpEntity<?>) request).getBody());
                    String respJson;
                    if (body.contains("Candidate MCQs")) {
                        // Exclude index 4 (unverified Reasoning item after Citadel & 9999s were pre-filtered)
                        respJson = wrapGeminiJson(Map.of("verifiedMcqIndices", List.of(0, 1, 2, 3), "verifiedOpenIndices", List.of(0)));
                    } else {
                        pass1Bodies.add(body);
                        if (!body.contains("\"start_offset\"")) {
                            List<Map<String, Object>> mcqs = List.of(
                                    mcq("Why did al-Mansur found Baghdad in 762 CE (بغداد)?", "RECALL", 310, "Founded in 762 CE"),
                                    mcq("What does the term endowment (وقف) mean?", "VOCABULARY", 420, "Waqf definition"),
                                    mcq("What does the term guild (صنف) mean?", "VOCABULARY", 510, "Sinf guild"),
                                    mcq("What does the term citadel (قلعة) mean?", "VOCABULARY", 620, "Excess 3rd vocab"),
                                    mcq("What does the term endowment (وقف) signify in practice?", "RECALL", 650, "Duplicate Waqf concept"),
                                    mcq("Out-of-bounds hallucinated event?", "RECALL", 9999, "Impossible timestamp"),
                                    mcq("Unverified event unsupported at 700s?", "REASONING", 700, "Dropped in Pass 2")
                            );
                            List<Map<String, Object>> opens = List.of(
                                    Map.of("promptText", "Explain how canal networks sustained Abbasid agriculture.", "evidenceTimestamp", 890, "evidence", "Canal irrigation segment")
                            );
                            respJson = wrapGeminiJson(Map.of("mcqs", mcqs, "openQuestions", opens));
                        } else {
                            respJson = wrapGeminiJson(Map.of("mcqs", List.of(), "openQuestions", List.of()));
                        }
                    }
                    return (ResponseEntity<T>) ResponseEntity.ok(objectMapper.readValue(respJson, Map.class));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        try {
            String genRes = mockMvc.perform(post("/api/admin/episodes/" + ep5000s.getId() + "/generate-bank")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode summary = objectMapper.readTree(genRes);

            // >35 min (5000s) splits into 4 segments (0-1600, 1600-3200, 3200-4800, 4800-5000)
            assertThat(pass1Bodies).hasSize(4);
            assertThat(summary.get("savedDraftMcqs").asInt()).isEqualTo(3);
            assertThat(summary.get("savedDraftOpenQuestions").asInt()).isEqualTo(1);

            mockMvc.perform(get("/api/admin/episodes/" + ep5000s.getId() + "/question-bank?status=DRAFT")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.youtubeVideoId").value("abc123XYZ99"))
                    .andExpect(jsonPath("$.approvedMcqCount").value(1))
                    .andExpect(jsonPath("$.draftMcqCount").value(3))
                    .andExpect(jsonPath("$.quizReadyForStudents").value(false))
                    .andExpect(jsonPath("$.questions.length()").value(3))
                    .andExpect(jsonPath("$.questions[0].evidenceTimestamp").value(310));

            mockMvc.perform(get("/api/admin/episodes/" + ep5000s.getId() + "/question-bank?type=VOCABULARY")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.questions.length()").value(2));

            mockMvc.perform(post("/api/admin/episodes/" + ep5000s.getId() + "/questions/bulk-approve")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approvedMcqs").value(3))
                    .andExpect(jsonPath("$.approvedOpenQuestions").value(1));

            assertThat(questionRepository.findById(preexistingId).orElseThrow().getStatus())
                    .isEqualTo(QuestionStatus.APPROVED);
        } finally {
            aiQuizService.setRestTemplate(prev);
        }
    }

    @Test
    void retriesTransient503MapsQuotaTimeoutAndUnavailableVideoAndServesModule() throws Exception {
        ep5000s.setDurationSeconds(900);
        episodeRepository.save(ep5000s);

        AtomicInteger calls = new AtomicInteger();
        RestTemplate prev = aiQuizService.getRestTemplate();
        aiQuizService.setRestTemplate(new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                int c = calls.incrementAndGet();
                try {
                    if (c == 1) {
                        throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "503", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
                    }
                    String body = objectMapper.writeValueAsString(((HttpEntity<?>) request).getBody());
                    String respJson = body.contains("Candidate MCQs")
                            ? wrapGeminiJson(Map.of("verifiedMcqIndices", List.of(0), "verifiedOpenIndices", List.of()))
                            : wrapGeminiJson(Map.of("mcqs", List.of(mcq("Recovered after transient 503?", "RECALL", 90, "Quote at 90s")), "openQuestions", List.of()));
                    return (ResponseEntity<T>) ResponseEntity.ok(objectMapper.readValue(respJson, Map.class));
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        try {
            mockMvc.perform(post("/api/admin/episodes/" + ep5000s.getId() + "/generate-bank")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.savedDraftMcqs").value(1));

            aiQuizService.setRestTemplate(new RestTemplate() {
                @Override
                public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                    throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Quota", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
                }
            });
            mockMvc.perform(post("/api/admin/episodes/" + ep5000s.getId() + "/generate-bank")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.retryAfterSeconds").value(60));

            aiQuizService.setRestTemplate(new RestTemplate() {
                @Override
                public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                    throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Private video", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
                }
            });
            mockMvc.perform(post("/api/admin/episodes/" + ep5000s.getId() + "/generate-bank")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("private, unavailable")));

            aiQuizService.setRestTemplate(new RestTemplate() {
                @Override
                public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                    throw new ResourceAccessException("Read timed out");
                }
            });
            mockMvc.perform(post("/api/admin/episodes/" + ep5000s.getId() + "/generate-bank")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("timed out")));

            mockMvc.perform(get("/p4-vault-hq/modules/pages/question-bank-manager.js"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Jump to moment")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("innerHTML"))));
        } finally {
            aiQuizService.setRestTemplate(prev);
        }
    }
}
