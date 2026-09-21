package com.acme.salary.dto;

import com.acme.salary.model.ChangeReason;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.model.Gender;
import com.acme.salary.model.PayFrequency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * FR-2.5: the full employee view -- profile, current compensation, full salary history, manager and
 * direct reports. {@code version} is what the client must send back on update (FR-2.6).
 */
public record EmployeeDetail(
        Long id,
        String employeeCode,
        String firstName,
        String lastName,
        String email,
        Gender gender,
        LocalDate hireDate,
        LocalDate terminationDate,
        EmploymentStatus employmentStatus,
        EmploymentType employmentType,
        BigDecimal fteRatio,
        Long departmentId,
        String departmentName,
        Long jobRoleId,
        String jobTitle,
        String jobLevel,
        Long locationId,
        String city,
        String countryCode,
        PersonRef manager,
        List<PersonRef> directReports,
        SalarySummary currentSalary,
        List<SalarySummary> salaryHistory,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    /** A colleague referenced from the detail view. */
    public record PersonRef(Long id, String employeeCode, String fullName) {
    }

    /** One salary record, as shown in the detail view and history. */
    public record SalarySummary(
            Long id,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal baseAmount,
            String currencyCode,
            PayFrequency payFrequency,
            BigDecimal annualisedAmount,
            BigDecimal annualisedAmountBaseCcy,
            BigDecimal targetBonusPct,
            ChangeReason changeReason,
            String notes) {
    }
}
