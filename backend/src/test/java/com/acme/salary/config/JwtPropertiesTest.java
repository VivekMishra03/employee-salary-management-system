package com.acme.salary.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-4 / NFR-6: the JWT signing secret comes from the environment and has no working default.
 * Like the datasource, an absent or weak secret must stop the application at startup rather than
 * let it run signing tokens with a guessable key.
 *
 * <p>Uses {@link ApplicationContextRunner}: no embedded database, no web server, milliseconds.
 */
class JwtPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    @DisplayName("NFR-4: with no secret configured the context fails to start")
    void context_failsToStart_whenSecretIsAbsent() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("NFR-4: with an empty secret (the application.yml default) the context fails to start")
    void context_failsToStart_whenSecretIsEmpty() {
        runner.withPropertyValues("app.security.jwt.secret=", "app.security.jwt.expiry-minutes=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("NFR-4: a secret shorter than 32 characters is rejected")
    void context_failsToStart_whenSecretIsTooShort() {
        runner.withPropertyValues("app.security.jwt.secret=short", "app.security.jwt.expiry-minutes=60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("FR-1.3: a non-positive expiry is rejected")
    void context_failsToStart_whenExpiryIsNotPositive() {
        runner.withPropertyValues(
                        "app.security.jwt.secret=test-only-secret-key-that-is-at-least-32-bytes-long!!",
                        "app.security.jwt.expiry-minutes=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("NFR-4: application.yml commits no working secret -- it reads JWT_SECRET, defaulting to empty")
    void applicationYml_hasNoCommittedSecret() throws Exception {
        // Loads the raw YAML rather than reading the resolved Environment: the Environment would
        // hand back whatever the test resources override it with, hiding a committed default.
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources.get(0).getProperty("app.security.jwt.secret")).isEqualTo("${JWT_SECRET:}");
    }

    @Test
    @DisplayName("a valid secret and expiry bind successfully")
    void context_starts_whenSecretAndExpiryAreValid() {
        runner.withPropertyValues(
                        "app.security.jwt.secret=test-only-secret-key-that-is-at-least-32-bytes-long!!",
                        "app.security.jwt.expiry-minutes=45")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtProperties.class).expiryMinutes()).isEqualTo(45);
                });
    }
}
