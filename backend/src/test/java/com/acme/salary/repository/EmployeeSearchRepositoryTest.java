package com.acme.salary.repository;

import com.acme.salary.dto.EmployeeFilter;
import com.acme.salary.model.Department;
import com.acme.salary.model.Employee;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.model.Gender;
import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import com.acme.salary.service.EmployeeSpecifications;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-2.2 / FR-2.3 / FR-2.4: server-side search, combinable filters and pagination, run against a
 * real PostgreSQL so the generated SQL (subqueries, LIKE with an escape character) is what ships.
 *
 * <p>Fixture (5 employees):
 * <pre>
 *  code          name            dept  country level role                       status      type
 *  ACME-000001   Ada Lovelace    ENG   US      L4    Software Engineer          ACTIVE      FULL_TIME
 *  ACME-000002   Grace Hopper    ENG   DE      L5    Senior Software Engineer   ACTIVE      FULL_TIME
 *  ACME-000003   Alan Turing     ENG   DE      L4    Software Engineer          ON_LEAVE    PART_TIME
 *  ACME-000004   Sam Seller      SAL   IN      L3    Sales Rep                  ACTIVE      CONTRACT
 *  ACME-000005   Ada Byron       SAL   US      L3    Sales Rep                  TERMINATED  FULL_TIME
 * </pre>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@Import({com.acme.salary.config.ClockConfig.class, com.acme.salary.config.JpaAuditingConfig.class})
class EmployeeSearchRepositoryTest {

    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private JobRoleRepository jobRoleRepository;

    private Long eng;
    private Long sal;
    private Long austin;
    private Long berlin;
    private Long pune;
    private Long sweL4;
    private Long sseL5;
    private Long salesL3;

    @BeforeEach
    void seed() {
        eng = departmentRepository.saveAndFlush(new Department("ENG", "Engineering", null, "CC-1")).getId();
        sal = departmentRepository.saveAndFlush(new Department("SAL", "Sales", null, "CC-2")).getId();
        austin = locationRepository.saveAndFlush(new Location("US", "United States", "Austin", "USD")).getId();
        berlin = locationRepository.saveAndFlush(new Location("DE", "Germany", "Berlin", "EUR")).getId();
        pune = locationRepository.saveAndFlush(new Location("IN", "India", "Pune", "INR")).getId();
        sweL4 = jobRoleRepository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4")).getId();
        sseL5 = jobRoleRepository.saveAndFlush(new JobRole("Senior Software Engineer", "Engineering", "L5")).getId();
        salesL3 = jobRoleRepository.saveAndFlush(new JobRole("Sales Rep", "Sales", "L3")).getId();

        save("ACME-000001", "Ada", "Lovelace", "ada@acme.example", eng, sweL4, austin,
                EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME);
        save("ACME-000002", "Grace", "Hopper", "grace@acme.example", eng, sseL5, berlin,
                EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME);
        save("ACME-000003", "Alan", "Turing", "alan@acme.example", eng, sweL4, berlin,
                EmploymentStatus.ON_LEAVE, EmploymentType.PART_TIME);
        save("ACME-000004", "Sam", "Seller", "sam@acme.example", sal, salesL3, pune,
                EmploymentStatus.ACTIVE, EmploymentType.CONTRACT);
        Employee terminated = save("ACME-000005", "Ada", "Byron", "ada.byron@acme.example", sal, salesL3, austin,
                EmploymentStatus.TERMINATED, EmploymentType.FULL_TIME);
        terminated.setTerminationDate(LocalDate.of(2024, 6, 30));
        employeeRepository.saveAndFlush(terminated);
    }

    private Employee save(String code, String first, String last, String email, Long dept, Long role, Long loc,
                          EmploymentStatus status, EmploymentType type) {
        Employee e = new Employee(code, first, last, email, Gender.PREFER_NOT_TO_SAY, LocalDate.of(2020, 1, 15),
                EmploymentStatus.ACTIVE, type, new BigDecimal("1.000"), dept, role, loc, null);
        if (status == EmploymentStatus.TERMINATED) {
            // Both halves of the iff invariant must be present in a single insert.
            e.setEmploymentStatus(EmploymentStatus.TERMINATED);
            e.setTerminationDate(LocalDate.of(2024, 6, 30));
        } else {
            e.setEmploymentStatus(status);
        }
        return employeeRepository.saveAndFlush(e);
    }

    private List<String> codes(EmployeeFilter filter) {
        return employeeRepository.findAll(EmployeeSpecifications.matching(filter), Sort.by("employeeCode"))
                .stream().map(Employee::getEmployeeCode).toList();
    }

    private static EmployeeFilter filter(String q, Long dept, String country, EmploymentStatus status,
                                         EmploymentType type, String level) {
        return new EmployeeFilter(q, dept, country, status, type, level);
    }

    private static final EmployeeFilter NONE_APPLIED = filter(null, null, null, null, null, null);

    @Test
    @DisplayName("FR-2.4: with no filter every employee is returned")
    void noFilter_returnsEveryone() {
        assertThat(codes(NONE_APPLIED)).hasSize(5);
    }

    @Test
    @DisplayName("FR-2.4: filter by department")
    void filterByDepartment() {
        assertThat(codes(filter(null, eng, null, null, null, null)))
                .containsExactly("ACME-000001", "ACME-000002", "ACME-000003");
    }

    @Test
    @DisplayName("FR-2.4: filter by country, resolved through the employee's location")
    void filterByCountry() {
        assertThat(codes(filter(null, null, "DE", null, null, null)))
                .containsExactly("ACME-000002", "ACME-000003");
    }

    @Test
    @DisplayName("FR-2.4: a lower-case country code matches too")
    void filterByCountry_isCaseInsensitive() {
        assertThat(codes(filter(null, null, "de", null, null, null)))
                .containsExactly("ACME-000002", "ACME-000003");
    }

    @Test
    @DisplayName("FR-2.4: filter by employment status")
    void filterByStatus() {
        assertThat(codes(filter(null, null, null, EmploymentStatus.TERMINATED, null, null)))
                .containsExactly("ACME-000005");
    }

    @Test
    @DisplayName("FR-2.4: filter by employment type")
    void filterByEmploymentType() {
        assertThat(codes(filter(null, null, null, null, EmploymentType.CONTRACT, null)))
                .containsExactly("ACME-000004");
    }

    @Test
    @DisplayName("FR-2.4: filter by job level, resolved through the employee's job role")
    void filterByJobLevel() {
        assertThat(codes(filter(null, null, null, null, null, "L4")))
                .containsExactly("ACME-000001", "ACME-000003");
    }

    @Test
    @DisplayName("FR-2.4: filters combine, and each one narrows the result")
    void filters_combineWithAnd() {
        assertThat(codes(filter(null, eng, "DE", null, null, "L4"))).containsExactly("ACME-000003");
        assertThat(codes(filter(null, eng, "DE", EmploymentStatus.ACTIVE, null, "L4"))).isEmpty();
    }

    @Test
    @DisplayName("FR-2.3: search matches the last name, case-insensitively")
    void search_byLastName() {
        assertThat(codes(filter("lovelace", null, null, null, null, null))).containsExactly("ACME-000001");
        assertThat(codes(filter("LOVELACE", null, null, null, null, null))).containsExactly("ACME-000001");
    }

    @Test
    @DisplayName("FR-2.3: search matches the employee code")
    void search_byEmployeeCode() {
        assertThat(codes(filter("ACME-000003", null, null, null, null, null))).containsExactly("ACME-000003");
    }

    @Test
    @DisplayName("FR-2.3: search matches the email")
    void search_byEmail() {
        assertThat(codes(filter("grace@acme", null, null, null, null, null))).containsExactly("ACME-000002");
    }

    @Test
    @DisplayName("FR-2.3: search matches a first name shared by several people")
    void search_byFirstName_returnsEveryMatch() {
        assertThat(codes(filter("ada", null, null, null, null, null)))
                .containsExactly("ACME-000001", "ACME-000005");
    }

    @Test
    @DisplayName("FR-2.3: every word must match, in any order")
    void search_withSeveralWords_requiresAllOfThemInAnyOrder() {
        assertThat(codes(filter("ada byron", null, null, null, null, null))).containsExactly("ACME-000005");
        assertThat(codes(filter("byron ada", null, null, null, null, null))).containsExactly("ACME-000005");
    }

    @Test
    @DisplayName("FR-2.3: '%' and '_' in the search text are literal characters, not wildcards")
    void search_treatsLikeWildcardsAsLiterals() {
        assertThat(codes(filter("%", null, null, null, null, null))).isEmpty();
        assertThat(codes(filter("_", null, null, null, null, null))).isEmpty();
        assertThat(codes(filter("a%e", null, null, null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("FR-2.3: blank search text means no search filter")
    void search_withBlankText_appliesNoFilter() {
        assertThat(codes(filter("   ", null, null, null, null, null))).hasSize(5);
    }

    @Test
    @DisplayName("FR-2.3 + FR-2.4: search and filters combine")
    void search_combinesWithFilters() {
        assertThat(codes(filter("ada", null, null, EmploymentStatus.ACTIVE, null, null)))
                .containsExactly("ACME-000001");
    }

    @Test
    @DisplayName("FR-2.2: results are paged and sorted on the server, with an accurate total")
    void paging_returnsRequestedPageAndTotal() {
        Page<Employee> first = employeeRepository.findAll(EmployeeSpecifications.matching(NONE_APPLIED),
                PageRequest.of(0, 2, Sort.by("lastName")));
        Page<Employee> second = employeeRepository.findAll(EmployeeSpecifications.matching(NONE_APPLIED),
                PageRequest.of(1, 2, Sort.by("lastName")));
        Page<Employee> third = employeeRepository.findAll(EmployeeSpecifications.matching(NONE_APPLIED),
                PageRequest.of(2, 2, Sort.by("lastName")));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.map(Employee::getLastName)).containsExactly("Byron", "Hopper");
        assertThat(second.map(Employee::getLastName)).containsExactly("Lovelace", "Seller");
        assertThat(third.map(Employee::getLastName)).containsExactly("Turing");
    }

    @Test
    @DisplayName("FR-2.2: the total reflects the filter, not the whole table")
    void paging_totalCountsOnlyMatchingRows() {
        Page<Employee> page = employeeRepository.findAll(
                EmployeeSpecifications.matching(filter(null, eng, null, null, null, null)),
                PageRequest.of(0, 1, Sort.by("employeeCode")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(1);
    }
}
