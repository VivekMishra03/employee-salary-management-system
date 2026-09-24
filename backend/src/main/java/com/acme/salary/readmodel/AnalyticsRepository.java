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
 * FR-4.1 - FR-4.3, FR-4.7: hand-written analytics SQL, computed in the database rather than in
 * application memory (CLAUDE.md section 7: analytics does not go through JPA).
 *
 * <p>Every query runs over the same "slice" (see {@link AnalyticsFilterSql}): employees matching the
 * filters, each joined to the salary record in force on {@code asOf}. All money is the
 * base-currency annualised amount and is returned as {@link java.math.BigDecimal} rounded to 2
 * places (NFR-2). SQL text is assembled only from constants; user input is bound (NFR-4).
 *
 * <p>Percentiles: PostgreSQL's {@code percentile_cont} works on double precision. It is cast to
 * NUMERIC and rounded immediately, so no floating-point value leaves the database.
 */
@Repository
public class AnalyticsRepository {

    /**
     * Pay statistics use the stored annualised amount as the full-time-equivalent rate, unscaled;
     * only payroll cost multiplies by fte_ratio (ADR-0013: the amount actually paid).
     */
    private static final String AMOUNT = "s.annualised_amount_base_ccy";

    private static final String MEDIAN =
            "ROUND(CAST(percentile_cont(0.50) WITHIN GROUP (ORDER BY " + AMOUNT + ") AS NUMERIC), 2)";

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * FR-4.1. An aggregate with no GROUP BY always returns exactly one row, so an empty slice comes
     * back as headcount 0, payroll 0.00 and null statistics rather than as no row.
     */
    public SummaryRow summary(EmployeeFilter filter, LocalDate asOf) {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.build(filter, asOf);
        String sql = """
                SELECT COUNT(*) AS headcount,
                       ROUND(COALESCE(SUM(%1$s * e.fte_ratio), 0), 2) AS total_payroll,
                       ROUND(AVG(%1$s), 2) AS mean,
                       ROUND(CAST(percentile_cont(0.25) WITHIN GROUP (ORDER BY %1$s) AS NUMERIC), 2) AS p25,
                       %2$s AS median,
                       ROUND(CAST(percentile_cont(0.75) WITHIN GROUP (ORDER BY %1$s) AS NUMERIC), 2) AS p75
                %3$s
                WHERE %4$s
                """.formatted(AMOUNT, MEDIAN, AnalyticsFilterSql.SLICE_FROM, clause.where());
        return jdbc.queryForObject(sql, new MapSqlParameterSource(clause.params()),
                (rs, i) -> new SummaryRow(rs.getLong("headcount"), rs.getBigDecimal("total_payroll"),
                        rs.getBigDecimal("mean"), rs.getBigDecimal("median"), rs.getBigDecimal("p25"),
                        rs.getBigDecimal("p75")));
    }

    /**
     * Requirements assumption 2: the latest {@code effective_date} in the rate table, so the UI can
     * say how stale the conversions are. A fact about the stored data, not the clock (NFR-3), and
     * independent of any filter. {@code MAX} over no rows is a single null row, hence null here.
     */
    public LocalDate latestRateDate() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT MAX(effective_date) FROM exchange_rate",
                LocalDate.class);
    }

    /** FR-4.2. Groups with no employees in the slice do not appear (inner joins). */
    public List<GroupRow> byGroup(GroupBy groupBy, EmployeeFilter filter, LocalDate asOf) {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.build(filter, asOf);
        String sql = """
                SELECT %1$s AS group_key,
                       %2$s AS group_label,
                       COUNT(*) AS headcount,
                       %3$s AS median,
                       ROUND(AVG(%4$s), 2) AS mean
                %5$s
                %6$s
                WHERE %7$s
                GROUP BY %1$s, %2$s
                ORDER BY group_label, group_key
                """.formatted(groupBy.keySql(), groupBy.labelSql(), MEDIAN, AMOUNT,
                AnalyticsFilterSql.SLICE_FROM, groupBy.extraJoin(), clause.where());
        return jdbc.query(sql, new MapSqlParameterSource(clause.params()),
                (rs, i) -> new GroupRow(rs.getString("group_key"), rs.getString("group_label"),
                        rs.getLong("headcount"), rs.getBigDecimal("median"), rs.getBigDecimal("mean")));
    }

    /**
     * FR-4.3: {@code buckets} equal-width buckets between the slice's minimum and maximum. The edges
     * are computed once, rounded to 2 places, and membership is decided against those same rounded
     * edges (lower inclusive, upper exclusive), so a value's bucket always agrees with the range
     * shown for it. (Deciding on exact edges but displaying rounded ones let a value equal to a
     * displayed edge appear in the wrong bucket.) Amounts are NUMERIC(15,2), so rounding to 2
     * places loses nothing that a stored value can carry. The maximum is included in the last
     * bucket, which makes that bucket closed. When min = max there is nothing to divide, so the
     * result is one bucket ({@code k = 1}). The same edge is a bucket's upper bound and the next
     * one's lower bound. Empty buckets are returned with a zero count; an empty slice returns no
     * rows. If rounding makes two edges equal, the bucket between them is empty.
     */
    public List<BucketRow> distribution(EmployeeFilter filter, LocalDate asOf, int buckets) {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.build(filter, asOf);
        String sql = """
                WITH slice AS (
                    SELECT %1$s AS amount
                    %2$s
                    WHERE %3$s
                ), bounds AS (
                    SELECT MIN(amount) AS lo, MAX(amount) AS hi, COUNT(*) AS n FROM slice
                ), sized AS (
                    SELECT lo, hi, n, CASE WHEN hi = lo THEN 1 ELSE :buckets END AS k FROM bounds
                ), edges AS (
                    SELECT g.idx, z.k,
                           ROUND(z.lo + (g.idx - 1) * (z.hi - z.lo) / z.k, 2) AS lower_bound,
                           CASE WHEN g.idx = z.k THEN z.hi
                                ELSE ROUND(z.lo + g.idx * (z.hi - z.lo) / z.k, 2) END AS upper_bound
                    FROM sized z
                    CROSS JOIN LATERAL generate_series(1, z.k) AS g(idx)
                    WHERE z.n > 0
                ), counts AS (
                    SELECT e.idx, COUNT(*) AS cnt
                    FROM slice sl
                    JOIN edges e ON sl.amount >= e.lower_bound
                                AND (sl.amount < e.upper_bound OR (e.idx = e.k AND sl.amount <= e.upper_bound))
                    GROUP BY e.idx
                )
                SELECT e.lower_bound, e.upper_bound, COALESCE(c.cnt, 0) AS bucket_count
                FROM edges e
                LEFT JOIN counts c ON c.idx = e.idx
                ORDER BY e.idx
                """.formatted(AMOUNT, AnalyticsFilterSql.SLICE_FROM, clause.where());
        Map<String, Object> params = new HashMap<>(clause.params());
        params.put("buckets", buckets);
        return jdbc.query(sql, new MapSqlParameterSource(params),
                (rs, i) -> new BucketRow(rs.getBigDecimal("lower_bound"), rs.getBigDecimal("upper_bound"),
                        rs.getLong("bucket_count")));
    }
}
