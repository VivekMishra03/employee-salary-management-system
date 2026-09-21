package com.acme.salary.exception;

/** A unique value (employee code, email) is already taken. The message never echoes the value. */
public class DuplicateResourceException extends ConflictException {

    public DuplicateResourceException(String code, String message) {
        super(code, message);
    }
}
