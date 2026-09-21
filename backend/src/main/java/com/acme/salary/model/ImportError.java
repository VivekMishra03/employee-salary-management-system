package com.acme.salary.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * requirements.md section 6.2 (FR-5.4) — one row-level failure from an {@link ImportJob}. Drives the
 * downloadable error report, so it carries the row number, the offending column (absent for a
 * whole-row failure), a stable error code, a human-readable message and the raw input row.
 */
@Entity
@Table(name = "import_error")
public class ImportError {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "import_error_id_seq")
    @SequenceGenerator(name = "import_error_id_seq", sequenceName = "import_error_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "import_job_id", nullable = false)
    private Long importJobId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "column_name", length = 100)
    private String columnName;

    @Column(name = "error_code", nullable = false, length = 50)
    private String errorCode;

    @Column(nullable = false)
    private String message;

    @Column(name = "raw_row")
    private String rawRow;

    protected ImportError() {
        // JPA
    }

    public ImportError(Long importJobId, int rowNumber, String columnName, String errorCode, String message,
                       String rawRow) {
        this.importJobId = importJobId;
        this.rowNumber = rowNumber;
        this.columnName = columnName;
        this.errorCode = errorCode;
        this.message = message;
        this.rawRow = rawRow;
    }

    public Long getId() {
        return id;
    }

    public Long getImportJobId() {
        return importJobId;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public String getRawRow() {
        return rawRow;
    }
}
