package com.acme.salary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Wires {@code @CreatedDate}/{@code @LastModifiedDate} (used on {@code employee.createdAt} /
 * {@code updatedAt}) to the injected {@link Clock} rather than the wall clock, so the audit
 * timestamps obey NFR-3 the same as everything else that touches time. {@code dateTimeProviderRef}
 * is what redirects Spring Data's auditing infrastructure from {@code Instant.now()} to this bean.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
