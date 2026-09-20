package com.acme.salary.jobrole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * requirements.md section 6.2 — a job role. {@code jobLevel} is a real column rather than text
 * parsed from {@code title}, because FR-4.2 and FR-4.5 group pay analytics by it directly.
 */
// The @UniqueConstraint below is documentation, not enforcement: ddl-auto is none (NFR-5), so
// Hibernate never issues DDL from this annotation. The real constraint is
// uq_job_role_title_level in V4__job_role.sql. Keep the two in sync by hand -- there is no test
// that would catch drift.
@Entity
@Table(name = "job_role", uniqueConstraints =
        @UniqueConstraint(name = "uq_job_role_title_level", columnNames = {"title", "job_level"}))
public class JobRole {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "job_role_id_seq")
    @SequenceGenerator(name = "job_role_id_seq", sequenceName = "job_role_id_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(name = "job_family", nullable = false, length = 100)
    private String jobFamily;

    @Column(name = "job_level", nullable = false, length = 10)
    private String jobLevel;

    protected JobRole() {
        // JPA
    }

    public JobRole(String title, String jobFamily, String jobLevel) {
        this.title = title;
        this.jobFamily = jobFamily;
        this.jobLevel = jobLevel;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getJobFamily() {
        return jobFamily;
    }

    public String getJobLevel() {
        return jobLevel;
    }
}
