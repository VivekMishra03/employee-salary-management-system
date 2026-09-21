package com.acme.salary.service;

import com.acme.salary.config.CompensationProperties;
import com.acme.salary.dto.EmployeeDetail.SalarySummary;
import com.acme.salary.dto.RecordSalaryRequest;
import com.acme.salary.exception.ConflictException;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.exception.ResourceNotFoundException;
import com.acme.salary.model.AppUser;
import com.acme.salary.model.AppUserRole;
import com.acme.salary.model.AuditAction;
import com.acme.salary.model.AuditLog;
import com.acme.salary.model.ChangeReason;
import com.acme.salary.model.Department;
import com.acme.salary.model.Employee;
import com.acme.salary.model.EmploymentStatus;
import com.acme.salary.model.EmploymentType;
import com.acme.salary.model.ExchangeRate;
import com.acme.salary.model.Gender;
import com.acme.salary.model.JobRole;
import com.acme.salary.model.Location;
import com.acme.salary.model.PayFrequency;
import com.acme.salary.model.SalaryRecord;
import com.acme.salary.repository.AppUserRepository;
import com.acme.salary.repository.AuditLogRepository;
import com.acme.salary.repository.DepartmentRepository;
import com.acme.salary.repository.EmployeeRepository;
import com.acme.salary.repository.ExchangeRateRepository;
import com.acme.salary.repository.JobRoleRepository;
import com.acme.salary.repository.LocationRepository;
import com.acme.salary.repository.SalaryRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * FR-3: recording salary changes against real PostgreSQL -- temporal transitions, derived amounts,
 * FX at the historical rate, and the audit trail. The pure decisions are unit-tested in {@link
 * SalaryTimelineTest} / {@link SalaryCalculatorTest}; this class proves they are wired to the
 * database correctly, including the ordering the EXCLUDE constraint forces on the writes.
 *
 * <p>Fixture: employees hired 2020-01-01 in Austin (USD) and Berlin (EUR); EUR/USD rates dated
 * 2020-01-01 (1.10), 2022-01-01 (1.05) and 2024-01-01 (1.08).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@AutoConfigureEmbeddedDatabase(provider = ZONKY)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({SalaryService.class, AuditLogService.class, com.acme.salary.config.ClockConfig.class,
        com.acme.salary.config.JpaAuditingConfig.class, SalaryServiceTest.Props.class})
class SalaryServiceTest {

    static final LocalDate HIRE = LocalDate.of(2020, 1, 1);

    @TestConfiguration
    static class Props {
        @Bean
        CompensationProperties compensationProperties() {
            return new CompensationProperties("USD");
        }
    }

    @Autowired
    private SalaryService service;
    @Autowired
    private SalaryRecordRepository salaryRecordRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
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
    @Autowired
    private ExchangeRateRepository exchangeRateRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private Long usEmployeeId;
    private Long deEmployeeId;
    private Long actorId;
    private Long deptId;
    private Long roleId;
    private Long austinId;

    @BeforeEach
    void seed() {
        deptId = departmentRepository.saveAndFlush(new Department("ENG", "Engineering", null, "CC-1")).getId();
        roleId = jobRoleRepository.saveAndFlush(new JobRole("Software Engineer", "Engineering", "L4")).getId();
        austinId = locationRepository.saveAndFlush(new Location("US", "United States", "Austin", "USD")).getId();
        Long berlinId = locationRepository.saveAndFlush(new Location("DE", "Germany", "Berlin", "EUR")).getId();
        actorId = appUserRepository.saveAndFlush(new AppUser("hr@acme.example",
                "$2a$10$placeholderXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", "Priya", AppUserRole.HR_MANAGER, true))
                .getId();
        usEmployeeId = newEmployee("ACME-000001", "ada@acme.example", austinId, EmploymentStatus.ACTIVE, null);
        deEmployeeId = newEmployee("ACME-000002", "grace@acme.example", berlinId, EmploymentStatus.ACTIVE, null);
    }

    private void rate(String from, String on, String rate) {
        exchangeRateRepository.saveAndFlush(new ExchangeRate(from, "USD", new BigDecimal(rate), LocalDate.parse(on), "test"));
    }

    private void seedEurRates() {
        rate("EUR", "2020-01-01", "1.10000000");
        rate("EUR", "2022-01-01", "1.05000000");
        rate("EUR", "2024-01-01", "1.08000000");
    }

    private Long newEmployee(String code, String email, Long locationId, EmploymentStatus status, LocalDate terminated) {
        Employee e = new Employee(code, "Test", "Person", email, Gender.PREFER_NOT_TO_SAY, HIRE,
                EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME, new BigDecimal("1.000"), deptId, roleId, locationId, null);
        e.setEmploymentStatus(status);
        e.setTerminationDate(terminated);
        return employeeRepository.saveAndFlush(e).getId();
    }

    private SalarySummary record(Long employeeId, String effectiveFrom, String amount, PayFrequency frequency,
                                 ChangeReason reason) {
        return service.record(employeeId, new RecordSalaryRequest(LocalDate.parse(effectiveFrom), new BigDecimal(amount),
                frequency, null, reason, null), actorId);
    }

    private SalarySummary annual(Long employeeId, String effectiveFrom, String amount) {
        return record(employeeId, effectiveFrom, amount, PayFrequency.ANNUAL, ChangeReason.MERIT_INCREASE);
    }

    private SalaryRecord stored(Long id) {
        return salaryRecordRepository.findById(id).orElseThrow();
    }

    private List<AuditLog> auditFor(Long recordId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByChangedAtDesc("salary_record", recordId);
    }

    /**
     * The FR-3.2 invariant, checked from outside the service: starting at the hire date, the
     * non-empty intervals tile the timeline exactly -- each begins where the previous ended, none
     * overlap, and exactly the last is open-ended. Zero-length records (superseded corrections) are
     * history, not part of the tiling.
     */
    private void assertTimelineValid(Long employeeId) {
        List<SalaryRecord> all = new ArrayList<>(salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(employeeId));
        Collections.reverse(all);
        List<SalaryRecord> live = all.stream().filter(r -> !r.getEffectiveFrom().equals(r.getEffectiveTo())).toList();

        assertThat(live).isNotEmpty();
        assertThat(live.get(0).getEffectiveFrom()).as("history starts on the hire date").isEqualTo(HIRE);
        for (int i = 1; i < live.size(); i++) {
            assertThat(live.get(i).getEffectiveFrom()).as("record %d starts where the previous ended", i)
                    .isEqualTo(live.get(i - 1).getEffectiveTo());
        }
        assertThat(live.get(live.size() - 1).getEffectiveTo()).as("the last record is open-ended").isNull();
        assertThat(live.stream().filter(r -> r.getEffectiveTo() == null)).as("exactly one open record").hasSize(1);
    }

    // ---- first record, derived amounts -----------------------------------------------------

    @Test
    @DisplayName("FR-3.1/3.4: the first record persists the amount, the derived amounts and who created it")
    void firstRecord_persistsDerivedAmountsAndCreator() {
        SalarySummary created = service.record(usEmployeeId, new RecordSalaryRequest(HIRE, new BigDecimal("120000.00"),
                PayFrequency.ANNUAL, new BigDecimal("10.00"), ChangeReason.NEW_HIRE, "Initial offer"), actorId);

        SalaryRecord r = stored(created.id());
        assertThat(r.getEmployeeId()).isEqualTo(usEmployeeId);
        assertThat(r.getEffectiveFrom()).isEqualTo(HIRE);
        assertThat(r.getEffectiveTo()).isNull();
        assertThat(r.getBaseAmount()).isEqualByComparingTo("120000.00");
        assertThat(r.getCurrencyCode()).isEqualTo("USD");
        assertThat(r.getAnnualisedAmount()).isEqualByComparingTo("120000.00");
        assertThat(r.getAnnualisedAmountBaseCcy()).isEqualByComparingTo("120000.00");
        assertThat(r.getTargetBonusPct()).isEqualByComparingTo("10.00");
        assertThat(r.getChangeReason()).isEqualTo(ChangeReason.NEW_HIRE);
        assertThat(r.getNotes()).isEqualTo("Initial offer");
        assertThat(r.getCreatedBy()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("requirements.md 6.2: an omitted bonus percentage defaults to zero")
    void omittedBonus_defaultsToZero() {
        SalarySummary created = annual(usEmployeeId, "2020-01-01", "100000.00");

        assertThat(created.targetBonusPct()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("FR-3.4: an hourly rate is annualised with 2080 hours")
    void hourlyRate_isAnnualised() {
        SalarySummary created = record(usEmployeeId, "2020-01-01", "45.50", PayFrequency.HOURLY, ChangeReason.NEW_HIRE);

        assertThat(stored(created.id()).getAnnualisedAmount()).isEqualByComparingTo("94640.00");
        assertThat(stored(created.id()).getBaseAmount()).isEqualByComparingTo("45.50");
    }

    @Test
    @DisplayName("FR-3.4: a monthly amount is annualised with 12 months")
    void monthlyAmount_isAnnualised() {
        SalarySummary created = record(usEmployeeId, "2020-01-01", "8333.33", PayFrequency.MONTHLY, ChangeReason.NEW_HIRE);

        assertThat(stored(created.id()).getAnnualisedAmount()).isEqualByComparingTo("99999.96");
    }

    @Test
    @DisplayName("FR-3.4: the currency is the employee's local currency, taken from their location, never from the caller")
    void currency_comesFromTheEmployeesLocation() {
        seedEurRates();

        SalarySummary created = annual(deEmployeeId, "2020-01-01", "100000.00");

        assertThat(created.currencyCode()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("FR-3.4: the base-currency amount is the annualised amount converted at the rate in force")
    void baseCurrencyAmount_isConverted() {
        seedEurRates();

        SalarySummary created = annual(deEmployeeId, "2020-01-01", "100000.00");

        assertThat(created.annualisedAmount()).isEqualByComparingTo("100000.00");
        assertThat(created.annualisedAmountBaseCcy()).isEqualByComparingTo("110000.00");
    }

    @Test
    @DisplayName("FR-3.5: the rate used is the one effective on the record's effective date, not the latest rate")
    void fx_usesTheRateEffectiveOnTheEffectiveDate() {
        seedEurRates();
        annual(deEmployeeId, "2020-01-01", "100000.00");

        SalarySummary in2022 = annual(deEmployeeId, "2022-06-01", "100000.00");   // latest on/before = 1.05
        SalarySummary in2024 = annual(deEmployeeId, "2024-03-01", "100000.00");   // latest on/before = 1.08

        assertThat(in2022.annualisedAmountBaseCcy()).isEqualByComparingTo("105000.00");
        assertThat(in2024.annualisedAmountBaseCcy()).isEqualByComparingTo("108000.00");
    }

    @Test
    @DisplayName("FR-3.5: a back-dated record is converted at the older rate, not today's")
    void fx_backDatedRecordUsesTheHistoricalRate() {
        seedEurRates();
        annual(deEmployeeId, "2020-01-01", "100000.00");
        annual(deEmployeeId, "2024-03-01", "100000.00");

        SalarySummary backDated = annual(deEmployeeId, "2021-06-01", "100000.00");

        assertThat(backDated.annualisedAmountBaseCcy()).isEqualByComparingTo("110000.00"); // the 2020 rate
    }

    @Test
    @DisplayName("FR-3.5: with no rate on or before the date the request is refused, and nothing is written")
    void fx_withNoRate_isRefusedAndWritesNothing() {
        assertThatThrownBy(() -> annual(deEmployeeId, "2020-01-01", "100000.00"))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCode()).isEqualTo("EXCHANGE_RATE_UNAVAILABLE"));

        assertThat(salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(deEmployeeId)).isEmpty();
        entityManager.flush(); // a raw JDBC read cannot see inserts still pending in the session
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test
    @DisplayName("FR-3.5: a rate dated only after the effective date is not used")
    void fx_withOnlyALaterRate_isRefused() {
        rate("EUR", "2022-01-01", "1.05000000");

        assertThatThrownBy(() -> annual(deEmployeeId, "2020-01-01", "100000.00")).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a stored rate of zero is treated as unavailable rather than silently zeroing the salary")
    void fx_withAZeroRate_isRefused() {
        rate("EUR", "2019-12-31", "0.00000000");

        assertThatThrownBy(() -> annual(deEmployeeId, "2020-01-01", "100000.00"))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCode()).isEqualTo("EXCHANGE_RATE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("an employee already paid in the base currency needs no exchange rate at all")
    void baseCurrencyEmployee_needsNoRate() {
        SalarySummary created = annual(usEmployeeId, "2020-01-01", "120000.00");

        assertThat(created.annualisedAmountBaseCcy()).isEqualByComparingTo("120000.00");
    }

    @Test
    @DisplayName("NFR-2: an amount too large for NUMERIC(15,2) once annualised is a validation error, not a database error")
    void amountOutOfRange_isRejected() {
        assertThatThrownBy(() -> record(usEmployeeId, "2020-01-01", "9999999999.99", PayFrequency.HOURLY,
                ChangeReason.NEW_HIRE))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getCode()).isEqualTo("AMOUNT_OUT_OF_RANGE"));
    }

    // ---- raises ----------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.2: a raise closes the previous record on the effective date and opens an open-ended one")
    void raise_closesThePreviousRecordAndOpensTheNext() {
        SalarySummary first = annual(usEmployeeId, "2020-01-01", "100000.00");
        SalarySummary raise = annual(usEmployeeId, "2021-06-01", "110000.00");

        assertThat(stored(first.id()).getEffectiveTo()).isEqualTo(LocalDate.of(2021, 6, 1));
        assertThat(stored(raise.id()).getEffectiveFrom()).isEqualTo(LocalDate.of(2021, 6, 1));
        assertThat(stored(raise.id()).getEffectiveTo()).isNull();
        assertThat(salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(usEmployeeId)).hasSize(2);
        assertTimelineValid(usEmployeeId);
    }

    @Test
    @DisplayName("FR-3.6: a back-dated change is slotted into the middle of history with no gap or overlap")
    void backDatedChange_isSlottedIntoHistory() {
        SalarySummary first = annual(usEmployeeId, "2020-01-01", "100000.00");
        SalarySummary latest = annual(usEmployeeId, "2022-01-01", "120000.00");

        SalarySummary inserted = annual(usEmployeeId, "2021-06-01", "110000.00");

        assertThat(stored(first.id()).getEffectiveTo()).isEqualTo(LocalDate.of(2021, 6, 1));
        assertThat(stored(inserted.id()).getEffectiveTo()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(stored(latest.id()).getEffectiveTo()).isNull();
        assertTimelineValid(usEmployeeId);
    }

    @Test
    @DisplayName("FR-3.6: a future-dated change leaves today's record in force until that date")
    void futureDatedChange_closesTheCurrentRecordAtTheFutureDate() {
        SalarySummary current = annual(usEmployeeId, "2020-01-01", "100000.00");

        SalarySummary future = annual(usEmployeeId, "2030-01-01", "130000.00");

        assertThat(stored(current.id()).getEffectiveTo()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(stored(future.id()).getEffectiveFrom()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertTimelineValid(usEmployeeId);
    }

    @Test
    @DisplayName("assumption 4: a same-day change supersedes the old record, which stays as zero-length history")
    void sameDayChange_supersedesWithoutDeleting() {
        SalarySummary wrong = annual(usEmployeeId, "2020-01-01", "100000.00");

        SalarySummary correction = record(usEmployeeId, "2020-01-01", "105000.00", PayFrequency.ANNUAL,
                ChangeReason.CORRECTION);

        // The superseded record is still there -- cut to zero length, never deleted -- and the
        // database's EXCLUDE constraint accepted the empty range.
        assertThat(stored(wrong.id()).getEffectiveFrom()).isEqualTo(stored(wrong.id()).getEffectiveTo());
        assertThat(stored(wrong.id()).getBaseAmount()).isEqualByComparingTo("100000.00");
        assertThat(stored(correction.id()).getEffectiveTo()).isNull();
        assertThat(salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(usEmployeeId)).hasSize(2);
        assertTimelineValid(usEmployeeId);
    }

    @Test
    @DisplayName("FR-3.2: a scripted sequence of forward, back-dated, same-day and leap-day changes never breaks the timeline")
    void aSequenceOfMixedChanges_keepsTheTimelineValidAfterEveryStep() {
        String[] dates = {"2020-01-01", "2023-01-01", "2021-06-01", "2022-03-01", "2021-06-01", "2025-01-01",
                "2020-01-01", "2024-02-29", "2030-12-31", "2022-03-01"};
        int amount = 100000;
        for (String date : dates) {
            annual(usEmployeeId, date, (amount += 1000) + ".00");
            assertTimelineValid(usEmployeeId);
        }
    }

    // ---- bounds ----------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.2: the first record must start on the hire date -- a later start would leave a gap")
    void firstRecordAfterHire_isRejected() {
        assertThatThrownBy(() -> annual(usEmployeeId, "2020-02-01", "100000.00"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getCode()).isEqualTo("GAP_AFTER_HIRE"));
    }

    @Test
    @DisplayName("FR-3.2: a record cannot take effect before the hire date")
    void recordBeforeHire_isRejected() {
        annual(usEmployeeId, "2020-01-01", "100000.00");

        assertThatThrownBy(() -> annual(usEmployeeId, "2019-06-01", "90000.00"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getCode()).isEqualTo("EFFECTIVE_BEFORE_HIRE"));
    }

    @Test
    @DisplayName("a record cannot take effect after the employee was terminated")
    void recordAfterTermination_isRejected() {
        Long terminated = newEmployee("ACME-000009", "gone@acme.example", austinId, EmploymentStatus.TERMINATED,
                LocalDate.of(2024, 6, 30));
        annual(terminated, "2020-01-01", "100000.00");

        assertThatThrownBy(() -> annual(terminated, "2024-07-01", "110000.00"))
                .isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).getCode()).isEqualTo("EFFECTIVE_AFTER_TERMINATION"));
    }

    @Test
    @DisplayName("recording salary for an unknown employee is a not-found error")
    void unknownEmployee_isNotFound() {
        assertThatThrownBy(() -> annual(-1L, "2020-01-01", "100000.00")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- audit (FR-3.7) --------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.7: creating a record writes a CREATE audit entry attributed to the actor, with the after-state")
    void firstRecord_writesACreateAuditEntry() throws Exception {
        SalarySummary created = annual(usEmployeeId, "2020-01-01", "120000.00");

        List<AuditLog> entries = auditFor(created.id());

        assertThat(entries).hasSize(1);
        AuditLog entry = entries.get(0);
        assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(entry.getActorUserId()).isEqualTo(actorId);
        assertThat(entry.getBeforeState()).isNull();
        JsonNode after = objectMapper.readTree(entry.getAfterState());
        assertThat(after.get("baseAmount").decimalValue()).isEqualByComparingTo("120000.00");
        assertThat(after.get("effectiveFrom").asText()).isEqualTo("2020-01-01");
        assertThat(after.get("changeReason").asText()).isEqualTo("MERIT_INCREASE");
    }

    @Test
    @DisplayName("FR-3.7: a raise audits both the closed record (UPDATE, with before/after) and the new one (CREATE)")
    void raise_auditsTheClosedRecordAndTheNewOne() throws Exception {
        SalarySummary first = annual(usEmployeeId, "2020-01-01", "100000.00");
        SalarySummary raise = annual(usEmployeeId, "2021-06-01", "110000.00");

        List<AuditLog> firstEntries = auditFor(first.id());
        assertThat(firstEntries).extracting(AuditLog::getAction)
                .containsExactlyInAnyOrder(AuditAction.CREATE, AuditAction.UPDATE);
        AuditLog update = firstEntries.stream().filter(a -> a.getAction() == AuditAction.UPDATE).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(update.getBeforeState()).get("effectiveTo").isNull()).isTrue();
        assertThat(objectMapper.readTree(update.getAfterState()).get("effectiveTo").asText()).isEqualTo("2021-06-01");
        assertThat(update.getActorUserId()).isEqualTo(actorId);

        assertThat(auditFor(raise.id())).extracting(AuditLog::getAction).containsExactly(AuditAction.CREATE);
    }

    @Test
    @DisplayName("FR-3.7: every mutation is audited -- N changes to one employee leave a complete trail")
    void everyMutationIsAudited() {
        annual(usEmployeeId, "2020-01-01", "100000.00");   // 1 CREATE
        annual(usEmployeeId, "2021-01-01", "101000.00");   // 1 UPDATE + 1 CREATE
        annual(usEmployeeId, "2022-01-01", "102000.00");   // 1 UPDATE + 1 CREATE

        // 3 CREATEs (one per record) + 2 UPDATEs (one per closed record) = 5, and nothing else.
        entityManager.flush(); // a raw JDBC read cannot see inserts still pending in the session
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Integer.class)).isEqualTo(5);
    }

    // ---- history ---------------------------------------------------------------------------

    @Test
    @DisplayName("FR-3.1: the history is returned newest first")
    void history_isNewestFirst() {
        annual(usEmployeeId, "2020-01-01", "100000.00");
        annual(usEmployeeId, "2021-06-01", "110000.00");
        annual(usEmployeeId, "2022-09-01", "120000.00");

        assertThat(service.history(usEmployeeId)).extracting(SalarySummary::effectiveFrom)
                .containsExactly(LocalDate.of(2022, 9, 1), LocalDate.of(2021, 6, 1), LocalDate.of(2020, 1, 1));
    }

    @Test
    @DisplayName("FR-3.1: history for an unknown employee is a not-found error")
    void history_forUnknownEmployee_isNotFound() {
        assertThatThrownBy(() -> service.history(-1L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
