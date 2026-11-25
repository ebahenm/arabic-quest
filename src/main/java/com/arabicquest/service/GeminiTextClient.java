// Gemini API client defaulting to gemini-3.8-flash with safe thinking-level validation and x-goog-api-key header.
package com.arabicquest.service;

import com.arabicquest.config.AiProviderClientException;
import com.arabicquest.config.TooManyRequestsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class GeminiTextClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiTextClient.class);
    public static final String DEFAULT_GEMINI_MODEL = "gemini-3.8-flash";
    private static final Set<String> ALLOWED_THINKING_LEVELS = Set.of("low", "medium", "high");

    private final ObjectMapper objectMapper;
    private RestTemplate restTemplate = GeminiVideoBankSupport.createDefaultRestTemplate();
    private AiUsageGuard aiUsageGuard;

    @Value("${GEMINI_API_KEY:${AI_API_KEY:}}")
    private String apiKey;

    @Value("${GEMINI_MODEL:" + DEFAULT_GEMINI_MODEL + "}")
    private String model = DEFAULT_GEMINI_MODEL;

    @Value("${GEMINI_THINKING_LEVEL:low}")
    private String thinkingLevel = "low";

    public GeminiTextClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Autowired(required = false)
    public void setAiUsageGuard(AiUsageGuard aiUsageGuard) {
        this.aiUsageGuard = aiUsageGuard;
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
        this.model = (model == null || model.isBlank()) ? DEFAULT_GEMINI_MODEL : model.trim();
    }

    public String getModel() {
        return (model == null || model.isBlank()) ? DEFAULT_GEMINI_MODEL : model.trim();
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = normalizeThinkingLevel(thinkingLevel);
    }

    public String getThinkingLevel() {
        return normalizeThinkingLevel(this.thinkingLevel);
    }

    public static String normalizeThinkingLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "low";
        }
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if ("minimal".equals(clean) || !ALLOWED_THINKING_LEVELS.contains(clean)) {
            return "low";
        }
        return clean;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String callTextJson(String systemInstruction, String userPrompt) {
        if (!isConfigured()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured.");
        }
        if (aiUsageGuard != null) {
            aiUsageGuard.assertCanCall("gemini");
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + getModel() + ":generateContent";

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("thinkingConfig", Map.of("thinkingLevel", getThinkingLevel()));

        Map<String, Object> body = new LinkedHashMap<>();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        }
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
        body.put("generationConfig", generationConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey.trim());

        int recordedIn = 0, recordedOut = 0;
        try {
            String reqJson = objectMapper.writeValueAsString(body);
            ResponseEntity<String> res = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(reqJson, headers),
                    String.class
            );
            String resBody = res.getBody();
            if (resBody != null && !resBody.isBlank()) {
                JsonNode meta = objectMapper.readTree(resBody).path("usageMetadata");
                recordedIn = meta.path("promptTokenCount").asInt(0);
                recordedOut = meta.path("candidatesTokenCount").asInt(0);
            }
            return extractTextFromResponse(resBody);
        } catch (HttpStatusCodeException ex) {
            int code = ex.getStatusCode().value();
            String detail = AiErrorSanitizer.extractProviderErrorDetail(ex, apiKey);
            log.warn("Gemini API returned HTTP {} (model={}): {}", code, getModel(), detail);
            if (code == 429) {
                throw new TooManyRequestsException("Gemini API rate limit reached: " + detail, 30);
            }
            if (code >= 400 && code < 500) {
                throw new AiProviderClientException(code, "Gemini API returned HTTP " + code + ": " + detail);
            }
            throw new IllegalStateException("Gemini API returned HTTP " + code + ": " + detail);
        } catch (ResourceAccessException ex) {
            throw new IllegalStateException("Gemini API network timeout: "
                    + AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to call Gemini API: "
                    + AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey));
        } finally {
            if (aiUsageGuard != null) {
                aiUsageGuard.recordCall("gemini", recordedIn, recordedOut);
            }
        }
    }

    public Map<String, Object> testConnection() {
        if (!isConfigured()) {
            return Map.of(
                    "provider", "gemini",
                    "model", getModel(),
                    "configured", false,
                    "ok", false,
                    "message", "GEMINI_API_KEY is not configured."
            );
        }
        try {
            String reply = callTextJson("Reply with JSON only.", "Return {\"status\":\"ok\"}");
            return Map.of(
                    "provider", "gemini",
                    "model", getModel(),
                    "configured", true,
                    "ok", reply != null && !reply.isBlank(),
                    "message", "Connected to Gemini (" + getModel() + ", thinkingLevel=" + getThinkingLevel() + ")"
            );
        } catch (Exception ex) {
            return Map.of(
                    "provider", "gemini",
                    "model", getModel(),
                    "configured", true,
                    "ok", false,
                    "message", AiErrorSanitizer.sanitizeText(ex.getMessage(), apiKey)
            );
        }
    }

    private String extractTextFromResponse(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty response from Gemini API.");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (parts.isArray() && !parts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : parts) {
                if (p.hasNonNull("text")) {
                    sb.append(p.get("text").asText());
                }
            }
            if (!sb.isEmpty()) {
                return GeminiJsonRepairSupport.stripCodeFences(sb.toString());
            }
        }
        throw new IllegalStateException("Gemini response contained no text parts.");
    }
}
