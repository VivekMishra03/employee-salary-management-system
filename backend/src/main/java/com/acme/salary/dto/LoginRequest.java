package com.acme.salary.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * FR-1.1. Both fields are length-bounded: BCrypt only uses the first 72 bytes anyway, and an
 * unbounded password field is a cheap way to make the server hash megabytes.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 128) String password) {
}
