package com.acme.salary.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * requirements.md assumption 1: one organisation with a single, configurable base reporting
 * currency (USD by default). Salaries in any other currency are converted to it (FR-3.4).
 * Not a secret, so it has a real default in application.yml.
 */
@Validated
@ConfigurationProperties(prefix = "app.compensation")
public record CompensationProperties(
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code") String baseCurrency) {
}
