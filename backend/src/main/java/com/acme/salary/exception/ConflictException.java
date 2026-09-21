package com.acme.salary.exception;

/**
 * A request that is well-formed but conflicts with the current state of the data: HTTP 409 with a
 * stable machine-readable {@code code} (requirements.md section 7). Subclasses name the specific
 * conflict; the handler needs to know only this type.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
