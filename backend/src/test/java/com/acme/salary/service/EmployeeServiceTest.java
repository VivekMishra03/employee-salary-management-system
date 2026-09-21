package com.acme.salary.service;

import com.acme.salary.dto.CreateEmployeeRequest;
import com.acme.salary.dto.EmployeeDetail;
import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.EmployeeListItem;
import com.acme.salary.dto.PageResponse;
import com.acme.salary.dto.UpdateEmployeeRequest;
import com.acme.salary.exception.ConcurrentUpdateException;
import com.acme.salary.exception.ConflictException;
import com.acme.salary.exception.DuplicateResourceException;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.exception.ResourceNotFoundException;
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
import com.acme.salary.repository.AppUserRepository;
import com.acme.salary.repository.DepartmentRepository;
import com.acme.salary.repository.EmployeeRepository;
import com.acme.salary.repository.JobRoleRepository;
import com.acme.salary.repository.LocationRepository;
import com.acme.salary.repository.SalaryRecordRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-2.1 / FR-2.5 / FR-2.6: the employee rules -- validation of references, duplicate detection,
 * optimistic-lock version checks, soft delete and the assembled detail view. Runs against real
 * repositories and PostgreSQL so DB constraints participate, with a fixed clock (NFR-3): "today" is
 * always 2026-03-01.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import({EmployeeService.class, com.acme.salary.config.JpaAuditingConfig.class,
        EmployeeServiceTest.FixedClock.class})
class EmployeeServiceTest {

    static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private EmployeeService service;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private JobRoleRepository jobRoleRepository;
    @Autowired
    private SalaryRecordRepository salaryRecordRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private TestEntityManager entityManager;

    private Long deptId;
    private Long otherDeptId;
    private Long locId;
    private Long roleId;
    private Long userId;

    @BeforeEach
    void seed() {
        deptId = departmentRepository.saveAndFlush(new Department("ENG", "Engineering", null, "CC-1")).getId();
        otherDeptId = departmentRepository.saveAndFlush(new Department("SAL", "Sales", null, "CC-2")).getId();
        locId = locationRepository.saveAndFlush(new Location("US", "United States", "Austin", "USD")).getId();
        roleId = jobRoleRepository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4")).getId();
        userId = appUserRepository.saveAndFlush(new AppUser("hr@acme.example",
                "$2a$10$placeholderXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "Priya", AppUserRole.HR_MANAGER, true))
                .getId();
    }

    private CreateEmployeeRequest createRequest(String code, String email) {
        return new CreateEmployeeRequest(code, "Ada", "Lovelace", email, Gender.FEMALE, LocalDate.of(2020, 1, 15),
                EmploymentType.FULL_TIME, new BigDecimal("1.000"), deptId, roleId, locId, null);
    }

    private EmployeeDetail create(String code, String email) {
        return service.create(createRequest(code, email));
    }

    private UpdateEmployeeRequest updateRequest(EmployeeDetail current) {
        return new UpdateEmployeeRequest(current.version(), current.firstName(), current.lastName(), current.email(),
                current.gender(), current.employmentType(), current.fteRatio(), current.departmentId(),
                current.jobRoleId(), current.locationId(), current.manager() == null ? null : current.manager().id(),
                current.employmentStatus());
    }

    private UpdateEmployeeRequest withLastName(UpdateEmployeeRequest r, String lastName) {
        return new UpdateEmployeeRequest(r.version(), r.firstName(), lastName, r.email(), r.gender(),
                r.employmentType(), r.fteRatio(), r.departmentId(), r.jobRoleId(), r.locationId(), r.managerId(),
                r.employmentStatus());
    }

    // ---- create ----------------------------------------------------------------------------

    @Test
    @DisplayName("FR-2.1: create persists an ACTIVE employee and returns the assembled detail")
    void create_persistsAnActiveEmployee() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");

        assertThat(created.id()).isNotNull();
        assertThat(created.employeeCode()).isEqualTo("ACME-000001");
        assertThat(created.employmentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(created.terminationDate()).isNull();
        assertThat(created.departmentName()).isEqualTo("Engineering");
        assertThat(created.jobTitle()).isEqualTo("Software Engineer");
        assertThat(created.jobLevel()).isEqualTo("L4");
        assertThat(created.city()).isEqualTo("Austin");
        assertThat(created.countryCode()).isEqualTo("US");
        assertThat(created.version()).isZero();
        assertThat(employeeRepository.findById(created.id())).isPresent();
    }

    @Test
    @DisplayName("requirements.md 6.2: an omitted FTE ratio defaults to 1.000")
    void create_withoutFteRatio_defaultsToFullTime() {
        CreateEmployeeRequest r = new CreateEmployeeRequest("ACME-000001", "Ada", "Lovelace", "ada@acme.example",
                null, LocalDate.of(2020, 1, 15), EmploymentType.FULL_TIME, null, deptId, roleId, locId, null);

        assertThat(service.create(r).fteRatio()).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("FR-2.1: surrounding whitespace in the code, names and email is trimmed")
    void create_trimsWhitespace() {
        CreateEmployeeRequest r = new CreateEmployeeRequest("  ACME-000001 ", " Ada ", " Lovelace ",
                " ada@acme.example ", null, LocalDate.of(2020, 1, 15), EmploymentType.FULL_TIME,
                null, deptId, roleId, locId, null);

        EmployeeDetail created = service.create(r);

        assertThat(created.employeeCode()).isEqualTo("ACME-000001");
        assertThat(created.firstName()).isEqualTo("Ada");
        assertThat(created.email()).isEqualTo("ada@acme.example");
    }

    @Test
    @DisplayName("requirements.md 6.2: a duplicate employee code is a conflict with a stable code")
    void create_withDuplicateCode_isRejected() {
        create("ACME-000001", "ada@acme.example");

        assertThatThrownBy(() -> create("ACME-000001", "different@acme.example"))
                .isInstanceOf(DuplicateResourceException.class)
                .satisfies(e -> assertThat(((DuplicateResourceException) e).getCode())
                        .isEqualTo("DUPLICATE_EMPLOYEE_CODE"));
    }

    @Test
    @DisplayName("requirements.md 6.2: a duplicate email is a conflict with a stable code")
    void create_withDuplicateEmail_isRejected() {
        create("ACME-000001", "ada@acme.example");

        assertThatThrownBy(() -> create("ACME-000002", "ada@acme.example"))
                .isInstanceOf(DuplicateResourceException.class)
                .satisfies(e -> assertThat(((DuplicateResourceException) e).getCode()).isEqualTo("DUPLICATE_EMAIL"));
    }

    @Test
    @DisplayName("FR-2.1: an unknown department is a 400-class error naming the field, not a DB exception")
    void create_withUnknownDepartment_isRejectedNamingTheField() {
        CreateEmployeeRequest r = new CreateEmployeeRequest("ACME-000001", "Ada", "Lovelace", "ada@acme.example",
                null, LocalDate.of(2020, 1, 15), EmploymentType.FULL_TIME, null, -1L, roleId, locId, null);

        assertThatThrownBy(() -> service.create(r))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> {
                    RequestValidationException ex = (RequestValidationException) e;
                    assertThat(ex.getCode()).isEqualTo("INVALID_REFERENCE");
                    assertThat(ex.getField()).isEqualTo("departmentId");
                });
    }

    @Test
    @DisplayName("FR-2.1: an unknown job role, location or manager is rejected naming the field")
    void create_withUnknownJobRoleLocationOrManager_isRejectedNamingTheField() {
        LocalDate hired = LocalDate.of(2020, 1, 15);
        assertThatThrownBy(() -> service.create(new CreateEmployeeRequest("ACME-000001", "A", "B", "a@acme.example",
                null, hired, EmploymentType.FULL_TIME, null, deptId, -1L, locId, null)))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getField()).isEqualTo("jobRoleId"));
        assertThatThrownBy(() -> service.create(new CreateEmployeeRequest("ACME-000001", "A", "B", "a@acme.example",
                null, hired, EmploymentType.FULL_TIME, null, deptId, roleId, -1L, null)))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getField()).isEqualTo("locationId"));
        assertThatThrownBy(() -> service.create(new CreateEmployeeRequest("ACME-000001", "A", "B", "a@acme.example",
                null, hired, EmploymentType.FULL_TIME, null, deptId, roleId, locId, -1L)))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getField()).isEqualTo("managerId"));
    }

    // ---- read / detail ---------------------------------------------------------------------

    @Test
    @DisplayName("FR-2.5: getting an unknown employee is a not-found error")
    void get_withUnknownId_isNotFound() {
        assertThatThrownBy(() -> service.get(-1L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("FR-2.5: the detail shows the manager and the direct reports")
    void get_includesManagerAndDirectReports() {
        EmployeeDetail boss = create("ACME-000001", "boss@acme.example");
        EmployeeDetail report1 = service.create(new CreateEmployeeRequest("ACME-000002", "Grace", "Hopper",
                "grace@acme.example", null, LocalDate.of(2021, 1, 1), EmploymentType.FULL_TIME, null,
                deptId, roleId, locId, boss.id()));
        service.create(new CreateEmployeeRequest("ACME-000003", "Alan", "Turing", "alan@acme.example", null,
                LocalDate.of(2021, 1, 1), EmploymentType.FULL_TIME, null, deptId, roleId, locId, boss.id()));

        EmployeeDetail reportDetail = service.get(report1.id());
        EmployeeDetail bossDetail = service.get(boss.id());

        assertThat(reportDetail.manager().id()).isEqualTo(boss.id());
        assertThat(reportDetail.manager().employeeCode()).isEqualTo("ACME-000001");
        assertThat(bossDetail.manager()).isNull();
        assertThat(bossDetail.directReports()).extracting(r -> r.employeeCode())
                .containsExactly("ACME-000002", "ACME-000003");
    }

    private SalaryRecord salary(Long employeeId, LocalDate from, LocalDate to, String amount) {
        return salaryRecordRepository.saveAndFlush(new SalaryRecord(employeeId, from, to, new BigDecimal(amount),
                "USD", PayFrequency.ANNUAL, new BigDecimal(amount), new BigDecimal(amount), BigDecimal.ZERO,
                ChangeReason.MERIT_INCREASE, null, userId));
    }

    @Test
    @DisplayName("FR-2.5 / FR-3.6: current compensation is the record in force today; history is newest first")
    void get_includesCurrentSalaryAndFullHistory() {
        Long id = create("ACME-000001", "ada@acme.example").id();
        salary(id, LocalDate.of(2020, 1, 15), LocalDate.of(2025, 1, 1), "100000.00");
        salary(id, LocalDate.of(2025, 1, 1), null, "120000.00");

        EmployeeDetail detail = service.get(id);

        assertThat(detail.currentSalary()).isNotNull();
        assertThat(detail.currentSalary().baseAmount()).isEqualByComparingTo("120000.00");
        assertThat(detail.salaryHistory()).extracting(s -> s.baseAmount().intValue())
                .containsExactly(120000, 100000);
    }

    @Test
    @DisplayName("FR-3.6: a record that ended before today is history, not current compensation")
    void get_withOnlyAnEndedRecord_hasNoCurrentSalary() {
        Long id = create("ACME-000001", "ada@acme.example").id();
        salary(id, LocalDate.of(2020, 1, 15), LocalDate.of(2025, 1, 1), "100000.00");

        EmployeeDetail detail = service.get(id);

        assertThat(detail.currentSalary()).isNull();
        assertThat(detail.salaryHistory()).hasSize(1);
    }

    @Test
    @DisplayName("FR-3.6: a record starting after today is not yet current")
    void get_withOnlyAFutureRecord_hasNoCurrentSalary() {
        Long id = create("ACME-000001", "ada@acme.example").id();
        salary(id, LocalDate.of(2026, 6, 1), null, "130000.00");

        assertThat(service.get(id).currentSalary()).isNull();
    }

    @Test
    @DisplayName("FR-3.2: a record ending exactly today is no longer current (intervals are half-open)")
    void get_withARecordEndingToday_hasNoCurrentSalary() {
        Long id = create("ACME-000001", "ada@acme.example").id();
        salary(id, LocalDate.of(2020, 1, 15), TODAY, "100000.00");

        assertThat(service.get(id).currentSalary()).isNull();
    }

    // ---- update / optimistic locking -------------------------------------------------------

    @Test
    @DisplayName("FR-2.1: update applies the changes and bumps the version")
    void update_appliesChangesAndBumpsTheVersion() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");

        EmployeeDetail updated = service.update(created.id(), withLastName(updateRequest(created), "Byron"));

        assertThat(updated.lastName()).isEqualTo("Byron");
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(updated.employeeCode()).isEqualTo("ACME-000001");
        assertThat(updated.hireDate()).isEqualTo(LocalDate.of(2020, 1, 15));
    }

    @Test
    @DisplayName("FR-2.1: update can move an employee to another department and put them on leave")
    void update_canChangeDepartmentAndStatus() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");
        UpdateEmployeeRequest r = updateRequest(created);
        UpdateEmployeeRequest changed = new UpdateEmployeeRequest(r.version(), r.firstName(), r.lastName(), r.email(),
                r.gender(), r.employmentType(), r.fteRatio(), otherDeptId, r.jobRoleId(), r.locationId(),
                r.managerId(), EmploymentStatus.ON_LEAVE);

        EmployeeDetail updated = service.update(created.id(), changed);

        assertThat(updated.departmentName()).isEqualTo("Sales");
        assertThat(updated.employmentStatus()).isEqualTo(EmploymentStatus.ON_LEAVE);
    }

    @Test
    @DisplayName("FR-2.6: an update carrying a stale version is rejected, not silently applied")
    void update_withStaleVersion_isRejected() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");
        UpdateEmployeeRequest first = withLastName(updateRequest(created), "Byron");
        service.update(created.id(), first);

        // Second writer still holds the version they originally read.
        UpdateEmployeeRequest stale = withLastName(updateRequest(created), "Something Else");

        assertThatThrownBy(() -> service.update(created.id(), stale)).isInstanceOf(ConcurrentUpdateException.class);
        assertThat(service.get(created.id()).lastName()).isEqualTo("Byron");
    }

    @Test
    @DisplayName("FR-2.1: updating an unknown employee is a not-found error")
    void update_withUnknownId_isNotFound() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");

        assertThatThrownBy(() -> service.update(-1L, updateRequest(created)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("invariant: an employee cannot be set as their own manager")
    void update_withSelfAsManager_isRejected() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");
        UpdateEmployeeRequest r = updateRequest(created);
        UpdateEmployeeRequest self = new UpdateEmployeeRequest(r.version(), r.firstName(), r.lastName(), r.email(),
                r.gender(), r.employmentType(), r.fteRatio(), r.departmentId(), r.jobRoleId(), r.locationId(),
                created.id(), r.employmentStatus());

        assertThatThrownBy(() -> service.update(created.id(), self))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getCode()).isEqualTo("INVALID_MANAGER"));
    }

    @Test
    @DisplayName("requirements.md 6.2: changing to another employee's email is a conflict")
    void update_toAnotherEmployeesEmail_isRejected() {
        create("ACME-000001", "ada@acme.example");
        EmployeeDetail second = create("ACME-000002", "grace@acme.example");
        UpdateEmployeeRequest r = updateRequest(second);
        UpdateEmployeeRequest clash = new UpdateEmployeeRequest(r.version(), r.firstName(), r.lastName(),
                "ada@acme.example", r.gender(), r.employmentType(), r.fteRatio(), r.departmentId(), r.jobRoleId(),
                r.locationId(), r.managerId(), r.employmentStatus());

        assertThatThrownBy(() -> service.update(second.id(), clash)).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("requirements.md 6.2: resubmitting one's own email is not a duplicate")
    void update_keepingOwnEmail_isNotADuplicate() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");

        assertThat(service.update(created.id(), withLastName(updateRequest(created), "Byron")).email())
                .isEqualTo("ada@acme.example");
    }

    @Test
    @DisplayName("FR-2.1: TERMINATED cannot be set through update -- soft delete is the only way")
    void update_toTerminatedStatus_isRejected() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");
        UpdateEmployeeRequest r = updateRequest(created);
        UpdateEmployeeRequest terminate = new UpdateEmployeeRequest(r.version(), r.firstName(), r.lastName(),
                r.email(), r.gender(), r.employmentType(), r.fteRatio(), r.departmentId(), r.jobRoleId(),
                r.locationId(), r.managerId(), EmploymentStatus.TERMINATED);

        assertThatThrownBy(() -> service.update(created.id(), terminate))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getCode()).isEqualTo("INVALID_STATUS"));
    }

    @Test
    @DisplayName("FR-2.1: a terminated employee's record can no longer be edited")
    void update_onATerminatedEmployee_isRejected() {
        EmployeeDetail created = create("ACME-000001", "ada@acme.example");
        service.deactivate(created.id());
        entityManager.clear();
        EmployeeDetail terminated = service.get(created.id());

        // A state conflict (409), not a malformed request (400): the request is fine, the record is not editable.
        assertThatThrownBy(() -> service.update(created.id(), withLastName(updateRequest(terminated), "Byron")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCode()).isEqualTo("EMPLOYEE_TERMINATED"));
    }

    // ---- soft delete -----------------------------------------------------------------------

    @Test
    @DisplayName("FR-2.1: deactivating sets TERMINATED and today's date from the injected clock")
    void deactivate_setsTerminatedAndTodaysDate() {
        Long id = create("ACME-000001", "ada@acme.example").id();

        service.deactivate(id);
        entityManager.clear();

        Employee e = employeeRepository.findById(id).orElseThrow();
        assertThat(e.getEmploymentStatus()).isEqualTo(EmploymentStatus.TERMINATED);
        assertThat(e.getTerminationDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("FR-2.1: deactivation never hard-deletes -- the row and its salary history remain")
    void deactivate_keepsTheRowAndItsSalaryHistory() {
        Long id = create("ACME-000001", "ada@acme.example").id();
        salary(id, LocalDate.of(2020, 1, 15), null, "100000.00");

        service.deactivate(id);
        entityManager.clear();

        assertThat(employeeRepository.findById(id)).isPresent();
        assertThat(salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(id)).hasSize(1);
    }

    @Test
    @DisplayName("invariant: a not-yet-started hire is terminated on their start date, never before it")
    void deactivate_whenHireDateIsInTheFuture_terminatesOnTheHireDate() {
        CreateEmployeeRequest future = new CreateEmployeeRequest("ACME-000001", "Ada", "Lovelace", "ada@acme.example",
                null, LocalDate.of(2026, 9, 1), EmploymentType.FULL_TIME, null, deptId, roleId, locId, null);
        Long id = service.create(future).id();

        service.deactivate(id);
        entityManager.clear();

        // termination_date >= hire_date is a CHECK constraint; "today" (March) would violate it.
        assertThat(employeeRepository.findById(id).orElseThrow().getTerminationDate())
                .isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("FR-2.1: deactivating twice is harmless and keeps the original termination date")
    void deactivate_isIdempotent() {
        Long id = create("ACME-000001", "ada@acme.example").id();
        service.deactivate(id);
        entityManager.clear();
        Employee first = employeeRepository.findById(id).orElseThrow();
        LocalDate originalDate = first.getTerminationDate();
        Long versionAfterFirst = first.getVersion();

        service.deactivate(id);
        entityManager.clear();

        Employee second = employeeRepository.findById(id).orElseThrow();
        assertThat(second.getTerminationDate()).isEqualTo(originalDate);
        assertThat(second.getVersion()).isEqualTo(versionAfterFirst);
    }

    @Test
    @DisplayName("FR-2.1: deactivating an unknown employee is a not-found error")
    void deactivate_withUnknownId_isNotFound() {
        assertThatThrownBy(() -> service.deactivate(-1L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- list ------------------------------------------------------------------------------

    @Test
    @DisplayName("FR-2.2: list items carry the department, role and location names")
    void search_returnsItemsWithResolvedNames() {
        create("ACME-000001", "ada@acme.example");

        PageResponse<EmployeeListItem> page = service.search(
                new EmployeeFilter(null, null, null, null, null, null), PageRequest.of(0, 10));

        assertThat(page.content()).hasSize(1);
        EmployeeListItem item = page.content().get(0);
        assertThat(item.departmentName()).isEqualTo("Engineering");
        assertThat(item.jobTitle()).isEqualTo("Software Engineer");
        assertThat(item.jobLevel()).isEqualTo("L4");
        assertThat(item.city()).isEqualTo("Austin");
        assertThat(item.countryCode()).isEqualTo("US");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-2.2: an unknown sort field is a validation error, not a server error")
    void search_withUnknownSortField_isRejected() {
        assertThatThrownBy(() -> service.search(new EmployeeFilter(null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by("passwordHash"))))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getCode()).isEqualTo("INVALID_SORT"));
    }

    @Test
    @DisplayName("FR-2.2: paging by a non-unique sort key neither repeats nor skips a row")
    void search_pagesByANonUniqueSortWithoutOverlap() {
        for (int i = 1; i <= 5; i++) {
            // Every employee shares the same last name, so ordering by it alone is ambiguous.
            service.create(new CreateEmployeeRequest("ACME-00000" + i, "First" + i, "Same", "e" + i + "@acme.example",
                    null, LocalDate.of(2020, 1, 15), EmploymentType.FULL_TIME, null, deptId, roleId, locId, null));
        }
        EmployeeFilter none = new EmployeeFilter(null, null, null, null, null, null);

        Set<String> seen = new HashSet<>();
        int total = 0;
        for (int page = 0; page < 3; page++) {
            List<EmployeeListItem> content = service.search(none, PageRequest.of(page, 2, Sort.by("lastName")))
                    .content();
            content.forEach(item -> seen.add(item.employeeCode()));
            total += content.size();
        }

        assertThat(total).isEqualTo(5);
        assertThat(seen).hasSize(5);
    }
}
