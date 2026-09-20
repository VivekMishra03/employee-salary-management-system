-- V7 — salary_record (requirements.md section 6.2/6.3), the temporal heart of the system.
--
-- No DEFAULT nextval(...) on id -- see the comment in V2__department.sql. Every insert must go
-- through the JPA repository.
--
-- The EXCLUDE constraint below is THE reason PostgreSQL was chosen over SQLite for this project
-- (ADR-0001) and the reason btree_gist was verified on the embedded engine (SchemaMigrationTest)
-- and on real Neon (ADR-0007) before this table was written. It enforces FR-3.2 -- that an
-- employee's salary intervals never overlap -- at the database itself, so no code path, migration
-- or manual correction can corrupt an employee's compensation history. Section 6.3 requires this
-- be enforced in two places; the service-level half (closing the prior record when a new one
-- opens) is domain logic, not schema, and arrives with the salary service at a later milestone.

CREATE SEQUENCE salary_record_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE salary_record (
    id                          BIGINT PRIMARY KEY,
    employee_id                 BIGINT         NOT NULL,
    effective_from              DATE           NOT NULL,
    effective_to                DATE,
    base_amount                 NUMERIC(15,2)  NOT NULL,
    currency_code                CHAR(3)        NOT NULL,
    pay_frequency                VARCHAR(10)    NOT NULL,
    annualised_amount            NUMERIC(15,2)  NOT NULL,
    annualised_amount_base_ccy   NUMERIC(15,2)  NOT NULL,
    target_bonus_pct             NUMERIC(5,2)   NOT NULL DEFAULT 0,
    change_reason                VARCHAR(30)    NOT NULL,
    notes                        TEXT,
    created_by                   BIGINT         NOT NULL,
    created_at                   TIMESTAMPTZ    NOT NULL,

    CONSTRAINT fk_salary_record_employee FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT fk_salary_record_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),

    CONSTRAINT chk_salary_record_base_amount_positive CHECK (base_amount > 0),

    CONSTRAINT chk_salary_record_pay_frequency_valid
        CHECK (pay_frequency IN ('ANNUAL', 'MONTHLY', 'HOURLY')),
    CONSTRAINT chk_salary_record_change_reason_valid
        CHECK (change_reason IN ('NEW_HIRE', 'MERIT_INCREASE', 'PROMOTION', 'MARKET_ADJUSTMENT',
                                  'ROLE_CHANGE', 'DEMOTION', 'CORRECTION')),

    -- FR-3.2, the invariant this table exists to protect: for one employee, the half-open
    -- interval [effective_from, effective_to) may never intersect another of their own intervals.
    -- effective_to = NULL ("still in force", FR-3.6) is modelled as an unbounded upper range by
    -- daterange's default bounds. Requires btree_gist to mix the equality column (employee_id)
    -- with the range column in one exclusion constraint.
    CONSTRAINT excl_salary_record_no_overlap EXCLUDE USING gist (
        employee_id WITH =,
        daterange(effective_from, effective_to) WITH &&
    )
);

ALTER SEQUENCE salary_record_id_seq OWNED BY salary_record.id;

-- Both indexes are exactly as requirements.md section 6.2 states them. The first serves the full-
-- history read (FR-3.1, findByEmployeeIdOrderByEffectiveFromDesc); the second, the far hotter
-- "what does this employee earn right now" lookup (FR-4's analytics, FR-2.5's detail view) without
-- scanning closed historical records. Not declared UNIQUE: the EXCLUDE constraint above already
-- makes at most one open (effective_to IS NULL) row per employee implicit, so a UNIQUE index here
-- would enforce nothing the schema doesn't already guarantee -- it would only be extra beyond what
-- the spec asks for.
CREATE INDEX idx_salary_record_employee_history ON salary_record (employee_id, effective_from DESC);
CREATE INDEX idx_salary_record_current ON salary_record (employee_id) WHERE effective_to IS NULL;
