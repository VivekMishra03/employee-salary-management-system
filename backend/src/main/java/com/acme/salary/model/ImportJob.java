package com.acme.salary.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * requirements.md section 6.2 (FR-5.6) — the record of one CSV upload, so a failed import is
 * diagnosable after the fact. Unlike most tables here it is mutable by nature: status and counters
 * change as the job runs. The counters start at zero in Java rather than via a SQL DEFAULT, which
 * Hibernate's explicit column writes would never let fire.
 */
@Entity
@Table(name = "import_job")
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "import_job_id_seq")
    @SequenceGenerator(name = "import_job_id_seq", sequenceName = "import_job_id_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 255)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_type", nullable = false, length = 30)
    private ImportType importType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportStatus status;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "success_rows", nullable = false)
    private int successRows;

    @Column(name = "failed_rows", nullable = false)
    private int failedRows;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    protected ImportJob() {
        // JPA
    }

    public ImportJob(String filename, ImportType importType, ImportStatus status, Long createdBy) {
        this.filename = filename;
        this.importType = importType;
        this.status = status;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public ImportType getImportType() {
        return importType;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public void setStatus(ImportStatus status) {
        this.status = status;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getSuccessRows() {
        return successRows;
    }

    public void setSuccessRows(int successRows) {
        this.successRows = successRows;
    }

    public int getFailedRows() {
        return failedRows;
    }

    public void setFailedRows(int failedRows) {
        this.failedRows = failedRows;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
