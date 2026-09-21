package com.acme.salary.service;

import com.acme.salary.config.CompensationProperties;
import com.acme.salary.dto.EmployeeDetail.SalarySummary;
import com.acme.salary.dto.RecordSalaryRequest;
import com.acme.salary.exception.ConflictException;
import com.acme.salary.exception.RequestValidationException;
import com.acme.salary.exception.ResourceNotFoundException;
import com.acme.salary.model.AuditAction;
import com.acme.salary.model.Employee;
import com.acme.salary.model.ExchangeRate;
import com.acme.salary.model.Location;
import com.acme.salary.model.SalaryRecord;
import com.acme.salary.repository.EmployeeRepository;
import com.acme.salary.repository.ExchangeRateRepository;
import com.acme.salary.repository.LocationRepository;
import com.acme.salary.repository.SalaryRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-3: records salary changes and serves the history.
 *
 * <p>Recording a change is one transaction: derive the amounts, close the record in force on the
 * effective date, insert the new record, and write the audit entries. If any step fails -- including
 * the audit write -- all of it rolls back, so a raise is never left half-applied (FR-3.2 "atomically";
 * proved by {@code SalaryAtomicityTest}).
 *
 * <p>The pure decisions live elsewhere and are unit-tested without a database:
 * {@link SalaryTimeline} (which record to close, where the new one ends) and {@link SalaryCalculator}
 * (annualisation, conversion, rounding). This class orchestrates them against the database.
 */
@Service
@Transactional(readOnly = true)
public class SalaryService {

    static final String ENTITY_TYPE = "salary_record";

    /** Largest value a NUMERIC(15,2) column can hold: 13 integer digits and 2 decimals. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");

    private final EmployeeRepository employeeRepository;
    private final LocationRepository locationRepository;
    private final SalaryRecordRepository salaryRecordRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final AuditLogService auditLogService;
    private final CompensationProperties compensationProperties;

    public SalaryService(EmployeeRepository employeeRepository, LocationRepository locationRepository,
                         SalaryRecordRepository salaryRecordRepository, ExchangeRateRepository exchangeRateRepository,
                         AuditLogService auditLogService, CompensationProperties compensationProperties) {
        this.employeeRepository = employeeRepository;
        this.locationRepository = locationRepository;
        this.salaryRecordRepository = salaryRecordRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.auditLogService = auditLogService;
        this.compensationProperties = compensationProperties;
    }

    /** FR-3.1: the full history, newest first. Superseded (zero-length) records are included. */
    public List<SalarySummary> history(Long employeeId) {
        requireEmployee(employeeId);
        return salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(employeeId).stream()
                .map(SalaryMapper::toSummary).toList();
    }

    /**
     * FR-3.2 - FR-3.7: records a salary change effective on {@code request.effectiveFrom()}.
     *
     * @param actorUserId the authenticated user making the change; recorded as {@code created_by}
     *                    and as the actor on every audit entry
     */
    @Transactional
    public SalarySummary record(Long employeeId, RecordSalaryRequest request, Long actorUserId) {
        Employee employee = requireEmployee(employeeId);
        // FR-3.4: the salary is held in the employee's local currency, so it comes from their
        // location -- never from the caller, who could otherwise book a salary in the wrong currency.
        String currency = locationRepository.findById(employee.getLocationId()).map(Location::getCurrencyCode)
                .orElseThrow();

        List<SalaryRecord> history = salaryRecordRepository.findByEmployeeIdOrderByEffectiveFromDesc(employeeId);
        SalaryTimeline.Plan plan = SalaryTimeline.plan(employee.getHireDate(), employee.getTerminationDate(),
                history.stream().map(r -> new SalaryTimeline.Interval(r.getId(), r.getEffectiveFrom(), r.getEffectiveTo()))
                        .toList(),
                request.effectiveFrom());

        // Everything that can be refused is decided before the first write.
        BigDecimal annualised = SalaryCalculator.annualise(request.baseAmount(), request.payFrequency());
        requireInRange(annualised);
        BigDecimal annualisedBase = SalaryCalculator.convert(annualised, rateToBaseCurrency(currency, request.effectiveFrom()));
        requireInRange(annualisedBase);

        if (plan.recordToCloseId() != null) {
            SalaryRecord closing = history.stream().filter(r -> r.getId().equals(plan.recordToCloseId())).findFirst()
                    .orElseThrow();
            Map<String, Object> before = snapshot(closing);
            closing.setEffectiveTo(request.effectiveFrom());
            // Flushed BEFORE the new record is inserted, deliberately. Hibernate normally runs a
            // flush's INSERTs before its UPDATEs, and the new record overlaps the old one until the
            // old one is shortened -- the EXCLUDE constraint (FR-3.2) would reject the insert. The
            // explicit flush forces "shorten, then insert".
            salaryRecordRepository.saveAndFlush(closing);
            auditLogService.record(ENTITY_TYPE, closing.getId(), AuditAction.UPDATE, actorUserId, before, snapshot(closing));
        }

        SalaryRecord created = salaryRecordRepository.saveAndFlush(new SalaryRecord(employeeId, request.effectiveFrom(),
                plan.newEffectiveTo(), request.baseAmount(), currency, request.payFrequency(), annualised, annualisedBase,
                request.targetBonusPct() == null ? BigDecimal.ZERO : request.targetBonusPct(), request.changeReason(),
                request.notes(), actorUserId));
        auditLogService.record(ENTITY_TYPE, created.getId(), AuditAction.CREATE, actorUserId, null, snapshot(created));
        return SalaryMapper.toSummary(created);
    }

    private Employee requireEmployee(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
    }

    /**
     * FR-3.5: the rate effective on the record's effective date -- the latest one on or before it,
     * so a back-dated record is converted at the rate of its time, not today's. Only direct
     * {@code currency -> base} rates are used; an inverse is never derived. A missing or non-positive
     * rate is reported as unavailable rather than converting at a guess.
     */
    private BigDecimal rateToBaseCurrency(String currency, LocalDate effectiveFrom) {
        String base = compensationProperties.baseCurrency();
        if (currency.equals(base)) {
            return BigDecimal.ONE;
        }
        return exchangeRateRepository
                .findFirstByFromCurrencyAndToCurrencyAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
                        currency, base, effectiveFrom)
                .map(ExchangeRate::getRate)
                .filter(rate -> rate.signum() > 0)
                .orElseThrow(() -> new ConflictException("EXCHANGE_RATE_UNAVAILABLE",
                        "No " + currency + " to " + base + " exchange rate is available on or before " + effectiveFrom));
    }

    private static void requireInRange(BigDecimal amount) {
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new RequestValidationException("AMOUNT_OUT_OF_RANGE", "baseAmount",
                    "The resulting annual amount is too large to store");
        }
    }

    /** The audited state of a record. Explicit and stable rather than a reflective dump of the entity. */
    private static Map<String, Object> snapshot(SalaryRecord r) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("employeeId", r.getEmployeeId());
        state.put("effectiveFrom", r.getEffectiveFrom());
        state.put("effectiveTo", r.getEffectiveTo());
        state.put("baseAmount", r.getBaseAmount());
        state.put("currencyCode", r.getCurrencyCode());
        state.put("payFrequency", r.getPayFrequency());
        state.put("annualisedAmount", r.getAnnualisedAmount());
        state.put("annualisedAmountBaseCcy", r.getAnnualisedAmountBaseCcy());
        state.put("targetBonusPct", r.getTargetBonusPct());
        state.put("changeReason", r.getChangeReason());
        state.put("notes", r.getNotes());
        return state;
    }
}
