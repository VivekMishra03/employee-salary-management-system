package com.acme.salary.readmodel;

import java.math.BigDecimal;

/**
 * FR-4.5: the raw gender pay gap of one group, before the small-group rule is applied. Counts are of
 * MALE and FEMALE employees only. The gaps are percentages (positive = women paid less, base
 * currency) and are null when they are undefined: no man or no woman in the group, or a male mean or
 * median of zero.
 */
public record GenderGapRow(String key, String label, long maleCount, long femaleCount,
                           BigDecimal meanGapPct, BigDecimal medianGapPct) {
}
