package com.acme.salary.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * NFR-4 / NFR-6: JWT settings, bound from {@code app.security.jwt.*}.
 *
 * <p>{@code secret} defaults to the empty string in application.yml and is validated non-blank and
 * at least 32 characters (256 bits for HS256), so a missing or weak secret stops the application at
 * startup instead of running with a guessable signing key. There is deliberately no default that
 * would work in production.
 */
@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters (256 bits) for HS256") String secret,
        @Min(value = 1, message = "must be at least 1 minute") long expiryMinutes) {
}
