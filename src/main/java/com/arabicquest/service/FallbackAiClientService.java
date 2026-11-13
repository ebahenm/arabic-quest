// OpenAI-compatible fallback AI client (FALLBACK_AI_API_KEY) for text-based quiz generation and verification.
package com.arabicquest.service;

import com.arabicquest.config.AiProviderClientException;
import com.arabicquest.config.TooManyRequestsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FallbackAiClientService {

    private static final Logger log = LoggerFactory.getLogger(FallbackAiClientService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate = GeminiVideoBankSupport.createDefaultRestTemplate();

    @Value("${FALLBACK_AI_API_KEY:}")
    private String apiKey;

    @Value("${FALLBACK_AI_BASE_URL:https://api.openai.com/v1}")
    private String baseUrl = "https://api.openai.com/v1";

    @Value("${FALLBACK_AI_MODEL:gpt-4.1}")
    private String model = "gpt-4.1";

    @Value("${app.ai.max-output-tokens:${GEMINI_MAX_OUTPUT_TOKENS:16384}}")
    private int maxOutputTokens = 16384;

    @Value("${app.ai.retry-backoff-ms:300}")
    private long retryBackoffMs = 300;

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public RestTemplate getRestTemplate() {
        return restTemplate;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String invokeStructuredJson(
            String systemInstruction,
            String userPrompt,
            String jsonShapeHint,
            String phaseName) {
        if (!isConfigured()) {
            throw new IllegalStateException("FALLBACK_AI_API_KEY is not configured on the server.");
        }
        String endpoint = buildChatCompletionsUrl();
        String chosenModel = (model == null || model.isBlank()) ? "gpt-4.1" : model.trim();
        String sysPrompt = (systemInstruction != null ? systemInstruction : "")
                + "\n\nRespond with a single valid JSON object only (no markdown fences) matching this structure:\n"
                + (jsonShapeHint != null ? jsonShapeHint : "{}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chosenModel);
        body.put("temperature", 0.25);
        body.put("max_tokens", maxOutputTokens > 0 ? maxOutputTokens : 16384);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", sysPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey.trim());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        Exception lastEx = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(endpoint, entity, String.class);
                return extractMessageContent(response.getBody());
            } catch (Exception ex) {
                lastEx = ex;
                boolean transientErr = ex instanceof HttpClientErrorException.TooManyRequests
                        || ex instanceof HttpServerErrorException
                        || ex instanceof ResourceAccessException;
                if (!transientErr || attempt == 2) {
                    AiErrorSanitizer.logProviderHttpError(log, "Fallback AI", phaseName, ex, apiKey);
                    throw mapFallbackException(ex, phaseName);
                }
                if (retryBackoffMs > 0) {
                    try {
                        Thread.sleep(retryBackoffMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw mapFallbackException(ex, phaseName);
                    }
                }
            }
        }
        throw mapFallbackException(lastEx, phaseName);
    }

    private String extractMessageContent(String rawResponse) throws Exception {
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                sb.append(part.path("text").asText(""));
            }
            return sb.toString();
        }
        return null;
    }

    private String buildChatCompletionsUrl() {
        String raw = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com/v1" : baseUrl.trim();
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw.endsWith("/chat/completions") ? raw : raw + "/chat/completions";
    }

    private RuntimeException mapFallbackException(Exception ex, String phaseName) {
        if (ex instanceof TooManyRequestsException tme) {
            return tme;
        }
        String detail = AiErrorSanitizer.extractProviderErrorDetail(ex, apiKey);
        if (ex instanceof HttpClientErrorException.TooManyRequests) {
            return new TooManyRequestsException(
                    "Fallback AI rate quota reached during " + phaseName
                            + (detail.isEmpty() ? "." : ": " + detail), 60L);
        }
        if (ex instanceof HttpClientErrorException clientErr) {
            int code = clientErr.getStatusCode().value();
            return new AiProviderClientException(code,
                    "Fallback AI rejected the request during " + phaseName + " (HTTP " + code + ")"
                            + (detail.isEmpty() ? "." : ": " + detail));
        }
        if (ex instanceof HttpServerErrorException serverErr) {
            return new IllegalStateException(
                    "Fallback AI service unavailable during " + phaseName
                            + " (HTTP " + serverErr.getStatusCode().value() + ")"
                            + (detail.isEmpty() ? "." : ": " + detail));
        }
        if (ex instanceof ResourceAccessException) {
            return new IllegalStateException("Fallback AI timed out during " + phaseName + ".");
        }
        String kind = ex != null ? ex.getClass().getSimpleName() : "UnknownError";
        return new IllegalStateException("Fallback AI failed during " + phaseName + " (" + kind + ").");
    }
}
