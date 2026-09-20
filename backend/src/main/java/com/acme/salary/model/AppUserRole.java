package com.acme.salary.model;

/**
 * requirements.md section 6.2 and assumption 1: one organisation, one persona (the HR Manager), so
 * a single role. A real enum rather than a boolean or a bare string, so a second role is an added
 * constant plus a migration, not a design change.
 */
public enum AppUserRole {
    HR_MANAGER
}
