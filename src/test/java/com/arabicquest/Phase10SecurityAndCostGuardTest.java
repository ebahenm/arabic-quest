// Tests PR 1: /turath-hq 404 (no admin path leak), strict AutoApprovalPolicy, AI cost guard caps/retries/date-reset, 10-char password min, and 403 isolation.
package com.arabicquest;

import com.arabicquest.model.*;
import com.arabicquest.repository.*;
import com.arabicquest.service.*;
import com.arabicquest.service.AiQuizService.CandidateMcq;
import com.arabicquest.service.AiQuizService.CandidateOption;
import com.arabicquest.service.AiUsageGuard.AiCostCapExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase10_sec_cost_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=p10secadmin", "ADMIN_PASSWORD=Phase10SecAdminPass#2026", "ADMIN_SECRET_PATH=ultra-secret-vault-99",
        "JWT_SECRET=phase10-sec-jwt-secret-key-with-plenty-of-entropy-over-32-chars",
        "GEMINI_API_KEY=test-gemini-key-sec", "GEMINI_MODEL=gemini-3.8-flash",
        "ANTHROPIC_API_KEY=test-claude-key-sec", "CLAUDE_MODEL=claude-sonnet-5-5"
})
class Phase10SecurityAndCostGuardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AutoApprovalPolicy autoApprovalPolicy;
    @Autowired private AiUsageGuard aiUsageGuard;
    @Autowired private AiUsageDailyRepository aiUsageDailyRepository;
    @Autowired private ClaudeClient claudeClient;
    @Autowired private GeminiTextClient geminiTextClient;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenerationJobService generationJobService;

    private static String adminBearer, studentBearer;
    private Episode episode;

    @BeforeEach
    void setUp() throws Exception {
        aiUsageDailyRepository.deleteAll();
        aiUsageGuard.setClock(Clock.systemUTC());
        aiUsageGuard.setDailyCallCap(AiUsageGuard.DEFAULT_DAILY_CALL_CAP);
        aiUsageGuard.setProviderOutputTokenCap("claude", AiUsageGuard.DEFAULT_CLAUDE_OUTPUT_TOKEN_CAP);
        autoApprovalPolicy.setAutoApproveSingleModel(false);

        if (adminBearer == null) {
            MvcResult res = mockMvc.perform(post("/api/auth/login").with(req -> { req.setRemoteAddr("10.110.0.1"); return req; })
                            .contentType(MediaType.APPLICATION_JSON).header("X-Requested-With", "XMLHttpRequest")
                            .content("{\"username\":\"p10secadmin\",\"password\":\"Phase10SecAdminPass#2026\"}")).andExpect(status().isOk()).andReturn();
            adminBearer = "Bearer " + objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
        }
        if (studentBearer == null) {
            studentBearer = Phase5GeminiTestSupport.createStudentAndLogin(mockMvc, objectMapper, adminBearer, "p10secstudent", "10.110.0.2");
        }
        Playlist course = playlistRepository.findAll().stream().filter(p -> "Phase10 Sec Course".equals(p.getTitle())).findFirst()
                .orElseGet(() -> { Playlist p = new Playlist("Phase10 Sec Course", "PL_P10_SEC", "Sec"); p.setPublished(true); return playlistRepository.save(p); });
        episode = episodeRepository.findByPlaylistIdOrderByEpisodeNumberAsc(course.getId()).stream().findFirst()
                .orElseGet(() -> { Episode ep = new Episode(course, 1, "Ep 1", "vid_p10s_1", 1200); ep.setEnabled(true); return episodeRepository.save(ep); });
    }

    @AfterEach
    void tearDown() {
        aiUsageGuard.setClock(Clock.systemUTC());
        aiUsageGuard.setDailyCallCap(AiUsageGuard.DEFAULT_DAILY_CALL_CAP);
        aiUsageGuard.setProviderOutputTokenCap("claude", AiUsageGuard.DEFAULT_CLAUDE_OUTPUT_TOKEN_CAP);
        autoApprovalPolicy.setAutoApproveSingleModel(false);
    }

    @Test
    void test1_legacyAdminPathsReturn404AndNeverLeakSecretPath() throws Exception {
        for (String path : List.of("/turath-hq", "/turath-hq/", "/turath-hq-login", "/turath-hq-login/")) {
            MvcResult res = mockMvc.perform(get(path)).andExpect(status().isNotFound()).andReturn();
            assertThat(res.getResponse().getHeader("Location")).isNull();
            assertThat(res.getResponse().getContentAsString()).doesNotContain("ultra-secret-vault-99");
        }
    }

    @Test
    void test2_strictAutoApprovalPolicy_everyFailureModeGivesDraft() {
        CandidateMcq validMcq = new CandidateMcq(
                "Why did Muawiya create the Registry of the Seal (ديوان الخاتم)?",
                QuestionType.REASONING,
                "Prevent forgery of decrees",
                120,
                List.of(
                        new CandidateOption("Prevent decree forgery", true),
                        new CandidateOption("Translate tax registers into Arabic script", false),
                        new CandidateOption("Mint the first gold Islamic dinar coin", false),
                        new CandidateOption("Collect chronicles from Baghdad libraries", false)
                ));
        CandidateMcq unverifiedTsMcq = new CandidateMcq(
                validMcq.questionText(), validMcq.type(),
                "Some quote " + PipelineCandidateNormalizer.UNVERIFIED_TIMESTAMP_TAG, null, validMcq.options());
        CandidateMcq softFlaggedMcq = new CandidateMcq(
                validMcq.questionText(), validMcq.type(), "Quote", 120,
                List.of(
                        new CandidateOption("This correct answer is deliberately much longer than every distractor option here", true),
                        new CandidateOption("Short A", false),
                        new CandidateOption("Short B", false),
                        new CandidateOption("Short C", false)
                ));

        // All 6 checks pass -> APPROVED
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, validMcq, true, false, "claude-sonnet-5-5", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.APPROVED);

        // 1) Unparseable Pass 2 -> DRAFT
        assertThat(autoApprovalPolicy.decideMcqStatus(true, false, validMcq, true, false, "claude-sonnet-5-5", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.DRAFT);

        // 2) Unverified timestamp -> DRAFT
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, unverifiedTsMcq, true, false, "claude-sonnet-5-5", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.DRAFT);

        // 3) Soft-flagged options -> DRAFT
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, softFlaggedMcq, true, false, "claude-sonnet-5-5", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.DRAFT);

        // 4) Failed blind test -> DRAFT
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, validMcq, false, false, "claude-sonnet-5-5", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.DRAFT);

        // 5) Episode needsReview -> DRAFT
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, validMcq, true, true, "claude-sonnet-5-5", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.DRAFT);

        // 6) Single-model (default AUTO_APPROVE_SINGLE_MODEL=false) -> DRAFT
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, validMcq, true, false, "gemini-3.8-flash", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.DRAFT);

        // Single-model opted-in (AUTO_APPROVE_SINGLE_MODEL=true) approves ONLY if all other checks pass
        autoApprovalPolicy.setAutoApproveSingleModel(true);
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, validMcq, true, false, "gemini-3.8-flash", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.APPROVED);
        assertThat(autoApprovalPolicy.decideMcqStatus(true, false, validMcq, true, false, "gemini-3.8-flash", "gemini-3.8-flash"))
                .isEqualTo(QuestionStatus.DRAFT);
    }

    @Test
    void test3_aiCostGuard_capsRetryCountingAndDateReset() throws Exception {
        Clock day1 = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);
        Clock day2 = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);
        aiUsageGuard.setClock(day1);
        aiUsageGuard.setDailyCallCap(2);
        aiUsageGuard.setProviderOutputTokenCap("claude", 500L);

        RestTemplate mockClaude = mock(RestTemplate.class);
        claudeClient.setRestTemplate(mockClaude);

        // Call 1 fails with HTTP 500 (retry/error MUST still count as 1 call!)
        when(mockClaude.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "err", "err".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThatThrownBy(() -> claudeClient.callMessagesDetailed("sys", "usr", 256))
                .isInstanceOf(IllegalStateException.class);

        // Call 2 succeeds with 600 output tokens (hits both call cap=2 and Claude token cap=500)
        String okClaude = objectMapper.writeValueAsString(Map.of(
                "stop_reason", "end_turn", "usage", Map.of("input_tokens", 100, "output_tokens", 600),
                "content", List.of(Map.of("type", "text", "text", "{\"ok\":true}"))));
        when(mockClaude.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(okClaude));
        claudeClient.callMessagesDetailed("sys", "usr", 256);

        // Call 3 must be refused by AiUsageGuard AND enqueueJob must refuse with AiCostCapExceededException
        assertThatThrownBy(() -> claudeClient.callMessagesDetailed("sys", "usr", 256))
                .isInstanceOf(AiCostCapExceededException.class);
        assertThatThrownBy(() -> generationJobService.enqueueJob(episode.getId(), "claude-sonnet-5-5", null, false))
                .isInstanceOf(AiCostCapExceededException.class);

        // Admin endpoint /api/admin/ai/usage reports 2/2 calls and capReached=true
        mockMvc.perform(get("/api/admin/ai/usage")
                        .header("Authorization", adminBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(2))
                .andExpect(jsonPath("$.dailyCallCap").value(2))
                .andExpect(jsonPath("$.capReached").value(true))
                .andExpect(jsonPath("$.providers.claude.outputTokens").value(600));

        // Advance clock to next UTC day -> caps reset cleanly!
        aiUsageGuard.setClock(day2);
        assertThat(aiUsageGuard.isGlobalCallCapReached()).isFalse();
        claudeClient.callMessagesDetailed("sys", "usr", 256);
        assertThat(aiUsageGuard.getDailyUsageSnapshot().get("totalCalls")).isEqualTo(1);
    }

    @Test
    void test4_passwordMin10Chars_andStudent403OnAdminUsageEndpoint() throws Exception {
        // 9-char password rejected on new student creation
        mockMvc.perform(post("/api/admin/students")
                        .header("Authorization", adminBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shortpwuser\",\"password\":\"Short123!\",\"fullName\":\"Short PW\"}"))
                .andExpect(status().isBadRequest());

        // 10-char password accepted
        mockMvc.perform(post("/api/admin/students")
                        .header("Authorization", adminBearer).header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tencharuser\",\"password\":\"Valid10Ch!\",\"fullName\":\"Ten Char\"}"))
                .andExpect(status().isOk());

        // Students get 403 on admin AI usage endpoint
        mockMvc.perform(get("/api/admin/ai/usage")
                        .header("Authorization", studentBearer).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());
    }
}
