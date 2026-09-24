package com.acme.salary.dto;

import java.math.BigDecimal;

/**
 * FR-4.6: one period of the compensation trend. {@code period} is 2026-03, 2026-Q1 or 2026;
 * {@code totalPayrollUsd} is the base-currency payroll on the period's last day (zero if none);
 * {@code avgIncreasePct} is null when the period has no qualifying salary change.
 */
public record TrendPoint(String period, BigDecimal totalPayrollUsd, BigDecimal avgIncreasePct) {
}
