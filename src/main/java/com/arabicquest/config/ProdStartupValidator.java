package com.arabicquest.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Profile("prod")
public class ProdStartupValidator implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ProdStartupValidator.class);

    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "admin123", "admin", "password", "password123", "postgres",
            "learn123", "changeme", "12345678", "turathtona",
            "turathtona2026!admin"
    );

    private static final Set<String> FORBIDDEN_ADMIN_USERNAMES = Set.of(
            "changeme", "password", "postgres", "root", "test", "default"
    );

    private static final Set<String> FORBIDDEN_ADMIN_PATHS = Set.of(
            "turath-hq", "vault-turathtona", "admin", "admin-hq", "admin-login", "admin.html",
            "admin-login.html", "login", "student", "beta", "api"
    );

    private static final Set<String> FORBIDDEN_JWT_SECRETS = Set.of(
            "changeme", "secret", "default", "jwt_secret", "your_jwt_secret",
            "12345678901234567890123456789012", "00000000000000000000000000000000",
            "replace-with-a-32-character-secret-key"
    );

    private final Environment environment;

    public ProdStartupValidator() {
        this.environment = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProdStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (isProdActive(env)) {
            validateProdEnvironment(env);
        } else {
            String configuredPath = env.getProperty("ADMIN_SECRET_PATH");
            if (configuredPath == null || configuredPath.isBlank()) {
                byte[] randomBytes = new byte[6];
                new SecureRandom().nextBytes(randomBytes);
                String generatedDevSlug = "dev-hq-" + HexFormat.of().formatHex(randomBytes);
                env.getPropertySources().addLast(new MapPropertySource(
                        "ephemeralDevAdminPath",
                        Map.of("ADMIN_SECRET_PATH", generatedDevSlug)
                ));
                log.info("Dev mode: generated ephemeral ADMIN_SECRET_PATH '/{}' (login at '/{}-login').",
                        generatedDevSlug, generatedDevSlug);
            }
        }
    }

    @PostConstruct
    public void verifyOnBeanInit() {
        if (this.environment != null && isProdActive(this.environment)) {
            validateProdEnvironment(this.environment);
        }
    }

    public static boolean isProdActive(Environment env) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            return true;
        }
        String activeProp = env.getProperty("spring.profiles.active", "");
        for (String p : activeProp.split(",")) {
            if ("prod".equalsIgnoreCase(p.trim())) {
                return true;
            }
        }
        return false;
    }

    public static void validateProdEnvironment(Environment env) {
        List<String> violations = new ArrayList<>();

        String jwtSecret = trimOrNull(env.getProperty("JWT_SECRET"));
        if (jwtSecret == null) {
            violations.add("JWT_SECRET is missing (must be a strong random secret of at least 32 characters)");
        } else if (jwtSecret.length() < 32) {
            violations.add("JWT_SECRET is too short (must be at least 32 characters)");
        } else if (FORBIDDEN_JWT_SECRETS.contains(jwtSecret.toLowerCase())) {
            violations.add("JWT_SECRET equals a forbidden default/placeholder value");
        }

        String adminUsername = trimOrNull(env.getProperty("ADMIN_USERNAME"));
        if (adminUsername == null) {
            violations.add("ADMIN_USERNAME is missing");
        } else if (adminUsername.length() < 3) {
            violations.add("ADMIN_USERNAME is too short (must be at least 3 characters)");
        } else if (FORBIDDEN_ADMIN_USERNAMES.contains(adminUsername.toLowerCase())) {
            violations.add("ADMIN_USERNAME equals a forbidden value");
        }

        String adminPassword = trimOrNull(env.getProperty("ADMIN_PASSWORD"));
        if (adminPassword == null) {
            violations.add("ADMIN_PASSWORD is missing (must be at least 12 characters)");
        } else if (adminPassword.length() < 12) {
            violations.add("ADMIN_PASSWORD is too short (must be at least 12 characters)");
        } else if (FORBIDDEN_PASSWORDS.contains(adminPassword.toLowerCase())) {
            violations.add("ADMIN_PASSWORD is weak or equals a forbidden default");
        }

        String adminPath = normalizeSlug(env.getProperty("ADMIN_SECRET_PATH"));
        if (adminPath == null) {
            violations.add("ADMIN_SECRET_PATH is missing");
        } else if (FORBIDDEN_ADMIN_PATHS.contains(adminPath.toLowerCase()) || adminPath.length() < 6) {
            violations.add("ADMIN_SECRET_PATH must not equal a forbidden default (such as 'turath-hq', 'vault-turathtona', or 'admin') and must be at least 6 characters");
        }

        String rawDbUrl = trimOrNull(env.getProperty("DATABASE_URL"));
        if (rawDbUrl == null) {
            rawDbUrl = trimOrNull(env.getProperty("SPRING_DATASOURCE_URL"));
        }
        if (rawDbUrl == null) {
            violations.add("DATABASE_URL is missing");
        } else {
            String lowerUrl = rawDbUrl.toLowerCase();
            if (lowerUrl.contains("localhost:5432/arabicquest")
                    || lowerUrl.contains("://postgres:postgres@")
                    || lowerUrl.equals("jdbc:postgresql://localhost:5432/postgres")) {
                violations.add("DATABASE_URL uses a forbidden local/default postgres fallback");
            }
        }

        String dbUser = trimOrNull(env.getProperty("SPRING_DATASOURCE_USERNAME"));
        String dbPass = trimOrNull(env.getProperty("SPRING_DATASOURCE_PASSWORD"));
        if ("postgres".equalsIgnoreCase(dbUser) && "postgres".equalsIgnoreCase(dbPass)) {
            violations.add("Database credentials use forbidden default postgres/postgres");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Startup aborted in 'prod' profile due to insecure or missing configuration: "
                            + String.join("; ", violations)
            );
        }
    }

    public static String normalizeSlug(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isBlank() ? null : s;
    }

    private static String trimOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }
}
