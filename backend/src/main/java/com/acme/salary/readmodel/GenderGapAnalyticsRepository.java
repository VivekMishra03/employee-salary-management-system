package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * FR-4.5, FR-4.7 (ADR-0013): the gender pay gap per group, computed in the database over the same
 * slice as the other analytics. The read model returns the raw gap for every group with a MALE or
 * FEMALE employee; the minimum-group-size rule belongs to {@code GenderGapService}, so it is one
 * testable rule rather than SQL.
 *
 * <p>Only MALE and FEMALE are compared (requirements.md assumption 3: gender is optional and
 * self-declared, used only for this aggregate). NON_BINARY, PREFER_NOT_TO_SAY and null are excluded
 * in the WHERE clause, so they enter neither the counts nor any figure. Gender is never selected as a
 * column, so it cannot leak into a row.
 */
@Repository
public class GenderGapAnalyticsRepository {

    /** Base-currency full-time-equivalent rate, unscaled: the same basis as the other pay statistics. */
    private static final String AMOUNT = "s.annualised_amount_base_ccy";

    private final NamedParameterJdbcTemplate jdbc;

    public GenderGapAnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gap = (male - female) / male * 100, from the exact NUMERIC means and (rounded) medians, rounded
     * once at the end to 2 places (NUMERIC ROUND is half away from zero: HALF_UP for a positive
     * value, and the mirror image for a negative gap). Positive means women are paid less.
     * {@code NULLIF(male, 0)} turns a zero male mean or median into a null gap rather than a
     * division error; a group with no man or no woman has a null mean on one side, which also
     * propagates to a null gap. Groups are ordered by label, then key.
     */
    public List<GenderGapRow> gaps(GroupBy groupBy, EmployeeFilter filter, LocalDate asOf) {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.build(filter, asOf);
        String sql = """
                WITH agg AS (
                    SELECT %1$s AS group_key,
                           %2$s AS group_label,
                           COUNT(*) FILTER (WHERE e.gender = 'MALE') AS male_count,
                           COUNT(*) FILTER (WHERE e.gender = 'FEMALE') AS female_count,
                           AVG(%3$s) FILTER (WHERE e.gender = 'MALE') AS mean_m,
                           AVG(%3$s) FILTER (WHERE e.gender = 'FEMALE') AS mean_f,
                           ROUND(CAST(percentile_cont(0.50) WITHIN GROUP (ORDER BY %3$s)
                                 FILTER (WHERE e.gender = 'MALE') AS NUMERIC), 2) AS median_m,
                           ROUND(CAST(percentile_cont(0.50) WITHIN GROUP (ORDER BY %3$s)
                                 FILTER (WHERE e.gender = 'FEMALE') AS NUMERIC), 2) AS median_f
                    %4$s
                    %5$s
                    WHERE %6$s AND e.gender IN ('MALE', 'FEMALE')
                    GROUP BY %1$s, %2$s
                )
                SELECT group_key, group_label, male_count, female_count,
                       ROUND((mean_m - mean_f) / NULLIF(mean_m, 0) * 100, 2) AS mean_gap_pct,
                       ROUND((median_m - median_f) / NULLIF(median_m, 0) * 100, 2) AS median_gap_pct
                FROM agg
                ORDER BY group_label, group_key
                """.formatted(groupBy.keySql(), groupBy.labelSql(), AMOUNT, AnalyticsFilterSql.SLICE_FROM,
                groupBy.extraJoin(), clause.where());
        return jdbc.query(sql, new MapSqlParameterSource(clause.params()),
                (rs, i) -> new GenderGapRow(rs.getString("group_key"), rs.getString("group_label"),
                        rs.getLong("male_count"), rs.getLong("female_count"), rs.getBigDecimal("mean_gap_pct"),
                        rs.getBigDecimal("median_gap_pct")));
    }
}
