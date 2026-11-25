// Rate-limited student endpoint logging sanitized client-side player/browser errors (message and browser only, no PII).
package com.arabicquest.controller;

import com.arabicquest.config.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@RestController
@RequestMapping("/api/student")
public class StudentClientErrorController {

    private static final Logger log = LoggerFactory.getLogger(StudentClientErrorController.class);
    public static final int MAX_ERRORS_PER_MINUTE = 10;
    private static final int MAX_MESSAGE_LEN = 300;
    private static final int MAX_BROWSER_LEN = 160;

    private final Map<String, Deque<Instant>> rateBuckets = new ConcurrentHashMap<>();

    public record ClientErrorRequest(String message, String browser) {}

    @PostMapping("/client-errors")
    public ResponseEntity<Map<String, Object>> reportClientError(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) ClientErrorRequest request,
            HttpServletRequest httpRequest) {
        String key = (userDetails != null ? userDetails.getUsername() : "anon")
                + ":" + (httpRequest != null && httpRequest.getRemoteAddr() != null ? httpRequest.getRemoteAddr() : "unknown");
        enforceRateLimit(key);

        String message = sanitize(request != null ? request.message() : null, MAX_MESSAGE_LEN);
        String browser = sanitize(request != null ? request.browser() : null, MAX_BROWSER_LEN);
        if (message.isEmpty()) {
            throw new IllegalArgumentException("Error message is required");
        }
        // Log only message and browser (never personal data, usernames, or cookies)
        log.warn("CLIENT_ERROR [browser={}] {}", browser.isEmpty() ? "unknown" : browser, message);
        return ResponseEntity.ok(Map.of("logged", true));
    }

    public void resetRateLimits() {
        rateBuckets.clear();
    }

    private void enforceRateLimit(String key) {
        Instant now = Instant.now();
        Deque<Instant> bucket = rateBuckets.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().isBefore(now.minusSeconds(60))) {
                bucket.pollFirst();
            }
            if (bucket.size() >= MAX_ERRORS_PER_MINUTE) {
                throw new TooManyRequestsException("Too many client error reports. Please wait a minute.", 60);
            }
            bucket.addLast(now);
        }
    }

    private static String sanitize(String raw, int maxLen) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[\\r\\n\\t\\x00-\\x1F]", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) : cleaned;
    }
}
