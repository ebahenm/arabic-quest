// Anthropic Claude API client using x-api-key header and sanitized error handling.
package com.arabicquest.service;

import com.arabicquest.config.AiProviderClientException;
import com.arabicquest.config.TooManyRequestsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    public static final String DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5";
    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ObjectMapper objectMapper;
    private RestTemplate restTemplate;

    @Value("${ANTHROPIC_API_KEY:}")
    private String apiKey;

    @Value("${CLAUDE_MODEL:" + DEFAULT_CLAUDE_MODEL + "}")
    private String model = DEFAULT_CLAUDE_MODEL;

    public ClaudeClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(90_000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public RestTemplate getRestTemplate() {
        return restTemplate;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setModel(String model) {
        this.model = (model == null || model.isBlank()) ? DEFAULT_CLAUDE_MODEL : model.trim();
    }

    public String getModel() {
        return (model == null || model.isBlank()) ? DEFAULT_CLAUDE_MODEL : model.trim();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public record ClaudeMessageResponse(String text, String stopReason, int inputTokens, int outputTokens) {
        public boolean isMaxTokensTruncated() {
            return "max_tokens".equalsIgnoreCase(stopReason);
        }
    }

    public String callMessagesJson(String systemPrompt, String userPrompt, int maxTokens) {
        return callMessagesDetailed(systemPrompt, userPrompt, maxTokens).text();
    }

    public ClaudeMessageResponse callMessagesDetailed(String systemPrompt, String userPrompt, int maxTokens) {
        if (!isConfigured()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not configured.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey.trim());
        headers.set("anthropic-version", ANTHROPIC_VERSION);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", getModel());
        body.put("max_tokens", Math.max(256, maxTokens));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));

        try {
            String reqJson = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = restTemplate.exchange(
                    MESSAGES_URL,
                    HttpMethod.POST,
                    new HttpEntity<>(reqJson, headers),
                    String.class
            );
            ClaudeMessageResponse parsed = extractMessageResponse(res.getBody());
            log.info("Claude API response (model={}, stop_reason={}, input_tokens={}, output_tokens={})",
                    getModel(), parsed.stopReason(), parsed.inputTokens(), parsed.outputTokens());
            return parsed;
        } catch (HttpStatusCodeException ex) {
            int code = ex.getStatusCode().value();
            String detail = AiErrorSanitizer.extractProviderErrorDetail(ex, apiKey);
            log.warn("Claude API returned HTTP {} (model={}): {}", code, getModel(), detail);
            if (code == 429) {
                throw new TooManyRequestsException("Claude API rate limit reached: " + detail, 30);
            }
            if (code >= 400 && code < 500) {
                throw new AiProviderClientException(code, "Claude API returned HTTP " + code + ": " + detail);
            }
            throw new IllegalStateException("Claude API returned HTTP " + code + ": " + detail);
        } catch (ResourceAccessException ex) {
            throw new IllegalStateException("Claude API network timeout: "
                    + AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to call Claude API: "
                    + AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey));
        }
    }

    public Map<String, Object> testConnection() {
        if (!isConfigured()) {
            return Map.of(
                    "provider", "claude",
                    "model", getModel(),
                    "configured", false,
                    "ok", false,
                    "message", "ANTHROPIC_API_KEY is not configured."
            );
        }
        try {
            String reply = callMessagesJson(
                    "Reply with valid JSON only.",
                    "Return {\"status\":\"ok\"}",
                    64
            );
            return Map.of(
                    "provider", "claude",
                    "model", getModel(),
                    "configured", true,
                    "ok", reply != null && !reply.isBlank(),
                    "message", "Connected to Claude (" + getModel() + ")"
            );
        } catch (Exception ex) {
            return Map.of(
                    "provider", "claude",
                    "model", getModel(),
                    "configured", true,
                    "ok", false,
                    "message", AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey)
            );
        }
    }

    private ClaudeMessageResponse extractMessageResponse(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty response from Claude API.");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        String stopReason = root.path("stop_reason").asText("end_turn");
        JsonNode usage = root.path("usage");
        int inputTokens = usage.path("input_tokens").asInt(0);
        int outputTokens = usage.path("output_tokens").asInt(0);
        JsonNode content = root.path("content");
        if (content.isArray() && !content.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block.hasNonNull("text")) {
                    sb.append(block.get("text").asText());
                }
            }
            if (!sb.isEmpty()) {
                String cleanText = GeminiJsonRepairSupport.stripCodeFences(sb.toString());
                return new ClaudeMessageResponse(cleanText, stopReason, inputTokens, outputTokens);
            }
        }
        throw new IllegalStateException("Claude response contained no text blocks.");
    }
}
