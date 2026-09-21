package com.acme.salary.service;

import com.acme.salary.model.PayFrequency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * FR-3.4 / NFR-2: the money arithmetic for a salary record, as pure static functions with no
 * Spring, database or clock involved so each rule can be proven in isolation.
 *
 * <p>Everything is {@link BigDecimal} and every result is rounded explicitly to two decimal places
 * with {@link RoundingMode#HALF_UP}. Rounding happens once, at the end of each step, never on an
 * intermediate value: the exchange rate is used at its full eight-decimal precision.
 */
public final class SalaryCalculator {

    /** requirements.md section 6.2: "monthly x 12, hourly x 2080". */
    static final int MONTHS_PER_YEAR = 12;
    static final int HOURS_PER_YEAR = 2080;

    private static final int MONEY_SCALE = 2;

    private SalaryCalculator() {
    }

    /** The yearly equivalent of {@code baseAmount} paid at the given frequency. */
    public static BigDecimal annualise(BigDecimal baseAmount, PayFrequency frequency) {
        BigDecimal annual = switch (frequency) {
            case ANNUAL -> baseAmount;
            case MONTHLY -> baseAmount.multiply(BigDecimal.valueOf(MONTHS_PER_YEAR));
            case HOURLY -> baseAmount.multiply(BigDecimal.valueOf(HOURS_PER_YEAR));
        };
        return annual.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Converts an amount to another currency at {@code rate}.
     *
     * @throws IllegalArgumentException for a zero or negative rate: converting at such a rate would
     *                                  silently produce a zero or negative salary, so it is refused
     *                                  here rather than trusted
     */
    public static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Exchange rate must be positive");
        }
        return amount.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
