package com.acme.salary.dto;

/**
 * The authenticated principal placed in the security context by the JWT filter. Carries the user id
 * as well as the email because later milestones need it for {@code created_by} and the audit trail
 * (FR-3.7): resolving it from the email would cost an extra query on every mutation.
 *
 * <p>Lives in {@code dto} because ADR-0008 fixes the package set and this is a plain data carrier
 * passed between the filter and controllers.
 */
public record AuthenticatedUser(Long userId, String email) {
}
