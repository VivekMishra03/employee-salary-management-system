package com.acme.salary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ACME salary management API.
 *
 * <p>See {@code requirements.md} for the specification and {@code CLAUDE.md} for the
 * engineering rules this codebase is built under.
 */
@SpringBootApplication
public class SalaryManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalaryManagementApplication.class, args);
    }
}
