package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.support.SqlFixture;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-4.6 (NFR-1 rewrite guard): characterisation of the exact day-boundary behaviour of the trend SQL
 * that a change of algorithm could plausibly break. Every case is hand-computed from the half-open
 * conventions ({@code [effective_from, effective_to)}, {@code hire <= day < termination}) and every
 * date is a fixed literal. Each test builds its own tiny fixture; all salaries are USD, base = local.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import(TrendAnalyticsRepository.class)
class TrendAnalyticsRepositoryBoundaryTest {

    private static final EmployeeFilter ALL = new EmployeeFilter(null, null, null, null, null, null);

    @Autowired
    private TrendAnalyticsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private SqlFixture fixture;
    private long nextSalaryId;

    @BeforeEach
    void base() {
        fixture = new SqlFixture(jdbc).baseData().jobRole(1, "Engineer", "L4");
        nextSalaryId = 1;
    }

    private void employee(long id, String hire, String termination) {
        fixture.employee(id, "E" + id, "Test", null, termination == null ? "ACTIVE" : "TERMINATED", "1.000",
                1, 1, 1, hire, termination);
    }

    private void record(long employeeId, String from, String to, String amount, String reason) {
        fixture.salary(nextSalaryId++, employeeId, from, to, "USD", amount, amount, reason);
    }

    private List<TrendRow> trend(TrendInterval interval, String from, String to) {
        return repository.trend(interval, LocalDate.parse(from), LocalDate.parse(to), ALL);
    }

    private static List<String> payrolls(List<TrendRow> rows) {
        return rows.stream().map(r -> r.totalPayroll().toPlainString()).toList();
    }

    private static List<String> increases(List<TrendRow> rows) {
        return rows.stream().map(r -> r.avgIncreasePct() == null ? null : r.avgIncreasePct().toPlainString()).toList();
    }

    // ---- payroll: measuring-day boundaries -------------------------------------------------

    @Test
    @DisplayName("FR-4.6: a record that starts and ends inside one month never contains a month-end, so it is never payroll")
    void recordInsideOnePeriod_contributesToNoPayroll() {
        employee(1, "2020-01-01", null);
        record(1, "2020-01-01", "2026-03-05", "100000.00", "NEW_HIRE");
        record(1, "2026-03-05", "2026-03-20", "999999.00", "MERIT_INCREASE");
        record(1, "2026-03-20", null, "120000.00", "MERIT_INCREASE");

        assertThat(payrolls(trend(TrendInterval.MONTH, "2026-02-01", "2026-04-30")))
                .containsExactly("100000.00", "120000.00", "120000.00");
    }

    @Test
    @DisplayName("FR-4.6: a record ending exactly on a measuring day is excluded that day (half-open); nothing replaces it")
    void recordEndingOnMeasuringDay_isExcluded() {
        employee(1, "2020-01-01", null);
        record(1, "2020-01-01", "2026-03-31", "100000.00", "NEW_HIRE");
        record(1, "2026-04-10", null, "130000.00", "MERIT_INCREASE");

        assertThat(payrolls(trend(TrendInterval.MONTH, "2026-02-01", "2026-04-30")))
                .containsExactly("100000.00", "0.00", "130000.00");
    }

    @Test
    @DisplayName("FR-4.6: a record starting exactly on a measuring day is included that day")
    void recordStartingOnMeasuringDay_isIncluded() {
        employee(1, "2026-03-31", null);
        record(1, "2026-03-31", null, "60000.00", "NEW_HIRE");

        assertThat(payrolls(trend(TrendInterval.MONTH, "2026-02-01", "2026-04-30")))
                .containsExactly("0.00", "60000.00", "60000.00");
    }

    @Test
    @DisplayName("FR-4.6: a termination exactly on a measuring day drops the employee on that day, not the day after")
    void terminationOnMeasuringDay_isExcluded() {
        employee(1, "2020-01-01", "2026-03-31");
        record(1, "2020-01-01", null, "50000.00", "NEW_HIRE");

        assertThat(payrolls(trend(TrendInterval.MONTH, "2026-02-01", "2026-04-30")))
                .containsExactly("50000.00", "0.00", "0.00");
    }

    @Test
    @DisplayName("FR-4.6: hired mid-month with a record that pre-dates the hire: counted from the hire month, not before")
    void hireMidPeriod_withEarlierRecord_countsFromHireMonth() {
        employee(1, "2026-03-10", null);
        record(1, "2020-01-01", null, "70000.00", "NEW_HIRE");

        assertThat(payrolls(trend(TrendInterval.MONTH, "2026-02-01", "2026-03-31")))
                .containsExactly("0.00", "70000.00");
    }

    @Test
    @DisplayName("FR-4.6: hired in March but the first record only starts in April: March has no payroll for that person")
    void hireBeforeFirstRecord_countsFromTheRecord() {
        employee(1, "2026-03-10", null);
        record(1, "2026-04-05", null, "80000.00", "NEW_HIRE");

        assertThat(payrolls(trend(TrendInterval.MONTH, "2026-03-01", "2026-04-30")))
                .containsExactly("0.00", "80000.00");
    }

    @Test
    @DisplayName("FR-4.6: the last period is measured on `to` when that is mid-month, with half-open edges exactly on `to`")
    void cappedLastPeriod_isMeasuredOnTo() {
        // A: 100,000 until 15 March (exclusive), 110,000 from 15 March: on `to` = 15 March, 110,000.
        employee(1, "2020-01-01", null);
        record(1, "2020-01-01", "2026-03-15", "100000.00", "NEW_HIRE");
        record(1, "2026-03-15", null, "110000.00", "MERIT_INCREASE");
        // B: terminated on `to`: gone that day, present on 28 February.
        employee(2, "2020-01-01", "2026-03-15");
        record(2, "2020-01-01", null, "40000.00", "NEW_HIRE");
        // C: hired the day after `to`: never counted.
        employee(3, "2026-03-16", null);
        record(3, "2026-03-16", null, "30000.00", "NEW_HIRE");
        // D: raise on 20 March, after `to`: still 20,000 on `to`, and the raise is not an increase in range.
        employee(4, "2020-01-01", null);
        record(4, "2020-01-01", "2026-03-20", "20000.00", "NEW_HIRE");
        record(4, "2026-03-20", null, "25000.00", "MERIT_INCREASE");

        List<TrendRow> rows = trend(TrendInterval.MONTH, "2026-01-01", "2026-03-15");

        assertThat(rows).extracting(TrendRow::periodStart).containsExactly(LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));
        assertThat(payrolls(rows)).containsExactly("160000.00", "160000.00", "130000.00");
        // The only qualifying change inside the range is A's +10% on 15 March (on `to`, so included).
        assertThat(increases(rows)).containsExactly(null, null, "10.00");
    }

    @Test
    @DisplayName("FR-4.6: from == to on the first of a month and on a mid-month day are each a single period")
    void fromEqualsTo_isASinglePeriod() {
        employee(1, "2020-01-01", null);
        record(1, "2020-01-01", "2026-03-15", "100000.00", "NEW_HIRE");
        record(1, "2026-03-15", null, "110000.00", "MERIT_INCREASE");

        assertThat(payrolls(trend(TrendInterval.MONTH, "2026-03-01", "2026-03-01"))).containsExactly("100000.00");
        assertThat(payrolls(trend(TrendInterval.MONTH, "2026-03-15", "2026-03-15"))).containsExactly("110000.00");
        assertThat(payrolls(trend(TrendInterval.QUARTER, "2026-03-15", "2026-03-15"))).containsExactly("110000.00");
        assertThat(payrolls(trend(TrendInterval.YEAR, "2026-03-14", "2026-03-14"))).containsExactly("100000.00");
        assertThat(increases(trend(TrendInterval.YEAR, "2026-03-15", "2026-03-15"))).containsExactly("10.00");
        assertThat(increases(trend(TrendInterval.YEAR, "2026-03-14", "2026-03-14"))).containsExactly((String) null);
    }

    // ---- leap day --------------------------------------------------------------------------

    private void leapFixture() {
        // A: 100,000 until 2024-02-29, then 120,000 (+20%). B hired on the leap day. C leaves on it.
        employee(1, "2020-01-01", null);
        record(1, "2020-01-01", "2024-02-29", "100000.00", "NEW_HIRE");
        record(1, "2024-02-29", null, "120000.00", "MERIT_INCREASE");
        employee(2, "2024-02-29", null);
        record(2, "2024-02-29", null, "10000.00", "NEW_HIRE");
        employee(3, "2020-01-01", "2024-02-29");
        record(3, "2020-01-01", null, "50000.00", "NEW_HIRE");
    }

    @Test
    @DisplayName("FR-4.6: MONTH across 2024-02-29: January 150,000; February (measured on the 29th) 130,000; March 130,000")
    void leapDay_month() {
        leapFixture();

        List<TrendRow> rows = trend(TrendInterval.MONTH, "2024-01-01", "2024-03-31");

        assertThat(payrolls(rows)).containsExactly("150000.00", "130000.00", "130000.00");
        assertThat(increases(rows)).containsExactly(null, "20.00", null);
        assertThat(payrolls(trend(TrendInterval.MONTH, "2024-02-29", "2024-02-29"))).containsExactly("130000.00");
        assertThat(payrolls(trend(TrendInterval.MONTH, "2024-02-28", "2024-02-28"))).containsExactly("150000.00");
    }

    @Test
    @DisplayName("FR-4.6: QUARTER across 2024-02-29: Q1 is measured on 31 March, and a Q1 capped at 29 Feb on the leap day")
    void leapDay_quarter() {
        leapFixture();

        List<TrendRow> full = trend(TrendInterval.QUARTER, "2024-01-01", "2024-06-30");
        List<TrendRow> capped = trend(TrendInterval.QUARTER, "2024-01-01", "2024-02-29");
        List<TrendRow> cappedBefore = trend(TrendInterval.QUARTER, "2024-01-01", "2024-02-28");

        assertThat(payrolls(full)).containsExactly("130000.00", "130000.00");
        assertThat(increases(full)).containsExactly("20.00", null);
        assertThat(payrolls(capped)).containsExactly("130000.00");
        assertThat(increases(capped)).containsExactly("20.00");
        assertThat(payrolls(cappedBefore)).containsExactly("150000.00");
        assertThat(increases(cappedBefore)).containsExactly((String) null);
    }

    @Test
    @DisplayName("FR-4.6: YEAR across 2024-02-29: 2023 is 150,000 (31 Dec), 2024 is 130,000, and a 2024 capped at 29 Feb still sees the raise")
    void leapDay_year() {
        leapFixture();

        List<TrendRow> full = trend(TrendInterval.YEAR, "2023-01-01", "2024-12-31");
        List<TrendRow> capped = trend(TrendInterval.YEAR, "2023-06-01", "2024-02-29");
        List<TrendRow> cappedBefore = trend(TrendInterval.YEAR, "2023-06-01", "2024-02-28");

        assertThat(payrolls(full)).containsExactly("150000.00", "130000.00");
        assertThat(increases(full)).containsExactly(null, "20.00");
        assertThat(payrolls(capped)).containsExactly("150000.00", "130000.00");
        assertThat(increases(capped)).containsExactly(null, "20.00");
        assertThat(payrolls(cappedBefore)).containsExactly("150000.00", "150000.00");
        assertThat(increases(cappedBefore)).containsExactly(null, null);
    }

    // ---- long histories and empty windows --------------------------------------------------

    @Test
    @DisplayName("FR-4.6: three consecutive records over many months: payroll steps at each change, increases fire once each")
    void threeConsecutiveRecords_monthlyAndQuarterly() {
        employee(1, "2025-01-01", null);
        record(1, "2025-01-01", "2025-04-15", "100000.00", "NEW_HIRE");
        record(1, "2025-04-15", "2025-10-01", "110000.00", "MERIT_INCREASE");
        record(1, "2025-10-01", null, "121000.00", "MERIT_INCREASE");

        List<TrendRow> monthly = trend(TrendInterval.MONTH, "2025-01-01", "2025-12-31");
        List<TrendRow> quarterly = trend(TrendInterval.QUARTER, "2025-01-01", "2025-12-31");

        assertThat(payrolls(monthly)).containsExactly(
                "100000.00", "100000.00", "100000.00",
                "110000.00", "110000.00", "110000.00", "110000.00", "110000.00", "110000.00",
                "121000.00", "121000.00", "121000.00");
        assertThat(increases(monthly)).containsExactly(null, null, null, "10.00", null, null, null, null, null,
                "10.00", null, null);
        assertThat(payrolls(quarterly)).containsExactly("100000.00", "110000.00", "110000.00", "121000.00");
        assertThat(increases(quarterly)).containsExactly(null, "10.00", null, "10.00");
    }

    @Test
    @DisplayName("FR-4.6: a window that starts after every record and employment ended is all zero payroll and null increases")
    void windowAfterEverythingEnded_isAllZero() {
        employee(1, "2020-01-01", "2022-06-30");
        record(1, "2020-01-01", "2021-05-01", "50000.00", "NEW_HIRE");
        record(1, "2021-05-01", null, "55000.00", "MERIT_INCREASE");
        employee(2, "2020-01-01", null);
        record(2, "2020-01-01", "2023-01-01", "70000.00", "NEW_HIRE");

        List<TrendRow> rows = trend(TrendInterval.MONTH, "2026-01-01", "2026-03-31");

        assertThat(payrolls(rows)).containsExactly("0.00", "0.00", "0.00");
        assertThat(increases(rows)).containsExactly(null, null, null);
    }

    // ---- increases: window edges -----------------------------------------------------------

    @Test
    @DisplayName("FR-4.6: a change counts iff effective_from is in [from, to], both ends included, and lands in its own month")
    void increaseWindowEdges_areInclusive() {
        long[] pcts = {50, 10, 20, 30, 40, 60};
        String[] dates = {"2026-02-09", "2026-02-10", "2026-02-28", "2026-03-01", "2026-04-20", "2026-04-21"};
        for (int i = 0; i < pcts.length; i++) {
            long id = i + 1;
            employee(id, "2020-01-01", null);
            record(id, "2020-01-01", dates[i], "100000.00", "NEW_HIRE");
            record(id, dates[i], null, (100000 + pcts[i] * 1000) + ".00", "MERIT_INCREASE");
        }

        List<TrendRow> rows = trend(TrendInterval.MONTH, "2026-02-10", "2026-04-20");

        assertThat(increases(rows)).containsExactly("15.00", "30.00", "40.00");
        // Measured on 28 Feb, 31 Mar and 20 Apr (`to`): the 21 April raise is never paid inside the range.
        assertThat(payrolls(rows)).containsExactly("680000.00", "710000.00", "750000.00");
    }
}
