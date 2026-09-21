package com.acme.salary.dto;

import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;

import java.time.LocalDate;

/**
 * FR-2.2: one row of the directory. Carries the resolved department, role and location names so
 * the UI needs no follow-up requests. Deliberately has no {@code gender}: requirements.md
 * assumption 3 says it is never shown on the directory list.
 */
public record EmployeeListItem(
        Long id,
        String employeeCode,
        String firstName,
        String lastName,
        String email,
        EmploymentStatus employmentStatus,
        EmploymentType employmentType,
        LocalDate hireDate,
        Long departmentId,
        String departmentName,
        Long jobRoleId,
        String jobTitle,
        String jobLevel,
        Long locationId,
        String city,
        String countryCode) {
}
