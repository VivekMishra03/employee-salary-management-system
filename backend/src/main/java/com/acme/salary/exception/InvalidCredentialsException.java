package com.acme.salary.exception;

/**
 * FR-1.1: sign-in failed. The same exception and the same fixed message cover an unknown email, a
 * wrong password and a disabled account, so a caller cannot use the response to discover which
 * emails have accounts.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
