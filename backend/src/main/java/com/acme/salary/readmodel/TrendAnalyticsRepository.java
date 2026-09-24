package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-4.6, FR-4.7 (ADR-0013): total payroll and average salary increase per calendar period,
 * computed in the database. Unlike the other analytics this is not a snapshot of "today", so it does
 * not use {@link AnalyticsFilterSql#build}: it uses {@link AnalyticsFilterSql#buildFiltersOnly}.
 *
 * <p><b>Filters refer to CURRENT attributes.</b> Department, location, job role, employment type and
 * status are not historised (only salary is, requirements.md section 6.3), so "department = Sales"
 * means people who are in Sales now, looked at across the whole period, even for a month when they
 * may have been elsewhere. An explicit status filter likewise applies to the current status, and
 * without one terminated employees are NOT excluded: someone who left in 2024 was on the 2023 payroll.
 *
 * <p>The period is a calendar bucket clipped to the requested range: {@code [max(bucket start, from),
 * min(bucket end, to)]}. A range that begins or ends mid-bucket therefore reports only what happened
 * inside the range, and no date outside it is looked at.
 */
@Repository
public class TrendAnalyticsRepository {

    /**
     * <p><b>"Employed on that day"</b> is made precise as the half-open interval
     * {@code hire_date <= day AND (termination_date IS NULL OR termination_date > day)}: the same
     * convention as salary records ({@code [effective_from, effective_to)}). The termination date
     * itself is therefore the first day the person is no longer counted.
     *
     * <p><b>Payroll</b> for a period is measured on the period's last day (capped at {@code to}): the
     * base-currency annualised amount of the record in force that day, times fte_ratio (the amount
     * actually paid), summed over the employees employed that day. It is a run-rate snapshot at the
     * end of the period, not the money paid during it.
     *
     * <p><b>Increase</b>: each salary record is compared with the record before it for the same
     * employee (LAG over effective_from). Zero-length superseded records (from = to, ADR-0011) are
     * filtered out BEFORE the LAG, so they are neither a "new" nor a "previous" side. A change counts
     * for the period its effective_from falls in, so a back-dated raise lands in the period it
     * applies to, not the period it was typed in. NEW_HIRE is not a raise, and CORRECTION fixes a
     * mistake rather than paying more; both are excluded. The comparison is in the record's own
     * (local) currency, so a change of currency (whose numbers are not comparable) is skipped, as
     * is a zero previous amount (no percentage exists). The period figure is the mean of the
     * individual percentages, not a ratio of sums.
     *
     * <p>Period starts come from Java ({@code :firstBucket}) so {@link TrendInterval} is the single
     * definition of a bucket; SQL only steps by {@code :months} and clips.
     *
     * <p><b>Why it is not a join of periods to rows (NFR-1).</b> Joining every period to every employee
     * and record costs periods x rows (120 x 10,000 in the worst case). Instead each (employee, record)
     * contributes {@code amount * fte} over the half-open interval {@code [max(effective_from,
     * hire_date), min(effective_to, termination_date))}, which is exactly "record in force AND employed"
     * on a day. A period counts a contribution iff that interval contains the period's measuring day, so
     * the query emits +amount at the first period whose measuring day is on or after the start and
     * -amount at the first period whose measuring day is on or after the end (a difference array), sums
     * those steps per bucket, and takes a running total across the periods. The first period measured on
     * or after a day is the period containing it (or the first period, if the day precedes the range; a
     * day after {@code to} has none and emits nothing), so the index is computed arithmetically from the
     * calendar bucket. LEAST/GREATEST ignore NULL, which is what makes "no end" work. All amounts stay
     * NUMERIC, so the running total is exact and rounding still happens once, at the end. Increases are
     * bucketed the same way: a change is in exactly one period, so it is grouped by its own bucket instead
     * of being joined to every period.
     */
    private static final String TREND = """
            WITH periods AS (
                SELECT CAST(gs AS DATE) AS bucket_start
                FROM generate_series(CAST(:firstBucket AS TIMESTAMP), CAST(:rangeTo AS TIMESTAMP),
                                     make_interval(months => :months)) AS gs
            ), contribution AS (
                SELECT GREATEST(s.effective_from, e.hire_date, CAST(:firstBucket AS DATE)) AS from_day,
                       LEAST(s.effective_to, e.termination_date) AS until_day,
                       s.annualised_amount_base_ccy * e.fte_ratio AS amount
                FROM employee e
                JOIN salary_record s ON s.employee_id = e.id
                JOIN location l ON l.id = e.location_id
                JOIN job_role jr ON jr.id = e.job_role_id
                WHERE %1$s
            ), payroll_events AS (
                SELECT from_day AS event_day, amount AS delta
                FROM contribution
                WHERE from_day <= CAST(:rangeTo AS DATE) AND (until_day IS NULL OR from_day < until_day)
                UNION ALL
                SELECT until_day, -amount
                FROM contribution
                WHERE until_day <= CAST(:rangeTo AS DATE) AND from_day < until_day
            ), payroll_steps AS (
                SELECT make_date(CAST(EXTRACT(YEAR FROM event_day) AS INT),
                                 (CAST(EXTRACT(MONTH FROM event_day) AS INT) - 1) / :months * :months + 1, 1) AS bucket_start,
                       SUM(delta) AS delta
                FROM payroll_events
                GROUP BY 1
            ), payroll AS (
                SELECT p.bucket_start,
                       SUM(COALESCE(d.delta, 0)) OVER (ORDER BY p.bucket_start) AS total
                FROM periods p
                LEFT JOIN payroll_steps d ON d.bucket_start = p.bucket_start
            ), changes AS (
                SELECT employee_id, effective_from, change_reason, currency_code, annualised_amount,
                       LAG(annualised_amount) OVER w AS prev_amount,
                       LAG(currency_code) OVER w AS prev_currency
                FROM salary_record
                WHERE effective_to IS NULL OR effective_from < effective_to
                WINDOW w AS (PARTITION BY employee_id ORDER BY effective_from)
            ), increases AS (
                SELECT make_date(CAST(EXTRACT(YEAR FROM s.effective_from) AS INT),
                                 (CAST(EXTRACT(MONTH FROM s.effective_from) AS INT) - 1) / :months * :months + 1, 1) AS bucket_start,
                       AVG((s.annualised_amount - s.prev_amount) / s.prev_amount * 100) AS avg_pct
                FROM changes s
                JOIN employee e ON e.id = s.employee_id
                JOIN location l ON l.id = e.location_id
                JOIN job_role jr ON jr.id = e.job_role_id
                WHERE %1$s
                  AND s.effective_from BETWEEN CAST(:rangeFrom AS DATE) AND CAST(:rangeTo AS DATE)
                  AND s.change_reason NOT IN ('NEW_HIRE', 'CORRECTION')
                  AND s.prev_amount > 0
                  AND s.prev_currency = s.currency_code
                GROUP BY 1
            )
            SELECT p.bucket_start,
                   COALESCE(ROUND(pay.total, 2), 0.00) AS total_payroll,
                   ROUND(i.avg_pct, 2) AS avg_increase_pct
            FROM periods p
            LEFT JOIN payroll pay ON pay.bucket_start = p.bucket_start
            LEFT JOIN increases i ON i.bucket_start = p.bucket_start
            ORDER BY p.bucket_start
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TrendAnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per calendar bucket touching {@code [from, to]}, in order, including empty ones. */
    public List<TrendRow> trend(TrendInterval interval, LocalDate from, LocalDate to, EmployeeFilter filter) {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.buildFiltersOnly(filter);
        Map<String, Object> params = new HashMap<>(clause.params());
        params.put("firstBucket", interval.bucketStart(from));
        params.put("rangeFrom", from);
        params.put("rangeTo", to);
        params.put("months", interval.months());
        return jdbc.query(TREND.formatted(clause.where()), new MapSqlParameterSource(params),
                (rs, i) -> new TrendRow(rs.getObject("bucket_start", LocalDate.class),
                        rs.getBigDecimal("total_payroll"), rs.getBigDecimal("avg_increase_pct")));
    }
}
