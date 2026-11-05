package com.arabicquest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final Environment environment;
    private final String adminSecretPath;
    private final String allowedOriginsProp;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            Environment environment,
            @Value("${ADMIN_SECRET_PATH:turath-hq}") String adminSecretPath,
            @Value("${ALLOWED_ORIGINS:https://turathtona.site,https://www.turathtona.site}") String allowedOriginsProp) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.environment = environment;
        this.adminSecretPath = cleanSlug(adminSecretPath);
        this.allowedOriginsProp = allowedOriginsProp;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = new ArrayList<>();
        for (String origin : allowedOriginsProp.split(",")) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed);
            }
        }
        if (isDevProfile()) {
            origins.add("http://localhost:8080");
            origins.add("http://127.0.0.1:8080");
        }

        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationEntryPoint customAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "error", "Unauthorized",
                    "message", "Authentication required"
            ));
        };
    }

    @Bean
    public AccessDeniedHandler customAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "error", "Forbidden",
                    "message", "Insufficient privileges"
            ));
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        String hqBase = "/" + adminSecretPath;
        String hqLogin = "/" + adminSecretPath + "-login";
        String hqJs = "/" + adminSecretPath + ".js";

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(customAuthenticationEntryPoint())
                .accessDeniedHandler(customAccessDeniedHandler())
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(
                    "/",
                    "/index.html",
                    "/login",
                    "/login/",
                    "/login.html",
                    "/student",
                    "/student/",
                    "/style.css",
                    "/app.js",
                    "/favicon.ico",
                    hqBase,
                    hqBase + "/",
                    hqLogin,
                    hqLogin + "/",
                    hqJs,
                    "/actuator/health",
                    "/api/public/**",
                    "/api/auth/login",
                    "/api/auth/google",
                    "/api/auth/logout"
                ).permitAll();

                if (isDevProfile()) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }

                auth.requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/student/**").hasAnyRole("STUDENT", "ADMIN")
                    .requestMatchers("/api/auth/me", "/api/auth/change-password").authenticated()
                    .anyRequest().authenticated();
            })
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private boolean isDevProfile() {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        return profiles.isEmpty() || profiles.contains("dev");
    }

    private static String cleanSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "turath-hq";
        }
        String s = raw.trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "turath-hq" : s;
    }
}
