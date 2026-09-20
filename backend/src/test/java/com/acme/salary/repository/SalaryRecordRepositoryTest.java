package com.acme.salary.repository;

import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.ChangeReason;
import com.acme.salary.model.Department;
import com.acme.salary.model.Employee;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.model.Gender;
import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import com.acme.salary.model.PayFrequency;
import com.acme.salary.model.SalaryRecord;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * M1.4: salary_record, the temporal heart of the system (requirements.md section 6.2/6.3).
 *
 * <p>The critical invariant (FR-3.2) is enforced at the database with a gist EXCLUDE constraint
 * mixing employee_id with daterange(effective_from, effective_to) -- already proven possible in
 * SchemaMigrationTest on a throwaway probe table. This class proves it on the real table.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import({com.acme.salary.config.ClockConfig.class, com.acme.salary.config.JpaAuditingConfig.class})
class SalaryRecordRepositoryTest {

    @Autowired
    private SalaryRecordRepository salaryRecordRepository;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private JobRoleRepository jobRoleRepository;
    @Autowired
    private AppUserRepository appUserRepository;

    private Long employeeId;
    private Long otherEmployeeId;
    private Long createdByUserId;

    @BeforeEach
    void seedReferenceData() {
        Long departmentId = departmentRepository.saveAndFlush(
                new Department("ENG", "Engineering", null, "CC-100")).getId();
        Long locationId = locationRepository.saveAndFlush(
                new Location("US", "United States", "Austin", "USD")).getId();
        Long jobRoleId = jobRoleRepository.saveAndFlush(
                new JobRole("Software Engineer", "Engineering", "L4")).getId();
        createdByUserId = appUserRepository.saveAndFlush(
                new AppUser("hr.manager@acme.example", "$2a$10$placeholderXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
                        "Priya Sharma", AppUserRole.HR_MANAGER, true)).getId();

        employeeId = employeeRepository.saveAndFlush(new Employee("ACME-000001", "Ada", "Lovelace",
                "ada@acme.example", Gender.FEMALE, LocalDate.of(2020, 1, 15), EmploymentStatus.ACTIVE,
                EmploymentType.FULL_TIME, new BigDecimal("1.000"), departmentId, jobRoleId, locationId, null))
                .getId();
        otherEmployeeId = employeeRepository.saveAndFlush(new Employee("ACME-000002", "Grace", "Hopper",
                "grace@acme.example", Gender.FEMALE, LocalDate.of(2019, 3, 1), EmploymentStatus.ACTIVE,
                EmploymentType.FULL_TIME, new BigDecimal("1.000"), departmentId, jobRoleId, locationId, null))
                .getId();
    }

    private SalaryRecord newHireRecord(Long employeeId, LocalDate effectiveFrom) {
        return new SalaryRecord(employeeId, effectiveFrom, null, new BigDecimal("120000.00"), "USD",
                PayFrequency.ANNUAL, new BigDecimal("120000.00"), new BigDecimal("120000.00"),
                new BigDecimal("10.00"), ChangeReason.NEW_HIRE, "Initial hire", createdByUserId);
    }

    @Test
    @DisplayName("a salary record round-trips with every documented column")
    void save_thenFindById_returnsTheSameSalaryRecord() {
        SalaryRecord saved = salaryRecordRepository.saveAndFlush(newHireRecord(employeeId, LocalDate.of(2020, 1, 15)));

        Optional<SalaryRecord> found = salaryRecordRepository.findById(saved.getId());

        assertThat(found).isPresent();
        SalaryRecord r = found.get();
        assertThat(r.getEmployeeId()).isEqualTo(employeeId);
        assertThat(r.getEffectiveFrom()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(r.getEffectiveTo()).isNull();
        assertThat(r.getBaseAmount()).isEqualByComparingTo("120000.00");
        assertThat(r.getCurrencyCode()).isEqualTo("USD");
        assertThat(r.getPayFrequency()).isEqualTo(PayFrequency.ANNUAL);
        assertThat(r.getAnnualisedAmount()).isEqualByComparingTo("120000.00");
        assertThat(r.getAnnualisedAmountBaseCcy()).isEqualByComparingTo("120000.00");
        assertThat(r.getTargetBonusPct()).isEqualByComparingTo("10.00");
        assertThat(r.getChangeReason()).isEqualTo(ChangeReason.NEW_HIRE);
        assertThat(r.getNotes()).isEqualTo("Initial hire");
        assertThat(r.getCreatedBy()).isEqualTo(createdByUserId);
        assertThat(r.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("FR-3.2: adjacent intervals for the same employee are accepted")
    void save_withAdjacentIntervalsForSameEmployee_isAllowed() {
        SalaryRecord first = newHireRecord(employeeId, LocalDate.of(2020, 1, 15));
        first.setEffectiveTo(LocalDate.of(2021, 6, 1));
        salaryRecordRepository.saveAndFlush(first);

        // [Jan 2020, Jun 2021) then [Jun 2021, ...) -- daterange is half-open, so this is exactly
        // how a raise closes the previous record without overlapping it.
        assertThatCode(() -> salaryRecordRepository.saveAndFlush(newHireRecord(employeeId, LocalDate.of(2021, 6, 1))))
                .as("a raise effective the day the prior record ends must not collide with it")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FR-3.2: overlapping intervals for the same employee are rejected")
    void save_withOverlappingIntervalsForSameEmployee_violatesExclusionConstraint() {
        SalaryRecord first = newHireRecord(employeeId, LocalDate.of(2020, 1, 15));
        first.setEffectiveTo(LocalDate.of(2022, 1, 15));
        salaryRecordRepository.saveAndFlush(first);

        // Starts a full year before the first record ends -- a genuine overlap, not an adjacency.
        assertThatThrownBy(() ->
                salaryRecordRepository.saveAndFlush(newHireRecord(employeeId, LocalDate.of(2021, 1, 15))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("excl_salary_record_no_overlap");
    }

    @Test
    @DisplayName("FR-3.2: the exclusion constraint is scoped per employee, not global")
    void save_withOverlappingIntervalsForDifferentEmployees_isAllowed() {
        salaryRecordRepository.saveAndFlush(newHireRecord(employeeId, LocalDate.of(2020, 1, 15)));

        assertThatCode(() -> salaryRecordRepository.saveAndFlush(newHireRecord(otherEmployeeId, LocalDate.of(2020, 1, 15))))
                .as("two different employees may hold overlapping salary periods")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("requirements.md 6.2: base_amount must be positive")
    void save_withNonPositiveBaseAmount_violatesCheckConstraint() {
        SalaryRecord record = newHireRecord(employeeId, LocalDate.of(2020, 1, 15));
        record.setBaseAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> salaryRecordRepository.saveAndFlush(record))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_salary_record_base_amount_positive");
    }

    @Test
    @DisplayName("requirements.md 6.2: employee_id must reference an existing employee")
    void save_withNonExistentEmployee_violatesForeignKey() {
        SalaryRecord record = newHireRecord(-1L, LocalDate.of(2020, 1, 15));

        assertThatThrownBy(() -> salaryRecordRepository.saveAndFlush(record))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_salary_record_employee");
    }

    @Test
    @DisplayName("requirements.md 6.2: created_by must reference an existing app_user")
    void save_withNonExistentCreatedBy_violatesForeignKey() {
        SalaryRecord record = new SalaryRecord(employeeId, LocalDate.of(2020, 1, 15), null,
                new BigDecimal("120000.00"), "USD", PayFrequency.ANNUAL, new BigDecimal("120000.00"),
                new BigDecimal("120000.00"), new BigDecimal("10.00"), ChangeReason.NEW_HIRE, null, -1L);

        assertThatThrownBy(() -> salaryRecordRepository.saveAndFlush(record))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_salary_record_created_by");
    }

    @Test
    @DisplayName("indexes: findByEmployeeIdOrderByEffectiveFromDesc returns full salary history, newest first")
    void findByEmployeeIdOrderByEffectiveFromDesc_returnsHistoryNewestFirst() {
        SalaryRecord hire = newHireRecord(employeeId, LocalDate.of(2020, 1, 15));
        hire.setEffectiveTo(LocalDate.of(2021, 6, 1));
        salaryRecordRepository.saveAndFlush(hire);
        salaryRecordRepository.saveAndFlush(newHireRecord(employeeId, LocalDate.of(2021, 6, 1)));

        List<SalaryRecord> history = salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(employeeId);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getEffectiveFrom()).isEqualTo(LocalDate.of(2021, 6, 1));
        assertThat(history.get(1).getEffectiveFrom()).isEqualTo(LocalDate.of(2020, 1, 15));
    }
}
