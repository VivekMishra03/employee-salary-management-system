package com.acme.salary.dto;

import com.acme.salary.model.PayBandAdherence;

import java.math.BigDecimal;

/**
 * FR-4.4: one named employee against their pay band, in LOCAL currency (a band is a local-market
 * figure). Band fields and {@code compaRatio} are null when there is no band; {@code compaRatio} is
 * amount / band mid-point to 2 places. Deliberately carries no gender (requirements.md assumption 3).
 */
public record PayBandEmployee(long employeeId, String employeeCode, String fullName, String jobTitle,
                              String jobLevel, String countryCode, String currencyCode,
                              BigDecimal annualisedAmount, BigDecimal bandMin, BigDecimal bandMid,
                              BigDecimal bandMax, BigDecimal compaRatio, PayBandAdherence adherence) {
}
