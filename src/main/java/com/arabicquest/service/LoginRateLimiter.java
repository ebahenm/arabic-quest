package com.arabicquest.service;

import com.arabicquest.config.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginRateLimiter {

    private final int maxAttempts;
    private final long windowSeconds;
    private final ConcurrentHashMap<String, Deque<Long>> attemptsByKey = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${LOGIN_MAX_ATTEMPTS:5}") int maxAttempts,
            @Value("${LOGIN_LOCKOUT_SECONDS:900}") long windowSeconds) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
    }

    public void checkAllowed(String clientIp, String username) {
        long now = Instant.now().getEpochSecond();
        checkKey("ip:" + normalize(clientIp), now);
        if (username != null && !username.isBlank()) {
            checkKey("user:" + normalize(username), now);
        }
    }

    public void recordFailure(String clientIp, String username) {
        long now = Instant.now().getEpochSecond();
        if (attemptsByKey.size() > 10_000) {
            evictStaleEntries(now);
        }
        recordKey("ip:" + normalize(clientIp), now);
        if (username != null && !username.isBlank()) {
            recordKey("user:" + normalize(username), now);
        }
    }

    public void resetOnSuccess(String clientIp, String username) {
        attemptsByKey.remove("ip:" + normalize(clientIp));
        if (username != null && !username.isBlank()) {
            attemptsByKey.remove("user:" + normalize(username));
        }
    }

    private void checkKey(String key, long now) {
        Deque<Long> deque = attemptsByKey.get(key);
        if (deque == null) {
            return;
        }
        synchronized (deque) {
            pruneOld(deque, now);
            if (deque.size() >= maxAttempts) {
                long oldest = deque.peekFirst() != null ? deque.peekFirst() : now;
                long retryAfter = Math.max(1L, (oldest + windowSeconds) - now);
                throw new TooManyRequestsException(
                        "Too many failed login attempts. Please wait before trying again.",
                        retryAfter
                );
            }
        }
    }

    private void recordKey(String key, long now) {
        Deque<Long> deque = attemptsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            pruneOld(deque, now);
            deque.addLast(now);
        }
    }

    private void pruneOld(Deque<Long> deque, long now) {
        long cutoff = now - windowSeconds;
        while (!deque.isEmpty() && deque.peekFirst() <= cutoff) {
            deque.pollFirst();
        }
    }

    private void evictStaleEntries(long now) {
        attemptsByKey.entrySet().removeIf(entry -> {
            Deque<Long> d = entry.getValue();
            synchronized (d) {
                pruneOld(d, now);
                return d.isEmpty();
            }
        });
    }

    private static String normalize(String val) {
        return val == null ? "unknown" : val.trim().toLowerCase();
    }
}
