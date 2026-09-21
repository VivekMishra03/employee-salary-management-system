-- V11 — import_job (requirements.md section 6.2, FR-5.6): a failed upload must be diagnosable
-- after the fact.
-- No DEFAULT nextval(...) on id -- see V2__department.sql. The row counters carry no SQL DEFAULT
-- either: Hibernate writes every column explicitly, so a default could never fire; the entity
-- initialises them to zero.

CREATE SEQUENCE import_job_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE import_job (
    id          BIGINT PRIMARY KEY,
    filename    VARCHAR(255) NOT NULL,
    import_type VARCHAR(30)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    total_rows   INTEGER     NOT NULL,
    success_rows INTEGER     NOT NULL,
    failed_rows  INTEGER     NOT NULL,
    -- Nullable: a PENDING job has not started, and an unfinished one has not finished.
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_by  BIGINT       NOT NULL,

    CONSTRAINT fk_import_job_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT chk_import_job_status_valid
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    -- FR-5.1: a CSV of employees and/or salary changes.
    CONSTRAINT chk_import_job_type_valid CHECK (import_type IN ('EMPLOYEES', 'SALARY_CHANGES'))
);

ALTER SEQUENCE import_job_id_seq OWNED BY import_job.id;
