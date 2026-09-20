package com.acme.salary.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * requirements.md section 6.2 — a location: the pairing of country and city that determines an
 * employee's local currency (FR-3.4) and pay band market (pay_band is keyed by role AND location,
 * per the modelling decisions in section 6.3).
 */
// The @UniqueConstraint below is documentation, not enforcement: ddl-auto is none (NFR-5), so
// Hibernate never issues DDL from this annotation. The real constraint is
// uq_location_country_city in V3__location.sql. Keep the two in sync by hand -- there is no test
// that would catch drift.
@Entity
@Table(name = "location", uniqueConstraints =
        @UniqueConstraint(name = "uq_location_country_city", columnNames = {"country_code", "city"}))
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "location_id_seq")
    @SequenceGenerator(name = "location_id_seq", sequenceName = "location_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "country_name", nullable = false, length = 100)
    private String countryName;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    protected Location() {
        // JPA
    }

    public Location(String countryCode, String countryName, String city, String currencyCode) {
        this.countryCode = countryCode;
        this.countryName = countryName;
        this.city = city;
        this.currencyCode = currencyCode;
    }

    public Long getId() {
        return id;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getCountryName() {
        return countryName;
    }

    public String getCity() {
        return city;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
