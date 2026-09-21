package com.acme.salary.service;

import com.acme.salary.dto.RecordSalaryRequest;
import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.AuditAction;
import com.acme.salary.model.ChangeReason;
import com.acme.salary.model.Department;
import com.acme.salary.model.Employee;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.model.Gender;
import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import com.acme.salary.model.PayFrequency;
import com.acme.salary.repository.AppUserRepository;
import com.acme.salary.repository.DepartmentRepository;
import com.acme.salary.repository.EmployeeRepository;
import com.acme.salary.repository.JobRoleRepository;
import com.acme.salary.repository.LocationRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * FR-3.2: "recording a raise closes the previous record and opens a new one <em>atomically</em>".
 *
 * <p>This deliberately is NOT transactional and not a {@code @DataJpaTest}: a test-managed
 * transaction would wrap the whole scenario and could never show a rollback. Each repository call
 * here commits for real, so a failure partway through {@code SalaryService.record} can be observed
 * as either "everything was undone" or "half of it stuck".
 *
 * <p>The failure is injected at the last step -- the audit entry for the new record -- which is
 * <em>after</em> the previous record has been truncated and flushed and its own audit entry
 * written. If those earlier writes survive, the employee is left with a closed record and nothing
 * after it: a permanent gap in their pay history.
 */
@SpringBootTest(properties = "PORT=8080")
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
class SalaryAtomicityTest {

    @Autowired
    private SalaryService salaryService;
    @MockitoSpyBean
    private AuditLogService auditLogService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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

    @AfterEach
    void cleanUp() {
        // These commits are real, so remove them in dependency order for whatever runs next.
        for (String table : new String[]{"audit_log", "salary_record", "employee", "app_user", "job_role", "location",
                "department"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    @DisplayName("FR-3.2: if recording a raise fails after the old record was closed, nothing is left half-done")
    void aFailureAfterClosingThePreviousRecord_rollsEverythingBack() {
        Long dept = departmentRepository.saveAndFlush(new Department("ENG", "Engineering", null, "CC-1")).getId();
        Long loc = locationRepository.saveAndFlush(new Location("US", "United States", "Austin", "USD")).getId();
        Long role = jobRoleRepository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4")).getId();
        Long actor = appUserRepository.saveAndFlush(new AppUser("hr@acme.example",
                "$2a$10$placeholderXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "Priya", AppUserRole.HR_MANAGER, true)).getId();
        Long employee = employeeRepository.saveAndFlush(new Employee("ACME-000001", "Ada", "Lovelace", "ada@acme.example",
                Gender.FEMALE, LocalDate.of(2020, 1, 1), EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME,
                new BigDecimal("1.000"), dept, role, loc, null)).getId();

        salaryService.record(employee, new RecordSalaryRequest(LocalDate.of(2020, 1, 1), new BigDecimal("100000.00"),
                PayFrequency.ANNUAL, null, ChangeReason.NEW_HIRE, null), actor);
        assertThat(count("salary_record")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);

        // The raise writes, in order: truncate old record, UPDATE audit for it, insert new record,
        // CREATE audit for it. Make that last step blow up.
        // Stub the spy *underneath* the transactional proxy. Calling record(...) on the proxy itself,
        // even to set up a stub, runs its MANDATORY interceptor -- which correctly refuses because
        // this test method has no transaction.
        AuditLogService spy = AopTestUtils.getUltimateTargetObject(auditLogService);
        doThrow(new IllegalStateException("audit store unavailable"))
                .when(spy).record(eq("salary_record"), any(), eq(AuditAction.CREATE), any(), any(), any());

        assertThatThrownBy(() -> salaryService.record(employee, new RecordSalaryRequest(LocalDate.of(2021, 6, 1),
                new BigDecimal("110000.00"), PayFrequency.ANNUAL, null, ChangeReason.MERIT_INCREASE, null), actor))
                .isInstanceOf(IllegalStateException.class);

        // Everything the failed call did is undone: the original record is still open-ended, the new
        // one was never persisted, and no audit entry for the aborted change survives.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT effective_to FROM salary_record WHERE effective_from = DATE '2020-01-01'", java.sql.Date.class))
                .as("the previous record must not have been closed").isNull();
        assertThat(count("salary_record")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);
    }
}
