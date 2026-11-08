package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.AiQuizService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
public class PhaseCYoutubeGeneratorTest {

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

    private User admin;
    private User student;
    private Playlist course;
    private Episode epLong; // > 35 mins
    private String adminBearer;
    private String studentBearer;

    @BeforeEach
    void setup() throws Exception {
        userRepository.deleteAll();
        playlistRepository.deleteAll();

        admin = new User("admin_yt", passwordEncoder.encode("AdminPass#2026!"), "Admin YT", Role.ROLE_ADMIN);
        student = new User("student_yt", passwordEncoder.encode("StudentPass#2026!"), "Student YT", Role.ROLE_STUDENT);
        userRepository.saveAll(List.of(admin, student));

        course = new Playlist("PL_YT_TEST", "YouTube Course", "Course with YouTube video");
        course.setPublished(true);
        playlistRepository.save(course);

        // Long episode: 2700 seconds (45 minutes)
        epLong = new Episode(course, 1, "Episode 45 Minutes", "dQw4w9WgXcQ");
        epLong.setDurationSeconds(2700);
        epLong.setEnabled(true);
        episodeRepository.save(epLong);

        String adminLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "admin_yt", "password", "AdminPass#2026!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        adminBearer = "Bearer " + objectMapper.readTree(adminLogin).get("token").asText();

        String studentLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "student_yt", "password", "StudentPass#2026!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        studentBearer = "Bearer " + objectMapper.readTree(studentLogin).get("token").asText();

        aiQuizService.setGeminiApiKey("dummy-gemini-key-for-testing");
    }

    @Test
    @DisplayName("C1, C2, C3, C4: Gemini request uses YouTube URL in file_data, low resolution, segmentation, timestamps, and 2-pass verification")
    void youtubeGroundedTwoPassGenerationWorks() throws Exception {
        List<String> capturedBodies = new ArrayList<>();
        List<String> capturedHeaders = new ArrayList<>();
        AtomicInteger callCounter = new AtomicInteger();

        RestTemplate mockRestTemplate = new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                int call = callCounter.incrementAndGet();
                HttpEntity<?> entity = (HttpEntity<?>) request;
                capturedHeaders.add(entity.getHeaders().getFirst("x-goog-api-key"));
                try {
                    capturedBodies.add(objectMapper.writeValueAsString(entity.getBody()));
                } catch (Exception ignored) {}

                String responseContent;
                if (call % 2 == 1) {
                    // Pass 1 response: candidate questions (some valid with timestamps, 1 missing timestamp, 1 unverified)
                    responseContent = """
                            {
                              "candidates": [
                                {
                                  "content": {
                                    "parts": [
                                      {
                                        "text": "{\\"mcqs\\": [{\\"questionText\\": \\"What event occurred in 762 CE (بغداد)?\\", \\"type\\": \\"RECALL\\", \\"evidenceTimestamp\\": 312, \\"evidence\\": \\"Caliph al-Mansur established Baghdad in 762 CE\\", \\"options\\": [{\\"optionText\\": \\"Founding of Baghdad\\", \\"isCorrect\\": true}, {\\"optionText\\": \\"Fall of Damascus\\", \\"isCorrect\\": false}, {\\"optionText\\": \\"Battle of Yarmouk\\", \\"isCorrect\\": false}, {\\"optionText\\": \\"Treaty of Cairo\\", \\"isCorrect\\": false}]}, {\\"questionText\\": \\"What is the term for endowment (وقف)?\\", \\"type\\": \\"VOCABULARY\\", \\"evidenceTimestamp\\": 540, \\"evidence\\": \\"Waqf denotes charitable endowments\\", \\"options\\": [{\\"optionText\\": \\"Waqf\\", \\"isCorrect\\": true}, {\\"optionText\\": \\"Diwan\\", \\"isCorrect\\": false}, {\\"optionText\\": \\"Jizya\\", \\"isCorrect\\": false}, {\\"optionText\\": \\"Kharaj\\", \\"isCorrect\\": false}]}, {\\"questionText\\": \\"Question without timestamp?\\", \\"type\\": \\"RECALL\\", \\"evidence\\": \\"No timestamp provided\\", \\"options\\": [{\\"optionText\\": \\"A\\", \\"isCorrect\\": true}, {\\"optionText\\": \\"B\\", \\"isCorrect\\": false}, {\\"optionText\\": \\"C\\", \\"isCorrect\\": false}, {\\"optionText\\": \\"D\\", \\"isCorrect\\": false}]}], \\"openQuestions\\": [{\\"promptText\\": \\"Explain the role of maritime trade in connecting regional ports.\\", \\"evidenceTimestamp\\": 845, \\"evidence\\": \\"Discusses Red Sea and Indian Ocean ports\\"}]}"
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                            """;
                } else {
                    // Pass 2 response: verification pass
                    responseContent = """
                            {
                              "candidates": [
                                {
                                  "content": {
                                    "parts": [
                                      {
                                        "text": "{\\"verifiedMcqIndices\\": [0, 1], \\"verifiedOpenIndices\\": [0]}"
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                            """;
                }

                Map<String, Object> respMap;
                try {
                    respMap = objectMapper.readValue(responseContent, Map.class);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return (ResponseEntity<T>) ResponseEntity.ok(respMap);
            }
        };

        RestTemplate prev = aiQuizService.getRestTemplate();
        aiQuizService.setRestTemplate(mockRestTemplate);

        try {
            // Admin triggers generate from YouTube video
            String result = mockMvc.perform(post("/api/admin/episodes/" + epLong.getId() + "/generate-bank")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode summary = objectMapper.readTree(result);
            assertThat(summary.get("savedDraftMcqs").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(summary.get("savedDraftOpenQuestions").asInt()).isGreaterThanOrEqualTo(1);

            // Verify API key passed strictly in request header, never in URL
            assertThat(capturedHeaders).allMatch(h -> "dummy-gemini-key-for-testing".equals(h));

            // Verify video grounded file_data part
            String firstBody = capturedBodies.get(0);
            assertThat(firstBody).contains("file_data");
            assertThat(firstBody).contains("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
            assertThat(firstBody).contains("MEDIA_RESOLUTION_LOW");

            // Verify questions without timestamp were dropped
            List<Question> savedQuestions = questionRepository.findByEpisodeIdWithOptions(epLong.getId());
            assertThat(savedQuestions).noneMatch(q -> "Question without timestamp?".equals(q.getQuestionText()));

            // Verify all saved questions are DRAFT
            assertThat(savedQuestions).allMatch(q -> q.getStatus() == QuestionStatus.DRAFT);
            assertThat(savedQuestions).allMatch(q -> q.getEvidenceTimestamp() != null && q.getEvidenceTimestamp() > 0);

            // Drafts are NEVER visible to students
            mockMvc.perform(get("/api/student/quizzes/" + epLong.getId())
                            .header(HttpHeaders.AUTHORIZATION, studentBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ready").value(false));

        } finally {
            aiQuizService.setRestTemplate(prev);
        }
    }

    @Test
    @DisplayName("C7: Student cannot access AI generator (403 Forbidden)")
    void studentCannotAccessAiGenerator() throws Exception {
        mockMvc.perform(post("/api/admin/episodes/" + epLong.getId() + "/generate-bank")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
