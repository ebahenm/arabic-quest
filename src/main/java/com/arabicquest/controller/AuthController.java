package com.arabicquest.controller;

import com.arabicquest.dto.*;
import com.arabicquest.model.Role;
import com.arabicquest.model.User;
import com.arabicquest.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${GOOGLE_CLIENT_ID:}")
    private String configuredGoogleClientId;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername().trim())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        String credentials = user.getUsername() + ":" + request.getPassword();
        String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return ResponseEntity.ok(new LoginResponse(
                token,
                user.getUsername(),
                user.getFullName(),
                user.getRole().name()
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        String cleanUsername = request.getUsername().trim().toLowerCase();

        if (userRepository.existsByUsername(cleanUsername)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Username '" + cleanUsername + "' is already taken"));
        }

        User newUser = new User(
                cleanUsername,
                passwordEncoder.encode(request.getPassword()),
                request.getFullName().trim(),
                Role.ROLE_STUDENT
        );
        userRepository.save(newUser);

        String credentials = newUser.getUsername() + ":" + request.getPassword();
        String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return ResponseEntity.ok(new LoginResponse(
                token,
                newUser.getUsername(),
                newUser.getFullName(),
                newUser.getRole().name()
        ));
    }

    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@Valid @RequestBody GoogleAuthRequest request) {
        try {
            // Verify Google ID token via Google TokenInfo API
            String url = "https://oauth2.googleapis.com/tokeninfo?id_token=" + request.getCredential();
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid Google authentication token"));
            }

            Map<String, Object> body = response.getBody();
            String email = (String) body.get("email");
            String name = (String) body.get("name");

            if (email == null || email.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Unable to retrieve email from Google"));
            }

            String cleanEmail = email.trim().toLowerCase();
            String studentName = (name != null && !name.isBlank()) ? name : cleanEmail.split("@")[0];

            // Find existing user or register new student account
            User user = userRepository.findByUsername(cleanEmail).orElse(null);
            String rawPassword;

            if (user == null) {
                rawPassword = "G-" + UUID.randomUUID().toString();
                user = new User(
                        cleanEmail,
                        passwordEncoder.encode(rawPassword),
                        studentName,
                        Role.ROLE_STUDENT
                );
                userRepository.save(user);
            } else {
                // Generate a temporary session credential for Basic auth
                rawPassword = "G-" + UUID.randomUUID().toString();
                user.setPassword(passwordEncoder.encode(rawPassword));
                userRepository.save(user);
            }

            String credentials = user.getUsername() + ":" + rawPassword;
            String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            return ResponseEntity.ok(new LoginResponse(
                token,
                user.getUsername(),
                user.getFullName(),
                user.getRole().name()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Google authentication failed: " + e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        return ResponseEntity.ok(new AuthResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole().name()
        ));
    }
}
