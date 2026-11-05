package com.arabicquest.controller;

import com.arabicquest.dto.*;
import com.arabicquest.model.Role;
import com.arabicquest.model.User;
import com.arabicquest.repository.UserRepository;
import com.arabicquest.service.JwtService;
import com.arabicquest.service.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Set<String> VALID_GOOGLE_ISSUERS = Set.of(
            "accounts.google.com",
            "https://accounts.google.com"
    );

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final String dummyHash;
    private RestTemplate restTemplate = new RestTemplate();

    @Value("${GOOGLE_CLIENT_ID:}")
    private String configuredGoogleClientId;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginRateLimiter loginRateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.dummyHash = passwordEncoder.encode("timing-protection-placeholder");
    }

    // Package-private setter for unit/integration tests
    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String rawUsername = request.getUsername().trim();
        String clientIp = httpRequest.getRemoteAddr();

        loginRateLimiter.checkAllowed(clientIp, rawUsername);

        User user = userRepository.findByUsername(rawUsername)
                .or(() -> userRepository.findByUsernameIgnoreCase(rawUsername))
                .orElse(null);

        if (user == null) {
            passwordEncoder.matches(request.getPassword(), dummyHash);
            loginRateLimiter.recordFailure(clientIp, rawUsername);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginRateLimiter.recordFailure(clientIp, rawUsername);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        loginRateLimiter.resetOnSuccess(clientIp, rawUsername);
        return buildAuthenticatedResponse(user, httpRequest);
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(
            @Valid @RequestBody GoogleAuthRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        loginRateLimiter.checkAllowed(clientIp, null);

        if (configuredGoogleClientId == null || configuredGoogleClientId.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Google Sign-In is not configured by the administrator yet."));
        }

        try {
            URI verifyUri = UriComponentsBuilder
                    .fromHttpUrl("https://oauth2.googleapis.com/tokeninfo")
                    .queryParam("id_token", request.getCredential())
                    .build()
                    .encode()
                    .toUri();

            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response =
                    (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) restTemplate.getForEntity(verifyUri, Map.class);

            Map<String, Object> body = response.getBody();
            if (response.getStatusCode() != HttpStatus.OK || body == null) {
                loginRateLimiter.recordFailure(clientIp, null);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid Google authentication token"));
            }

            String aud = String.valueOf(body.get("aud"));
            if (!configuredGoogleClientId.trim().equals(aud)) {
                loginRateLimiter.recordFailure(clientIp, null);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Google token audience mismatch"));
            }

            String iss = String.valueOf(body.get("iss"));
            if (!VALID_GOOGLE_ISSUERS.contains(iss)) {
                loginRateLimiter.recordFailure(clientIp, null);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid Google token issuer"));
            }

            String emailVerified = String.valueOf(body.get("email_verified"));
            if (!"true".equalsIgnoreCase(emailVerified)) {
                loginRateLimiter.recordFailure(clientIp, null);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Google email is not verified"));
            }

            Object expObj = body.get("exp");
            if (expObj != null) {
                long exp = Long.parseLong(String.valueOf(expObj));
                if (Instant.now().getEpochSecond() >= exp) {
                    loginRateLimiter.recordFailure(clientIp, null);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("error", "Google token has expired"));
                }
            }

            String email = (String) body.get("email");
            if (email == null || email.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Unable to retrieve email from Google"));
            }

            String cleanEmail = email.trim().toLowerCase();

            // Admin-only registration policy: DO NOT auto-create accounts and NEVER overwrite passwords!
            User user = userRepository.findByUsername(cleanEmail).orElse(null);
            if (user == null) {
                loginRateLimiter.recordFailure(clientIp, cleanEmail);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "No student account found for " + cleanEmail + ". Please ask the administrator to create your account."));
            }

            loginRateLimiter.resetOnSuccess(clientIp, cleanEmail);
            return buildAuthenticatedResponse(user, httpRequest);
        } catch (HttpClientErrorException e) {
            loginRateLimiter.recordFailure(clientIp, null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired Google credential"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Unable to verify Google authentication at this time"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest httpRequest) {
        ResponseCookie clearCookie = ResponseCookie.from(JwtService.AUTH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(isSecureRequest(httpRequest))
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(Map.of("message", "Logged out successfully"));
    }

    @Transactional
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Current password is incorrect"));
        }

        if ("admin123".equals(request.newPassword()) || "learn123".equals(request.newPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Please choose a strong, non-default password"));
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setCredentialsCustomized(true);
        user.incrementTokenVersion();
        userRepository.save(user);

        String freshToken = jwtService.generateToken(user);
        ResponseCookie cookie = buildAuthCookie(freshToken, httpRequest);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("message", "Password updated successfully. All previous sessions have been invalidated."));
    }

    @Transactional
    @PostMapping("/update-credentials")
    public ResponseEntity<?> updateCredentials(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateCredentialsRequest request,
            HttpServletRequest httpRequest) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Current password is incorrect"));
        }

        boolean changed = false;

        if (request.newUsername() != null && !request.newUsername().isBlank()) {
            String cleanUsername = request.newUsername().trim();
            if (cleanUsername.length() < 3) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Username must be at least 3 characters"));
            }
            if (!cleanUsername.equals(user.getUsername())) {
                if (userRepository.existsByUsernameIgnoreCaseAndIdNot(cleanUsername, user.getId())) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "Username '" + cleanUsername + "' is already taken"));
                }
                user.setUsername(cleanUsername);
                changed = true;
            }
        }

        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            String newPw = request.newPassword();
            if (newPw.length() < 8) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "New password must be at least 8 characters"));
            }
            if ("admin123".equals(newPw) || "learn123".equals(newPw)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Please choose a strong, non-default password"));
            }
            user.setPassword(passwordEncoder.encode(newPw));
            changed = true;
        }

        if (!changed) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Please enter a new username or a new password to save changes"));
        }

        user.setCredentialsCustomized(true);
        user.incrementTokenVersion();
        userRepository.save(user);

        String freshToken = jwtService.generateToken(user);
        ResponseCookie cookie = buildAuthCookie(freshToken, httpRequest);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of(
                        "message", "Login credentials updated successfully!",
                        "username", user.getUsername(),
                        "fullName", user.getFullName(),
                        "role", user.getRole().name(),
                        "token", freshToken
                ));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        boolean defaultPasswordWarning = user.getRole() == Role.ROLE_ADMIN
                && passwordEncoder.matches("admin123", user.getPassword());

        return ResponseEntity.ok(new AuthResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole().name(),
                defaultPasswordWarning
        ));
    }

    private ResponseEntity<LoginResponse> buildAuthenticatedResponse(User user, HttpServletRequest httpRequest) {
        String jwt = jwtService.generateToken(user);
        ResponseCookie cookie = buildAuthCookie(jwt, httpRequest);

        LoginResponse payload = new LoginResponse(
                jwt,
                user.getUsername(),
                user.getFullName(),
                user.getRole().name()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(payload);
    }

    private ResponseCookie buildAuthCookie(String jwt, HttpServletRequest httpRequest) {
        return ResponseCookie.from(JwtService.AUTH_COOKIE_NAME, jwt)
                .httpOnly(true)
                .secure(isSecureRequest(httpRequest))
                .sameSite("Strict")
                .path("/")
                .maxAge(jwtService.getTtlSeconds())
                .build();
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        return proto != null && proto.equalsIgnoreCase("https");
    }
}
