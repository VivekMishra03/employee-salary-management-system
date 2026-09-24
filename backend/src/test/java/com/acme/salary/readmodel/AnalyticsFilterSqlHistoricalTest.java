package com.acme.salary.readmodel;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.EmploymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-4.6 / FR-4.7 (ADR-0013): the trend looks at past dates, so it needs the user's filters WITHOUT
 * the two population rules of {@link AnalyticsFilterSql#build} -- "record in force on asOf" (the trend
 * picks a record per period) and "exclude terminated by default" (someone terminated in 2024 was on
 * the 2023 payroll). Kept in its own class so the slice A test file stays untouched.
 */
class AnalyticsFilterSqlHistoricalTest {

    private static final EmployeeFilter NO_FILTER = new EmployeeFilter(null, null, null, null, null, null);

    @Test
    @DisplayName("FR-4.6: with no filter there is no restriction at all and no asOf parameter")
    void noFilter_hasNoPredicateAndNoParameter() {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.buildFiltersOnly(NO_FILTER);

        assertThat(clause.where()).isEqualTo("TRUE");
        assertThat(clause.params()).isEmpty();
    }

    @Test
    @DisplayName("FR-4.6: terminated employees are NOT excluded by default for historical periods")
    void noFilter_doesNotExcludeTerminated() {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.buildFiltersOnly(NO_FILTER);

        assertThat(clause.where()).doesNotContain("TERMINATED").doesNotContain("effective_from");
    }

    @Test
    @DisplayName("FR-4.6 / FR-4.7: an explicit status still applies, to the current status, as a bound parameter")
    void explicitStatus_isStillApplied() {
        AnalyticsFilterSql.Clause clause = AnalyticsFilterSql.buildFiltersOnly(
                new EmployeeFilter(null, null, null, EmploymentStatus.ACTIVE, null, null));

        assertThat(clause.where()).isEqualTo("e.employment_status = :status");
        assertThat(clause.params()).containsEntry("status", "ACTIVE");
    }

    @Test
    @DisplayName("FR-4.7: the other filters translate exactly as in the point-in-time builder")
    void otherFilters_matchThePointInTimeBuilder() {
        EmployeeFilter filter = new EmployeeFilter("ada l", 3L, " us ", EmploymentStatus.ON_LEAVE, null, "l4");

        AnalyticsFilterSql.Clause historical = AnalyticsFilterSql.buildFiltersOnly(filter);
        AnalyticsFilterSql.Clause pointInTime = AnalyticsFilterSql.build(filter, LocalDate.of(2026, 6, 15));

        assertThat(pointInTime.where()).endsWith(historical.where());
        assertThat(historical.params()).containsEntry("countryCode", "US").containsEntry("jobLevel", "L4")
                .containsEntry("departmentId", 3L).doesNotContainKey("asOf");
    }
}
