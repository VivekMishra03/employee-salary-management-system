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
 * FR-4.5: the minimum group size for gender-gap reporting is configuration, because the spec says
 * only "a minimum size". Below 2 the suppression would protect nobody (a group of one is one person).
 */
class AnalyticsPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(AnalyticsProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    @DisplayName("FR-4.5: a configured minimum group size binds")
    void minGroupSize_binds() {
        runner.withPropertyValues("app.analytics.min-group-size=8")
                .run(context -> assertThat(context.getBean(AnalyticsProperties.class).minGroupSize()).isEqualTo(8));
    }

    @Test
    @DisplayName("FR-4.5: a minimum group size below 2 is rejected at startup")
    void minGroupSize_belowTwo_failsToStart() {
        runner.withPropertyValues("app.analytics.min-group-size=1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("FR-4.5: the boundary value 2 is accepted")
    void minGroupSize_two_isAccepted() {
        runner.withPropertyValues("app.analytics.min-group-size=2")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("FR-4.5: application.yml supplies a default of 5, overridable from the environment")
    void applicationYml_defaultsToFive() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertThat(sources.get(0).getProperty("app.analytics.min-group-size"))
                .isEqualTo("${ANALYTICS_MIN_GROUP_SIZE:5}");
    }
}
