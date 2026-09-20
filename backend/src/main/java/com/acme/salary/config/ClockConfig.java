package com.acme.salary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * NFR-3: production code never calls {@code Instant.now()} or {@code LocalDate.now()} directly --
 * a test whose result changes at midnight or on 29 February is a bug. Every place that needs the
 * current time is injected a {@link Clock} instead, and tests supply {@link Clock#fixed}.
 *
 * <p>{@code systemUTC()} rather than the JVM default zone: timestamps are stored as {@code
 * TIMESTAMPTZ} and compared across environments (a developer's machine, CI, Render), so the
 * clock's zone must not be an accident of whichever host runs the code.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
