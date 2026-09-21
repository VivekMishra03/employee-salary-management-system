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
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * requirements.md section 6.2 — the system of record. Outgoing FKs to department, job role and
 * location, plus the self-referencing manager link, are plain {@code Long} columns rather than
 * {@code @ManyToOne} associations, the same choice made for {@code Department.parentDepartmentId}
 * and for the same reason: the employee detail view (FR-2.5) needs one level of lookup, which the
 * service can do explicitly, and mapping the association would let a lazy traversal walk the
 * reporting chain by accident on a table sized for 10,000 rows.
 *
 * <p>The three CHECK constraints in V5__employee.sql are the enforcement; nothing here re-validates
 * them in Java, because a constraint that only Java knows about is exactly the gap FR-3.2's
 * database-level EXCLUDE constraint was chosen to avoid (section 6.3) — a manual correction or a
 * future code path must not be able to violate them.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "employee", uniqueConstraints = {
        @UniqueConstraint(name = "uq_employee_code", columnNames = "employee_code"),
        @UniqueConstraint(name = "uq_employee_email", columnNames = "email")
})
// @UniqueConstraint above is documentation only -- ddl-auto is none (NFR-5). The enforced
// constraints are uq_employee_code / uq_employee_email in V5__employee.sql.
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "employee_id_seq")
    @SequenceGenerator(name = "employee_id_seq", sequenceName = "employee_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "employee_code", nullable = false, length = 20)
    private String employeeCode;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Gender gender;

    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 20)
    private EmploymentStatus employmentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false, length = 20)
    private EmploymentType employmentType;

    @Column(name = "fte_ratio", nullable = false, precision = 4, scale = 3)
    private BigDecimal fteRatio;

    @Column(name = "department_id", nullable = false)
    private Long departmentId;

    @Column(name = "job_role_id", nullable = false)
    private Long jobRoleId;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @Column(name = "manager_id")
    private Long managerId;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // FR-2.6: optimistic locking. Spring Data checks this column on every UPDATE and throws
    // ObjectOptimisticLockingFailureException if it no longer matches the row in the database --
    // the defence against two HR users overwriting each other's edit to the same employee.
    @Version
    @Column(nullable = false)
    private Long version;

    protected Employee() {
        // JPA
    }

    public Employee(String employeeCode, String firstName, String lastName, String email, Gender gender,
                     LocalDate hireDate, EmploymentStatus employmentStatus, EmploymentType employmentType,
                     BigDecimal fteRatio, Long departmentId, Long jobRoleId, Long locationId, Long managerId) {
        this.employeeCode = employeeCode;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.gender = gender;
        this.hireDate = hireDate;
        this.employmentStatus = employmentStatus;
        this.employmentType = employmentType;
        // V5__employee.sql's "DEFAULT 1.000" can never fire: Hibernate always writes fte_ratio
        // explicitly, so a raw SQL default is not enough on its own (the same reasoning as the
        // removed DEFAULT nextval(...) on id, ADR review from M1.2). A null here -- e.g. from a
        // future FR-5 CSV import row that leaves the FTE column blank -- must resolve to the
        // documented default in code, not fail with a surprising NOT NULL violation.
        this.fteRatio = fteRatio != null ? fteRatio : new BigDecimal("1.000");
        this.departmentId = departmentId;
        this.jobRoleId = jobRoleId;
        this.locationId = locationId;
        this.managerId = managerId;
    }

    public Long getId() {
        return id;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public LocalDate getTerminationDate() {
        return terminationDate;
    }

    public void setTerminationDate(LocalDate terminationDate) {
        this.terminationDate = terminationDate;
    }

    public EmploymentStatus getEmploymentStatus() {
        return employmentStatus;
    }

    public void setEmploymentStatus(EmploymentStatus employmentStatus) {
        this.employmentStatus = employmentStatus;
    }

    public EmploymentType getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(EmploymentType employmentType) {
        this.employmentType = employmentType;
    }

    public BigDecimal getFteRatio() {
        return fteRatio;
    }

    public void setFteRatio(BigDecimal fteRatio) {
        this.fteRatio = fteRatio;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(Long departmentId) {
        this.departmentId = departmentId;
    }

    public Long getJobRoleId() {
        return jobRoleId;
    }

    public void setJobRoleId(Long jobRoleId) {
        this.jobRoleId = jobRoleId;
    }

    public Long getLocationId() {
        return locationId;
    }

    public void setLocationId(Long locationId) {
        this.locationId = locationId;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
