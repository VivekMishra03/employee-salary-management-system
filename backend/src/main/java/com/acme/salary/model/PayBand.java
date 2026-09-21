package com.acme.salary.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * requirements.md section 6.2 — the salary band for a role in a location, the input to FR-4.4
 * (compa-ratio = annualised amount / mid). {@code jobRoleId}/{@code locationId} are plain {@code
 * Long} columns for the same reason as on {@link Employee}. The CHECK and UNIQUE constraints live
 * in V8__pay_band.sql; nothing here re-validates them.
 */
@Entity
@Table(name = "pay_band")
public class PayBand {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "pay_band_id_seq")
    @SequenceGenerator(name = "pay_band_id_seq", sequenceName = "pay_band_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "job_role_id", nullable = false)
    private Long jobRoleId;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "min_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "mid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal midAmount;

    @Column(name = "max_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal maxAmount;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected PayBand() {
        // JPA
    }

    public PayBand(Long jobRoleId, Long locationId, String currencyCode, BigDecimal minAmount,
                   BigDecimal midAmount, BigDecimal maxAmount, LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.jobRoleId = jobRoleId;
        this.locationId = locationId;
        this.currencyCode = currencyCode;
        this.minAmount = minAmount;
        this.midAmount = midAmount;
        this.maxAmount = maxAmount;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    public Long getId() {
        return id;
    }

    public Long getJobRoleId() {
        return jobRoleId;
    }

    public Long getLocationId() {
        return locationId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMidAmount() {
        return midAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
