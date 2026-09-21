package com.acme.salary.dto;

import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;

/**
 * FR-2.3 / FR-2.4: the search text and combinable filters for the employee list. A standalone type
 * rather than loose controller parameters because FR-4.7 ("analytics respect the active filters")
 * and FR-5.5 (export the filtered view) must accept exactly the same filters -- one definition,
 * reused.
 *
 * <p>Every field is optional; a null or blank field applies no restriction.
 */
public record EmployeeFilter(
        String q,
        Long departmentId,
        String countryCode,
        EmploymentStatus status,
        EmploymentType employmentType,
        String jobLevel) {
}
