package com.acme.salary.dto;

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
import java.time.LocalDate;

/**
 * FR-2.1. A new employee is always created ACTIVE: TERMINATED needs a termination date and is
 * reached only through soft delete. {@code fteRatio} is optional and defaults to 1.000
 * (requirements.md section 6.2).
 */
public record CreateEmployeeRequest(
        @NotBlank @Size(max = 20) String employeeCode,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        Gender gender,
        @NotNull LocalDate hireDate,
        @NotNull EmploymentType employmentType,
        @DecimalMin("0.001") @DecimalMax("1.000") @Digits(integer = 1, fraction = 3) BigDecimal fteRatio,
        @NotNull Long departmentId,
        @NotNull Long jobRoleId,
        @NotNull Long locationId,
        Long managerId) {
}
