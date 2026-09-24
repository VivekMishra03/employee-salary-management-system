package com.acme.salary.readmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-4.6: calendar bucketing rules for the payroll trend, without a database. The SQL generates its
 * period starts from the same {@code months()} step, so these tests define what a period is.
 */
class TrendIntervalTest {

    @Test
    @DisplayName("FR-4.6: a month bucket starts on the 1st of the month")
    void month_bucketStart_isFirstOfMonth() {
        assertThat(TrendInterval.MONTH.bucketStart(LocalDate.of(2026, 3, 17))).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("FR-4.6: a quarter bucket starts on 1 Jan, 1 Apr, 1 Jul or 1 Oct")
    void quarter_bucketStart_isFirstDayOfCalendarQuarter() {
        assertThat(TrendInterval.QUARTER.bucketStart(LocalDate.of(2026, 1, 31))).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(TrendInterval.QUARTER.bucketStart(LocalDate.of(2026, 5, 15))).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(TrendInterval.QUARTER.bucketStart(LocalDate.of(2026, 9, 30))).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(TrendInterval.QUARTER.bucketStart(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("FR-4.6: a year bucket starts on 1 January, including from the leap day")
    void year_bucketStart_isFirstOfJanuary() {
        assertThat(TrendInterval.YEAR.bucketStart(LocalDate.of(2024, 2, 29))).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    @DisplayName("FR-4.6: labels are 2026-03, 2026-Q1 and 2026")
    void labels_followTheDocumentedFormats() {
        assertThat(TrendInterval.MONTH.label(LocalDate.of(2026, 3, 1))).isEqualTo("2026-03");
        assertThat(TrendInterval.QUARTER.label(LocalDate.of(2026, 1, 1))).isEqualTo("2026-Q1");
        assertThat(TrendInterval.QUARTER.label(LocalDate.of(2026, 4, 1))).isEqualTo("2026-Q2");
        assertThat(TrendInterval.QUARTER.label(LocalDate.of(2026, 10, 1))).isEqualTo("2026-Q4");
        assertThat(TrendInterval.YEAR.label(LocalDate.of(2026, 1, 1))).isEqualTo("2026");
    }

    @Test
    @DisplayName("FR-4.6: mid-period from and to count the whole calendar buckets they touch")
    void periodCount_countsTouchedBuckets() {
        // 2026-02-15 .. 2026-04-02 touches Feb, Mar, Apr.
        assertThat(TrendInterval.MONTH.periodCount(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 4, 2))).isEqualTo(3);
        // 2025-11-30 .. 2026-05-01 touches Q4 2025, Q1 2026, Q2 2026.
        assertThat(TrendInterval.QUARTER.periodCount(LocalDate.of(2025, 11, 30), LocalDate.of(2026, 5, 1))).isEqualTo(3);
        // 2024-12-31 .. 2026-01-01 touches 2024, 2025, 2026.
        assertThat(TrendInterval.YEAR.periodCount(LocalDate.of(2024, 12, 31), LocalDate.of(2026, 1, 1))).isEqualTo(3);
    }

    @Test
    @DisplayName("FR-4.6: from equal to to is exactly one period")
    void periodCount_sameDay_isOne() {
        assertThat(TrendInterval.MONTH.periodCount(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29))).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-4.6: a span too large for an int is a huge positive count, never a wrapped-around negative one")
    void periodCount_hugeSpan_doesNotOverflow() {
        long count = TrendInterval.MONTH.periodCount(LocalDate.MIN, LocalDate.MAX);

        // (999999999 * 2 + 1) years of 12 months each, from Jan of the first year to Dec of the last.
        assertThat(count).isEqualTo(1_999_999_999L * 12);
    }

    @Test
    @DisplayName("FR-4.6: the same huge span in quarters and years is also positive and exact")
    void periodCount_hugeSpan_quartersAndYears() {
        assertThat(TrendInterval.QUARTER.periodCount(LocalDate.MIN, LocalDate.MAX)).isEqualTo(1_999_999_999L * 4);
        assertThat(TrendInterval.YEAR.periodCount(LocalDate.MIN, LocalDate.MAX)).isEqualTo(1_999_999_999L);
    }

    @Test
    @DisplayName("FR-4.6: the SQL step is 1, 3 and 12 months for month, quarter and year")
    void months_areOneThreeTwelve() {
        assertThat(TrendInterval.MONTH.months()).isEqualTo(1);
        assertThat(TrendInterval.QUARTER.months()).isEqualTo(3);
        assertThat(TrendInterval.YEAR.months()).isEqualTo(12);
    }
}
