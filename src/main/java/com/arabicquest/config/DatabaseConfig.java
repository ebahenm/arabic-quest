package com.arabicquest.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
@Profile("prod")
public class DatabaseConfig {

    public record ParsedJdbcConfig(String jdbcUrl, String username, String password) {}

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        String url = properties.getUrl();
        String username = properties.getUsername();
        String password = properties.getPassword();

        String envDbUrl = System.getenv("DATABASE_URL");
        if (envDbUrl != null && !envDbUrl.isBlank()) {
            ParsedJdbcConfig parsed = parseDatabaseUrl(envDbUrl.trim(), username, password);
            url = parsed.jdbcUrl();
            username = parsed.username();
            password = parsed.password();
        } else if (url != null && !url.startsWith("jdbc:")) {
            ParsedJdbcConfig parsed = parseDatabaseUrl(url.trim(), username, password);
            url = parsed.jdbcUrl();
            username = parsed.username();
            password = parsed.password();
        }

        return DataSourceBuilder.create()
                .driverClassName("org.postgresql.Driver")
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    public static ParsedJdbcConfig parseDatabaseUrl(String rawUrl, String fallbackUsername, String fallbackPassword) {
        if (rawUrl.startsWith("jdbc:")) {
            return new ParsedJdbcConfig(rawUrl, fallbackUsername, fallbackPassword);
        }

        try {
            String normalizedScheme = rawUrl;
            if (normalizedScheme.startsWith("postgres://")) {
                normalizedScheme = "postgresql://" + normalizedScheme.substring("postgres://".length());
            }

            URI uri = new URI(normalizedScheme);
            String username = fallbackUsername;
            String password = fallbackPassword;

            String rawUserInfo = uri.getRawUserInfo();
            if (rawUserInfo != null && !rawUserInfo.isEmpty()) {
                int colonIdx = rawUserInfo.indexOf(':');
                if (colonIdx >= 0) {
                    username = percentDecode(rawUserInfo.substring(0, colonIdx));
                    password = percentDecode(rawUserInfo.substring(colonIdx + 1));
                } else {
                    username = percentDecode(rawUserInfo);
                }
            }

            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/postgres" : uri.getRawPath();
            String rawQuery = uri.getRawQuery();

            String finalQuery;
            if (rawQuery == null || rawQuery.isBlank()) {
                finalQuery = "sslmode=require";
            } else if (!rawQuery.contains("sslmode=")) {
                finalQuery = rawQuery + "&sslmode=require";
            } else {
                finalQuery = rawQuery;
            }

            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path + "?" + finalQuery;
            return new ParsedJdbcConfig(jdbcUrl, username, password);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid DATABASE_URL format", e);
        }
    }

    private static String percentDecode(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
