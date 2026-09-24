package com.acme.salary.readmodel;

import java.math.BigDecimal;

/** FR-4.3: one histogram bucket, {@code [lower, upper)} except the last, which includes {@code upper}. */
public record BucketRow(BigDecimal lower, BigDecimal upper, long count) {
}
