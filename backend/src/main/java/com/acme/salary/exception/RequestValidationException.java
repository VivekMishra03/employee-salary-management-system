package com.acme.salary.exception;

/**
 * A business-rule violation in a request that already passed Bean Validation (an id that does not
 * exist, an invalid sort field, an employee set as their own manager): HTTP 400 with a stable
 * {@code code} and the offending {@code field}, in the same {@code errors} shape as {@code
 * VALIDATION_FAILED} so a client handles both the same way. Never carries the rejected value.
 */
public class RequestValidationException extends RuntimeException {

    private final String code;
    private final String field;

    public RequestValidationException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String getCode() {
        return code;
    }

    public String getField() {
        return field;
    }
}
