package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-4.1 / FR-4.2 / FR-4.3 / FR-4.7: the analytics SQL run against a real PostgreSQL (including
 * {@code percentile_cont}), over a small hand-built fixture whose expected numbers are worked out
 * by hand below. All amounts are base-currency (USD) annualised full-time-equivalent rates.
 *
 * <pre>
 *  id name           dept  country level status     type       fte   salary history (base ccy)
 *  1  Ada Lovelace   ENG   US      L4    ACTIVE     FULL_TIME  1.0   100,000 [2024-01-01, 2026-09-01)  then 130,000 from 2026-09-01 (future raise)
 *  2  Grace Hopper   ENG   DE      L5    ACTIVE     FULL_TIME  1.0   999,999.99 zero-length [2025-03-01, 2025-03-01)  then 140,000 from 2025-03-01
 *                                                                     (140,000 = EUR 120,000 in local currency: the second currency)
 *  3  Alan Turing    ENG   DE      L4    ON_LEAVE   PART_TIME  0.5   60,000 from 2025-01-01
 *  4  Sam Seller     SAL   US      L3    ACTIVE     CONTRACT   1.0   70,000 [2023-01-01, 2024-01-01)  then 80,000 from 2024-01-01
 *  5  Ada Byron      SAL   US      L3    TERMINATED FULL_TIME  1.0   90,000 from 2022-01-01, never closed (terminated 2025-12-31)
 *  6  Nora Nobody    SAL   DE      L3    ACTIVE     FULL_TIME  1.0   no salary record at all
 * </pre>
 *
 * <p>Default population on 2026-06-15 (no terminated, no employee without a record): Ada 100k,
 * Grace 140k, Alan 60k, Sam 80k -- sorted 60k, 80k, 100k, 140k.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import(AnalyticsRepository.class)
class AnalyticsRepositoryTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 15);
    private static final EmployeeFilter ALL = filter(null, null, null, null, null, null);

    @Autowired
    private AnalyticsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (1, 'ENG', 'Engineering'), (2, 'SAL', 'Sales')");
        jdbc.update("INSERT INTO location (id, country_code, country_name, city, currency_code) VALUES "
                + "(1, 'US', 'United States', 'Austin', 'USD'), (2, 'DE', 'Germany', 'Berlin', 'EUR')");
        jdbc.update("INSERT INTO job_role (id, title, job_family, job_level) VALUES "
                + "(1, 'Software Engineer', 'Engineering', 'L4'), (2, 'Senior Software Engineer', 'Engineering', 'L5'), "
                + "(3, 'Sales Rep', 'Sales', 'L3')");
        jdbc.update("INSERT INTO app_user (id, email, password_hash, full_name, role, enabled, created_at) VALUES "
                + "(1, 'hr@acme.example', 'x', 'HR', 'HR_MANAGER', true, TIMESTAMPTZ '2020-01-01 00:00:00+00')");

        employee(1, "Ada", "Lovelace", "ACTIVE", "FULL_TIME", "1.000", 1, 1, 1, null);
        employee(2, "Grace", "Hopper", "ACTIVE", "FULL_TIME", "1.000", 1, 2, 2, null);
        employee(3, "Alan", "Turing", "ON_LEAVE", "PART_TIME", "0.500", 1, 1, 2, null);
        employee(4, "Sam", "Seller", "ACTIVE", "CONTRACT", "1.000", 2, 3, 1, null);
        employee(5, "Ada", "Byron", "TERMINATED", "FULL_TIME", "1.000", 2, 3, 1, "2025-12-31");
        employee(6, "Nora", "Nobody", "ACTIVE", "FULL_TIME", "1.000", 2, 3, 2, null);

        salary(1, 1, "2024-01-01", "2026-09-01", "USD", "100000.00", "100000.00");
        salary(2, 1, "2026-09-01", null, "USD", "130000.00", "130000.00");
        salary(3, 2, "2025-03-01", "2025-03-01", "EUR", "999999.99", "999999.99");
        salary(4, 2, "2025-03-01", null, "EUR", "120000.00", "140000.00");
        salary(5, 3, "2025-01-01", null, "USD", "60000.00", "60000.00");
        salary(6, 4, "2023-01-01", "2024-01-01", "USD", "70000.00", "70000.00");
        salary(7, 4, "2024-01-01", null, "USD", "80000.00", "80000.00");
        salary(8, 5, "2022-01-01", null, "USD", "90000.00", "90000.00");
    }

    // ---- fixture helpers -------------------------------------------------------------------

    private void employee(long id, String first, String last, String status, String type, String fte,
                          long dept, long role, long location, String terminationDate) {
        jdbc.update("INSERT INTO employee (id, employee_code, first_name, last_name, email, hire_date, "
                        + "termination_date, employment_status, employment_type, fte_ratio, department_id, "
                        + "job_role_id, location_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, DATE '2020-01-01', CAST(? AS DATE), ?, ?, CAST(? AS NUMERIC), ?, ?, ?, "
                        + "TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')",
                id, "ACME-00000" + id, first, last, first.toLowerCase() + "." + last.toLowerCase() + "@acme.example",
                terminationDate, status, type, fte, dept, role, location);
    }

    private void salary(long id, long employeeId, String from, String to, String currency, String local, String base) {
        jdbc.update("INSERT INTO salary_record (id, employee_id, effective_from, effective_to, base_amount, "
                        + "currency_code, pay_frequency, annualised_amount, annualised_amount_base_ccy, "
                        + "change_reason, created_by, created_at) "
                        + "VALUES (?, ?, CAST(? AS DATE), CAST(? AS DATE), CAST(? AS NUMERIC), ?, 'ANNUAL', "
                        + "CAST(? AS NUMERIC), CAST(? AS NUMERIC), 'NEW_HIRE', 1, TIMESTAMPTZ '2020-01-01 00:00:00+00')",
                id, employeeId, from, to, local, currency, local, base);
    }

    private static EmployeeFilter filter(String q, Long dept, String country, EmploymentStatus status,
                                         EmploymentType type, String level) {
        return new EmployeeFilter(q, dept, country, status, type, level);
    }

    /** Compares the exact text, so both the value and the 2-decimal scale of a money figure are asserted. */
    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).as("money value").isNotNull();
        assertThat(actual.toPlainString()).isEqualTo(expected);
    }

    private SummaryRow summary(EmployeeFilter filter) {
        return repository.summary(filter, AS_OF);
    }

    // ---- summary: population and as_of -----------------------------------------------------

    @Test
    @DisplayName("FR-4.1: statistics over the default population are worked out by hand (60k, 80k, 100k, 140k)")
    void summary_defaultPopulation_matchesHandComputedFigures() {
        SummaryRow row = summary(ALL);

        // headcount 4 = Ada, Grace, Alan, Sam. Payroll = 100k + 140k + 60k*0.5 + 80k = 350,000.
        // mean = 380k/4 = 95,000. percentile_cont position = p*(n-1):
        // p25 -> 0.75: 60k + 0.75*20k = 75,000 ; median -> 1.5: 80k + 0.5*20k = 90,000 ;
        // p75 -> 2.25: 100k + 0.25*40k = 110,000.
        assertThat(row.headcount()).isEqualTo(4);
        assertMoney(row.totalPayroll(), "350000.00");
        assertMoney(row.mean(), "95000.00");
        assertMoney(row.p25(), "75000.00");
        assertMoney(row.median(), "90000.00");
        assertMoney(row.p75(), "110000.00");
    }

    @Test
    @DisplayName("FR-4.1: a raise effective after asOf is not used; once its date is reached it replaces the old record")
    void summary_futureDatedRaise_isPickedOnlyFromItsEffectiveDate() {
        SummaryRow before = repository.summary(ALL, LocalDate.of(2026, 8, 31));
        SummaryRow onRaiseDate = repository.summary(ALL, LocalDate.of(2026, 9, 1));

        // Before: Ada 100k -> payroll 350,000. On 2026-09-01 Ada is 130k: values 60k, 80k, 130k, 140k.
        // payroll = 130k + 140k + 30k + 80k = 380,000 ; mean = 410k/4 = 102,500 ;
        // p25 = 60k + 0.75*20k = 75,000 ; median = 80k + 0.5*50k = 105,000 ; p75 = 130k + 0.25*10k = 132,500.
        assertMoney(before.totalPayroll(), "350000.00");
        assertMoney(onRaiseDate.totalPayroll(), "380000.00");
        assertMoney(onRaiseDate.mean(), "102500.00");
        assertMoney(onRaiseDate.p25(), "75000.00");
        assertMoney(onRaiseDate.median(), "105000.00");
        assertMoney(onRaiseDate.p75(), "132500.00");
        assertThat(onRaiseDate.headcount()).isEqualTo(4);
    }

    @Test
    @DisplayName("FR-4.1: on the day one record ends and the next begins, only the new record is counted (half-open interval)")
    void summary_onTheBoundaryDate_countsEachEmployeeOnce() {
        // As of 2024-01-01: Ada's first record starts (100k); Sam's 70k record ends that day (to > asOf
        // fails) and his 80k record starts. Grace (from 2025-03-01) and Alan (from 2025-01-01) have
        // nothing yet; Byron is terminated. So headcount 2: 100k and 80k -> mean 90,000, payroll 180,000.
        SummaryRow row = repository.summary(ALL, LocalDate.of(2024, 1, 1));

        assertThat(row.headcount()).isEqualTo(2);
        assertMoney(row.totalPayroll(), "180000.00");
        assertMoney(row.mean(), "90000.00");
        assertMoney(row.median(), "90000.00");
    }

    @Test
    @DisplayName("FR-4.1: a zero-length superseded record is never the one in force")
    void summary_zeroLengthRecord_isNeverInForce() {
        // Grace's 999,999.99 record has from = to = 2025-03-01. On that exact date it would match if the
        // predicate were 'to >= asOf'. Expected: Ada 100k, Grace 140k, Alan 60k (fte 0.5), Sam 80k.
        SummaryRow row = repository.summary(ALL, LocalDate.of(2025, 3, 1));

        assertThat(row.headcount()).isEqualTo(4);
        assertMoney(row.totalPayroll(), "350000.00");
        assertMoney(row.mean(), "95000.00");
    }

    @Test
    @DisplayName("FR-4.1: before anyone's first record there is no salary to report, and no error")
    void summary_beforeAnyRecord_isEmpty() {
        SummaryRow row = repository.summary(ALL, LocalDate.of(2019, 1, 1));

        assertThat(row.headcount()).isZero();
    }

    @Test
    @DisplayName("FR-4.1: an employee with no salary record is not counted")
    void summary_employeeWithoutSalaryRecord_isNotCounted() {
        assertThat(summary(filter("nora", null, null, null, null, null)).headcount()).isZero();
    }

    @Test
    @DisplayName("FR-4.1: a terminated employee is excluded by default (ADR-0011/0013)")
    void summary_terminatedEmployee_isExcludedByDefault() {
        assertThat(summary(filter("byron", null, null, null, null, null)).headcount()).isZero();
    }

    @Test
    @DisplayName("FR-4.7: status=TERMINATED analyses exactly the terminated employees, using their open record")
    void summary_statusTerminated_includesTerminatedEmployees() {
        SummaryRow row = summary(filter(null, null, null, EmploymentStatus.TERMINATED, null, null));

        assertThat(row.headcount()).isEqualTo(1);
        assertMoney(row.totalPayroll(), "90000.00");
        assertMoney(row.median(), "90000.00");
    }

    @Test
    @DisplayName("FR-4.7: status=ACTIVE excludes ON_LEAVE; the mean is rounded HALF_UP to 2 decimals (NFR-2)")
    void summary_statusActive_excludesOnLeaveAndRoundsHalfUp() {
        // Ada 100k, Grace 140k, Sam 80k (Alan is ON_LEAVE, Nora has no record). Sorted 80k, 100k, 140k.
        // mean = 320,000/3 = 106,666.666... -> 106,666.67. p25 = 80k + 0.5*20k = 90,000. median 100,000.
        // p75 = 100k + 0.5*40k = 120,000. payroll = 320,000.
        SummaryRow row = summary(filter(null, null, null, EmploymentStatus.ACTIVE, null, null));

        assertThat(row.headcount()).isEqualTo(3);
        assertMoney(row.totalPayroll(), "320000.00");
        assertMoney(row.mean(), "106666.67");
        assertMoney(row.p25(), "90000.00");
        assertMoney(row.median(), "100000.00");
        assertMoney(row.p75(), "120000.00");
    }

    @Test
    @DisplayName("FR-4.1: ON_LEAVE counts as employed in the default population")
    void summary_onLeaveEmployee_isIncludedByDefault() {
        assertThat(summary(filter("turing", null, null, null, null, null)).headcount()).isEqualTo(1);
    }

    // ---- summary: money rules --------------------------------------------------------------

    @Test
    @DisplayName("ADR-0013: a part-timer's payroll cost is scaled by FTE, but pay statistics use the unscaled FTE rate")
    void summary_partTimer_halvesPayrollButNotStatistics() {
        SummaryRow row = summary(filter(null, null, null, null, EmploymentType.PART_TIME, null));

        // Alan: rate 60,000 at fte 0.5 -> cost 30,000, but median/mean/percentiles stay 60,000.
        assertThat(row.headcount()).isEqualTo(1);
        assertMoney(row.totalPayroll(), "30000.00");
        assertMoney(row.mean(), "60000.00");
        assertMoney(row.median(), "60000.00");
        assertMoney(row.p25(), "60000.00");
        assertMoney(row.p75(), "60000.00");
    }

    @Test
    @DisplayName("FR-4.1: statistics use the base-currency amount, not the local one (Grace: EUR 120,000 = 140,000)")
    void summary_usesBaseCurrencyAmount() {
        SummaryRow row = summary(filter("grace", null, null, null, null, null));

        assertMoney(row.median(), "140000.00");
        assertMoney(row.totalPayroll(), "140000.00");
    }

    // ---- summary: edge slices --------------------------------------------------------------

    @Test
    @DisplayName("FR-4.1: an empty slice yields zero headcount, zero payroll and null statistics, never an error")
    void summary_emptySlice_isZerosAndNulls() {
        SummaryRow row = summary(filter("no-such-person", null, null, null, null, null));

        assertThat(row.headcount()).isZero();
        assertMoney(row.totalPayroll(), "0.00");
        assertThat(row.mean()).isNull();
        assertThat(row.median()).isNull();
        assertThat(row.p25()).isNull();
        assertThat(row.p75()).isNull();
    }

    @Test
    @DisplayName("FR-4.1: a single-employee slice has every statistic equal to that salary")
    void summary_singleEmployee_allStatisticsEqualTheSalary() {
        SummaryRow row = summary(filter("sam", null, null, null, null, null));

        assertThat(row.headcount()).isEqualTo(1);
        assertMoney(row.mean(), "80000.00");
        assertMoney(row.p25(), "80000.00");
        assertMoney(row.median(), "80000.00");
        assertMoney(row.p75(), "80000.00");
        assertMoney(row.totalPayroll(), "80000.00");
    }

    // ---- summary: each filter narrows the slice --------------------------------------------

    @Test
    @DisplayName("FR-4.7: departmentId=ENG -> Ada 100k, Grace 140k, Alan 60k")
    void summary_departmentFilter() {
        // sorted 60k, 100k, 140k: mean 100,000 ; p25 = 60k + 0.5*40k = 80,000 ; median 100,000 ;
        // p75 = 100k + 0.5*40k = 120,000 ; payroll = 100k + 140k + 30k = 270,000.
        SummaryRow row = summary(filter(null, 1L, null, null, null, null));

        assertThat(row.headcount()).isEqualTo(3);
        assertMoney(row.totalPayroll(), "270000.00");
        assertMoney(row.mean(), "100000.00");
        assertMoney(row.p25(), "80000.00");
        assertMoney(row.median(), "100000.00");
        assertMoney(row.p75(), "120000.00");
    }

    @Test
    @DisplayName("FR-4.7: countryCode=de (lower-case) -> Grace 140k, Alan 60k")
    void summary_countryFilter_isCaseInsensitive() {
        // sorted 60k, 140k: mean 100,000 ; median 100,000 ; p25 = 60k + 0.25*80k = 80,000 ;
        // p75 = 60k + 0.75*80k = 120,000 ; payroll = 140k + 30k = 170,000.
        SummaryRow row = summary(filter(null, null, "de", null, null, null));

        assertThat(row.headcount()).isEqualTo(2);
        assertMoney(row.totalPayroll(), "170000.00");
        assertMoney(row.mean(), "100000.00");
        assertMoney(row.p25(), "80000.00");
        assertMoney(row.median(), "100000.00");
        assertMoney(row.p75(), "120000.00");
    }

    @Test
    @DisplayName("FR-4.7: jobLevel=' l4 ' (padded, lower-case) -> Ada 100k, Alan 60k")
    void summary_jobLevelFilter_isNormalised() {
        // mean = median = 80,000 ; p25 = 60k + 0.25*40k = 70,000 ; p75 = 90,000 ; payroll = 100k + 30k.
        SummaryRow row = summary(filter(null, null, null, null, null, " l4 "));

        assertThat(row.headcount()).isEqualTo(2);
        assertMoney(row.totalPayroll(), "130000.00");
        assertMoney(row.mean(), "80000.00");
        assertMoney(row.p25(), "70000.00");
        assertMoney(row.median(), "80000.00");
        assertMoney(row.p75(), "90000.00");
    }

    @Test
    @DisplayName("FR-4.7: employmentType=CONTRACT -> Sam only")
    void summary_employmentTypeFilter() {
        assertMoney(summary(filter(null, null, null, null, EmploymentType.CONTRACT, null)).median(), "80000.00");
    }

    @Test
    @DisplayName("FR-4.7: q matches any of name, code and email, every token must match, in any order")
    void summary_searchFilter() {
        // 'ada' matches Ada Lovelace (and Ada Byron, who is terminated and so excluded by default).
        assertMoney(summary(filter("ADA", null, null, null, null, null)).median(), "100000.00");
        // 'hopper grace' - tokens in any order across first and last name.
        assertMoney(summary(filter("hopper grace", null, null, null, null, null)).median(), "140000.00");
        // Employee code and email are searchable too.
        assertMoney(summary(filter("acme-000004", null, null, null, null, null)).median(), "80000.00");
        assertMoney(summary(filter("alan.turing@", null, null, null, null, null)).median(), "60000.00");
        // Every token must match: 'ada grace' matches nobody.
        assertThat(summary(filter("ada grace", null, null, null, null, null)).headcount()).isZero();
    }

    @Test
    @DisplayName("FR-4.7: LIKE wildcards in q are literal, so '%' does not match everyone")
    void summary_searchFilter_treatsWildcardsLiterally() {
        assertThat(summary(filter("%", null, null, null, null, null)).headcount()).isZero();
        assertThat(summary(filter("_", null, null, null, null, null)).headcount()).isZero();
    }

    @Test
    @DisplayName("NFR-4: a hostile q is treated as text and finds nobody, without breaking the query")
    void summary_searchFilter_withSqlInjectionAttempt_isHarmless() {
        SummaryRow row = summary(filter("x' OR 1=1 --", null, null, null, null, null));

        assertThat(row.headcount()).isZero();
        assertThat(summary(ALL).headcount()).isEqualTo(4);
    }

    @Test
    @DisplayName("FR-4.7: filters combine with AND: department ENG and country DE -> Grace and Alan")
    void summary_combinedFilters_narrow() {
        SummaryRow row = summary(filter(null, 1L, "DE", null, null, null));

        assertThat(row.headcount()).isEqualTo(2);
        assertMoney(row.median(), "100000.00");
    }

    // ---- by group --------------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.2: grouped by department, ordered by name")
    void byGroup_department() {
        List<GroupRow> groups = repository.byGroup(GroupBy.DEPARTMENT, ALL, AS_OF);

        // Engineering: Ada 100k, Grace 140k, Alan 60k -> median 100,000, mean 100,000.
        // Sales: Sam 80k only (Byron terminated, Nora has no record).
        assertThat(groups).extracting(GroupRow::key, GroupRow::label, GroupRow::headcount)
                .containsExactly(
                        tuple("1", "Engineering", 3L),
                        tuple("2", "Sales", 1L));
        assertMoney(groups.get(0).median(), "100000.00");
        assertMoney(groups.get(0).mean(), "100000.00");
        assertMoney(groups.get(1).median(), "80000.00");
        assertMoney(groups.get(1).mean(), "80000.00");
    }

    @Test
    @DisplayName("FR-4.2: grouped by country, keyed by country code and labelled with the country name")
    void byGroup_country() {
        List<GroupRow> groups = repository.byGroup(GroupBy.COUNTRY, ALL, AS_OF);

        // DE: Grace 140k, Alan 60k -> median 100,000. US: Ada 100k, Sam 80k -> median 90,000.
        assertThat(groups).extracting(GroupRow::key, GroupRow::label, GroupRow::headcount)
                .containsExactly(
                        tuple("DE", "Germany", 2L),
                        tuple("US", "United States", 2L));
        assertMoney(groups.get(0).median(), "100000.00");
        assertMoney(groups.get(1).median(), "90000.00");
        assertMoney(groups.get(1).mean(), "90000.00");
    }

    @Test
    @DisplayName("FR-4.2: grouped by job level")
    void byGroup_jobLevel() {
        List<GroupRow> groups = repository.byGroup(GroupBy.JOB_LEVEL, ALL, AS_OF);

        // L3: Sam 80k. L4: Ada 100k + Alan 60k -> median 80,000. L5: Grace 140k.
        assertThat(groups).extracting(GroupRow::key, GroupRow::label, GroupRow::headcount)
                .containsExactly(
                        tuple("L3", "L3", 1L),
                        tuple("L4", "L4", 2L),
                        tuple("L5", "L5", 1L));
        assertMoney(groups.get(0).median(), "80000.00");
        assertMoney(groups.get(1).median(), "80000.00");
        assertMoney(groups.get(1).mean(), "80000.00");
        assertMoney(groups.get(2).median(), "140000.00");
    }

    @Test
    @DisplayName("FR-4.7: grouping respects the filters, so the terminated slice is a group of its own")
    void byGroup_respectsFilters() {
        List<GroupRow> groups = repository.byGroup(GroupBy.DEPARTMENT,
                filter(null, null, null, EmploymentStatus.TERMINATED, null, null), AS_OF);

        assertThat(groups).extracting(GroupRow::label, GroupRow::headcount).containsExactly(
                tuple("Sales", 1L));
        assertMoney(groups.get(0).median(), "90000.00");
    }

    @Test
    @DisplayName("FR-4.2: the group headcounts add up to the summary headcount for every grouping")
    void byGroup_headcountsSumToSummaryHeadcount() {
        for (GroupBy groupBy : GroupBy.values()) {
            long total = repository.byGroup(groupBy, ALL, AS_OF).stream().mapToLong(GroupRow::headcount).sum();
            assertThat(total).as(groupBy.name()).isEqualTo(summary(ALL).headcount());
        }
    }

    @Test
    @DisplayName("FR-4.2: an empty slice has no groups, and no error")
    void byGroup_emptySlice_isEmpty() {
        assertThat(repository.byGroup(GroupBy.COUNTRY, filter("nobody-here", null, null, null, null, null), AS_OF))
                .isEmpty();
    }

    // ---- distribution ----------------------------------------------------------------------

    @Test
    @DisplayName("FR-4.3: 4 buckets over 60k..140k are 20k wide; a value on a boundary belongs to the upper bucket, the max to the last")
    void distribution_fourBuckets_boundariesAreLowerInclusiveAndLastIsClosed() {
        List<BucketRow> buckets = repository.distribution(ALL, AS_OF, 4);

        // Values 60k, 80k, 100k, 140k. [60,80)={60k} [80,100)={80k} [100,120)={100k} [120,140]={140k}.
        assertThat(buckets).hasSize(4);
        String[][] expected = {{"60000.00", "80000.00"}, {"80000.00", "100000.00"},
                {"100000.00", "120000.00"}, {"120000.00", "140000.00"}};
        for (int i = 0; i < 4; i++) {
            assertMoney(buckets.get(i).lower(), expected[i][0]);
            assertMoney(buckets.get(i).upper(), expected[i][1]);
            assertThat(buckets.get(i).count()).as("bucket " + i).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("FR-4.3: buckets with no employees are still returned, with a zero count")
    void distribution_includesEmptyBuckets() {
        // On 2026-09-01 the values are 60k, 80k, 130k, 140k: [60,80)=1 [80,100)=1 [100,120)=0 [120,140]=2.
        List<BucketRow> buckets = repository.distribution(ALL, LocalDate.of(2026, 9, 1), 4);

        assertThat(buckets).extracting(BucketRow::count).containsExactly(1L, 1L, 0L, 2L);
    }

    @Test
    @DisplayName("FR-4.3: the buckets parameter is respected: 3 buckets over 60k..140k are 26,666.67 wide")
    void distribution_threeBuckets_usesRoundedBounds() {
        List<BucketRow> buckets = repository.distribution(ALL, AS_OF, 3);

        // Edges 60,000 ; 86,666.67 ; 113,333.33 ; 140,000. [60,86.67)={60k,80k} [86.67,113.33)={100k} [113.33,140]={140k}.
        assertThat(buckets).hasSize(3);
        assertMoney(buckets.get(0).lower(), "60000.00");
        assertMoney(buckets.get(0).upper(), "86666.67");
        assertMoney(buckets.get(1).lower(), "86666.67");
        assertMoney(buckets.get(1).upper(), "113333.33");
        assertMoney(buckets.get(2).lower(), "113333.33");
        assertMoney(buckets.get(2).upper(), "140000.00");
        assertThat(buckets).extracting(BucketRow::count).containsExactly(2L, 1L, 1L);
    }

    @Test
    @DisplayName("FR-4.3: a value on a rounded bucket edge is counted in the bucket whose displayed range holds it")
    void distribution_membershipAgreesWithTheDisplayedRoundedEdges() {
        // 60,000.00 .. 60,000.08 in 3 buckets: exact edges 60,000.0267 / 60,000.0533 display as .03 / .05.
        // 60,000.05 is below the exact edge (60,000.0533) but is displayed as the START of the last bucket.
        employee(20, "Zed", "One", "ACTIVE", "FULL_TIME", "1.000", 1, 1, 1, null);
        employee(21, "Zed", "Two", "ACTIVE", "FULL_TIME", "1.000", 1, 1, 1, null);
        employee(22, "Zed", "Three", "ACTIVE", "FULL_TIME", "1.000", 1, 1, 1, null);
        salary(20, 20, "2020-01-01", null, "USD", "60000.00", "60000.00");
        salary(21, 21, "2020-01-01", null, "USD", "60000.05", "60000.05");
        salary(22, 22, "2020-01-01", null, "USD", "60000.08", "60000.08");

        List<BucketRow> buckets = repository.distribution(filter("zed", null, null, null, null, null), AS_OF, 3);

        assertThat(buckets).hasSize(3);
        assertMoney(buckets.get(0).lower(), "60000.00");
        assertMoney(buckets.get(0).upper(), "60000.03");
        assertMoney(buckets.get(1).lower(), "60000.03");
        assertMoney(buckets.get(1).upper(), "60000.05");
        assertMoney(buckets.get(2).lower(), "60000.05");
        assertMoney(buckets.get(2).upper(), "60000.08");
        // [60000.00, .03) = {60000.00}; [.03, .05) = {}; [.05, .08] = {60000.05, 60000.08}.
        assertThat(buckets).extracting(BucketRow::count).containsExactly(1L, 0L, 2L);
    }

    @Test
    @DisplayName("FR-4.3: two buckets over 60k..140k split at 100k, which belongs to the upper one")
    void distribution_twoBuckets() {
        assertThat(repository.distribution(ALL, AS_OF, 2)).extracting(BucketRow::count).containsExactly(2L, 2L);
    }

    @Test
    @DisplayName("FR-4.3: whatever the bucket count, every employee is in exactly one bucket and the range is the slice min..max")
    void distribution_bucketsSumToHeadcountForEveryBucketCount() {
        for (int n = 2; n <= 12; n++) {
            List<BucketRow> buckets = repository.distribution(ALL, AS_OF, n);

            assertThat(buckets).as("bucket count " + n).hasSize(n);
            assertThat(buckets.stream().mapToLong(BucketRow::count).sum()).as("sum for " + n).isEqualTo(4);
            assertMoney(buckets.get(0).lower(), "60000.00");
            assertMoney(buckets.get(n - 1).upper(), "140000.00");
        }
    }

    @Test
    @DisplayName("FR-4.3: when every salary in the slice is equal there is a single bucket, whatever buckets was asked for")
    void distribution_minEqualsMax_isOneBucket() {
        List<BucketRow> buckets = repository.distribution(filter("sam", null, null, null, null, null), AS_OF, 12);

        assertThat(buckets).hasSize(1);
        assertMoney(buckets.get(0).lower(), "80000.00");
        assertMoney(buckets.get(0).upper(), "80000.00");
        assertThat(buckets.get(0).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-4.3: an empty slice has no buckets, and no error")
    void distribution_emptySlice_isEmpty() {
        assertThat(repository.distribution(filter("nobody-here", null, null, null, null, null), AS_OF, 12)).isEmpty();
    }

    @Test
    @DisplayName("FR-4.7: the histogram respects the filters: ENG only spans 60k..140k with 3 people")
    void distribution_respectsFilters() {
        List<BucketRow> buckets = repository.distribution(filter(null, 1L, null, null, null, null), AS_OF, 2);

        // ENG values 60k, 100k, 140k; 2 buckets split at 100k: [60,100)={60k} [100,140]={100k,140k}.
        assertThat(buckets).extracting(BucketRow::count).containsExactly(1L, 2L);
        assertMoney(buckets.get(0).upper(), "100000.00");
    }

    // ---- rates "as of" (requirements.md assumption 2) --------------------------------------

    private void rate(long id, String from, String to, String rate, String effectiveDate) {
        jdbc.update("INSERT INTO exchange_rate (id, from_currency, to_currency, rate, effective_date, source) "
                + "VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS DATE), 'TEST')", id, from, to, rate, effectiveDate);
    }

    @Test
    @DisplayName("Assumption 2: the rates as-of date is the latest effective_date over all currencies")
    void latestRateDate_isTheMaximumEffectiveDateAcrossAllRows() {
        rate(1, "EUR", "USD", "1.08000000", "2025-03-01");
        rate(2, "GBP", "USD", "1.27000000", "2025-06-30");
        rate(3, "JPY", "USD", "0.00650000", "2024-12-31");

        assertThat(repository.latestRateDate()).isEqualTo(LocalDate.of(2025, 6, 30));
    }

    @Test
    @DisplayName("Assumption 2: with no exchange rates the as-of date is null, not an error")
    void latestRateDate_withNoRates_isNull() {
        assertThat(repository.latestRateDate()).isNull();
    }
}
