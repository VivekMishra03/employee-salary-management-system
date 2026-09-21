package com.acme.salary.dto;

import com.acme.salary.model.ChangeReason;
import com.acme.salary.model.PayFrequency;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FR-3.2 - FR-3.6: record a salary change.
 *
 * <p>Deliberately absent: the currency (always the employee's local currency, taken from their
 * location -- FR-3.4), the annualised and base-currency amounts (derived), and who made the change
 * (taken from the authenticated user, never from the request -- FR-3.7).
 *
 * <p>{@code baseAmount} allows 13 integer digits to match {@code NUMERIC(15,2)}; a value that is
 * valid here but overflows once annualised is rejected by the service with {@code AMOUNT_OUT_OF_RANGE}.
 */
public record RecordSalaryRequest(
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal baseAmount,
        @NotNull PayFrequency payFrequency,
        @DecimalMin("0.00") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2) BigDecimal targetBonusPct,
        @NotNull ChangeReason changeReason,
        @Size(max = 2000) String notes) {
}
