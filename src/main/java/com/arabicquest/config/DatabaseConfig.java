package com.arabicquest.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
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

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    public record ParsedJdbcConfig(String jdbcUrl, String username, String password) {}
    public record HostAndDatabase(String host, String databaseName) {}

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
        } else if (url != null && !url.isBlank() && !url.startsWith("jdbc:")) {
            ParsedJdbcConfig parsed = parseDatabaseUrl(url.trim(), username, password);
            url = parsed.jdbcUrl();
            username = parsed.username();
            password = parsed.password();
        }

        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is missing in 'prod' profile.");
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("TurathtonaNeonPool");
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);

        // Tuned specifically for Render 512MB + Neon Serverless PostgreSQL (cold-wakeup resilient)
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(60_000L);
        hikari.setValidationTimeout(10_000L);
        hikari.setIdleTimeout(120_000L);
        hikari.setMaxLifetime(240_000L);
        hikari.setKeepaliveTime(60_000L);
        // Never abort SpringApplication.run with status 1 if Neon compute is waking from sleep
        hikari.setInitializationFailTimeout(-1L);

        // PostgreSQL driver-level socket & PgBouncer compatibility guards
        hikari.addDataSourceProperty("connectTimeout", "20");
        hikari.addDataSourceProperty("loginTimeout", "20");
        hikari.addDataSourceProperty("socketTimeout", "60");
        hikari.addDataSourceProperty("tcpKeepAlive", "true");
        hikari.addDataSourceProperty("prepareThreshold", "0");

        HostAndDatabase redacted = extractHostAndDatabase(url);
        log.info("Initialized resilient HikariDataSource (maxPoolSize=4, minIdle=0, connTimeout=60s) for host={}, database={}",
                redacted.host(), redacted.databaseName());
        return new HikariDataSource(hikari);
    }

    public static HostAndDatabase extractHostAndDatabase(String jdbcOrRawUrl) {
        if (jdbcOrRawUrl == null || jdbcOrRawUrl.isBlank()) {
            return new HostAndDatabase("unknown", "unknown");
        }
        try {
            String stripped = jdbcOrRawUrl.trim();
            if (stripped.startsWith("jdbc:")) {
                stripped = stripped.substring("jdbc:".length());
            }
            if (stripped.startsWith("postgres://")) {
                stripped = "postgresql://" + stripped.substring("postgres://".length());
            }
            URI uri = new URI(stripped);
            String host = uri.getHost() != null ? uri.getHost() : "unknown";
            String path = uri.getPath();
            String dbName = "unknown";
            if (path != null && !path.isBlank()) {
                dbName = path.startsWith("/") ? path.substring(1) : path;
                if (dbName.isBlank()) {
                    dbName = "unknown";
                }
            }
            return new HostAndDatabase(host, dbName);
        } catch (Exception e) {
            return new HostAndDatabase("unparseable-host", "unknown");
        }
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
            throw new IllegalStateException("Invalid DATABASE_URL format");
        }
    }

    private static String percentDecode(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
