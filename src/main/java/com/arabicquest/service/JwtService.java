package com.arabicquest.service;

import com.arabicquest.model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String HMAC_ALG = "HmacSHA256";
    public static final String AUTH_COOKIE_NAME = "turath_session";

    private final byte[] signingKey;
    private final long ttlSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record JwtClaims(String username, String role, int tokenVersion, long expiresAtEpochSeconds) {}

    public JwtService(
            @Value("${JWT_SECRET:}") String configuredSecret,
            @Value("${JWT_TTL_SECONDS:43200}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
        if (configuredSecret != null && configuredSecret.trim().length() >= 32) {
            this.signingKey = configuredSecret.trim().getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] randomBytes = new byte[32];
            new SecureRandom().nextBytes(randomBytes);
            this.signingKey = randomBytes;
            log.info("Generated ephemeral 256-bit JWT signing key. Set JWT_SECRET (>=32 chars) in production to persist sessions across restarts.");
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public String generateToken(User user) {
        try {
            long now = Instant.now().getEpochSecond();
            long exp = now + ttlSeconds;

            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", user.getUsername());
            payload.put("role", user.getRole().name());
            payload.put("ver", user.getTokenVersion());
            payload.put("iat", now);
            payload.put("exp", exp);

            String encodedHeader = base64UrlEncode(objectMapper.writeValueAsBytes(header));
            String encodedPayload = base64UrlEncode(objectMapper.writeValueAsBytes(payload));
            String signingInput = encodedHeader + "." + encodedPayload;
            String signature = base64UrlEncode(hmacSha256(signingInput));

            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    public Optional<JwtClaims> verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = token.trim().split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }

            Map<String, Object> header = objectMapper.readValue(
                    base64UrlDecode(parts[0]), new TypeReference<Map<String, Object>>() {});
            if (!"HS256".equals(header.get("alg"))) {
                return Optional.empty();
            }

            String signingInput = parts[0] + "." + parts[1];
            byte[] expectedSig = hmacSha256(signingInput);
            byte[] actualSig = base64UrlDecode(parts[2]);
            if (!MessageDigest.isEqual(expectedSig, actualSig)) {
                return Optional.empty();
            }

            Map<String, Object> payload = objectMapper.readValue(
                    base64UrlDecode(parts[1]), new TypeReference<Map<String, Object>>() {});

            Object expObj = payload.get("exp");
            String sub = (String) payload.get("sub");
            String role = (String) payload.get("role");
            Object verObj = payload.get("ver");

            if (!(expObj instanceof Number expNum) || sub == null || sub.isBlank() || role == null) {
                return Optional.empty();
            }

            long exp = expNum.longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                return Optional.empty();
            }

            int ver = (verObj instanceof Number v) ? v.intValue() : 0;
            return Optional.of(new JwtClaims(sub, role, ver, exp));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] hmacSha256(String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALG);
        mac.init(new SecretKeySpec(signingKey, HMAC_ALG));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] base64UrlDecode(String str) {
        return Base64.getUrlDecoder().decode(str);
    }
}
