package com.acme.salary.readmodel;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FR-4.6: one period of the compensation trend. {@code periodStart} is the first day of the calendar
 * bucket. {@code totalPayroll} is in the base currency (zero for a period with no payroll);
 * {@code avgIncreasePct} is null when the period has no qualifying salary change.
 */
public record TrendRow(LocalDate periodStart, BigDecimal totalPayroll, BigDecimal avgIncreasePct) {
}
