package com.acme.salary.model;

/**
 * FR-5.1: "upload a CSV of employees and/or salary changes". The section 6.2 table names an {@code
 * import_type} column without listing its values; these two are taken directly from FR-5.1.
 */
public enum ImportType {
    EMPLOYEES,
    SALARY_CHANGES
}
