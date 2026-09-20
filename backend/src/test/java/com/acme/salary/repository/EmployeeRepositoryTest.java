package com.acme.salary.repository;

import com.acme.salary.model.Employee;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.model.Gender;
import com.acme.salary.model.Department;
import com.acme.salary.repository.DepartmentRepository;
import com.acme.salary.model.JobRole;
import com.acme.salary.repository.JobRoleRepository;
import com.acme.salary.model.Location;
import com.acme.salary.repository.LocationRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.boot.test.context.TestConfiguration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.3: employee, the system of record (requirements.md section 6.2). The largest table in the
 * schema, so it carries the broadest test set: three outgoing FKs, a self-referencing manager FK,
 * three CHECK invariants, and optimistic locking (FR-2.6).
 *
 * <p>Imports the real Clock/auditing configuration and overrides it with a fixed clock, so
 * created_at/updated_at assertions are deterministic (NFR-3) rather than comparing against
 * whatever Instant.now() happens to be when the test runs.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import({com.acme.salary.config.ClockConfig.class, com.acme.salary.config.JpaAuditingConfig.class,
        EmployeeRepositoryTest.FixedClockOverride.class})
class EmployeeRepositoryTest {

    static final Instant FIXED_INSTANT = Instant.parse("2026-01-15T10:00:00Z");

    /**
     * A {@link Clock} whose instant can be moved during a test, needed to prove that {@code
     * updatedAt} genuinely advances on update rather than being written once at insert and frozen
     * forever -- a plain {@code Clock.fixed(...)} cannot distinguish those two behaviours, because
     * both produce the same timestamp on every read.
     */
    static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advanceTo(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class FixedClockOverride {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private JobRoleRepository jobRoleRepository;
    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long departmentId;
    private Long locationId;
    private Long jobRoleId;

    @BeforeEach
    void seedReferenceData() {
        // The MutableClock bean lives in a Spring context that Spring's TestContext framework
        // caches and shares across every @Test method in this class (there is no @DirtiesContext).
        // Without this reset, a test that advances the clock would leak that advanced time into
        // whichever test happens to run next -- exactly the "tests share mutable state" failure
        // NFR-3 forbids. Resetting here means every test starts from the same known instant
        // regardless of execution order.
        clock.advanceTo(FIXED_INSTANT);

        departmentId = departmentRepository.saveAndFlush(
                new Department("ENG", "Engineering", null, "CC-100")).getId();
        locationId = locationRepository.saveAndFlush(
                new Location("US", "United States", "Austin", "USD")).getId();
        jobRoleId = jobRoleRepository.saveAndFlush(
                new JobRole("Software Engineer", "Engineering", "L4")).getId();
    }

    private Employee newActiveEmployee(String code, String email) {
        return new Employee(code, "Ada", "Lovelace", email, Gender.FEMALE,
                LocalDate.of(2020, 1, 15), EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME,
                new BigDecimal("1.000"), departmentId, jobRoleId, locationId, null);
    }

    @Test
    @DisplayName("an employee round-trips with all reference-table FKs and a fixed-clock audit stamp")
    void save_thenFindById_returnsTheSameEmployee() {
        Employee saved = employeeRepository.saveAndFlush(newActiveEmployee("ACME-000001", "ada@acme.example"));

        Optional<Employee> found = employeeRepository.findById(saved.getId());

        assertThat(found).isPresent();
        Employee e = found.get();
        assertThat(e.getEmployeeCode()).isEqualTo("ACME-000001");
        assertThat(e.getFirstName()).isEqualTo("Ada");
        assertThat(e.getLastName()).isEqualTo("Lovelace");
        assertThat(e.getEmail()).isEqualTo("ada@acme.example");
        assertThat(e.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(e.getHireDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(e.getTerminationDate()).isNull();
        assertThat(e.getEmploymentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(e.getEmploymentType()).isEqualTo(EmploymentType.FULL_TIME);
        assertThat(e.getFteRatio()).isEqualByComparingTo("1.000");
        assertThat(e.getDepartmentId()).isEqualTo(departmentId);
        assertThat(e.getJobRoleId()).isEqualTo(jobRoleId);
        assertThat(e.getLocationId()).isEqualTo(locationId);
        assertThat(e.getManagerId()).isNull();
        // NFR-3: the audit stamp comes from the injected Clock, never wall-clock time.
        assertThat(e.getCreatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(e.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(e.getVersion()).isZero();
    }

    @Test
    @DisplayName("audit: updated_at advances on a real update; created_at never does")
    void update_advancesUpdatedAtButLeavesCreatedAtUnchanged() {
        // A single Clock.fixed(...) cannot distinguish "updatedAt is refreshed on every update"
        // from "updatedAt is written once at insert and frozen forever" -- both produce the same
        // timestamp on every read. This test moves the clock between the insert and the update,
        // which only the correct behaviour can pass.
        Employee saved = employeeRepository.saveAndFlush(newActiveEmployee("ACME-000001", "ada@acme.example"));
        entityManager.clear();

        Instant oneHourLater = FIXED_INSTANT.plusSeconds(3600);
        clock.advanceTo(oneHourLater);

        Employee loaded = employeeRepository.findById(saved.getId()).orElseThrow();
        loaded.setLastName("Byron");
        Employee updated = employeeRepository.saveAndFlush(loaded);

        assertThat(updated.getCreatedAt())
                .as("created_at must not move on update")
                .isEqualTo(FIXED_INSTANT);
        assertThat(updated.getUpdatedAt())
                .as("updated_at must advance to the clock's new instant")
                .isEqualTo(oneHourLater);
    }

    @Test
    @DisplayName("requirements.md 6.2: employee_code is unique")
    void save_withDuplicateEmployeeCode_violatesUniqueConstraint() {
        employeeRepository.saveAndFlush(newActiveEmployee("ACME-000001", "ada@acme.example"));

        assertThatThrownBy(() ->
                employeeRepository.saveAndFlush(newActiveEmployee("ACME-000001", "different@acme.example")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_employee_code");
    }

    @Test
    @DisplayName("requirements.md 6.2: email is unique")
    void save_withDuplicateEmail_violatesUniqueConstraint() {
        employeeRepository.saveAndFlush(newActiveEmployee("ACME-000001", "ada@acme.example"));

        assertThatThrownBy(() ->
                employeeRepository.saveAndFlush(newActiveEmployee("ACME-000002", "ada@acme.example")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_employee_email");
    }

    @Test
    @DisplayName("requirements.md 6.2: manager_id is a self-referencing FK, null for the CEO")
    void save_withManager_persistsTheReportingLink() {
        Employee ceo = employeeRepository.saveAndFlush(newActiveEmployee("ACME-000001", "ceo@acme.example"));

        Employee report = newActiveEmployee("ACME-000002", "report@acme.example");
        report.setManagerId(ceo.getId());
        Employee savedReport = employeeRepository.saveAndFlush(report);

        Optional<Employee> found = employeeRepository.findById(savedReport.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getManagerId()).isEqualTo(ceo.getId());
    }

    @Test
    @DisplayName("invariant: termination_date must not be before hire_date")
    void save_withTerminationBeforeHire_violatesCheckConstraint() {
        Employee employee = newActiveEmployee("ACME-000001", "ada@acme.example");
        employee.setEmploymentStatus(EmploymentStatus.TERMINATED);
        employee.setTerminationDate(employee.getHireDate().minusDays(1));

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_employee_termination_after_hire");
    }

    @Test
    @DisplayName("invariant: an employee cannot be their own manager")
    void save_withManagerEqualToSelf_violatesCheckConstraint() {
        Employee employee = employeeRepository.saveAndFlush(newActiveEmployee("ACME-000001", "ada@acme.example"));
        employee.setManagerId(employee.getId());

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_employee_manager_not_self");
    }

    // Employee.java deliberately maps department/job role/location/manager as plain Long columns,
    // not @ManyToOne (see the class javadoc) -- so the database FK is the *only* thing standing
    // between the system of record and a dangling reference. Nothing in Java stops
    // `new Employee(..., 99999L, jobRoleId, locationId, null)`. A code-correctness review proved
    // this by deleting fk_employee_department and fk_employee_manager from V5 and observing the
    // full test class still pass -- these four tests are what closes that gap.

    @Test
    @DisplayName("requirements.md 6.2: department_id must reference an existing department")
    void save_withNonExistentDepartment_violatesForeignKey() {
        Employee employee = newActiveEmployee("ACME-000001", "ada@acme.example");
        employee.setDepartmentId(-1L);

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_employee_department");
    }

    @Test
    @DisplayName("requirements.md 6.2: job_role_id must reference an existing job role")
    void save_withNonExistentJobRole_violatesForeignKey() {
        Employee employee = newActiveEmployee("ACME-000001", "ada@acme.example");
        employee.setJobRoleId(-1L);

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_employee_job_role");
    }

    @Test
    @DisplayName("requirements.md 6.2: location_id must reference an existing location")
    void save_withNonExistentLocation_violatesForeignKey() {
        Employee employee = newActiveEmployee("ACME-000001", "ada@acme.example");
        employee.setLocationId(-1L);

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_employee_location");
    }

    @Test
    @DisplayName("requirements.md 6.2: manager_id must reference an existing employee")
    void save_withNonExistentManager_violatesForeignKey() {
        Employee employee = newActiveEmployee("ACME-000001", "ada@acme.example");
        employee.setManagerId(-1L);

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_employee_manager");
    }

    // Java's enum type system makes it impossible to construct an Employee with an invalid gender/
    // status/type through the entity's own API, so these three tests deliberately bypass JPA with a
    // raw INSERT -- the same route a manual correction or a future migration could take, which is
    // exactly the gap chk_employee_gender_valid/status_valid/type_valid exist to close.

    @Test
    @DisplayName("invariant: gender, if set, must be one of the four documented values")
    void insert_withInvalidGender_violatesCheckConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO employee (id, employee_code, first_name, last_name, email, gender,
                    hire_date, employment_status, employment_type, fte_ratio, department_id,
                    job_role_id, location_id, created_at, updated_at, version)
                VALUES (900000001, 'ACME-900001', 'Ada', 'Lovelace', 'probe1@acme.example', 'BANANA',
                    DATE '2020-01-15', 'ACTIVE', 'FULL_TIME', 1.000, ?, ?, ?, now(), now(), 0)
                """, departmentId, jobRoleId, locationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_employee_gender_valid");
    }

    @Test
    @DisplayName("invariant: employment_status must be one of the three documented values")
    void insert_withInvalidEmploymentStatus_violatesCheckConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO employee (id, employee_code, first_name, last_name, email,
                    hire_date, employment_status, employment_type, fte_ratio, department_id,
                    job_role_id, location_id, created_at, updated_at, version)
                VALUES (900000002, 'ACME-900002', 'Ada', 'Lovelace', 'probe2@acme.example',
                    DATE '2020-01-15', 'RETIRED', 'FULL_TIME', 1.000, ?, ?, ?, now(), now(), 0)
                """, departmentId, jobRoleId, locationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_employee_status_valid");
    }

    @Test
    @DisplayName("invariant: employment_type must be one of the three documented values")
    void insert_withInvalidEmploymentType_violatesCheckConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO employee (id, employee_code, first_name, last_name, email,
                    hire_date, employment_status, employment_type, fte_ratio, department_id,
                    job_role_id, location_id, created_at, updated_at, version)
                VALUES (900000003, 'ACME-900003', 'Ada', 'Lovelace', 'probe3@acme.example',
                    DATE '2020-01-15', 'ACTIVE', 'INTERN', 1.000, ?, ?, ?, now(), now(), 0)
                """, departmentId, jobRoleId, locationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_employee_type_valid");
    }

    @Test
    @DisplayName("invariant: employment_status is TERMINATED if and only if termination_date is set")
    void save_withTerminatedStatusButNoTerminationDate_violatesCheckConstraint() {
        Employee employee = newActiveEmployee("ACME-000001", "ada@acme.example");
        employee.setEmploymentStatus(EmploymentStatus.TERMINATED);
        // terminationDate deliberately left null -- this is the violation under test.

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_employee_status_termination_consistency");
    }

    @Test
    @DisplayName("invariant: an ACTIVE employee must not carry a termination_date")
    void save_withActiveStatusButTerminationDateSet_violatesCheckConstraint() {
        Employee employee = newActiveEmployee("ACME-000001", "ada@acme.example");
        employee.setTerminationDate(employee.getHireDate().plusYears(1));
        // employmentStatus deliberately left ACTIVE -- this is the other half of the same invariant.

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(employee))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_employee_status_termination_consistency");
    }

    @Test
    @DisplayName("FR-2.6: a stale write is rejected by optimistic locking")
    void save_withStaleVersion_isRejectedByOptimisticLocking() {
        Long id = employeeRepository.saveAndFlush(newActiveEmployee("ACME-000001", "ada@acme.example")).getId();
        entityManager.clear();

        Employee loaded = employeeRepository.findById(id).orElseThrow();
        assertThat(loaded.getVersion()).isZero();

        // Simulates a second HR user's concurrent write landing first, via a raw UPDATE that
        // never touches this test's persistence context. This is deliberate: an earlier version
        // of this test simulated the second writer as a detached JPA entity with the same id, and
        // Hibernate's merge() silently collapsed it onto the already-managed `loaded` instance in
        // the identity map -- syncing loaded's in-memory version and erasing the conflict this
        // test exists to prove. A raw SQL update cannot be collapsed that way.
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE employee SET last_name = 'Byron', version = version + 1 WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate();

        loaded.setLastName("Something Else Entirely");

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(loaded))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
