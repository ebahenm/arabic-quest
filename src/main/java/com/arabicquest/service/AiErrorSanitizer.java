// Extracts, sanitizes, and logs upstream AI HTTP errors without exposing API keys or secrets.
package com.arabicquest.service;

import com.arabicquest.config.AiProviderClientException;
import com.arabicquest.config.TooManyRequestsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientResponseException;

import java.util.regex.Pattern;

public final class AiErrorSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern GOOGLE_KEY_PATTERN = Pattern.compile("AIza[0-9A-Za-z_-]{10,}");
    private static final Pattern OPENAI_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{10,}");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]+");
    private static final Pattern QUERY_KEY_PATTERN = Pattern.compile("(?i)([?&]key=)[^&\\s\"']+");
    private static final int MAX_DETAIL_LEN = 320;

    private AiErrorSanitizer() {
    }

    public static String extractProviderErrorDetail(Exception ex, String... secrets) {
        if (!(ex instanceof RestClientResponseException rcre)) {
            return "";
        }
        String rawBody = rcre.getResponseBodyAsString();
        String parsed = extractFromBody(rawBody);
        if (parsed.isBlank() && ex instanceof HttpStatusCodeException hsce) {
            String statusText = hsce.getStatusText();
            if (statusText != null && !statusText.isBlank() && rawBody != null && !rawBody.isBlank()) {
                parsed = statusText;
            }
        }
        return sanitizeText(parsed, secrets);
    }

    public static void logProviderHttpError(
            Logger log,
            String providerName,
            String phaseName,
            Exception ex,
            String... secrets) {
        if (log == null || ex == null) {
            return;
        }
        if (ex instanceof RestClientResponseException rcre) {
            int code = rcre.getStatusCode().value();
            String detail = extractProviderErrorDetail(ex, secrets);
            if (!detail.isBlank()) {
                log.warn("{} error during {} (HTTP {}): {}", providerName, phaseName, code, detail);
            } else {
                log.warn("{} error during {} (HTTP {})", providerName, phaseName, code);
            }
            return;
        }
        log.warn("{} error during {}: {}", providerName, phaseName, ex.getClass().getSimpleName());
    }

    public static String sanitizeText(String raw, String... secrets) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String clean = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (secrets != null) {
            for (String s : secrets) {
                if (s != null && !s.isBlank()) {
                    clean = clean.replace(s.trim(), "[REDACTED]");
                }
            }
        }
        clean = GOOGLE_KEY_PATTERN.matcher(clean).replaceAll("[REDACTED]");
        clean = OPENAI_KEY_PATTERN.matcher(clean).replaceAll("[REDACTED]");
        clean = BEARER_PATTERN.matcher(clean).replaceAll("Bearer [REDACTED]");
        clean = QUERY_KEY_PATTERN.matcher(clean).replaceAll("$1[REDACTED]");
        clean = clean.replaceAll("\\s+", " ").trim();
        if (clean.length() > MAX_DETAIL_LEN) {
            clean = clean.substring(0, MAX_DETAIL_LEN) + "...";
        }
        return clean;
    }

    public static boolean isFallbackTriggerError(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof TooManyRequestsException) {
            return true;
        }
        if (ex instanceof AiProviderClientException apce) {
            int code = apce.getStatusCode();
            return code == 400 || code == 403 || code == 404 || code == 429 || code >= 500;
        }
        if (ex instanceof HttpClientErrorException clientErr) {
            int code = clientErr.getStatusCode().value();
            return code == 400 || code == 403 || code == 429;
        }
        if (ex instanceof HttpServerErrorException) {
            return true;
        }
        if (ex instanceof IllegalStateException ise && ise.getMessage() != null) {
            String msg = ise.getMessage();
            return msg.contains("HTTP 5") || msg.contains("HTTP 400") || msg.contains("HTTP 403") || msg.contains("HTTP 429");
        }
        return false;
    }

    private static String extractFromBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(rawBody);
            JsonNode err = root.has("error") ? root.get("error") : root;
            String status = firstNonBlank(
                    err.path("status").asText(""),
                    err.path("type").asText(""),
                    err.path("code").asText("")
            );
            String message = firstNonBlank(
                    err.path("message").asText(""),
                    root.path("message").asText("")
            );
            if (!status.isBlank() && !message.isBlank()) {
                return status + ": " + message;
            }
            if (!message.isBlank()) {
                return message;
            }
            if (!status.isBlank()) {
                return status;
            }
        } catch (Exception ignored) {
        }
        return rawBody.length() > MAX_DETAIL_LEN ? rawBody.substring(0, MAX_DETAIL_LEN) : rawBody;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }
}
