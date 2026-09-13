package com.shiva.wallet.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Translates a platform-supplied {@code DATABASE_URL} into the three properties Spring
 * actually needs.
 *
 * <p>Neon and Render both hand out URLs shaped like
 * {@code postgres://user:pass@host/db?sslmode=require}. JDBC cannot parse that form, so
 * without this the app would need three separate environment variables configured by hand
 * on every host. Running as an {@link EnvironmentPostProcessor} means the translation
 * happens before the datasource is built.
 *
 * <p>An explicitly set {@code SPRING_DATASOURCE_URL} always wins, so local development and
 * Testcontainers are never affected.
 *
 * <p>See docs/OPERATIONS.md.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "databaseUrlTranslation";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.trim().isEmpty()) {
            return;
        }
        if (environment.getProperty("SPRING_DATASOURCE_URL") != null) {
            return;
        }
        if (databaseUrl.startsWith("jdbc:")) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE_NAME, singleProperty("spring.datasource.url", databaseUrl)));
            return;
        }

        URI uri;
        try {
            uri = new URI(databaseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("DATABASE_URL is not a valid URI", e);
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", toJdbcUrl(uri));

        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int separator = userInfo.indexOf(':');
            if (separator >= 0) {
                properties.put("spring.datasource.username", userInfo.substring(0, separator));
                properties.put("spring.datasource.password", userInfo.substring(separator + 1));
            } else {
                properties.put("spring.datasource.username", userInfo);
            }
        }

        environment.getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }

    private static String toJdbcUrl(URI uri) {
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getPath() == null ? "" : uri.getPath());
        if (uri.getQuery() != null) {
            jdbc.append('?').append(uri.getQuery());
        }
        return jdbc.toString();
    }

    private static Map<String, Object> singleProperty(String key, String value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}
