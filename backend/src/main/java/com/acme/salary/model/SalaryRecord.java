package com.acme.salary.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * requirements.md section 6.2/6.3 — the temporal heart of the system. Salary is a sequence of
 * dated records, not a column on {@link Employee}; every FR-3 and FR-4 requirement depends on that
 * choice (section 6.3, modelling decision 1).
 *
 * <p>{@code employeeId} and {@code createdBy} are plain {@code Long} columns rather than {@code
 * @ManyToOne}, the same reasoning as {@link Employee}'s outgoing FKs: one level of lookup belongs
 * to the service, not to an accidental lazy traversal.
 *
 * <p>FR-3.2's invariant -- an employee's intervals never overlap -- is enforced in two places per
 * section 6.3: the database ({@code excl_salary_record_no_overlap} in V7__salary_record.sql, a
 * gist EXCLUDE constraint) and, later, the domain service that closes the prior record when a new
 * one opens. This class only carries the first half; the service arrives at a later milestone.
 *
 * <p>No {@code updatedAt} or {@code @Version}: the spec lists only {@code created_at} for this
 * table (unlike {@link Employee}), and a salary record is corrected by superseding it with a new
 * row, never by editing it in place (section 6.3) -- there is no "update" for optimistic locking
 * to protect.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "salary_record")
public class SalaryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "salary_record_id_seq")
    @SequenceGenerator(name = "salary_record_id_seq", sequenceName = "salary_record_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "base_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_frequency", nullable = false, length = 10)
    private PayFrequency payFrequency;

    @Column(name = "annualised_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal annualisedAmount;

    @Column(name = "annualised_amount_base_ccy", nullable = false, precision = 15, scale = 2)
    private BigDecimal annualisedAmountBaseCcy;

    @Column(name = "target_bonus_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetBonusPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_reason", nullable = false, length = 30)
    private ChangeReason changeReason;

    @Column
    private String notes;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SalaryRecord() {
        // JPA
    }

    public SalaryRecord(Long employeeId, LocalDate effectiveFrom, LocalDate effectiveTo, BigDecimal baseAmount,
                         String currencyCode, PayFrequency payFrequency, BigDecimal annualisedAmount,
                         BigDecimal annualisedAmountBaseCcy, BigDecimal targetBonusPct, ChangeReason changeReason,
                         String notes, Long createdBy) {
        this.employeeId = employeeId;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.baseAmount = baseAmount;
        this.currencyCode = currencyCode;
        this.payFrequency = payFrequency;
        this.annualisedAmount = annualisedAmount;
        this.annualisedAmountBaseCcy = annualisedAmountBaseCcy;
        this.targetBonusPct = targetBonusPct;
        this.changeReason = changeReason;
        this.notes = notes;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(BigDecimal baseAmount) {
        this.baseAmount = baseAmount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public PayFrequency getPayFrequency() {
        return payFrequency;
    }

    public BigDecimal getAnnualisedAmount() {
        return annualisedAmount;
    }

    public BigDecimal getAnnualisedAmountBaseCcy() {
        return annualisedAmountBaseCcy;
    }

    public BigDecimal getTargetBonusPct() {
        return targetBonusPct;
    }

    public ChangeReason getChangeReason() {
        return changeReason;
    }

    public String getNotes() {
        return notes;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
