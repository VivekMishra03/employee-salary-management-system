package com.acme.salary.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * requirements.md section 6.2 — a department in the org hierarchy.
 *
 * <p>{@code parentDepartmentId} is a plain foreign key column, not a {@code @ManyToOne} to a self
 * type. The employee directory (FR-2.5) needs a department's parent name for breadcrumbs, which is
 * a one-level lookup the service can do explicitly; mapping the association would let a lazy
 * traversal walk the whole hierarchy by accident.
 *
 * <p>Uses {@code SEQUENCE} generation with a pooled allocation size, not {@code IDENTITY}
 * (ADR-0006) — {@code IDENTITY} disables JDBC batching, and FR-6.1 seeds thousands of rows.
 */
// The @UniqueConstraint below is documentation, not enforcement: ddl-auto is none (NFR-5), so
// Hibernate never issues DDL from this annotation. The real constraint is uq_department_code in
// V2__department.sql. Keep the two in sync by hand -- there is no test that would catch drift.
@Entity
@Table(name = "department", uniqueConstraints = @UniqueConstraint(name = "uq_department_code", columnNames = "code"))
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "department_id_seq")
    @SequenceGenerator(name = "department_id_seq", sequenceName = "department_id_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "parent_department_id")
    private Long parentDepartmentId;

    @Column(name = "cost_center", length = 20)
    private String costCenter;

    protected Department() {
        // JPA
    }

    public Department(String code, String name, Long parentDepartmentId, String costCenter) {
        this.code = code;
        this.name = name;
        this.parentDepartmentId = parentDepartmentId;
        this.costCenter = costCenter;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Long getParentDepartmentId() {
        return parentDepartmentId;
    }

    public String getCostCenter() {
        return costCenter;
    }
}
