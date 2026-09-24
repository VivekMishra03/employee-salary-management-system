package com.acme.salary.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * FR-4.5: the smallest number of employees of EACH compared gender a group needs before its gender
 * pay gap is reported. The spec says only "a minimum size", so the number is configuration (default
 * 5 in application.yml). Below 2 the rule would protect nobody -- a group of one is one identifiable
 * person -- so the application refuses to start with a smaller value. Not a secret.
 */
@Validated
@ConfigurationProperties(prefix = "app.analytics")
public record AnalyticsProperties(@Min(2) int minGroupSize) {
}
