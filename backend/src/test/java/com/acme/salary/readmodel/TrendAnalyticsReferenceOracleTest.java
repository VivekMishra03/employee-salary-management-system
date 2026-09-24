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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-4.6 (NFR-1 rewrite guard): compares {@link TrendAnalyticsRepository} with a REFERENCE ORACLE, the
 * original straightforward SQL (one row per period x employee x record, filtered by the definition of
 * "in force on the period's last day"). The oracle is slow at 10,000 employees, which is why the
 * production query no longer works that way, but it is obviously correct and is the specification
 * of the semantics. Fixture: 200 employees from {@code new Random(42)}; nothing reads a clock.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import(TrendAnalyticsRepository.class)
class TrendAnalyticsReferenceOracleTest {

    /** The pre-rewrite query, verbatim: periods x employees x records. Test-only reference oracle. */
    private static final String ORACLE = """
            WITH periods AS (
                SELECT CAST(gs AS DATE) AS bucket_start
                FROM generate_series(CAST(:firstBucket AS TIMESTAMP), CAST(:rangeTo AS TIMESTAMP),
                                     make_interval(months => :months)) AS gs
            ), bounds AS (
                SELECT bucket_start,
                       GREATEST(bucket_start, CAST(:rangeFrom AS DATE)) AS p_from,
                       LEAST(CAST(bucket_start + make_interval(months => :months) - INTERVAL '1 day' AS DATE),
                             CAST(:rangeTo AS DATE)) AS p_to
                FROM periods
            ), payroll AS (
                SELECT b.bucket_start,
                       SUM(s.annualised_amount_base_ccy * e.fte_ratio) AS total
                FROM bounds b
                JOIN employee e ON e.hire_date <= b.p_to
                               AND (e.termination_date IS NULL OR e.termination_date > b.p_to)
                JOIN salary_record s ON s.employee_id = e.id
                                    AND s.effective_from <= b.p_to
                                    AND (s.effective_to IS NULL OR s.effective_to > b.p_to)
                JOIN location l ON l.id = e.location_id
                JOIN job_role jr ON jr.id = e.job_role_id
                WHERE %1$s
                GROUP BY b.bucket_start
            ), changes AS (
                SELECT employee_id, effective_from, change_reason, currency_code, annualised_amount,
                       LAG(annualised_amount) OVER w AS prev_amount,
                       LAG(currency_code) OVER w AS prev_currency
                FROM salary_record
                WHERE effective_to IS NULL OR effective_from < effective_to
                WINDOW w AS (PARTITION BY employee_id ORDER BY effective_from)
            ), increases AS (
                SELECT b.bucket_start,
                       AVG((s.annualised_amount - s.prev_amount) / s.prev_amount * 100) AS avg_pct
                FROM bounds b
                JOIN changes s ON s.effective_from BETWEEN b.p_from AND b.p_to
                JOIN employee e ON e.id = s.employee_id
                JOIN location l ON l.id = e.location_id
                JOIN job_role jr ON jr.id = e.job_role_id
                WHERE %1$s
                  AND s.change_reason NOT IN ('NEW_HIRE', 'CORRECTION')
                  AND s.prev_amount > 0
                  AND s.prev_currency = s.currency_code
                GROUP BY b.bucket_start
            )
            SELECT b.bucket_start,
                   COALESCE(ROUND(p.total, 2), 0.00) AS total_payroll,
                   ROUND(i.avg_pct, 2) AS avg_increase_pct
            FROM bounds b
            LEFT JOIN payroll p ON p.bucket_start = b.bucket_start
            LEFT JOIN increases i ON i.bucket_start = b.bucket_start
            ORDER BY b.bucket_start
            """;

    private static final EmployeeFilter ALL = new EmployeeFilter(null, null, null, null, null, null);
    private static final List<String> RAISE_REASONS =
            List.of("MERIT_INCREASE", "PROMOTION", "MARKET_ADJUSTMENT", "ROLE_CHANGE", "DEMOTION", "CORRECTION",
                    "NEW_HIRE");
    private static final LocalDate EPOCH = LocalDate.of(2018, 1, 1);

    @Autowired
    private TrendAnalyticsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedRandomButFixedFixture() {
        SqlFixture f = new SqlFixture(jdbc).baseData().jobRole(1, "Engineer", "L4");
        Random random = new Random(42);
        long salaryId = 1;
        for (long id = 1; id <= 200; id++) {
            LocalDate hire = EPOCH.plusDays(random.nextInt(3000));
            LocalDate termination = random.nextInt(4) == 0 ? hire.plusDays(random.nextInt(1500)) : null;
            String fte = List.of("1.000", "0.500", "0.800").get(random.nextInt(3));
            long department = 1 + random.nextInt(2);
            long location = 1 + random.nextInt(2);
            f.employee(id, "E" + id, "Random", null, termination == null ? "ACTIVE" : "TERMINATED", fte,
                    department, 1, location, hire.toString(), termination == null ? null : termination.toString());

            // The first record starts up to 60 days before or after the hire date; each next record
            // follows the previous one directly, after a gap, or as a zero-length superseded record.
            LocalDate from = hire.plusDays(random.nextInt(121) - 60);
            int records = 1 + random.nextInt(4);
            for (int r = 0; r < records; r++) {
                boolean last = r == records - 1;
                LocalDate to = last && random.nextInt(10) < 7 ? null : from.plusDays(20 + random.nextInt(480));
                boolean euro = random.nextInt(5) == 0;
                BigDecimal local = BigDecimal.valueOf(3_000_000 + random.nextInt(17_000_000), 2);
                BigDecimal base = euro ? local.multiply(new BigDecimal("1.1")).setScale(2, RoundingMode.HALF_UP) : local;
                String reason = r == 0 ? "NEW_HIRE" : RAISE_REASONS.get(random.nextInt(RAISE_REASONS.size()));
                f.salary(salaryId++, id, from.toString(), to == null ? null : to.toString(), euro ? "EUR" : "USD",
                        local.toPlainString(), base.toPlainString(), reason);
                if (to == null) {
                    break;
                }
                if (random.nextInt(8) == 0) {
                    f.salary(salaryId++, id, to.toString(), to.toString(), "USD", "999999.00", "999999.00",
                            "MERIT_INCREASE");
                }
                from = to.plusDays(random.nextInt(6) == 0 ? random.nextInt(40) : 0);
            }
        }
    }

    private List<TrendRow> oracle(TrendInterval interval, LocalDate from, LocalDate to, EmployeeFilter filter) {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.buildFiltersOnly(filter);
        Map<String, Object> params = new HashMap<>(clause.params());
        params.put("firstBucket", interval.bucketStart(from));
        params.put("rangeFrom", from);
        params.put("rangeTo", to);
        params.put("months", interval.months());
        return new NamedParameterJdbcTemplate(jdbc).query(ORACLE.formatted(clause.where()),
                new MapSqlParameterSource(params),
                (rs, i) -> new TrendRow(rs.getObject("bucket_start", LocalDate.class),
                        rs.getBigDecimal("total_payroll"), rs.getBigDecimal("avg_increase_pct")));
    }

    private static List<LocalDate[]> windows(TrendInterval interval) {
        return switch (interval) {
            case MONTH -> List.of(range("2019-01-01", "2025-12-31"), range("2022-03-15", "2024-11-10"),
                    range("2024-02-29", "2024-02-29"), range("2023-07-01", "2023-07-01"),
                    range("2018-01-01", "2018-06-30"), range("2026-01-01", "2028-12-31"),
                    range("2020-12-31", "2021-03-01"));
            case QUARTER -> List.of(range("2018-02-20", "2026-05-05"), range("2020-01-01", "2023-12-31"),
                    range("2024-02-29", "2024-02-29"), range("2021-03-31", "2021-04-01"),
                    range("2017-01-01", "2018-03-31"));
            case YEAR -> List.of(range("2016-06-30", "2026-12-31"), range("2020-01-01", "2020-12-31"),
                    range("2019-02-28", "2024-02-29"), range("2022-12-31", "2022-12-31"));
        };
    }

    private static LocalDate[] range(String from, String to) {
        return new LocalDate[]{LocalDate.parse(from), LocalDate.parse(to)};
    }

    @Test
    @DisplayName("FR-4.6: the rewritten trend SQL equals the reference oracle for MONTH/QUARTER/YEAR, several windows and filters")
    void trend_equalsTheReferenceOracle() {
        List<EmployeeFilter> filters = List.of(ALL,
                new EmployeeFilter(null, 2L, null, null, null, null),
                new EmployeeFilter(null, null, "DE", null, null, null),
                new EmployeeFilter(null, null, null, EmploymentStatus.TERMINATED, null, null));
        int nonZeroPayrollPeriods = 0;
        int periodsWithIncrease = 0;

        for (TrendInterval interval : TrendInterval.values()) {
            for (LocalDate[] window : windows(interval)) {
                for (EmployeeFilter filter : filters) {
                    List<TrendRow> expected = oracle(interval, window[0], window[1], filter);
                    List<TrendRow> actual = repository.trend(interval, window[0], window[1], filter);

                    assertThat(actual).as("%s %s..%s %s", interval, window[0], window[1], filter)
                            .isEqualTo(expected);
                    for (TrendRow row : expected) {
                        nonZeroPayrollPeriods += row.totalPayroll().signum() > 0 ? 1 : 0;
                        periodsWithIncrease += row.avgIncreasePct() != null ? 1 : 0;
                    }
                }
            }
        }

        // Guard against a vacuous comparison: the random fixture must actually produce figures.
        assertThat(nonZeroPayrollPeriods).isGreaterThan(200);
        assertThat(periodsWithIncrease).isGreaterThan(50);
    }
}
