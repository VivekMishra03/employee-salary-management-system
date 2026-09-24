package com.acme.salary.controller;

import com.acme.salary.model.AppUserRole;
import com.acme.salary.service.JwtService;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-4.1 - FR-4.3 wiring: HTTP -> security -> controller -> service -> real read-model SQL on a real
 * PostgreSQL, in the full application context. One employee whose salary record started in 2020 and
 * is still open, so the answer does not depend on which day the suite runs.
 */
@SpringBootTest(properties = "PORT=8080")
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Transactional
class AnalyticsEndpointIntegrationTest {

    private static final String BASE = "/api/v1/analytics";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (1, 'ENG', 'Engineering')");
        jdbc.update("INSERT INTO location (id, country_code, country_name, city, currency_code) "
                + "VALUES (1, 'US', 'United States', 'Austin', 'USD')");
        jdbc.update("INSERT INTO job_role (id, title, job_family, job_level) VALUES (1, 'Software Engineer', 'Engineering', 'L4')");
        jdbc.update("INSERT INTO app_user (id, email, password_hash, full_name, role, enabled, created_at) VALUES "
                + "(1, 'hr@acme.example', 'x', 'HR', 'HR_MANAGER', true, TIMESTAMPTZ '2020-01-01 00:00:00+00')");
        jdbc.update("INSERT INTO employee (id, employee_code, first_name, last_name, email, hire_date, "
                + "employment_status, employment_type, fte_ratio, department_id, job_role_id, location_id, "
                + "created_at, updated_at) VALUES (1, 'ACME-000001', 'Ada', 'Lovelace', 'ada@acme.example', "
                + "DATE '2020-01-01', 'ACTIVE', 'FULL_TIME', 1.000, 1, 1, 1, "
                + "TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')");
        jdbc.update("INSERT INTO salary_record (id, employee_id, effective_from, effective_to, base_amount, "
                + "currency_code, pay_frequency, annualised_amount, annualised_amount_base_ccy, change_reason, "
                + "created_by, created_at) VALUES (1, 1, DATE '2020-01-01', NULL, 100000.00, 'USD', 'ANNUAL', "
                + "100000.00, 100000.00, 'NEW_HIRE', 1, TIMESTAMPTZ '2020-01-01 00:00:00+00')");
        token = jwtService.issue(1L, "hr@acme.example", AppUserRole.HR_MANAGER).token();
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("FR-4.1: /summary runs the real SQL through the full stack")
    void summary_endToEnd() throws Exception {
        mockMvc.perform(authed(get(BASE + "/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headcount").value(1))
                .andExpect(jsonPath("$.totalPayroll").value(100000.00))
                .andExpect(jsonPath("$.median").value(100000.00));
    }

    @Test
    @DisplayName("FR-4.2: /by-group runs the real SQL through the full stack")
    void byGroup_endToEnd() throws Exception {
        mockMvc.perform(authed(get(BASE + "/by-group").param("groupBy", "DEPARTMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("Engineering"))
                .andExpect(jsonPath("$[0].headcount").value(1));
    }

    @Test
    @DisplayName("FR-4.3: /distribution runs the real SQL through the full stack")
    void distribution_endToEnd() throws Exception {
        mockMvc.perform(authed(get(BASE + "/distribution")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].count").value(1));
    }

    @Test
    @DisplayName("FR-4.7: a filter that matches nobody yields an empty, successful answer")
    void filterMatchingNobody_isStillA200() throws Exception {
        mockMvc.perform(authed(get(BASE + "/summary").param("q", "nobody")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headcount").value(0));
        mockMvc.perform(authed(get(BASE + "/distribution").param("q", "nobody")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- slice B (FR-4.4 - FR-4.6) ---------------------------------------------------------

    @Test
    @DisplayName("FR-4.4: /pay-bands runs the real SQL: 100000 against a band of 80000/100000/120000 is WITHIN, compa-ratio 1.00")
    void payBands_endToEnd() throws Exception {
        jdbc.update("INSERT INTO pay_band (id, job_role_id, location_id, currency_code, min_amount, mid_amount, "
                + "max_amount, effective_from, effective_to) VALUES (1, 1, 1, 'USD', 80000.00, 100000.00, "
                + "120000.00, DATE '2019-01-01', NULL)");

        mockMvc.perform(authed(get(BASE + "/pay-bands")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.below").value(0))
                .andExpect(jsonPath("$.counts.within").value(1))
                .andExpect(jsonPath("$.counts.above").value(0))
                .andExpect(jsonPath("$.counts.noBand").value(0))
                .andExpect(jsonPath("$.employees.totalElements").value(1))
                .andExpect(jsonPath("$.employees.content[0].fullName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.employees.content[0].compaRatio").value(1.00))
                .andExpect(jsonPath("$.employees.content[0].adherence").value("WITHIN"));
    }

    @Test
    @DisplayName("FR-4.4: with no band on file the employee is counted as NO_BAND, not guessed")
    void payBands_withoutBand_isNoBand() throws Exception {
        mockMvc.perform(authed(get(BASE + "/pay-bands").param("adherence", "NO_BAND")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.noBand").value(1))
                .andExpect(jsonPath("$.employees.content[0].compaRatio").doesNotExist())
                .andExpect(jsonPath("$.employees.content[0].adherence").value("NO_BAND"));
    }

    @Test
    @DisplayName("FR-4.5: /gender-gap runs the real SQL: a lone man is below the minimum group size, so it is suppressed")
    void genderGap_endToEnd_smallGroupIsSuppressed() throws Exception {
        jdbc.update("UPDATE employee SET gender = 'MALE' WHERE id = 1");

        mockMvc.perform(authed(get(BASE + "/gender-gap").param("groupBy", "DEPARTMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("Engineering"))
                .andExpect(jsonPath("$[0].suppressed").value(true))
                .andExpect(jsonPath("$[0].maleCount").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].meanGapPct").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("FR-4.6: /trend runs the real SQL: one 2020 quarter with the hire's 100000 payroll and no qualifying increase")
    void trend_endToEnd() throws Exception {
        mockMvc.perform(authed(get(BASE + "/trend").param("from", "2020-01-01").param("to", "2020-06-30")
                        .param("interval", "QUARTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].period").value("2020-Q1"))
                .andExpect(jsonPath("$[0].totalPayrollUsd").value(100000.00))
                .andExpect(jsonPath("$[0].avgIncreasePct").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].period").value("2020-Q2"))
                .andExpect(jsonPath("$[1].totalPayrollUsd").value(100000.00));
    }

    /** An employee (id also used for the salary record) with an open-ended USD salary; fixed ids keep the test repeatable. */
    private void hire(long id, long departmentId, String gender, String annualUsd) {
        jdbc.update("INSERT INTO employee (id, employee_code, first_name, last_name, email, gender, hire_date, "
                + "employment_status, employment_type, fte_ratio, department_id, job_role_id, location_id, "
                + "created_at, updated_at) VALUES (?, ?, 'P', ?, ?, ?, DATE '2020-01-01', 'ACTIVE', 'FULL_TIME', "
                + "1.000, ?, 1, 1, TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')",
                id, "ACME-" + id, "L" + id, "p" + id + "@acme.example", gender, departmentId);
        jdbc.update("INSERT INTO salary_record (id, employee_id, effective_from, effective_to, base_amount, "
                + "currency_code, pay_frequency, annualised_amount, annualised_amount_base_ccy, change_reason, "
                + "created_by, created_at) VALUES (?, ?, DATE '2020-01-01', NULL, ?, 'USD', 'ANNUAL', ?, ?, "
                + "'NEW_HIRE', 1, TIMESTAMPTZ '2020-01-01 00:00:00+00')",
                id, id, new java.math.BigDecimal(annualUsd), new java.math.BigDecimal(annualUsd),
                new java.math.BigDecimal(annualUsd));
    }

    @Test
    @DisplayName("FR-4.5: at the default minimum of 5, Sales (5 men, 5 women) is shown with gap 10.00 and Legal (5 men, 4 women) is suppressed")
    void genderGap_endToEnd_minimumBoundary() throws Exception {
        jdbc.update("INSERT INTO department (id, code, name) VALUES (2, 'SAL', 'Sales'), (3, 'LEG', 'Legal')");
        for (long i = 0; i < 5; i++) {
            hire(100 + i, 2, "MALE", "100000.00");   // Sales men: mean and median 100000
            hire(110 + i, 2, "FEMALE", "90000.00");  // Sales women: mean and median 90000 -> gap (100-90)/100 = 10.00
            hire(120 + i, 3, "MALE", "100000.00");
        }
        for (long i = 0; i < 4; i++) {
            hire(130 + i, 3, "FEMALE", "90000.00");  // Legal has only 4 women
        }

        mockMvc.perform(authed(get(BASE + "/gender-gap").param("groupBy", "DEPARTMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].label").value("Legal"))
                .andExpect(jsonPath("$[0].suppressed").value(true))
                .andExpect(jsonPath("$[0].maleCount").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].femaleCount").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].meanGapPct").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].label").value("Sales"))
                .andExpect(jsonPath("$[1].suppressed").value(false))
                .andExpect(jsonPath("$[1].maleCount").value(5))
                .andExpect(jsonPath("$[1].femaleCount").value(5))
                .andExpect(jsonPath("$[1].meanGapPct").value(10.00))
                .andExpect(jsonPath("$[1].medianGapPct").value(10.00));
    }
}
