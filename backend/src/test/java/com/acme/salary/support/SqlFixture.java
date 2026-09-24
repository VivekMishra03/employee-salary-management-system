package com.acme.salary.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plain-SQL row builders for the analytics read-model tests. Those tests need exact control over
 * dates, currencies and genders (and rows the service layer would refuse to create, such as
 * overlapping pay bands), so they insert with SQL rather than through JPA or the services.
 * All timestamps are fixed literals: nothing here reads a clock (NFR-3).
 */
public final class SqlFixture {

    private final JdbcTemplate jdbc;

    public SqlFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One department, two locations (US/USD, DE/EUR), the HR user every salary record refers to. */
    public SqlFixture baseData() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (1, 'ENG', 'Engineering'), (2, 'SAL', 'Sales')");
        jdbc.update("INSERT INTO location (id, country_code, country_name, city, currency_code) VALUES "
                + "(1, 'US', 'United States', 'Austin', 'USD'), (2, 'DE', 'Germany', 'Berlin', 'EUR')");
        jdbc.update("INSERT INTO app_user (id, email, password_hash, full_name, role, enabled, created_at) VALUES "
                + "(1, 'hr@acme.example', 'x', 'HR', 'HR_MANAGER', true, TIMESTAMPTZ '2020-01-01 00:00:00+00')");
        return this;
    }

    public SqlFixture jobRole(long id, String title, String level) {
        jdbc.update("INSERT INTO job_role (id, title, job_family, job_level) VALUES (?, ?, 'Family', ?)",
                id, title, level);
        return this;
    }

    /**
     * @param gender      FEMALE, MALE, NON_BINARY, PREFER_NOT_TO_SAY or null
     * @param termination termination date text, or null (status must then not be TERMINATED)
     */
    public SqlFixture employee(long id, String first, String last, String gender, String status, String fte,
                               long dept, long role, long location, String hire, String termination) {
        jdbc.update("INSERT INTO employee (id, employee_code, first_name, last_name, email, gender, hire_date, "
                        + "termination_date, employment_status, employment_type, fte_ratio, department_id, "
                        + "job_role_id, location_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS DATE), CAST(? AS DATE), ?, 'FULL_TIME', CAST(? AS NUMERIC), "
                        + "?, ?, ?, TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')",
                id, "ACME-%06d".formatted(id), first, last,
                (first + "." + last + id).toLowerCase() + "@acme.example", gender, hire, termination,
                status, fte, dept, role, location);
        return this;
    }

    /** An ACTIVE full-time employee hired 2020-01-01: the common case. */
    public SqlFixture employee(long id, String first, String last, String gender, long dept, long role, long location) {
        return employee(id, first, last, gender, "ACTIVE", "1.000", dept, role, location, "2020-01-01", null);
    }

    /**
     * @param to     end date text (exclusive) or null for open-ended
     * @param local  annualised amount in the record's own currency
     * @param base   annualised amount in the base currency
     * @param reason one of the change_reason values
     */
    public SqlFixture salary(long id, long employeeId, String from, String to, String currency, String local,
                             String base, String reason) {
        jdbc.update("INSERT INTO salary_record (id, employee_id, effective_from, effective_to, base_amount, "
                        + "currency_code, pay_frequency, annualised_amount, annualised_amount_base_ccy, "
                        + "change_reason, created_by, created_at) "
                        + "VALUES (?, ?, CAST(? AS DATE), CAST(? AS DATE), CAST(? AS NUMERIC), ?, 'ANNUAL', "
                        + "CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, 1, TIMESTAMPTZ '2020-01-01 00:00:00+00')",
                id, employeeId, from, to, local, currency, local, base, reason);
        return this;
    }

    /** An open-ended USD record starting 2020-01-01 whose local and base amounts are equal. */
    public SqlFixture salary(long id, long employeeId, String usdAmount) {
        return salary(id, employeeId, "2020-01-01", null, "USD", usdAmount, usdAmount, "NEW_HIRE");
    }

    public SqlFixture payBand(long id, long role, long location, String currency, String min, String mid,
                              String max, String from, String to) {
        jdbc.update("INSERT INTO pay_band (id, job_role_id, location_id, currency_code, min_amount, mid_amount, "
                        + "max_amount, effective_from, effective_to) VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), "
                        + "CAST(? AS NUMERIC), CAST(? AS NUMERIC), CAST(? AS DATE), CAST(? AS DATE))",
                id, role, location, currency, min, mid, max, from, to);
        return this;
    }
}
