package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FR-4.7 (ADR-0013): translates the same {@link EmployeeFilter} the employee list uses into a
 * parameterised SQL fragment for the analytics queries. The employee list builds a JPA
 * Specification; analytics is hand-written SQL, so the translation is repeated here, with the same
 * normalisation (trim and upper-case country and level; a query split into tokens that must each
 * match a case-insensitive substring of name, code and email).
 *
 * <p>The fragment assumes the aliases fixed by {@link #SLICE_FROM}: {@code e} employee, {@code s}
 * its salary record in force on the as-of date, {@code l} location, {@code jr} job role. User text
 * is only ever bound as a named parameter; the SQL text is built from constants (NFR-4).
 */
public final class AnalyticsFilterSql {

    /**
     * The one place the "current salary" is defined. There is deliberately no {@code
     * v_current_salary} view (ADR-0013): {@code effective_to IS NULL} would pick a future-dated
     * raise as "current". The record in force on a date is the one whose half-open interval
     * {@code [effective_from, effective_to)} contains it, which also means a zero-length superseded
     * record (from = to, ADR-0011) can never match. The join is an inner join, so an employee with
     * no record in force is simply not part of the slice.
     */
    public static final String SLICE_FROM = """
            FROM employee e
            JOIN salary_record s ON s.employee_id = e.id
            JOIN location l ON l.id = e.location_id
            JOIN job_role jr ON jr.id = e.job_role_id
            """;

    private static final String IN_FORCE_ON_AS_OF =
            "s.effective_from <= :asOf AND (s.effective_to IS NULL OR s.effective_to > :asOf)";

    private static final String SEARCHABLE_TEXT =
            "lower(e.first_name || ' ' || e.last_name || ' ' || e.employee_code || ' ' || e.email)";

    private static final char LIKE_ESCAPE = '\\';

    private AnalyticsFilterSql() {
    }

    /** A WHERE-clause body (without the keyword) and the named parameters it references. */
    public record Clause(String where, Map<String, Object> params) {
    }

    /**
     * The user's filters alone, for queries that choose their own dates (the trend, FR-4.6): no
     * "record in force on asOf" predicate, and no default exclusion of terminated employees --
     * someone terminated in 2024 was still part of the 2023 payroll. An explicit status filter is
     * still applied, and it applies to the CURRENT status (attributes are not historised).
     * Returns {@code TRUE} when there is nothing to restrict, so it can always follow a WHERE.
     */
    public static Clause buildFiltersOnly(EmployeeFilter filter) {
        List<String> predicates = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        addFilterPredicates(filter, false, predicates, params);
        return new Clause(predicates.isEmpty() ? "TRUE" : String.join(" AND ", predicates), params);
    }

    public static Clause build(EmployeeFilter filter, LocalDate asOf) {
        List<String> predicates = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();

        predicates.add(IN_FORCE_ON_AS_OF);
        params.put("asOf", asOf);
        addFilterPredicates(filter, true, predicates, params);
        return new Clause(String.join(" AND ", predicates), params);
    }

    private static void addFilterPredicates(EmployeeFilter filter, boolean excludeTerminatedByDefault,
                                            List<String> predicates, Map<String, Object> params) {
        if (filter.status() != null) {
            predicates.add("e.employment_status = :status");
            params.put("status", filter.status().name());
        } else if (excludeTerminatedByDefault) {
            // ADR-0011/0013: a terminated employee's last record stays open-ended, so it must not
            // be mistaken for someone currently paid. Only an explicit status filter includes them.
            predicates.add("e.employment_status <> 'TERMINATED'");
        }
        if (filter.departmentId() != null) {
            predicates.add("e.department_id = :departmentId");
            params.put("departmentId", filter.departmentId());
        }
        if (filter.employmentType() != null) {
            predicates.add("e.employment_type = :employmentType");
            params.put("employmentType", filter.employmentType().name());
        }
        if (hasText(filter.countryCode())) {
            predicates.add("l.country_code = :countryCode");
            params.put("countryCode", filter.countryCode().trim().toUpperCase(Locale.ROOT));
        }
        if (hasText(filter.jobLevel())) {
            predicates.add("jr.job_level = :jobLevel");
            params.put("jobLevel", filter.jobLevel().trim().toUpperCase(Locale.ROOT));
        }
        if (hasText(filter.q())) {
            String[] tokens = filter.q().trim().toLowerCase(Locale.ROOT).split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                predicates.add(SEARCHABLE_TEXT + " LIKE :q" + i + " ESCAPE '" + LIKE_ESCAPE + "'");
                params.put("q" + i, "%" + escapeLike(tokens[i]) + "%");
            }
        }
    }

    /** Makes {@code %}, {@code _} and the escape character itself match literally rather than as wildcards. */
    private static String escapeLike(String token) {
        return token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
