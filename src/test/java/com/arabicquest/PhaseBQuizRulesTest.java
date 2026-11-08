package com.arabicquest;

import com.arabicquest.dto.OpenAnswerSubmitRequest;
import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.OpenAnswerReviewService;
import com.arabicquest.service.PayoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
public class PhaseBQuizRulesTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private OpenQuestionRepository openQuestionRepository;
    @Autowired private OpenAnswerSubmissionRepository openAnswerSubmissionRepository;
    @Autowired private EpisodeRewardClaimRepository episodeRewardClaimRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private PayoutService payoutService;
    @Autowired private OpenAnswerReviewService openAnswerReviewService;
    @Autowired private PasswordEncoder passwordEncoder;

    private User student;
    private User admin;
    private Playlist course;
    private Episode ep1;
    private Episode ep2;
    private OpenQuestion openQ1;
    private String studentBearer;
    private String adminBearer;

    private static final String VALID_FORTY_WORD_ANSWER =
            "The historical narrative clearly demonstrates how diplomatic alliances and maritime commercial networks " +
            "fostered economic resilience across the Mediterranean basin. By analyzing primary textual documents and architectural monuments, " +
            "scholars reconstructed institutional governance, educational academies, judicial traditions, and scholarly transmission that endured across centuries of dynamic change.";

    @BeforeEach
    void setup() throws Exception {
        userRepository.deleteAll();
        playlistRepository.deleteAll();

        admin = new User("admin_rules", passwordEncoder.encode("AdminPass#2026!"), "Admin Rules", Role.ROLE_ADMIN);
        student = new User("student_rules", passwordEncoder.encode("StudentPass#2026!"), "Student Rules", Role.ROLE_STUDENT);
        userRepository.saveAll(List.of(admin, student));

        course = new Playlist("PL_RULES", "Quiz Rules Course", "Rules Description");
        course.setPublished(true);
        course.setMinEpisodesForBonus(2);
        course.setCompletionBonus(new BigDecimal("50.00"));
        playlistRepository.save(course);

        ep1 = new Episode(course, 1, "EP1_RULES", "Rules Ep 1");
        ep1.setEnabled(true);
        ep2 = new Episode(course, 2, "EP2_RULES", "Rules Ep 2");
        ep2.setEnabled(true);
        episodeRepository.saveAll(List.of(ep1, ep2));

        openQ1 = new OpenQuestion(ep1, "Explain how trade networks influenced intellectual exchange.", "Trade route evidence", QuestionStatus.APPROVED);
        openQuestionRepository.save(openQ1);

        openQuestionRepository.save(new OpenQuestion(ep2, "Analyze the architectural evolution of historical academies.", "Academy evidence", QuestionStatus.APPROVED));

        // Create 10 approved questions for ep1 and ep2
        Quiz quiz1 = new Quiz(ep1);
        quiz1.setPassingScorePercentage(100.0);
        int order1 = 1;
        for (int i = 1; i <= 10; i++) {
            Question q = new Question(quiz1, "Ep 1 Question " + i + "?", order1++);
            q.setStatus(QuestionStatus.APPROVED);
            q.setActive(true);
            q.setType(QuestionType.RECALL);
            q.setEvidence("Evidence for ep1 q " + i);
            q.getOptions().add(new QuestionOption(q, "Correct Option", true));
            q.getOptions().add(new QuestionOption(q, "Distractor A", false));
            q.getOptions().add(new QuestionOption(q, "Distractor B", false));
            q.getOptions().add(new QuestionOption(q, "Distractor C", false));
            quiz1.getQuestions().add(q);
        }
        quizRepository.save(quiz1);

        Quiz quiz2 = new Quiz(ep2);
        quiz2.setPassingScorePercentage(100.0);
        int order2 = 1;
        for (int i = 1; i <= 10; i++) {
            Question q = new Question(quiz2, "Ep 2 Question " + i + "?", order2++);
            q.setStatus(QuestionStatus.APPROVED);
            q.setActive(true);
            q.setType(QuestionType.RECALL);
            q.setEvidence("Evidence for ep2 q " + i);
            q.getOptions().add(new QuestionOption(q, "Correct Option", true));
            q.getOptions().add(new QuestionOption(q, "Distractor A", false));
            q.getOptions().add(new QuestionOption(q, "Distractor B", false));
            q.getOptions().add(new QuestionOption(q, "Distractor C", false));
            quiz2.getQuestions().add(q);
        }
        quizRepository.save(quiz2);

        // Student passes 10/10 MCQ
        quizAttemptRepository.save(new QuizAttempt(student, ep1, WatchMode.ARABIC_SUB, 100.0, true, BigDecimal.ZERO));

        // Login both
        String adminLoginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "admin_rules", "password", "AdminPass#2026!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        adminBearer = "Bearer " + objectMapper.readTree(adminLoginRes).get("token").asText();

        String studentLoginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "student_rules", "password", "StudentPass#2026!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        studentBearer = "Bearer " + objectMapper.readTree(studentLoginRes).get("token").asText();
    }

    @Test
    @DisplayName("B1 & B2: Valid 40-word submission immediately and idempotently creates reward and unlocks next episode")
    void validWrittenAnswerImmediatelyPassesAndUnlocksNext() throws Exception {
        // 1. Submit valid 40+ word reflection
        mockMvc.perform(post("/api/student/episodes/" + ep1.getId() + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answerText", VALID_FORTY_WORD_ANSWER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionStatus").value("APPROVED"))
                .andExpect(jsonPath("$.rewardCreated").value(true));

        // 2. Earnings reflect immediate pass ($15.00 for ARABIC_SUB mode)
        var earnings = payoutService.getStudentEarnings(student.getUsername());
        assertThat(earnings.getTotalEarned()).isEqualByComparingTo("15.00");
        assertThat(earnings.getBalanceOwed()).isEqualByComparingTo("15.00");

        // 3. Reward claim created exactly once
        assertThat(episodeRewardClaimRepository.existsByStudentIdAndEpisodeId(student.getId(), ep1.getId())).isTrue();

        // 4. Double submit / re-submit does not duplicate reward
        mockMvc.perform(post("/api/student/episodes/" + ep1.getId() + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answerText", VALID_FORTY_WORD_ANSWER))))
                .andExpect(status().isOk());

        earnings = payoutService.getStudentEarnings(student.getUsername());
        assertThat(earnings.getTotalEarned()).isEqualByComparingTo("15.00");

        // 5. Episode 2 is now unlocked (AVAILABLE)
        String epListJson = mockMvc.perform(get("/api/student/episodes")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode episodes = objectMapper.readTree(epListJson);
        JsonNode ep2Node = null;
        for (JsonNode e : episodes) {
            if (e.get("id").asLong() == ep2.getId()) {
                ep2Node = e;
                break;
            }
        }
        assertThat(ep2Node).isNotNull();
        assertThat(ep2Node.get("state").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("B2: Invalid written answers are rejected (too short, character spam, copying question)")
    void invalidAnswersRejectedWithBadRequest() throws Exception {
        // Under 40 words
        mockMvc.perform(post("/api/student/episodes/" + ep1.getId() + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answerText", "This answer is too short to pass the check."))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("at least 40 words")));

        // Repeated characters spam
        String charSpam = "The answer is awesomeeeeeeeeeeeeeeeeeeeeeeeeeeeeee and has words to reach the count but is invalid spam because of repeating letters. " +
                "The answer is awesome and has words to reach the count but is invalid spam because of repeating letters in words.";
        mockMvc.perform(post("/api/student/episodes/" + ep1.getId() + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answerText", charSpam))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("excessively repeated characters")));

        // Copying the prompt text
        String copyingPrompt = (openQ1.getPromptText() + " ").repeat(8);
        mockMvc.perform(post("/api/student/episodes/" + ep1.getId() + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answerText", copyingPrompt))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("copying or slightly rewording the question prompt")));
    }

    @Test
    @DisplayName("B4: Reading list unread counter and mark-as-read action work cleanly")
    void readingListUnreadCounterAndMarkAsRead() throws Exception {
        // Submit answer -> sets readByAdmin = false
        mockMvc.perform(post("/api/student/episodes/" + ep1.getId() + "/open-answer")
                        .header(HttpHeaders.AUTHORIZATION, studentBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answerText", VALID_FORTY_WORD_ANSWER))))
                .andExpect(status().isOk());

        // Check unread count is 1
        mockMvc.perform(get("/api/admin/open-answers/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        // Get submission id
        String listJson = mockMvc.perform(get("/api/admin/open-answers?status=UNREAD")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long subId = objectMapper.readTree(listJson).get(0).get("id").asLong();

        // Mark as read with optional feedback note
        mockMvc.perform(post("/api/admin/open-answers/" + subId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Excellent historical analysis!\"}"))
                .andExpect(status().isOk());

        // Unread count is now 0
        mockMvc.perform(get("/api/admin/open-answers/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }
}
