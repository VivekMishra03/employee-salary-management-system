package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.EmploymentStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-4.6 / FR-4.7: the payroll trend SQL on a real PostgreSQL. Nothing here depends on today's date:
 * every date is explicit. All employees are hired 2020-01-01 unless stated; base = USD.
 *
 * <pre>
 *  id  who        history (annualised, base ccy)                                             notes
 *  1   A raise    100,000 to 2026-03-15 ; 110,000 from 2026-03-15 MERIT_INCREASE (+10%)      raise mid-March
 *  2   B backdate  80,000 to 2026-02-10 ;  88,000 from 2026-02-10 MERIT_INCREASE (+10%)      created_at 2026-06-01: recorded months late
 *  3   C new hire  60,000 from 2026-03-10 NEW_HIRE                                           hired 2026-03-10
 *  4   D leaver    50,000 open, NEW_HIRE                                                     TERMINATED, termination_date 2026-04-20
 *  5   E correct   70,000 to 2026-04-05 ; 77,000 from 2026-04-05 CORRECTION                  correction: not an increase
 *  6   F currency  EUR 90,000 (= 99,000 base) to 2026-05-10 ; USD 100,000 from 2026-05-10 ROLE_CHANGE   currency changes: skipped
 *  7   G zero-len  60,000 to 2026-02-01 ; 999,999 [2026-02-01, 2026-02-01) ; 66,000 from 2026-02-01 MERIT_INCREASE (+10%)
 *  8   H part-time 80,000 open, fte 0.5 -> 40,000 of payroll                                 department 2
 *  9   I promo     50,000 to 2026-03-20 ; 60,000 from 2026-03-20 PROMOTION (+20%)
 *  10  J rounding 200,000 to 2026-06-12 ; 202,470 from 2026-06-12 MERIT_INCREASE (+1.235%)
 *  11  K leap      30,000 from 2024-02-29 NEW_HIRE ; hired 2024-02-29 ; TERMINATED 2024-03-15
 *
 *  Payroll on each month's last day (thousands):
 *    Jan 749 = 100+80+50+70+99+60+40+50+200
 *    Feb 763 (B 88, G 66)          Mar 843 (A 110, C 60, I 60)      Apr 800 (D gone -50, E 77)
 *    May 801 (F 100)               Jun 803.47 (J 202.47)
 *  Average increase % per month: Jan null, Feb (10+10)/2 = 10.00, Mar (10+20)/2 = 15.00 (mean of the
 *    changes, not 30/150 = 20.00), Apr null (CORRECTION), May null (currency), Jun 1.235 -> 1.24.
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import(TrendAnalyticsRepository.class)
class TrendAnalyticsRepositoryTest {

    private static final EmployeeFilter ALL = new EmployeeFilter(null, null, null, null, null, null);

    @Autowired
    private TrendAnalyticsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        SqlFixture f = new SqlFixture(jdbc).baseData().jobRole(1, "Engineer", "L4");

        f.employee(1, "A", "Raise", null, 1, 1, 1);
        f.salary(1, 1, "2020-01-01", "2026-03-15", "USD", "100000.00", "100000.00", "NEW_HIRE");
        f.salary(2, 1, "2026-03-15", null, "USD", "110000.00", "110000.00", "MERIT_INCREASE");

        f.employee(2, "B", "Backdate", null, 1, 1, 1);
        f.salary(3, 2, "2020-01-01", "2026-02-10", "USD", "80000.00", "80000.00", "NEW_HIRE");
        f.salary(4, 2, "2026-02-10", null, "USD", "88000.00", "88000.00", "MERIT_INCREASE");
        jdbc.update("UPDATE salary_record SET created_at = TIMESTAMPTZ '2026-06-01 00:00:00+00' WHERE id = 4");

        f.employee(3, "C", "NewHire", null, "ACTIVE", "1.000", 1, 1, 1, "2026-03-10", null);
        f.salary(5, 3, "2026-03-10", null, "USD", "60000.00", "60000.00", "NEW_HIRE");

        f.employee(4, "D", "Leaver", null, "TERMINATED", "1.000", 1, 1, 1, "2020-01-01", "2026-04-20");
        f.salary(6, 4, "2020-01-01", null, "USD", "50000.00", "50000.00", "NEW_HIRE");

        f.employee(5, "E", "Correct", null, 1, 1, 1);
        f.salary(7, 5, "2020-01-01", "2026-04-05", "USD", "70000.00", "70000.00", "NEW_HIRE");
        f.salary(8, 5, "2026-04-05", null, "USD", "77000.00", "77000.00", "CORRECTION");

        f.employee(6, "F", "Currency", null, 1, 1, 2);
        f.salary(9, 6, "2020-01-01", "2026-05-10", "EUR", "90000.00", "99000.00", "NEW_HIRE");
        f.salary(10, 6, "2026-05-10", null, "USD", "100000.00", "100000.00", "ROLE_CHANGE");

        f.employee(7, "G", "ZeroLen", null, 1, 1, 1);
        f.salary(11, 7, "2020-01-01", "2026-02-01", "USD", "60000.00", "60000.00", "NEW_HIRE");
        f.salary(12, 7, "2026-02-01", "2026-02-01", "USD", "999999.00", "999999.00", "MERIT_INCREASE");
        f.salary(13, 7, "2026-02-01", null, "USD", "66000.00", "66000.00", "MERIT_INCREASE");

        f.employee(8, "H", "PartTime", null, "ACTIVE", "0.500", 2, 1, 1, "2020-01-01", null);
        f.salary(14, 8, "80000.00");

        f.employee(9, "I", "Promo", null, 1, 1, 1);
        f.salary(15, 9, "2020-01-01", "2026-03-20", "USD", "50000.00", "50000.00", "NEW_HIRE");
        f.salary(16, 9, "2026-03-20", null, "USD", "60000.00", "60000.00", "PROMOTION");

        f.employee(10, "J", "Rounding", null, 1, 1, 1);
        f.salary(17, 10, "2020-01-01", "2026-06-12", "USD", "200000.00", "200000.00", "NEW_HIRE");
        f.salary(18, 10, "2026-06-12", null, "USD", "202470.00", "202470.00", "MERIT_INCREASE");

        f.employee(11, "K", "Leap", null, "TERMINATED", "1.000", 1, 1, 1, "2024-02-29", "2024-03-15");
        f.salary(19, 11, "2024-02-29", null, "USD", "30000.00", "30000.00", "NEW_HIRE");
    }

    private List<TrendRow> trend(TrendInterval interval, String from, String to, EmployeeFilter filter) {
        return repository.trend(interval, LocalDate.parse(from), LocalDate.parse(to), filter);
    }

    private List<TrendRow> months(String from, String to) {
        return trend(TrendInterval.MONTH, from, to, ALL);
    }

    private static List<String> payrolls(List<TrendRow> rows) {
        return rows.stream().map(r -> r.totalPayroll().toPlainString()).toList();
    }

    private static List<String> increases(List<TrendRow> rows) {
        return rows.stream().map(r -> r.avgIncreasePct() == null ? null : r.avgIncreasePct().toPlainString()).toList();
    }

    private static List<LocalDate> starts(List<TrendRow> rows) {
        return rows.stream().map(TrendRow::periodStart).toList();
    }

    // ---- payroll ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.6: payroll is the sum of the records in force on each month's last day, times fte")
    void monthlyPayroll_matchesHandComputedFigures() {
        List<TrendRow> rows = months("2026-01-01", "2026-06-30");

        assertThat(starts(rows)).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 6, 1));
        assertThat(payrolls(rows)).containsExactly("749000.00", "763000.00", "843000.00", "800000.00",
                "801000.00", "803470.00");
    }

    @Test
    @DisplayName("FR-4.6: a raise in the middle of March counts for March (110,000 on 31 March) but not for February")
    void raiseMidPeriod_appearsInThatPeriodAndNotBefore() {
        List<TrendRow> rows = months("2026-02-01", "2026-03-31");

        // A: 100,000 on 28 Feb, 110,000 on 31 Mar. The March-minus-February difference of 80,000 is
        // A +10 and C +60 (new hire) and I +10 (promotion) = 80.
        assertThat(payrolls(rows)).containsExactly("763000.00", "843000.00");
    }

    @Test
    @DisplayName("FR-4.6: a raise recorded months late (created 2026-06-01, effective 2026-02-10) counts in February, not June")
    void backDatedRaise_countsInItsEffectivePeriod() {
        List<TrendRow> rows = months("2026-01-01", "2026-02-28");

        // B is 80,000 at the end of January and 88,000 at the end of February, whatever created_at says.
        // Jan 749 -> Feb 763 = +14 = B +8 and G +6.
        assertThat(payrolls(rows)).containsExactly("749000.00", "763000.00");
        assertThat(increases(rows)).containsExactly(null, "10.00");
    }

    @Test
    @DisplayName("FR-4.6: an employee hired on 10 March is on the March payroll (60,000) but not on February's")
    void newHire_isCountedFromTheHireMonth() {
        List<TrendRow> rows = months("2026-02-01", "2026-03-31");

        // If C were counted from the start of the year February would be 823,000.
        assertThat(rows.get(0).totalPayroll().toPlainString()).isEqualTo("763000.00");
        assertThat(rows.get(1).totalPayroll().toPlainString()).isEqualTo("843000.00");
    }

    @Test
    @DisplayName("FR-4.6: a leaver terminated on 2026-04-20 is in March's payroll but gone from April's")
    void termination_dropsOutOfLaterPeriods() {
        List<TrendRow> rows = months("2026-03-01", "2026-04-30");

        // March 843 -> April 800: D (50) leaves, E's correction adds 7: 843 - 50 + 7 = 800.
        assertThat(payrolls(rows)).containsExactly("843000.00", "800000.00");
    }

    @Test
    @DisplayName("FR-4.6: on the day of the termination date the employee is no longer counted (half-open: hire <= day < termination)")
    void termination_onThePeriodEndDay_isNotCounted() {
        // D's termination_date is 2026-04-20. With to = 2026-04-20 the only period ends that day.
        List<TrendRow> onTerminationDay = trend(TrendInterval.MONTH, "2026-04-20", "2026-04-20", ALL);
        List<TrendRow> dayBefore = trend(TrendInterval.MONTH, "2026-04-19", "2026-04-19", ALL);

        assertThat(payrolls(dayBefore)).containsExactly("850000.00");
        assertThat(payrolls(onTerminationDay)).containsExactly("800000.00");
    }

    @Test
    @DisplayName("FR-4.6: the part-timer counts at half of the FTE rate: filtering to department 2 gives 40,000 a month")
    void partTimer_countsAtFteScaledPay() {
        List<TrendRow> rows = trend(TrendInterval.MONTH, "2026-01-01", "2026-03-31",
                new EmployeeFilter(null, 2L, null, null, null, null));

        assertThat(payrolls(rows)).containsExactly("40000.00", "40000.00", "40000.00");
    }

    @Test
    @DisplayName("FR-4.6: a zero-length superseded record (999,999) is never the payroll figure")
    void zeroLengthRecord_isNeverPayroll() {
        // On 2026-02-01 itself G's 66,000 record starts and the zero-length 999,999 record (from = to =
        // that day) must not match. Payroll that day = January's 749 + G's 66 - 60 = 755 (B is still 80).
        List<TrendRow> rows = trend(TrendInterval.MONTH, "2026-02-01", "2026-02-01", ALL);

        assertThat(payrolls(rows)).containsExactly("755000.00");
    }

    // ---- average increase ------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.6: average increase is the mean of the individual increases: March (+10% and +20%) is 15.00, not 13.33")
    void averageIncrease_isTheMeanOfTheChanges() {
        List<TrendRow> rows = months("2026-01-01", "2026-06-30");

        assertThat(increases(rows)).containsExactly(null, "10.00", "15.00", null, null, "1.24");
    }

    @Test
    @DisplayName("FR-4.6: NEW_HIRE (C's 60,000) and CORRECTION (E's 77,000) are never counted as an increase")
    void newHireAndCorrection_areExcludedFromTheIncrease() {
        List<TrendRow> rows = months("2026-03-01", "2026-04-30");

        // March: only A (+10) and I (+20). (C's NEW_HIRE is the employee's first record, so it has no
        // predecessor and never counts either way; the rule itself is pinned by the test below.)
        // April: only E's CORRECTION (+10%) happened, so there is no qualifying change: null.
        assertThat(increases(rows)).containsExactly("15.00", null);
    }

    @Test
    @DisplayName("FR-4.6: a NEW_HIRE record that follows an earlier record (a rehire) is not a raise, even though it has a predecessor")
    void newHireRecord_withAPredecessor_isNotCountedAsAnIncrease() {
        // L: 100,000 until 2026-07-10, then a second NEW_HIRE record at 150,000. Unlike C in the shared
        // fixture, this NEW_HIRE has a previous record, so only the change_reason exclusion keeps the
        // +50% out of July. Nobody else has a change in July 2026.
        SqlFixture f = new SqlFixture(jdbc);
        f.employee(12, "L", "Rehire", null, 1, 1, 1);
        f.salary(20, 12, "2020-01-01", "2026-07-10", "USD", "100000.00", "100000.00", "NEW_HIRE");
        f.salary(21, 12, "2026-07-10", null, "USD", "150000.00", "150000.00", "NEW_HIRE");

        assertThat(increases(months("2026-07-01", "2026-07-31"))).containsExactly((String) null);
    }

    @Test
    @DisplayName("FR-4.6: a change of currency (EUR to USD) is skipped: May has no qualifying change")
    void currencyChange_isSkipped() {
        List<TrendRow> rows = months("2026-05-01", "2026-05-31");

        // 90,000 EUR -> 100,000 USD would read as +11.11% if the currencies were ignored.
        assertThat(increases(rows)).containsExactly((String) null);
        assertThat(payrolls(rows)).containsExactly("801000.00");
    }

    @Test
    @DisplayName("FR-4.6: a zero-length record is excluded from both sides of a change: G's raise is +10%, not -99.99%")
    void zeroLengthRecord_isExcludedFromIncreases() {
        List<TrendRow> rows = months("2026-02-01", "2026-02-28");

        // Increases in February: B 80 -> 88 (+10%) and G 60 -> 66 (+10%) = 10.00. With the zero-length
        // record as G's predecessor the mean would be wildly different.
        assertThat(increases(rows)).containsExactly("10.00");
    }

    @Test
    @DisplayName("FR-4.6: the average increase is rounded HALF_UP to 2 places: 1.235 becomes 1.24")
    void averageIncrease_roundsHalfUp() {
        assertThat(increases(months("2026-06-01", "2026-06-30"))).containsExactly("1.24");
    }

    // ---- empty periods and bucketing -------------------------------------------------------

    @Test
    @DisplayName("FR-4.6: periods before anyone was hired are still present, with zero payroll and a null increase")
    void emptyPeriods_appearWithZeroPayrollAndNullIncrease() {
        List<TrendRow> rows = months("2019-11-01", "2020-01-31");

        assertThat(starts(rows)).containsExactly(LocalDate.of(2019, 11, 1), LocalDate.of(2019, 12, 1),
                LocalDate.of(2020, 1, 1));
        assertThat(payrolls(rows)).containsExactly("0.00", "0.00", "749000.00");
        // January 2020: everyone's first record is NEW_HIRE, so still no increase.
        assertThat(increases(rows)).containsExactly(null, null, null);
    }

    @Test
    @DisplayName("FR-4.6: quarters clip to from and to: Q1 from 15 Feb counts only March's raises (15.00), Q3 ends on 10 Aug")
    void quarterly_clipsTheFirstAndLastPeriodToFromAndTo() {
        List<TrendRow> rows = trend(TrendInterval.QUARTER, "2026-02-15", "2026-08-10", ALL);

        assertThat(starts(rows)).containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 7, 1));
        // Q1 payroll is on 31 March = 843; Q2 on 30 June = 803.47; Q3 is capped at `to`, 10 August = 803.47.
        assertThat(payrolls(rows)).containsExactly("843000.00", "803470.00", "803470.00");
        // Q1 increases inside [15 Feb, 31 Mar]: A +10 and I +20 = 15.00 (B's and G's February raises are
        // before `from`). Q2: E's correction and F's currency change are skipped; J +1.235 -> 1.24. Q3: none.
        assertThat(increases(rows)).containsExactly("15.00", "1.24", null);
    }

    @Test
    @DisplayName("FR-4.6: a year period runs to `to`: 2025 is one day (31 Dec), 2026 is capped at 30 June with mean increase 10.25")
    void yearly_capsTheLastPeriodAtTo() {
        List<TrendRow> rows = trend(TrendInterval.YEAR, "2025-12-31", "2026-06-30", ALL);

        assertThat(starts(rows)).containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
        assertThat(payrolls(rows)).containsExactly("749000.00", "803470.00");
        // 2026 changes up to 30 June: B 10, G 10, A 10, I 20, J 1.235 -> 51.235 / 5 = 10.247 -> 10.25.
        assertThat(increases(rows)).containsExactly(null, "10.25");
    }

    @Test
    @DisplayName("FR-4.6: on a leap year February ends on the 29th: a hire on 29 Feb 2024 is in February's payroll")
    void leapYear_februaryEndsOnThe29th() {
        List<TrendRow> rows = months("2024-02-01", "2024-03-31");

        // February: 749 + K's 30 = 779 (hired on the 29th, the last day). March: K left on 15 March.
        assertThat(payrolls(rows)).containsExactly("779000.00", "749000.00");
    }

    @Test
    @DisplayName("FR-4.6: in a non-leap year February ends on the 28th, and a single-day range is one period")
    void nonLeapYear_februaryEndsOnThe28th() {
        List<TrendRow> rows = months("2023-02-28", "2023-02-28");

        assertThat(starts(rows)).containsExactly(LocalDate.of(2023, 2, 1));
        assertThat(payrolls(rows)).containsExactly("749000.00");
    }

    // ---- filters ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.6: terminated employees are NOT excluded by default: the leaver is in the January payroll of 749,000")
    void terminatedEmployees_areIncludedForHistoricalPeriods() {
        assertThat(months("2026-01-01", "2026-01-31").get(0).totalPayroll().toPlainString())
                .isEqualTo("749000.00");
    }

    @Test
    @DisplayName("FR-4.6 / FR-4.7: an explicit TERMINATED status filter keeps only the leaver: 50,000 until March, 0 from April")
    void statusFilter_appliesToTheCurrentStatus() {
        List<TrendRow> rows = trend(TrendInterval.MONTH, "2026-01-01", "2026-04-30",
                new EmployeeFilter(null, null, null, EmploymentStatus.TERMINATED, null, null));

        assertThat(payrolls(rows)).containsExactly("50000.00", "50000.00", "50000.00", "0.00");
        assertThat(increases(rows)).containsExactly(null, null, null, null);
    }

    @Test
    @DisplayName("FR-4.7: the country filter applies to the increase as well: only F is in DE, and F's change is skipped")
    void countryFilter_appliesToPayrollAndIncrease() {
        List<TrendRow> rows = trend(TrendInterval.MONTH, "2026-04-01", "2026-05-31",
                new EmployeeFilter(null, null, "DE", null, null, null));

        // F (location 2 = DE): 99,000 EUR-converted until 10 May, 100,000 after.
        assertThat(payrolls(rows)).containsExactly("99000.00", "100000.00");
        assertThat(increases(rows)).containsExactly(null, null);
    }
}
