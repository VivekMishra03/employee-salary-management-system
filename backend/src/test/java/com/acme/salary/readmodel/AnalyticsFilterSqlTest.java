package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-4.7 / NFR-4: the filter-to-SQL translation, tested without a database. What matters here is the
 * text of the fragment (which predicates are present) and that user input only ever travels as a
 * bound parameter.
 */
class AnalyticsFilterSqlTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 15);
    private static final EmployeeFilter NO_FILTER = new EmployeeFilter(null, null, null, null, null, null);

    private static AnalyticsFilterSql.Clause build(EmployeeFilter filter) {
        return AnalyticsFilterSql.build(filter, AS_OF);
    }

    @Test
    @DisplayName("FR-4.1: with no filter the slice is the record in force on asOf, excluding terminated employees")
    void noFilter_appliesAsOfAndExcludesTerminated() {
        AnalyticsFilterSql.Clause clause = build(NO_FILTER);

        assertThat(clause.where())
                .contains("s.effective_from <= :asOf")
                .contains("s.effective_to IS NULL OR s.effective_to > :asOf")
                .contains("e.employment_status <> 'TERMINATED'");
        assertThat(clause.params()).containsExactly(Map.entry("asOf", AS_OF));
    }

    @Test
    @DisplayName("FR-4.7: an explicit status replaces the default exclusion, so TERMINATED can be analysed")
    void status_replacesTheDefaultTerminatedExclusion() {
        AnalyticsFilterSql.Clause clause = build(
                new EmployeeFilter(null, null, null, EmploymentStatus.TERMINATED, null, null));

        assertThat(clause.where()).contains("e.employment_status = :status").doesNotContain("<>");
        assertThat(clause.params()).containsEntry("status", "TERMINATED");
    }

    @Test
    @DisplayName("FR-4.7: departmentId filters on the employee's department")
    void departmentId_isBound() {
        AnalyticsFilterSql.Clause clause = build(new EmployeeFilter(null, 7L, null, null, null, null));

        assertThat(clause.where()).contains("e.department_id = :departmentId");
        assertThat(clause.params()).containsEntry("departmentId", 7L);
    }

    @Test
    @DisplayName("FR-4.7: employmentType filters on the employment type by name")
    void employmentType_isBoundByName() {
        AnalyticsFilterSql.Clause clause = build(
                new EmployeeFilter(null, null, null, null, EmploymentType.PART_TIME, null));

        assertThat(clause.where()).contains("e.employment_type = :employmentType");
        assertThat(clause.params()).containsEntry("employmentType", "PART_TIME");
    }

    @Test
    @DisplayName("FR-4.7: countryCode is trimmed and upper-cased, exactly as the employee list does")
    void countryCode_isNormalised() {
        AnalyticsFilterSql.Clause clause = build(new EmployeeFilter(null, null, " de ", null, null, null));

        assertThat(clause.where()).contains("l.country_code = :countryCode");
        assertThat(clause.params()).containsEntry("countryCode", "DE");
    }

    @Test
    @DisplayName("FR-4.7: jobLevel is trimmed and upper-cased, exactly as the employee list does")
    void jobLevel_isNormalised() {
        AnalyticsFilterSql.Clause clause = build(new EmployeeFilter(null, null, null, null, null, " l4"));

        assertThat(clause.where()).contains("jr.job_level = :jobLevel");
        assertThat(clause.params()).containsEntry("jobLevel", "L4");
    }

    @Test
    @DisplayName("FR-4.7: blank text filters apply no restriction")
    void blankTextFilters_areIgnored() {
        AnalyticsFilterSql.Clause clause = build(new EmployeeFilter("  ", null, " ", null, null, ""));

        assertThat(clause.params()).containsOnlyKeys("asOf");
        assertThat(clause.where()).doesNotContain(":countryCode").doesNotContain(":jobLevel").doesNotContain("LIKE");
    }

    @Test
    @DisplayName("FR-4.7: q splits on whitespace and each token must match a lower-cased substring")
    void q_isSplitIntoLowerCasedContainsTokens() {
        AnalyticsFilterSql.Clause clause = build(new EmployeeFilter("  Byron   ADA ", null, null, null, null, null));

        assertThat(clause.where()).contains("LIKE :q0").contains("LIKE :q1").doesNotContain(":q2");
        assertThat(clause.where())
                .contains("lower(e.first_name || ' ' || e.last_name || ' ' || e.employee_code || ' ' || e.email)");
        assertThat(clause.params()).containsEntry("q0", "%byron%").containsEntry("q1", "%ada%");
    }

    @Test
    @DisplayName("FR-4.7: LIKE wildcards and the escape character in q match literally")
    void q_escapesLikeWildcards() {
        AnalyticsFilterSql.Clause clause = build(new EmployeeFilter("50%_a\\b", null, null, null, null, null));

        assertThat(clause.params()).containsEntry("q0", "%50\\%\\_a\\\\b%");
        assertThat(clause.where()).contains("ESCAPE '\\'");
    }

    @Test
    @DisplayName("NFR-4: hostile text in q never appears in the SQL, only in bound parameters")
    void q_withSqlInjectionAttempt_staysOutOfTheSqlText() {
        AnalyticsFilterSql.Clause clause = build(new EmployeeFilter("x' OR 1=1 --", null, null, null, null, null));

        assertThat(clause.where()).doesNotContain("x'").doesNotContain("1=1").doesNotContain("--");
        assertThat(clause.params()).containsEntry("q0", "%x'%").containsEntry("q1", "%or%")
                .containsEntry("q2", "%1=1%").containsEntry("q3", "%--%");
    }

    @Test
    @DisplayName("NFR-4: hostile text in the normalised filters never appears in the SQL either")
    void otherTextFilters_withSqlInjectionAttempt_staysOutOfTheSqlText() {
        AnalyticsFilterSql.Clause clause = build(
                new EmployeeFilter(null, null, "DE'; DROP TABLE employee;--", null, null, "L4' OR 'a'='a"));

        assertThat(clause.where()).doesNotContain("DROP").doesNotContain("'A'").doesNotContain("DE'");
        assertThat(clause.params()).containsEntry("countryCode", "DE'; DROP TABLE EMPLOYEE;--")
                .containsEntry("jobLevel", "L4' OR 'A'='A");
    }

    @Test
    @DisplayName("FR-4.7: all filters combine with AND and every parameter is bound")
    void allFilters_combine() {
        AnalyticsFilterSql.Clause clause = build(new EmployeeFilter("ada", 3L, "us", EmploymentStatus.ACTIVE,
                EmploymentType.FULL_TIME, "l3"));

        assertThat(clause.params()).containsOnly(
                Map.entry("asOf", AS_OF), Map.entry("q0", "%ada%"), Map.entry("departmentId", 3L),
                Map.entry("countryCode", "US"), Map.entry("status", "ACTIVE"),
                Map.entry("employmentType", "FULL_TIME"), Map.entry("jobLevel", "L3"));
        assertThat(clause.where()).contains("e.department_id = :departmentId", "l.country_code = :countryCode",
                "e.employment_status = :status", "e.employment_type = :employmentType",
                "jr.job_level = :jobLevel", "LIKE :q0");
    }

    @Test
    @DisplayName("FR-4.1: every named parameter in the SQL has a value, and no value is unused")
    void everyPlaceholderHasAValue() {
        AnalyticsFilterSql.Clause clause = build(
                new EmployeeFilter("a b", 1L, "us", null, EmploymentType.CONTRACT, "l1"));

        Matcher m = Pattern.compile(":(\\w+)").matcher(clause.where());
        Set<String> placeholders = new TreeSet<>();
        while (m.find()) {
            placeholders.add(m.group(1));
        }
        assertThat(placeholders).containsExactlyInAnyOrderElementsOf(clause.params().keySet());
    }
}
