package com.acme.salary.readmodel;

import java.math.BigDecimal;

/**
 * FR-4.1 for one slice, in base currency. Statistics are null for an empty slice (there is no mean
 * of nothing); headcount and payroll are then zero.
 */
public record SummaryRow(long headcount, BigDecimal totalPayroll, BigDecimal mean, BigDecimal median,
                         BigDecimal p25, BigDecimal p75) {
}
