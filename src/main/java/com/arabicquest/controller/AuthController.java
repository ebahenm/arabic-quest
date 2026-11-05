package com.arabicquest.controller;

import com.arabicquest.dto.AuthResponse;
import com.arabicquest.dto.LoginRequest;
import com.arabicquest.dto.LoginResponse;
import com.arabicquest.model.User;
import com.arabicquest.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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

        // Generate base64 basic auth token
        String credentials = user.getUsername() + ":" + request.getPassword();
        String token = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return ResponseEntity.ok(new LoginResponse(
                token,
                user.getUsername(),
                user.getFullName(),
                user.getRole().name()
        ));
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
