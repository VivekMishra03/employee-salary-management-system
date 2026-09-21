-- V12 — import_error (requirements.md section 6.2, FR-5.4): drives the downloadable error report.
-- No DEFAULT nextval(...) on id -- see V2__department.sql.

CREATE SEQUENCE import_error_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE import_error (
    id            BIGINT PRIMARY KEY,
    import_job_id BIGINT       NOT NULL,
    row_number    INTEGER      NOT NULL,
    -- Nullable: a row-level failure (e.g. a duplicate employee code) has no single offending column.
    column_name   VARCHAR(100),
    error_code    VARCHAR(50)  NOT NULL,
    message       TEXT         NOT NULL,
    raw_row       TEXT,

    CONSTRAINT fk_import_error_job FOREIGN KEY (import_job_id) REFERENCES import_job (id)
);

ALTER SEQUENCE import_error_id_seq OWNED BY import_error.id;

-- The one read: a job's errors in row order, for the report download.
CREATE INDEX idx_import_error_job_row ON import_error (import_job_id, row_number);
