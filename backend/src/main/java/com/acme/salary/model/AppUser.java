package com.acme.salary.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * requirements.md section 6.2 — an HR system operator, kept deliberately separate from {@link
 * Employee}: an HR Manager is not necessarily an employee record, and conflating authentication
 * with HR data is a mistake to avoid from the start.
 *
 * <p>Built ahead of its original milestone slot because {@code salary_record.created_by} requires
 * it to exist first (docs/adr). Only persistence is in scope: {@code passwordHash} is stored
 * opaquely here and hashed/verified by FR-1's login flow at M2, not by this class.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "app_user", uniqueConstraints = @UniqueConstraint(name = "uq_app_user_email", columnNames = "email"))
// @UniqueConstraint above is documentation only -- ddl-auto is none (NFR-5). The enforced
// constraint is uq_app_user_email in V6__app_user.sql.
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_user_id_seq")
    @SequenceGenerator(name = "app_user_id_seq", sequenceName = "app_user_id_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppUserRole role;

    @Column(nullable = false)
    private boolean enabled;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {
        // JPA
    }

    public AppUser(String email, String passwordHash, String fullName, AppUserRole role, boolean enabled) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public AppUserRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
