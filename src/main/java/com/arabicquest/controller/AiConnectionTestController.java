// Admin-only rate-limited endpoint to test connectivity to Gemini and Claude APIs with sanitized output.
package com.arabicquest.controller;

import com.arabicquest.config.TooManyRequestsException;
import com.arabicquest.service.ClaudeClient;
import com.arabicquest.service.GeminiTextClient;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@RestController
@RequestMapping("/api/admin/ai")
public class AiConnectionTestController {

    private static final int MAX_TESTS_PER_WINDOW = 6;
    private static final long WINDOW_SECONDS = 60L;

    private final GeminiTextClient geminiTextClient;
    private final ClaudeClient claudeClient;
    private final Map<String, Deque<Instant>> rateLimitBuckets = new ConcurrentHashMap<>();

    public AiConnectionTestController(GeminiTextClient geminiTextClient, ClaudeClient claudeClient) {
        this.geminiTextClient = geminiTextClient;
        this.claudeClient = claudeClient;
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testAiConnections(
            @AuthenticationPrincipal UserDetails userDetails) {
        String key = userDetails != null ? userDetails.getUsername() : "admin";
        enforceRateLimit(key);

        Map<String, Object> geminiStatus = geminiTextClient.testConnection();
        Map<String, Object> claudeStatus = claudeClient.testConnection();
        boolean anyOk = Boolean.TRUE.equals(geminiStatus.get("ok")) || Boolean.TRUE.equals(claudeStatus.get("ok"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", anyOk);
        result.put("gemini", geminiStatus);
        result.put("claude", claudeStatus);
        return ResponseEntity.ok(result);
    }

    public void clearRateLimits() {
        rateLimitBuckets.clear();
    }

    private void enforceRateLimit(String key) {
        Instant now = Instant.now();
        Deque<Instant> bucket = rateLimitBuckets.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        while (!bucket.isEmpty() && bucket.peekFirst().isBefore(now.minusSeconds(WINDOW_SECONDS))) {
            bucket.pollFirst();
        }
        if (bucket.size() >= MAX_TESTS_PER_WINDOW) {
            throw new TooManyRequestsException("Too many connection tests. Please wait 60 seconds before retrying.", 60);
        }
        bucket.addLast(now);
    }
}
