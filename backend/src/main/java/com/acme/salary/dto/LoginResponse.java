package com.acme.salary.dto;

import java.time.Instant;

/** FR-1.1: the bearer token and the instant it stops being valid (FR-1.3). */
public record LoginResponse(String accessToken, String tokenType, Instant expiresAt) {

    public static LoginResponse bearer(String accessToken, Instant expiresAt) {
        return new LoginResponse(accessToken, "Bearer", expiresAt);
    }
}
