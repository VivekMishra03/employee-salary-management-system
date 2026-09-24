package com.acme.salary.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FR-4.1: headcount, total annualised payroll cost and pay statistics for the filtered slice, all
 * money in the base currency. For an empty slice headcount and payroll are zero and the statistics
 * are null.
 *
 * <p>{@code ratesAsOf} (requirements.md assumption 2) is the latest effective date in the exchange
 * rate table, so the UI can show how stale the conversions are. It describes the rate table, not the
 * slice, so it ignores the filters; it is null when there are no rates.
 */
public record AnalyticsSummary(long headcount, BigDecimal totalPayroll, BigDecimal mean, BigDecimal median,
                               BigDecimal p25, BigDecimal p75, LocalDate ratesAsOf) {
}
