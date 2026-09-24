package com.acme.salary.readmodel;

import java.math.BigDecimal;

/** FR-4.2: one group of a pay comparison, in base currency. */
public record GroupRow(String key, String label, long headcount, BigDecimal median, BigDecimal mean) {
}
