package com.arabicquest.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
@Profile("prod")
public class DatabaseConfig {

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
            envDbUrl = envDbUrl.trim();
            if (!envDbUrl.startsWith("jdbc:")) {
                try {
                    URI uri = new URI(envDbUrl);
                    String userInfo = uri.getUserInfo();
                    if (userInfo != null && userInfo.contains(":")) {
                        String[] parts = userInfo.split(":", 2);
                        username = parts[0];
                        password = parts[1];
                    }

                    String host = uri.getHost();
                    int port = uri.getPort() == -1 ? 5432 : uri.getPort();
                    String path = uri.getPath(); // /dbname

                    // Construct clean JDBC URL with sslmode=require
                    url = "jdbc:postgresql://" + host + ":" + port + path + "?sslmode=require";
                    System.out.println(">>> Configured JDBC connection to database host: " + host);
                } catch (Exception e) {
                    System.err.println(">>> Warning: Failed to parse DATABASE_URL URI: " + e.getMessage());
                }
            } else {
                url = envDbUrl;
            }
        }

        return org.springframework.boot.jdbc.DataSourceBuilder.create()
                .driverClassName("org.postgresql.Driver")
                .url(url)
                .username(username)
                .password(password)
                .build();
    }
}
