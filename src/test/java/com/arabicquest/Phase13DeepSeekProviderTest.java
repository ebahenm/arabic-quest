// Tests for optional DeepSeekClient (mock HTTP server): JSON repair, 429/5xx backoff, sanitized errors, AiUsageGuard caps, privacy, and writer!=verifier.
package com.arabicquest;

import com.arabicquest.controller.AiConnectionTestController;
import com.arabicquest.model.QuestionStatus;
import com.arabicquest.model.QuestionType;
import com.arabicquest.repository.AiUsageDailyRepository;
import com.arabicquest.service.*;
import com.arabicquest.service.ClaudeClient.ClaudeMessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase13_deepseek_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "ADMIN_USERNAME=p13admin",
        "ADMIN_PASSWORD=Phase13StrongPassword!99",
        "ADMIN_SECRET_PATH=p13-secret-hq",
        "JWT_SECRET=phase13-deepseek-provider-jwt-secret-key-well-over-32-chars",
        "DEEPSEEK_API_KEY=",
        "DEEPSEEK_DAILY_CALL_CAP=150",
        "DEEPSEEK_DAILY_OUTPUT_TOKEN_CAP=250000"
})
class Phase13DeepSeekProviderTest {

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiUsageGuard aiUsageGuard;
    @Autowired private AiUsageDailyRepository aiUsageDailyRepository;
    @Autowired private AiConnectionTestController aiConnectionTestController;
    @Autowired private AutoApprovalPolicy autoApprovalPolicy;
    @Autowired private OpenAnswerReviewService openAnswerReviewService;

    private HttpServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
            mockServer = null;
        }
        aiUsageDailyRepository.deleteAll();
        aiUsageGuard.setDailyCallCap(AiUsageGuard.DEFAULT_DAILY_CALL_CAP);
        aiUsageGuard.setProviderCallCap("deepseek", AiUsageGuard.DEFAULT_DEEPSEEK_DAILY_CALL_CAP);
        aiUsageGuard.setProviderOutputTokenCap("deepseek", AiUsageGuard.DEFAULT_DEEPSEEK_OUTPUT_TOKEN_CAP);
    }

    @Test
    void deepSeekClient_parsesFencedJson_sendsBearerAndJsonResponseFormat_andRetries429And503() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.createContext("/v1/chat/completions", ex -> {
            capturedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int n = attempts.incrementAndGet();
            int status;
            String body;
            if (n == 1) {
                status = 429;
                body = "{\"error\":{\"message\":\"rate limited\"}}";
            } else if (n == 2) {
                status = 503;
                body = "{\"error\":{\"message\":\"temporary overload\"}}";
            } else {
                status = 200;
                body = """
                        {
                          "choices": [{
                            "finish_reason": "stop",
                            "message": { "content": "Here is the JSON:\\n```json\\n{\\"mcqs\\":[{\\"q\\":\\"Test\\"}]}\\n```" }
                          }],
                          "usage": { "prompt_tokens": 120, "completion_tokens": 45 }
                        }
                        """;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        mockServer.start();

        DeepSeekClient client = new DeepSeekClient(objectMapper);
        client.setBaseUrl("http://127.0.0.1:" + mockServer.getAddress().getPort());
        client.setApiKey("sk-deepseek-secret-99999");
        client.setModel("deepseek-flash");
        client.setBackoffBaseMs(0L);
        client.setMaxAttempts(3);

        ClaudeMessageResponse resp = client.callChatDetailed("System prompt", "Public episode transcript text", 1024);

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(capturedAuth.get()).isEqualTo("Bearer sk-deepseek-secret-99999");
        assertThat(capturedBody.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(resp.inputTokens()).isEqualTo(120);
        assertThat(resp.outputTokens()).isEqualTo(45);
        assertThat(objectMapper.readTree(resp.text()).path("mcqs").get(0).path("q").asText()).isEqualTo("Test");
    }

    @Test
    void deepSeekClient_sanitizesApiKeyFromErrorsAndTestConnection() throws Exception {
        String secret = "sk-deepseek-super-secret-xyz";
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.createContext("/v1/chat/completions", ex -> {
            String errBody = "{\"error\":{\"message\":\"Invalid key " + secret + " rejected\"}}";
            byte[] bytes = errBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(401, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        mockServer.start();

        DeepSeekClient client = new DeepSeekClient(objectMapper);
        client.setBaseUrl("http://127.0.0.1:" + mockServer.getAddress().getPort());
        client.setApiKey(secret);
        client.setBackoffBaseMs(0L);
        client.setMaxAttempts(1);

        assertThatThrownBy(() -> client.callChatJson("sys", "public transcript", 256))
                .hasMessageNotContaining(secret);

        Map<String, Object> testRes = client.testConnection();
        assertThat(testRes.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(testRes.get("message"))).doesNotContain(secret);
    }

    @Test
    void aiUsageGuard_enforcesDeepSeekCallAndOutputTokenCaps() {
        aiUsageDailyRepository.deleteAll();
        aiUsageGuard.setDailyCallCap(500);
        aiUsageGuard.setProviderCallCap("deepseek", 2);
        aiUsageGuard.setProviderOutputTokenCap("deepseek", 1000L);

        aiUsageGuard.assertCanCall("deepseek");
        aiUsageGuard.recordCall("deepseek", 100, 400);
        aiUsageGuard.assertCanCall("deepseek");
        aiUsageGuard.recordCall("deepseek", 100, 400);

        // 3rd call hits DeepSeek daily call cap (2)
        assertThatThrownBy(() -> aiUsageGuard.assertCanCall("deepseek"))
                .isInstanceOf(AiUsageGuard.AiCostCapExceededException.class)
                .hasMessageContaining("deepseek");

        // Raise call cap, push output tokens past 1000 -> hits output token cap
        aiUsageGuard.setProviderCallCap("deepseek", 10);
        aiUsageGuard.recordCall("deepseek", 50, 300);
        assertThatThrownBy(() -> aiUsageGuard.assertCanCall("deepseek"))
                .isInstanceOf(AiUsageGuard.AiCostCapExceededException.class)
                .hasMessageContaining("output token cap");

        Map<String, Object> snap = aiUsageGuard.getDailyUsageSnapshot();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> providers = (Map<String, Map<String, Object>>) snap.get("providers");
        assertThat(providers).containsKey("deepseek");
        assertThat(providers.get("deepseek").get("calls")).isEqualTo(3);
    }

    @Test
    void routerEnforcesWriterDiffersFromVerifier_andPrivacyIsolatesStudentAnswers() {
        ClaudeClient claude = new ClaudeClient(objectMapper);
        claude.setApiKey("sk-ant-test");
        GeminiTextClient gemini = new GeminiTextClient(objectMapper);
        gemini.setApiKey("gem-test");
        DeepSeekClient deepSeek = new DeepSeekClient(objectMapper);
        deepSeek.setApiKey("ds-test");

        PipelineModelRouter.ProviderPlan plan = PipelineModelRouter.resolveProviders("deepseek-flash", claude, gemini, deepSeek);
        assertThat(plan.writerProvider()).isEqualTo("deepseek");
        assertThat(plan.verifierProvider()).isNotEqualTo("deepseek");
        assertThat(plan.writerModelName()).isNotEqualTo(plan.verifierModelName());

        // AutoApprovalPolicy allows auto-approval when writer != verifier, blocks when same
        var mcq = new AiQuizService.CandidateMcq("Q?", QuestionType.RECALL, "quote", 120, List.of(
                new AiQuizService.CandidateOption("A", true),
                new AiQuizService.CandidateOption("B", false),
                new AiQuizService.CandidateOption("C", false),
                new AiQuizService.CandidateOption("D", false)));
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, mcq, true, false, plan.writerModelName(), plan.verifierModelName()))
                .isEqualTo(QuestionStatus.APPROVED);
        assertThat(autoApprovalPolicy.decideMcqStatus(true, true, mcq, true, false, "deepseek-flash", "deepseek-flash"))
                .isEqualTo(QuestionStatus.DRAFT);

        // Privacy check: student written-answer review service never holds a DeepSeekClient reference
        for (Field f : openAnswerReviewService.getClass().getDeclaredFields()) {
            assertThat(f.getType()).isNotEqualTo(DeepSeekClient.class);
        }
    }

    @Test
    void testConnectionEndpoint_reportsDeepSeekStatus() {
        aiConnectionTestController.clearRateLimits();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> loginRes = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/auth/login",
                new HttpEntity<>(Map.of("username", "p13admin", "password", "Phase13StrongPassword!99"), loginHeaders),
                String.class);
        assertThat(loginRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> cookies = loginRes.getHeaders().get(HttpHeaders.SET_COOKIE);
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set(HttpHeaders.COOKIE, String.join("; ", cookies.stream().map(c -> c.split(";", 2)[0]).toList()));
        authHeaders.set("X-Requested-With", "XMLHttpRequest");

        ResponseEntity<Map> res = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/admin/ai/test-connection",
                new HttpEntity<>(authHeaders),
                Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKey("deepseek");
        @SuppressWarnings("unchecked")
        Map<String, Object> ds = (Map<String, Object>) res.getBody().get("deepseek");
        assertThat(ds.get("provider")).isEqualTo("deepseek");
        assertThat(ds.get("configured")).isEqualTo(false);
    }
}
