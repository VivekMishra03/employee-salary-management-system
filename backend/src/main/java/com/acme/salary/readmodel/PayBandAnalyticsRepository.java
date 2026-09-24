package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.PayBandAdherence;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-4.4, FR-4.7 (ADR-0013): pay-band adherence, computed in the database. Every employee of the
 * filtered slice is matched to the pay band for their role AND location in force on {@code asOf} and
 * classified once; the counts and the page of rows are two views of that one classification, so
 * they can never disagree. All amounts are LOCAL currency: a band is a local-market figure, and the
 * stored annualised amount is the full-time-equivalent rate, which is what a band describes.
 * SQL text is built from constants only; filters and paging are bound parameters (NFR-4).
 */
@Repository
public class PayBandAnalyticsRepository {

    /**
     * The classified slice. Three decisions live here:
     * <ul>
     *   <li>The band is fetched with LEFT JOIN LATERAL ... ORDER BY effective_from DESC LIMIT 1
     *       rather than a plain join. The schema only makes (role, location, effective_from) unique,
     *       so two bands could both be in force on one date; a plain join would then list the
     *       employee twice and double-count them. The most recently started band IN THE SALARY'S
     *       CURRENCY wins (the currency rule below is part of the lookup, not a check made
     *       afterwards). No such band at all leaves the band columns null, which is what makes the
     *       employee NO_BAND.</li>
     *   <li>"In force" is {@code effective_from <= asOf AND (effective_to IS NULL OR effective_to >
     *       asOf)}: the same half-open rule as salary records, so a band that ended on asOf is gone.</li>
     *   <li>A band only applies to a salary in the SAME currency. An employee who moved country
     *       keeps a salary record in the old currency until a new one is recorded, and comparing (say)
     *       GBP 60,000 with EUR band limits would classify a currency difference as a pay finding.
     *       Decision: with no band in the salary's currency the employee is NO_BAND (a band in another
     *       currency is simply not a candidate); no exchange rate is applied, because
     *       converting would guess a rate for a comparison the data cannot support.</li>
     *   <li>Compa-ratio divides by {@code NULLIF(mid, 0)}. The schema only requires min &lt;= mid
     *       &lt;= max, so a mid of zero is storable; that yields a null ratio instead of an error,
     *       while adherence (which needs only min and max) is still classified. ROUND on NUMERIC
     *       rounds halves away from zero, i.e. HALF_UP for these positive ratios.</li>
     * </ul>
     */
    private static final String CLASSIFIED = """
            WITH classified AS (
                SELECT e.id AS employee_id,
                       e.employee_code,
                       e.first_name || ' ' || e.last_name AS full_name,
                       jr.title AS job_title,
                       jr.job_level,
                       l.country_code,
                       s.currency_code,
                       s.annualised_amount AS amount,
                       b.min_amount,
                       b.mid_amount,
                       b.max_amount,
                       ROUND(s.annualised_amount / NULLIF(b.mid_amount, 0), 2) AS compa_ratio,
                       CASE WHEN b.min_amount IS NULL THEN 'NO_BAND'
                            WHEN s.annualised_amount < b.min_amount THEN 'BELOW'
                            WHEN s.annualised_amount > b.max_amount THEN 'ABOVE'
                            ELSE 'WITHIN' END AS adherence
                %1$s
                LEFT JOIN LATERAL (
                    SELECT pb.min_amount, pb.mid_amount, pb.max_amount
                    FROM pay_band pb
                    WHERE pb.job_role_id = e.job_role_id
                      AND pb.location_id = e.location_id
                      AND pb.currency_code = s.currency_code
                      AND pb.effective_from <= :asOf
                      AND (pb.effective_to IS NULL OR pb.effective_to > :asOf)
                    ORDER BY pb.effective_from DESC
                    LIMIT 1
                ) b ON TRUE
                WHERE %2$s
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PayBandAnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The whole filtered slice, independent of any adherence filter or page. Zeros for an empty slice. */
    public PayBandCountsRow counts(EmployeeFilter filter, LocalDate asOf) {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.build(filter, asOf);
        String sql = CLASSIFIED.formatted(AnalyticsFilterSql.SLICE_FROM, clause.where()) + """
                SELECT COUNT(*) FILTER (WHERE adherence = 'BELOW') AS below,
                       COUNT(*) FILTER (WHERE adherence = 'WITHIN') AS within_band,
                       COUNT(*) FILTER (WHERE adherence = 'ABOVE') AS above,
                       COUNT(*) FILTER (WHERE adherence = 'NO_BAND') AS no_band
                FROM classified
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource(clause.params()),
                (rs, i) -> new PayBandCountsRow(rs.getLong("below"), rs.getLong("within_band"),
                        rs.getLong("above"), rs.getLong("no_band")));
    }

    /**
     * One page of the classified slice, optionally restricted to one adherence class. Sorted by the
     * compa-ratio as displayed (2 places), ascending, then by employee code so paging is stable;
     * rows without a ratio (no band, or a zero mid) sort last. The adherence predicate is added to
     * the SQL text only when a class is requested, and its value is bound.
     */
    public List<PayBandRow> rows(EmployeeFilter filter, LocalDate asOf, PayBandAdherence adherence, int limit,
                                 long offset) {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.build(filter, asOf);
        String sql = CLASSIFIED.formatted(AnalyticsFilterSql.SLICE_FROM, clause.where()) + """
                SELECT employee_id, employee_code, full_name, job_title, job_level, country_code,
                       currency_code, amount, min_amount, mid_amount, max_amount, compa_ratio, adherence
                FROM classified
                """ + (adherence == null ? "" : "WHERE adherence = :adherence\n") + """
                ORDER BY compa_ratio ASC NULLS LAST, employee_code ASC
                LIMIT :limit OFFSET :offset
                """;
        Map<String, Object> params = new HashMap<>(clause.params());
        if (adherence != null) {
            params.put("adherence", adherence.name());
        }
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.query(sql, new MapSqlParameterSource(params),
                (rs, i) -> new PayBandRow(rs.getLong("employee_id"), rs.getString("employee_code"),
                        rs.getString("full_name"), rs.getString("job_title"), rs.getString("job_level"),
                        rs.getString("country_code"), rs.getString("currency_code"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("min_amount"), rs.getBigDecimal("mid_amount"),
                        rs.getBigDecimal("max_amount"), rs.getBigDecimal("compa_ratio"),
                        PayBandAdherence.valueOf(rs.getString("adherence"))));
    }
}
