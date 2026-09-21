package com.acme.salary.controller;

import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.Department;
import com.acme.salary.model.Employee;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.model.ExchangeRate;
import com.acme.salary.model.Gender;
import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import com.acme.salary.repository.AppUserRepository;
import com.acme.salary.repository.DepartmentRepository;
import com.acme.salary.repository.EmployeeRepository;
import com.acme.salary.repository.ExchangeRateRepository;
import com.acme.salary.repository.JobRoleRepository;
import com.acme.salary.repository.LocationRepository;
import com.acme.salary.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-3 over HTTP, behind the real security chain, attributing changes to the token's user. */
@SpringBootTest(properties = "PORT=8080")
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Transactional
class SalaryControllerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private JobRoleRepository jobRoleRepository;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    private Long usEmployeeId;
    private Long deEmployeeId;
    private Long actorId;
    private String token;

    @BeforeEach
    void seed() {
        Long dept = departmentRepository.saveAndFlush(new Department("ENG", "Engineering", null, "CC-1")).getId();
        Long role = jobRoleRepository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4")).getId();
        Long austin = locationRepository.saveAndFlush(new Location("US", "United States", "Austin", "USD")).getId();
        Long berlin = locationRepository.saveAndFlush(new Location("DE", "Germany", "Berlin", "EUR")).getId();
        actorId = appUserRepository.saveAndFlush(new AppUser("hr.manager@acme.example",
                "$2a$10$placeholderXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "Priya", AppUserRole.HR_MANAGER, true))
                .getId();
        usEmployeeId = employee("ACME-000001", "ada@acme.example", dept, role, austin);
        deEmployeeId = employee("ACME-000002", "grace@acme.example", dept, role, berlin);
        token = jwtService.issue(actorId, "hr.manager@acme.example", AppUserRole.HR_MANAGER).token();
    }

    private Long employee(String code, String email, Long dept, Long role, Long loc) {
        return employeeRepository.saveAndFlush(new Employee(code, "Test", "Person", email, Gender.FEMALE,
                LocalDate.of(2020, 1, 1), EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME, new BigDecimal("1.000"),
                dept, role, loc, null)).getId();
    }

    private String url(Long employeeId) {
        return "/api/v1/employees/" + employeeId + "/salaries";
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + token);
    }

    private Map<String, Object> salary(String effectiveFrom, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("effectiveFrom", effectiveFrom);
        m.put("baseAmount", new BigDecimal(amount));
        m.put("payFrequency", "ANNUAL");
        m.put("targetBonusPct", new BigDecimal("10.00"));
        m.put("changeReason", "MERIT_INCREASE");
        m.put("notes", "Annual review");
        return m;
    }

    private ResultActions postSalary(Long employeeId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(authed(post(url(employeeId))).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    // ---- security --------------------------------------------------------------------------

    @Test
    @DisplayName("FR-1.2: the salary endpoints reject a request without a token")
    void withoutToken_returns401() throws Exception {
        mockMvc.perform(get(url(usEmployeeId))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(url(usEmployeeId)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- record ----------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.1: POST records a salary and returns 201 with the derived amounts")
    void post_recordsASalary() throws Exception {
        postSalary(usEmployeeId, salary("2020-01-01", "120000.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseAmount").value(120000.00))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.annualisedAmount").value(120000.00))
                .andExpect(jsonPath("$.annualisedAmountBaseCcy").value(120000.00))
                .andExpect(jsonPath("$.effectiveFrom").value("2020-01-01"))
                .andExpect(jsonPath("$.effectiveTo").doesNotExist())
                .andExpect(jsonPath("$.changeReason").value("MERIT_INCREASE"));
    }

    @Test
    @DisplayName("FR-3.7: the change is attributed to the authenticated user, not to anything in the request")
    void post_attributesTheChangeToTheTokenUser() throws Exception {
        postSalary(usEmployeeId, salary("2020-01-01", "120000.00")).andExpect(status().isCreated());
        entityManager.flush(); // the test's transaction has not committed, so push pending inserts to the DB

        Long auditedActor = jdbcTemplate.queryForObject(
                "SELECT actor_user_id FROM audit_log WHERE entity_type = 'salary_record'", Long.class);
        Long createdBy = jdbcTemplate.queryForObject("SELECT created_by FROM salary_record", Long.class);

        assertThat(auditedActor).isEqualTo(actorId);
        assertThat(createdBy).isEqualTo(actorId);
    }

    @Test
    @DisplayName("FR-3.4/3.5: a Berlin employee is paid in EUR and converted at the stored rate")
    void post_convertsANonBaseCurrencyEmployee() throws Exception {
        exchangeRateRepository.saveAndFlush(new ExchangeRate("EUR", "USD", new BigDecimal("1.10000000"),
                LocalDate.of(2020, 1, 1), "test"));

        postSalary(deEmployeeId, salary("2020-01-01", "100000.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currencyCode").value("EUR"))
                .andExpect(jsonPath("$.annualisedAmountBaseCcy").value(110000.00));
    }

    @Test
    @DisplayName("FR-3.5: with no exchange rate the request is a 409 with a stable code")
    void post_withNoExchangeRate_returns409() throws Exception {
        postSalary(deEmployeeId, salary("2020-01-01", "100000.00"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("FR-3.2: a record before the hire date is a 400 naming the field")
    void post_beforeHire_returns400() throws Exception {
        postSalary(usEmployeeId, salary("2019-06-01", "90000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EFFECTIVE_BEFORE_HIRE"))
                .andExpect(jsonPath("$.errors[0].field").value("effectiveFrom"));
    }

    @Test
    @DisplayName("FR-3.2: a first record after the hire date is a 400, because it would leave a gap")
    void post_firstRecordAfterHire_returns400() throws Exception {
        postSalary(usEmployeeId, salary("2020-03-01", "100000.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GAP_AFTER_HIRE"));
    }

    @Test
    @DisplayName("NFR-4: an invalid body names every bad field")
    void post_withInvalidBody_returns400WithFieldErrors() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("baseAmount", 0);
        bad.put("payFrequency", "ANNUAL");
        // effectiveFrom and changeReason deliberately missing

        postSalary(usEmployeeId, bad)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='baseAmount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='effectiveFrom')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='changeReason')]").exists());
    }

    @Test
    @DisplayName("an unknown pay frequency is a 400, not a server error")
    void post_withUnknownPayFrequency_returns400() throws Exception {
        Map<String, Object> body = salary("2020-01-01", "100000.00");
        body.put("payFrequency", "FORTNIGHTLY");

        postSalary(usEmployeeId, body).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    @DisplayName("recording salary for an unknown employee is a 404")
    void post_forUnknownEmployee_returns404() throws Exception {
        postSalary(999999L, salary("2020-01-01", "100000.00")).andExpect(status().isNotFound());
    }

    // ---- history ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.1: the history shows every record newest first, the older one closed by the raise")
    void get_returnsTheHistoryNewestFirst() throws Exception {
        postSalary(usEmployeeId, salary("2020-01-01", "100000.00")).andExpect(status().isCreated());
        postSalary(usEmployeeId, salary("2021-06-01", "110000.00")).andExpect(status().isCreated());

        mockMvc.perform(authed(get(url(usEmployeeId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].effectiveFrom").value("2021-06-01"))
                .andExpect(jsonPath("$[0].effectiveTo").doesNotExist())
                .andExpect(jsonPath("$[1].effectiveFrom").value("2020-01-01"))
                .andExpect(jsonPath("$[1].effectiveTo").value("2021-06-01"));
    }

    @Test
    @DisplayName("FR-2.5: the employee detail's current compensation reflects the record just recorded")
    void employeeDetail_showsTheRecordedCurrentSalary() throws Exception {
        postSalary(usEmployeeId, salary("2020-01-01", "100000.00")).andExpect(status().isCreated());
        postSalary(usEmployeeId, salary("2021-06-01", "110000.00")).andExpect(status().isCreated());

        mockMvc.perform(authed(get("/api/v1/employees/" + usEmployeeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSalary.baseAmount").value(110000.00))
                .andExpect(jsonPath("$.salaryHistory", hasSize(2)));
    }

    @Test
    @DisplayName("FR-3.1: history for an unknown employee is a 404")
    void get_forUnknownEmployee_returns404() throws Exception {
        mockMvc.perform(authed(get(url(999999L)))).andExpect(status().isNotFound());
    }
}
