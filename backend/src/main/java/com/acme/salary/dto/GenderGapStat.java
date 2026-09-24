package com.acme.salary.dto;

import java.math.BigDecimal;

/**
 * FR-4.5: the gender pay gap of one group, base currency, positive = women paid less. When {@code
 * suppressed} is true (too few men or women, see GenderGapService) the counts and both gaps are
 * null. When not suppressed a gap can still be null if it is undefined (a male mean or median of zero).
 */
public record GenderGapStat(String key, String label, Long maleCount, Long femaleCount,
                            BigDecimal meanGapPct, BigDecimal medianGapPct, boolean suppressed) {
}
