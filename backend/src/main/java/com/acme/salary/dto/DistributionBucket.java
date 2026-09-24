package com.acme.salary.dto;

import java.math.BigDecimal;

/** FR-4.3: one histogram bucket, {@code [lower, upper)} except the last, which includes {@code upper}. */
public record DistributionBucket(BigDecimal lower, BigDecimal upper, long count) {
}
