package com.acme.salary.dto;

import java.math.BigDecimal;

/**
 * FR-4.2: one group of a pay comparison. {@code key} is the stable identifier (department id,
 * country code or job level) and {@code label} what to show (department name, country name, level).
 */
public record GroupStat(String key, String label, long headcount, BigDecimal median, BigDecimal mean) {
}
