package com.acme.salary.model;

/**
 * FR-4.4: where an employee's annualised pay sits relative to the pay band for their role and
 * location. Band limits are inclusive, so pay exactly on the minimum or maximum is {@code WITHIN}.
 * {@code NO_BAND} means no band is in force for the role and location; such an employee is counted
 * separately and never guessed into another class.
 */
public enum PayBandAdherence {
    BELOW,
    WITHIN,
    ABOVE,
    NO_BAND
}
