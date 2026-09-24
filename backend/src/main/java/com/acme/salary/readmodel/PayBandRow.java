package com.acme.salary.readmodel;

import com.acme.salary.model.PayBandAdherence;

import java.math.BigDecimal;

/**
 * FR-4.4: one employee against their pay band, in LOCAL currency (the band is local). The band
 * fields and {@code compaRatio} are null when there is no band ({@code NO_BAND}); {@code compaRatio}
 * is also null when the band's mid-point is zero.
 */
public record PayBandRow(long employeeId, String employeeCode, String fullName, String jobTitle,
                         String jobLevel, String countryCode, String currencyCode,
                         BigDecimal annualisedAmount, BigDecimal bandMin, BigDecimal bandMid,
                         BigDecimal bandMax, BigDecimal compaRatio, PayBandAdherence adherence) {
}
