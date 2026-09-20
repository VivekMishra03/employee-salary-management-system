package com.acme.salary.model;

/**
 * requirements.md section 6.2 (FR-3.3) — the "why" a salary changed. This is the field Excel
 * loses and this system exists to keep.
 */
public enum ChangeReason {
    NEW_HIRE,
    MERIT_INCREASE,
    PROMOTION,
    MARKET_ADJUSTMENT,
    ROLE_CHANGE,
    DEMOTION,
    CORRECTION
}
