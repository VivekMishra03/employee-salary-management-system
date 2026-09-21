package com.acme.salary.service;

import com.acme.salary.dto.CreateEmployeeRequest;
import com.acme.salary.dto.EmployeeDetail;
import com.acme.salary.dto.EmployeeDetail.PersonRef;
import com.acme.salary.dto.EmployeeDetail.SalarySummary;
import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.dto.EmployeeListItem;
import com.acme.salary.dto.PageResponse;
import com.acme.salary.dto.UpdateEmployeeRequest;
import com.acme.salary.exception.ConcurrentUpdateException;
import com.acme.salary.exception.ConflictException;
import com.acme.salary.exception.DuplicateResourceException;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.exception.ResourceNotFoundException;
import com.acme.salary.model.Department;
import com.acme.salary.model.Employee;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import com.acme.salary.model.SalaryRecord;
import com.acme.salary.repository.DepartmentRepository;
import com.acme.salary.repository.EmployeeRepository;
import com.acme.salary.repository.JobRoleRepository;
import com.acme.salary.repository.LocationRepository;
import com.acme.salary.repository.SalaryRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FR-2: the employee directory. Owns the business rules -- reference validation, duplicate
 * detection, the optimistic-lock version check (FR-2.6), soft delete -- and assembles DTOs so that
 * no entity ever crosses the HTTP boundary.
 *
 * <p>"Today" always comes from the injected {@link Clock} (NFR-3), never the system clock.
 *
 * <p>FR-3.7's audit trail covers salary mutations, not employee edits, so nothing here writes to it.
 */
@Service
@Transactional(readOnly = true)
public class EmployeeService {

    /**
     * The only fields a client may sort by. A whitelist rather than passing the requested property
     * straight to JPA, so an unknown or sensitive property is a 400 instead of a server error (or,
     * worse, a working sort on something that should not be exposed).
     */
    private static final Set<String> SORTABLE = Set.of(
            "employeeCode", "firstName", "lastName", "email", "hireDate", "employmentStatus", "employmentType");

    private static final Sort DEFAULT_SORT = Sort.by("lastName", "firstName");

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final JobRoleRepository jobRoleRepository;
    private final LocationRepository locationRepository;
    private final SalaryRecordRepository salaryRecordRepository;
    private final Clock clock;

    public EmployeeService(EmployeeRepository employeeRepository, DepartmentRepository departmentRepository,
                           JobRoleRepository jobRoleRepository, LocationRepository locationRepository,
                           SalaryRecordRepository salaryRecordRepository, Clock clock) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.jobRoleRepository = jobRoleRepository;
        this.locationRepository = locationRepository;
        this.salaryRecordRepository = salaryRecordRepository;
        this.clock = clock;
    }

    // ---- queries ---------------------------------------------------------------------------

    public EmployeeDetail get(Long id) {
        return toDetail(findOrThrow(id));
    }

    /** FR-2.2 / FR-2.3 / FR-2.4: filtered, sorted, paged -- all in the database. */
    public PageResponse<EmployeeListItem> search(EmployeeFilter filter, Pageable pageable) {
        Page<Employee> page = employeeRepository.findAll(EmployeeSpecifications.matching(filter),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), effectiveSort(pageable.getSort())));
        return new PageResponse<>(toListItems(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Validates the requested sort against the whitelist and normalises it: default ordering when
     * none was requested, and always a trailing {@code id} tiebreaker. Static and package-private so
     * it can be unit-tested directly -- whether a database happens to return tied rows consistently
     * is not something a test can rely on.
     */
    static Sort effectiveSort(Sort requested) {
        for (Sort.Order order : requested) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new RequestValidationException("INVALID_SORT", "sort",
                        "Sorting is not supported by that field. Supported fields: " + SORTABLE.stream().sorted().toList());
            }
        }
        Sort base = requested.isSorted() ? requested : DEFAULT_SORT;
        // The id tiebreaker is what makes paging stable: ordering by a non-unique column alone lets
        // the database return tied rows in a different order on each request, so a row can appear
        // on two pages or on none.
        return base.and(Sort.by("id"));
    }

    // ---- commands --------------------------------------------------------------------------

    @Transactional
    public EmployeeDetail create(CreateEmployeeRequest request) {
        String code = request.employeeCode().trim();
        String email = request.email().trim();
        if (employeeRepository.existsByEmployeeCode(code)) {
            throw new DuplicateResourceException("DUPLICATE_EMPLOYEE_CODE", "An employee with this code already exists");
        }
        if (employeeRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("DUPLICATE_EMAIL", "An employee with this email already exists");
        }
        requireDepartment(request.departmentId());
        requireJobRole(request.jobRoleId());
        requireLocation(request.locationId());
        requireManager(request.managerId());

        Employee employee = new Employee(code, request.firstName().trim(), request.lastName().trim(), email,
                request.gender(), request.hireDate(), EmploymentStatus.ACTIVE, request.employmentType(),
                scaled(request.fteRatio()), request.departmentId(), request.jobRoleId(), request.locationId(),
                request.managerId());
        return toDetail(employeeRepository.saveAndFlush(employee));
    }

    @Transactional
    public EmployeeDetail update(Long id, UpdateEmployeeRequest request) {
        Employee employee = findOrThrow(id);

        // FR-2.6: refuse an edit made against a stale copy. This explicit check gives the client a
        // clear error; @Version on the entity additionally guards the race where two requests pass
        // this check simultaneously (that surfaces as an optimistic-locking failure at flush).
        if (!employee.getVersion().equals(request.version())) {
            throw new ConcurrentUpdateException();
        }
        if (employee.getEmploymentStatus() == EmploymentStatus.TERMINATED) {
            throw new ConflictException("EMPLOYEE_TERMINATED", "A terminated employee's record can no longer be edited");
        }
        if (request.employmentStatus() == EmploymentStatus.TERMINATED) {
            throw new RequestValidationException("INVALID_STATUS", "employmentStatus",
                    "TERMINATED cannot be set by an update; deactivate the employee instead");
        }
        if (id.equals(request.managerId())) {
            throw new RequestValidationException("INVALID_MANAGER", "managerId", "An employee cannot be their own manager");
        }
        requireDepartment(request.departmentId());
        requireJobRole(request.jobRoleId());
        requireLocation(request.locationId());
        requireManager(request.managerId());

        String email = request.email().trim();
        if (!email.equals(employee.getEmail()) && employeeRepository.existsByEmailAndIdNot(email, id)) {
            throw new DuplicateResourceException("DUPLICATE_EMAIL", "An employee with this email already exists");
        }

        employee.setFirstName(request.firstName().trim());
        employee.setLastName(request.lastName().trim());
        employee.setEmail(email);
        employee.setGender(request.gender());
        employee.setEmploymentType(request.employmentType());
        employee.setFteRatio(scaled(request.fteRatio()));
        employee.setDepartmentId(request.departmentId());
        employee.setJobRoleId(request.jobRoleId());
        employee.setLocationId(request.locationId());
        employee.setManagerId(request.managerId());
        employee.setEmploymentStatus(request.employmentStatus());
        return toDetail(employeeRepository.saveAndFlush(employee));
    }

    /**
     * FR-2.1: soft delete. The row and its salary history stay, so historical analytics never
     * silently rewrite past payroll (requirements.md section 6.3).
     *
     * <p>Idempotent: deactivating an already-terminated employee changes nothing, so the original
     * termination date is never overwritten. The termination date is today, or the hire date if the
     * hire has not started yet -- {@code termination_date >= hire_date} is a database CHECK.
     */
    @Transactional
    public void deactivate(Long id) {
        Employee employee = findOrThrow(id);
        if (employee.getEmploymentStatus() == EmploymentStatus.TERMINATED) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        employee.setTerminationDate(today.isBefore(employee.getHireDate()) ? employee.getHireDate() : today);
        employee.setEmploymentStatus(EmploymentStatus.TERMINATED);
        employeeRepository.saveAndFlush(employee);
    }

    // ---- validation helpers ----------------------------------------------------------------

    private Employee findOrThrow(Long id) {
        return employeeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee", id));
    }

    private void requireDepartment(Long id) {
        requireExists(departmentRepository.existsById(id), "departmentId");
    }

    private void requireJobRole(Long id) {
        requireExists(jobRoleRepository.existsById(id), "jobRoleId");
    }

    private void requireLocation(Long id) {
        requireExists(locationRepository.existsById(id), "locationId");
    }

    private void requireManager(Long id) {
        if (id != null) {
            requireExists(employeeRepository.existsById(id), "managerId");
        }
    }

    private static void requireExists(boolean exists, String field) {
        if (!exists) {
            throw new RequestValidationException("INVALID_REFERENCE", field, "The referenced record does not exist");
        }
    }

    /** Explicit rounding rule (NFR-2). Input validation already limits the ratio to three decimals. */
    private static BigDecimal scaled(BigDecimal fteRatio) {
        return (fteRatio == null ? BigDecimal.ONE : fteRatio).setScale(3, RoundingMode.HALF_UP);
    }

    // ---- assembly --------------------------------------------------------------------------

    private EmployeeDetail toDetail(Employee e) {
        Department department = departmentRepository.findById(e.getDepartmentId()).orElseThrow();
        JobRole role = jobRoleRepository.findById(e.getJobRoleId()).orElseThrow();
        Location location = locationRepository.findById(e.getLocationId()).orElseThrow();

        PersonRef manager = e.getManagerId() == null ? null
                : employeeRepository.findById(e.getManagerId()).map(EmployeeService::personRef).orElse(null);
        List<PersonRef> reports = employeeRepository.findByManagerIdOrderByLastNameAscFirstNameAsc(e.getId())
                .stream().map(EmployeeService::personRef).toList();

        List<SalaryRecord> history = salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(e.getId());
        LocalDate today = LocalDate.now(clock);
        // Intervals never overlap (FR-3.2), so at most one record is in force today. They are
        // half-open [from, to): a record ending today is no longer current.
        SalarySummary current = history.stream()
                .filter(r -> !r.getEffectiveFrom().isAfter(today)
                        && (r.getEffectiveTo() == null || today.isBefore(r.getEffectiveTo())))
                .findFirst().map(EmployeeService::salarySummary).orElse(null);

        return new EmployeeDetail(e.getId(), e.getEmployeeCode(), e.getFirstName(), e.getLastName(), e.getEmail(),
                e.getGender(), e.getHireDate(), e.getTerminationDate(), e.getEmploymentStatus(),
                e.getEmploymentType(), e.getFteRatio(), department.getId(), department.getName(), role.getId(),
                role.getTitle(), role.getJobLevel(), location.getId(), location.getCity(), location.getCountryCode(),
                manager, reports, current, history.stream().map(EmployeeService::salarySummary).toList(),
                e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    /** Three batched lookups for the whole page, not three per row. */
    private List<EmployeeListItem> toListItems(List<Employee> employees) {
        Map<Long, Department> departments = byId(departmentRepository.findAllById(
                employees.stream().map(Employee::getDepartmentId).collect(Collectors.toSet())), Department::getId);
        Map<Long, JobRole> roles = byId(jobRoleRepository.findAllById(
                employees.stream().map(Employee::getJobRoleId).collect(Collectors.toSet())), JobRole::getId);
        Map<Long, Location> locations = byId(locationRepository.findAllById(
                employees.stream().map(Employee::getLocationId).collect(Collectors.toSet())), Location::getId);

        return employees.stream().map(e -> {
            Department d = departments.get(e.getDepartmentId());
            JobRole r = roles.get(e.getJobRoleId());
            Location l = locations.get(e.getLocationId());
            return new EmployeeListItem(e.getId(), e.getEmployeeCode(), e.getFirstName(), e.getLastName(),
                    e.getEmail(), e.getEmploymentStatus(), e.getEmploymentType(), e.getHireDate(), d.getId(),
                    d.getName(), r.getId(), r.getTitle(), r.getJobLevel(), l.getId(), l.getCity(), l.getCountryCode());
        }).toList();
    }

    private static <T> Map<Long, T> byId(List<T> items, Function<T, Long> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity()));
    }

    private static PersonRef personRef(Employee e) {
        return new PersonRef(e.getId(), e.getEmployeeCode(), e.getFirstName() + " " + e.getLastName());
    }

    private static SalarySummary salarySummary(SalaryRecord r) {
        return new SalarySummary(r.getId(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getBaseAmount(),
                r.getCurrencyCode(), r.getPayFrequency(), r.getAnnualisedAmount(), r.getAnnualisedAmountBaseCcy(),
                r.getTargetBonusPct(), r.getChangeReason(), r.getNotes());
    }
}
