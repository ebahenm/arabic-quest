package com.arabicquest.config;

import com.arabicquest.model.QuestionStatus;
import com.arabicquest.model.QuestionType;
import com.arabicquest.model.Role;
import com.arabicquest.model.User;
import com.arabicquest.repository.EpisodeRepository;
import com.arabicquest.repository.QuestionRepository;
import com.arabicquest.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;

/**
 * Non-sensitive startup bootstrapper.
 * Syncs administrator credentials and safely backfills default flags on pre-existing database rows
 * without embedding any episode, quiz, answer, vocabulary, or transcript content in source code.
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private final SecureRandom secureRandom = new SecureRandom();

    @Bean
    public CommandLineRunner initData(
            UserRepository userRepository,
            QuestionRepository questionRepository,
            EpisodeRepository episodeRepository,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            @Value("${ADMIN_USERNAME:admin}") String adminUsername,
            @Value("${ADMIN_PASSWORD:}") String adminPassword,
            @Value("${FORCE_ADMIN_ENV_SYNC:false}") boolean forceAdminEnvSync) {
        return args -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    tx.executeWithoutResult(status -> {
                        syncAdminAccount(userRepository, passwordEncoder, adminUsername, adminPassword, forceAdminEnvSync);
                        backfillExistingDatabaseQuestions(questionRepository);
                        episodeRepository.backfillNullEnabledToTrue();
                        episodeRepository.backfillNullSortOrder();
                    });
                    return;
                } catch (Exception ex) {
                    if (attempt < maxAttempts) {
                        log.warn("DataInitializer attempt {}/{} encountered transient error ({}); retrying in 3s...",
                                attempt, maxAttempts, ex.getClass().getSimpleName());
                        try {
                            Thread.sleep(3000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } else {
                        log.error("DataInitializer failed after {} attempts; continuing server startup without aborting container.",
                                maxAttempts);
                    }
                }
            }
        };
    }

    private void backfillExistingDatabaseQuestions(QuestionRepository questionRepository) {
        int updatedActive = questionRepository.backfillNullActiveToTrue();
        int updatedStatus = questionRepository.backfillNullStatus(QuestionStatus.APPROVED);
        int updatedType = questionRepository.backfillNullType(QuestionType.RECALL);
        int updatedReportCount = questionRepository.backfillNullReportCount();
        if (updatedActive > 0 || updatedStatus > 0 || updatedType > 0 || updatedReportCount > 0) {
            log.info("Backfilled existing database questions safely (active={}, status={}, type={}, reportCount={}).",
                    updatedActive, updatedStatus, updatedType, updatedReportCount);
        }
    }

    private void syncAdminAccount(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            String configuredUsername,
            String configuredPassword,
            boolean forceAdminEnvSync) {

        String cleanAdminUser = (configuredUsername == null || configuredUsername.isBlank())
                ? "admin"
                : configuredUsername.trim();

        User existingAdmin = userRepository.findByRole(Role.ROLE_ADMIN).stream().findFirst()
                .or(() -> userRepository.findAllByOrderByIdAsc().stream()
                        .filter(u -> u.getUsername() != null && u.getUsername().equalsIgnoreCase(cleanAdminUser))
                        .findFirst())
                .orElse(null);

        if (existingAdmin != null && existingAdmin.isCredentialsCustomized() && !forceAdminEnvSync) {
            log.info("Administrator '{}' has customized login credentials via Admin Command Center; preserving database credentials.",
                    existingAdmin.getUsername());
            return;
        }

        if (configuredPassword != null && !configuredPassword.isBlank()) {
            if (existingAdmin == null) {
                User admin = new User(
                        cleanAdminUser,
                        passwordEncoder.encode(configuredPassword),
                        "Administrator",
                        Role.ROLE_ADMIN
                );
                userRepository.save(admin);
                log.info("Created administrator account '{}' from ADMIN_PASSWORD environment variable.", cleanAdminUser);
            } else {
                boolean canUseTargetUsername = existingAdmin.getUsername().equalsIgnoreCase(cleanAdminUser)
                        || !userRepository.existsByUsernameIgnoreCaseAndIdNot(cleanAdminUser, existingAdmin.getId());
                String targetUsername = canUseTargetUsername ? cleanAdminUser : existingAdmin.getUsername();
                boolean usernameChanged = !existingAdmin.getUsername().equals(targetUsername);
                boolean passwordChanged = !passwordEncoder.matches(configuredPassword, existingAdmin.getPassword());
                if (usernameChanged || passwordChanged) {
                    existingAdmin.setUsername(targetUsername);
                    existingAdmin.setPassword(passwordEncoder.encode(configuredPassword));
                    existingAdmin.incrementTokenVersion();
                    userRepository.save(existingAdmin);
                    log.info("Synchronized administrator credentials for '{}' from environment configuration.", targetUsername);
                }
            }
        } else if (existingAdmin == null) {
            // Only reachable in non-prod ('dev') because ProdStartupValidator enforces ADMIN_PASSWORD in 'prod'.
            String bootstrapPassword = generateRandomPassword(16);
            User admin = new User(
                    cleanAdminUser,
                    passwordEncoder.encode(bootstrapPassword),
                    "Administrator",
                    Role.ROLE_ADMIN
            );
            userRepository.save(admin);
            log.info("Dev bootstrap administrator created for username '{}'. Set ADMIN_PASSWORD in dev if a fixed password is needed.",
                    cleanAdminUser);
        }
    }

    private String generateRandomPassword(int length) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
