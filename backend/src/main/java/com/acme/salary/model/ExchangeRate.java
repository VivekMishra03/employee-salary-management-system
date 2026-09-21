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
 * requirements.md section 6.2 (FR-3.5) — a dated exchange rate. {@code rate} is {@code
 * NUMERIC(18,8)}, not money precision, because FX precision differs from money precision.
 */
@Entity
@Table(name = "exchange_rate")
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "exchange_rate_id_seq")
    @SequenceGenerator(name = "exchange_rate_id_seq", sequenceName = "exchange_rate_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "from_currency", nullable = false, length = 3)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 3)
    private String toCurrency;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false, length = 50)
    private String source;

    protected ExchangeRate() {
        // JPA
    }

    public ExchangeRate(String fromCurrency, String toCurrency, BigDecimal rate, LocalDate effectiveDate,
                        String source) {
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.rate = rate;
        this.effectiveDate = effectiveDate;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public String getSource() {
        return source;
    }
}
