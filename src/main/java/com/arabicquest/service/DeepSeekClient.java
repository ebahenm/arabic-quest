// Optional DeepSeek OpenAI-compatible Chat Completions client (public transcript text only; never student data).
package com.arabicquest.service;

import com.arabicquest.config.AiProviderClientException;
import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.service.ClaudeClient.ClaudeMessageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-flash";
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    public static final int READ_TIMEOUT_MS = 120_000;
    public static final int DEFAULT_MAX_RETRIES = 3;

    private final ObjectMapper objectMapper;
    private RestTemplate restTemplate;
    private AiUsageGuard aiUsageGuard;
    private int maxAttempts = DEFAULT_MAX_RETRIES;
    private long backoffBaseMs = 500L;

    @Value("${DEEPSEEK_API_KEY:}")
    private String apiKey;

    @Value("${DEEPSEEK_BASE_URL:" + DEFAULT_BASE_URL + "}")
    private String baseUrl = DEFAULT_BASE_URL;

    @Value("${DEEPSEEK_MODEL:" + DEFAULT_MODEL + "}")
    private String model = DEFAULT_MODEL;

    public DeepSeekClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Autowired(required = false)
    public void setAiUsageGuard(AiUsageGuard aiUsageGuard) {
        this.aiUsageGuard = aiUsageGuard;
    }

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    public void setModel(String model) {
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
    }

    public void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = Math.max(0L, backoffBaseMs);
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, Math.min(5, maxAttempts));
    }

    public String getModel() {
        return (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
    }

    public String getBaseUrl() {
        return (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String callChatJson(String systemPrompt, String userPrompt, int maxTokens) {
        return callChatDetailed(systemPrompt, userPrompt, maxTokens).text();
    }

    public ClaudeMessageResponse callChatDetailed(String systemPrompt, String userPrompt, int maxTokens) {
        if (!isConfigured()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is not configured.");
        }

        String endpoint = buildChatCompletionsUrl(getBaseUrl());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt != null ? userPrompt : ""));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", getModel());
        body.put("max_tokens", Math.max(256, maxTokens));
        body.put("temperature", 0.2);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", messages);

        String reqJson;
        try {
            reqJson = objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize DeepSeek request: "
                    + AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey));
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (aiUsageGuard != null) {
                aiUsageGuard.assertCanCall("deepseek");
            }
            int recordedIn = 0, recordedOut = 0;
            try {
                ResponseEntity<String> res = restTemplate.exchange(
                        endpoint,
                        HttpMethod.POST,
                        new HttpEntity<>(reqJson, headers),
                        String.class
                );
                ClaudeMessageResponse parsed = extractChatResponse(res.getBody());
                recordedIn = parsed.inputTokens();
                recordedOut = parsed.outputTokens();
                log.info("DeepSeek API response (model={}, finish_reason={}, input_tokens={}, output_tokens={})",
                        getModel(), parsed.stopReason(), parsed.inputTokens(), parsed.outputTokens());
                return parsed;
            } catch (HttpStatusCodeException ex) {
                int code = ex.getStatusCode().value();
                String detail = AiErrorSanitizer.extractProviderErrorDetail(ex, apiKey);
                log.warn("DeepSeek API returned HTTP {} (model={}, attempt {}/{}): {}",
                        code, getModel(), attempt, maxAttempts, detail);
                boolean retryable = (code == 429 || code >= 500);
                if (retryable && attempt < maxAttempts) {
                    sleepBackoff(attempt);
                    continue;
                }
                if (code == 429) {
                    throw new TooManyRequestsException("DeepSeek API rate limit reached: " + detail, 30);
                }
                if (code >= 400 && code < 500) {
                    throw new AiProviderClientException(code, "DeepSeek API returned HTTP " + code + ": " + detail);
                }
                throw new IllegalStateException("DeepSeek API returned HTTP " + code + ": " + detail);
            } catch (ResourceAccessException ex) {
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                    continue;
                }
                throw new IllegalStateException("DeepSeek API network timeout: "
                        + AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey));
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to call DeepSeek API: "
                        + AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey));
            } finally {
                if (aiUsageGuard != null) {
                    aiUsageGuard.recordCall("deepseek", recordedIn, recordedOut);
                }
            }
        }
        throw new IllegalStateException("DeepSeek API call exhausted retry attempts.");
    }

    public Map<String, Object> testConnection() {
        if (!isConfigured()) {
            return Map.of(
                    "provider", "deepseek",
                    "model", getModel(),
                    "configured", false,
                    "ok", false,
                    "message", "DEEPSEEK_API_KEY is not configured."
            );
        }
        try {
            String reply = callChatJson(
                    "Reply with valid JSON only.",
                    "Return {\"status\":\"ok\"}",
                    64
            );
            return Map.of(
                    "provider", "deepseek",
                    "model", getModel(),
                    "configured", true,
                    "ok", reply != null && !reply.isBlank(),
                    "message", "Connected to DeepSeek (" + getModel() + ")"
            );
        } catch (Exception ex) {
            return Map.of(
                    "provider", "deepseek",
                    "model", getModel(),
                    "configured", true,
                    "ok", false,
                    "message", AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey)
            );
        }
    }

    private ClaudeMessageResponse extractChatResponse(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty response from DeepSeek API.");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("DeepSeek response contained no choices.");
        }
        JsonNode first = choices.get(0);
        String finishReason = first.path("finish_reason").asText("stop");
        String normalizedStop = "length".equalsIgnoreCase(finishReason) ? "max_tokens" : finishReason;
        String rawContent = first.path("message").path("content").asText("");
        if (rawContent.isBlank()) {
            throw new IllegalStateException("DeepSeek response contained empty message content.");
        }
        JsonNode usage = root.path("usage");
        int inputTokens = usage.path("prompt_tokens").asInt(0);
        int outputTokens = usage.path("completion_tokens").asInt(0);

        String clean = GeminiJsonRepairSupport.stripCodeFences(rawContent);
        // Validate/repair JSON cleanly using existing lenient repair support
        JsonNode repaired = GeminiJsonRepairSupport.parseLenientJson(objectMapper, clean);
        return new ClaudeMessageResponse(objectMapper.writeValueAsString(repaired), normalizedStop, inputTokens, outputTokens);
    }

    private static String buildChatCompletionsUrl(String base) {
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (trimmed.endsWith("/chat/completions")) return trimmed;
        if (trimmed.endsWith("/v1")) return trimmed + "/chat/completions";
        return trimmed + "/v1/chat/completions";
    }

    private void sleepBackoff(int attempt) {
        if (backoffBaseMs <= 0L) return;
        long delayMs = backoffBaseMs * (1L << Math.min(attempt - 1, 4));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
