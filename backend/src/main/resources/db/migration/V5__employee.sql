-- V5 — employee (requirements.md section 6.2), the system of record every other domain table
-- eventually references.
--
-- No DEFAULT nextval(...) on id -- see the comment in V2__department.sql. Every insert must go
-- through the JPA repository.
--
-- The three CHECK constraints below are the invariants requirements.md section 6.2 states in
-- prose; encoding them here means no code path -- including a future raw migration or manual
-- fix -- can violate them, the same reasoning as the salary-interval EXCLUDE constraint (FR-3.2).

CREATE SEQUENCE employee_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE employee (
    id                BIGINT PRIMARY KEY,
    employee_code     VARCHAR(20)  NOT NULL,
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    gender            VARCHAR(20),
    hire_date         DATE         NOT NULL,
    termination_date  DATE,
    employment_status VARCHAR(20)  NOT NULL,
    employment_type   VARCHAR(20)  NOT NULL,
    fte_ratio         NUMERIC(4,3) NOT NULL DEFAULT 1.000,
    department_id     BIGINT       NOT NULL,
    job_role_id       BIGINT       NOT NULL,
    location_id       BIGINT       NOT NULL,
    manager_id        BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_employee_code UNIQUE (employee_code),
    CONSTRAINT uq_employee_email UNIQUE (email),

    CONSTRAINT fk_employee_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_employee_job_role FOREIGN KEY (job_role_id) REFERENCES job_role (id),
    CONSTRAINT fk_employee_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT fk_employee_manager FOREIGN KEY (manager_id) REFERENCES employee (id),

    -- A terminated employee's last day cannot precede the day they started.
    CONSTRAINT chk_employee_termination_after_hire
        CHECK (termination_date IS NULL OR termination_date >= hire_date),

    -- Nobody reports to themselves.
    CONSTRAINT chk_employee_manager_not_self
        CHECK (manager_id IS NULL OR manager_id <> id),

    -- The two-way implication requirements.md states as "iff": TERMINATED status and a
    -- termination_date must appear together, or not at all.
    CONSTRAINT chk_employee_status_termination_consistency
        CHECK ((employment_status = 'TERMINATED') = (termination_date IS NOT NULL)),

    -- Java's @Enumerated(EnumType.STRING) rejects an unrecognised value only when Hibernate reads
    -- the row back, by which point a bad value from a manual correction, a future migration or a
    -- raw INSERT is already stored and every earlier constraint (including the consistency check
    -- above, which only special-cases the literal 'TERMINATED') is satisfied by it. These three
    -- CHECKs close that gap the same way the others do -- at write time, in the database.
    CONSTRAINT chk_employee_gender_valid
        CHECK (gender IS NULL OR gender IN ('FEMALE', 'MALE', 'NON_BINARY', 'PREFER_NOT_TO_SAY')),
    CONSTRAINT chk_employee_status_valid
        CHECK (employment_status IN ('ACTIVE', 'ON_LEAVE', 'TERMINATED')),
    CONSTRAINT chk_employee_type_valid
        CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT'))
);

ALTER SEQUENCE employee_id_seq OWNED BY employee.id;

CREATE INDEX idx_employee_department ON employee (department_id);
CREATE INDEX idx_employee_location ON employee (location_id);
CREATE INDEX idx_employee_job_role ON employee (job_role_id);
CREATE INDEX idx_employee_status ON employee (employment_status);
CREATE INDEX idx_employee_manager ON employee (manager_id);

-- FR-2.3: free-text search across name, employee code and email that stays responsive at 10,000
-- rows (NFR-1). A trigram GIN index over the concatenation, rather than four separate indexes plus
-- an application-side OR, is what lets a single similarity query serve the whole search box.
CREATE INDEX idx_employee_search ON employee
    USING gin ((first_name || ' ' || last_name || ' ' || employee_code || ' ' || email) gin_trgm_ops);
