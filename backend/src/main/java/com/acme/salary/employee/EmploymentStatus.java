package com.acme.salary.employee;

/**
 * requirements.md section 6.2. Drives the soft delete in FR-2.1 -- a TERMINATED employee is never
 * hard-deleted, so historical analytics never silently rewrite past payroll totals (section 6.3).
 */
public enum EmploymentStatus {
    ACTIVE,
    ON_LEAVE,
    TERMINATED
}
