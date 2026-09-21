package com.acme.salary.service;

import com.acme.salary.model.PayFrequency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-3.4 / NFR-2: annualisation and currency conversion. Pure unit tests -- no Spring, no database
 * -- because money arithmetic is exactly what must be provable in isolation. Every result is
 * compared by value at an explicit scale; nothing here uses floating point.
 */
class SalaryCalculatorTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ---- annualise -------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.4: an annual amount is already annualised")
    void annualise_annual_isUnchanged() {
        assertThat(SalaryCalculator.annualise(bd("120000.00"), PayFrequency.ANNUAL)).isEqualByComparingTo("120000.00");
    }

    @Test
    @DisplayName("requirements.md 6.2: a monthly amount is multiplied by 12")
    void annualise_monthly_multipliesByTwelve() {
        assertThat(SalaryCalculator.annualise(bd("8333.33"), PayFrequency.MONTHLY)).isEqualByComparingTo("99999.96");
    }

    @Test
    @DisplayName("requirements.md 6.2: an hourly amount is multiplied by 2080")
    void annualise_hourly_multipliesByTwoThousandEighty() {
        assertThat(SalaryCalculator.annualise(bd("45.50"), PayFrequency.HOURLY)).isEqualByComparingTo("94640.00");
    }

    @Test
    @DisplayName("NFR-2: the annualised amount always has exactly two decimal places")
    void annualise_alwaysReturnsScaleTwo() {
        assertThat(SalaryCalculator.annualise(bd("100000"), PayFrequency.ANNUAL).scale()).isEqualTo(2);
        assertThat(SalaryCalculator.annualise(bd("1"), PayFrequency.MONTHLY).scale()).isEqualTo(2);
        assertThat(SalaryCalculator.annualise(bd("0.01"), PayFrequency.HOURLY).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("NFR-2: no precision is lost multiplying the smallest unit by 2080")
    void annualise_smallestHourlyUnit_isExact() {
        assertThat(SalaryCalculator.annualise(bd("0.01"), PayFrequency.HOURLY)).isEqualByComparingTo("20.80");
    }

    // ---- convert ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.5: conversion multiplies by the rate and rounds to cents")
    void convert_multipliesByTheRate() {
        assertThat(SalaryCalculator.convert(bd("100000.00"), bd("1.08000000"))).isEqualByComparingTo("108000.00");
    }

    @Test
    @DisplayName("NFR-2: a rate is used at its full eight-decimal precision, then rounded once")
    void convert_usesFullRatePrecision() {
        // 99999.96 * 0.01197354 = 1197.35...; rounding the rate first to 4 places would give 1200.00.
        assertThat(SalaryCalculator.convert(bd("99999.96"), bd("0.01197354"))).isEqualByComparingTo("1197.35");
    }

    @Test
    @DisplayName("NFR-2: rounding is HALF_UP -- an exact half rounds away from zero")
    void convert_roundsHalfUp() {
        // 100.00 * 0.00125 = 0.125 exactly. HALF_UP gives 0.13; HALF_EVEN (the JDK habit) gives 0.12.
        assertThat(SalaryCalculator.convert(bd("100.00"), bd("0.00125000"))).isEqualByComparingTo("0.13");
    }

    @Test
    @DisplayName("NFR-2: just below a half rounds down")
    void convert_justBelowAHalf_roundsDown() {
        assertThat(SalaryCalculator.convert(bd("100.00"), bd("0.00124999"))).isEqualByComparingTo("0.12");
    }

    @Test
    @DisplayName("NFR-2: the converted amount always has exactly two decimal places")
    void convert_alwaysReturnsScaleTwo() {
        assertThat(SalaryCalculator.convert(bd("100000.00"), BigDecimal.ONE).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("a zero or negative rate is refused rather than silently zeroing the salary")
    void convert_withNonPositiveRate_isRefused() {
        assertThatThrownBy(() -> SalaryCalculator.convert(bd("100000.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SalaryCalculator.convert(bd("100000.00"), bd("-1.08")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
