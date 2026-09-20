package com.acme.salary.model;

/**
 * requirements.md section 6.2 and assumption 3: optional, self-declared, used only for the
 * gender-pay-gap analytics in FR-4.5 -- never shown on the employee directory list.
 */
public enum Gender {
    FEMALE,
    MALE,
    NON_BINARY,
    PREFER_NOT_TO_SAY
}
