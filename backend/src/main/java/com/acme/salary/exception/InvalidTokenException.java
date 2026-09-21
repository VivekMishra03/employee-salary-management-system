package com.acme.salary.exception;

/**
 * FR-1.2/FR-1.3: a bearer token that is malformed, tampered with, unsigned, expired or missing
 * required claims. Deliberately one type with no detail about *which* check failed: the reason is
 * useful to an attacker probing the API and useless to a legitimate client, which can only act by
 * signing in again.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidTokenException(String message) {
        super(message);
    }
}
