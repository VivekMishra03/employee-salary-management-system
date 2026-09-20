-- V2 — department (requirements.md section 6.2).
--
-- The first domain table. code is the natural key departments are referred to by (CSV imports,
-- FR-5); id is the surrogate key everything else joins on. parent_department_id is nullable and
-- self-referencing to support an org hierarchy -- the top-level departments simply have no parent.

-- No DEFAULT nextval(...) on the id column: Hibernate's pooled sequence optimizer (allocationSize
-- matching INCREMENT BY, below) calls nextval() itself to claim a block of ids and hands them out
-- client-side. A raw INSERT relying on the column default landing between two of those nextval()
-- calls could claim an id inside a block Hibernate has already reserved -- a duplicate key. Every
-- insert must go through the JPA repository, including the FR-6.1 seed.
CREATE SEQUENCE department_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE department (
    id                   BIGINT PRIMARY KEY,
    code                 VARCHAR(20)  NOT NULL,
    name                 VARCHAR(150) NOT NULL,
    parent_department_id BIGINT,
    cost_center          VARCHAR(20),

    CONSTRAINT uq_department_code UNIQUE (code),
    CONSTRAINT fk_department_parent FOREIGN KEY (parent_department_id)
        REFERENCES department (id)
);

ALTER SEQUENCE department_id_seq OWNED BY department.id;

-- Every department-scoped query in FR-2.4 and FR-4.2 filters or groups by this FK once employee
-- exists; indexing it now costs nothing and avoids a forgotten index once the table has rows.
CREATE INDEX idx_department_parent ON department (parent_department_id);
