package com.acme.salary.dto;

import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.model.Gender;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * FR-2.1 / FR-2.6. A full replacement of the editable fields.
 *
 * <p>{@code version} is required: it is the version the client read, and an update carrying a stale
 * one is refused with a 409 rather than silently overwriting someone else's change. Making it
 * mandatory is what makes a blind overwrite impossible.
 *
 * <p>Deliberately absent: {@code employeeCode} and {@code hireDate} (immutable once created -- the
 * code is the key HR uses in CSVs, and the hire date bounds every salary interval) and
 * {@code TERMINATED} as a status (reached only by soft delete, which also sets the termination date).
 */
public record UpdateEmployeeRequest(
        @NotNull Long version,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        Gender gender,
        @NotNull EmploymentType employmentType,
        @NotNull @DecimalMin("0.001") @DecimalMax("1.000") @Digits(integer = 1, fraction = 3) BigDecimal fteRatio,
        @NotNull Long departmentId,
        @NotNull Long jobRoleId,
        @NotNull Long locationId,
        Long managerId,
        @NotNull EmploymentStatus employmentStatus) {
}
