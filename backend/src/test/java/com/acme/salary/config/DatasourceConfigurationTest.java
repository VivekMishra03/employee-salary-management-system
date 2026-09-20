package com.acme.salary.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-4/NFR-6: configuration arrives from the environment, and a missing database URL must stop the
 * application rather than let it start against something unintended.
 *
 * <p>{@code application.yml} defaults the datasource properties to the empty string rather than to a
 * working local connection string. The comment there claims that an unset {@code DATABASE_URL} means
 * the application refuses to start. That claim was previously only a comment; this asserts it.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than {@code @SpringBootTest} deliberately: it
 * needs no embedded database, starts no web server, and runs in milliseconds. NFR-3 asks for the
 * bulk of the suite to be fast tests that need no infrastructure.
 */
class DatasourceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class));

    @Test
    @DisplayName("NFR-6: with no database URL the context fails to start, naming the cause")
    void context_failsToStart_whenDatabaseUrlIsAbsent() {
        // The failure mode this guards against is not a crash -- it is the opposite. If an embedded
        // database driver ever reaches the runtime classpath, Spring Boot silently substitutes an
        // in-memory database and the service starts up healthy while pointing at nothing. On Render
        // that looks like a successful deploy serving an empty org.
        contextRunner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("Failed to determine a suitable driver class"));
    }

    @Test
    @DisplayName("NFR-6: a supplied DATABASE_URL is what the datasource is built from")
    void context_startsAndUsesTheSuppliedUrl() {
        // The mirror of the test above: proves the failure is caused by the URL being absent, not by
        // the auto-configuration being broken in this test harness.
        contextRunner
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/acme")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(javax.sql.DataSource.class));
    }
}
