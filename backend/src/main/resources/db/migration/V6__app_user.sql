-- V6 — app_user (requirements.md section 6.2).
--
-- Built ahead of the original milestone sketch: salary_record.created_by is a required FK to this
-- table, so app_user must exist first. Kept deliberately separate from employee -- an HR system
-- operator is not necessarily an employee record, and conflating authentication with HR data is a
-- mistake to avoid from the start (section 6.2's own reasoning).
--
-- No DEFAULT nextval(...) on id -- see the comment in V2__department.sql. Every insert must go
-- through the JPA repository.
--
-- Only the column set is built now. BCrypt hashing, login and JWT issuance are FR-1 / M2.

CREATE SEQUENCE app_user_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE app_user (
    id            BIGINT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    -- BCrypt hashes are 60 characters; sized wider so a future hashing algorithm change is a
    -- data migration, not a schema one.
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_app_user_email UNIQUE (email),

    -- Same reasoning as employee's enum CHECKs (V5): Java's enum type prevents an invalid role
    -- through the entity API, but not through a manual correction or a future migration.
    CONSTRAINT chk_app_user_role_valid CHECK (role IN ('HR_MANAGER'))
);

ALTER SEQUENCE app_user_id_seq OWNED BY app_user.id;
