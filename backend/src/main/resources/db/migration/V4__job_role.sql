-- V4 — job_role (requirements.md section 6.2).
--
-- job_level is a real column, not parsed out of title, because FR-4.2 and FR-4.5 group analytics
-- by it directly and a title-parsing approach would break the moment a title does not follow the
-- convention. The same title recurs at multiple levels ("Software Engineer" at L3, L4, L5...),
-- which is exactly why the unique constraint is on the pair.

-- No DEFAULT nextval(...) on the id column -- see the comment in V2__department.sql. Every insert
-- must go through the JPA repository.
CREATE SEQUENCE job_role_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE job_role (
    id         BIGINT PRIMARY KEY,
    title      VARCHAR(150) NOT NULL,
    job_family VARCHAR(100) NOT NULL,
    job_level  VARCHAR(10)  NOT NULL,

    CONSTRAINT uq_job_role_title_level UNIQUE (title, job_level)
);

ALTER SEQUENCE job_role_id_seq OWNED BY job_role.id;

-- FR-4.2 and FR-4.5 group by job_level across the whole employee table; indexed now, before there
-- is data, so the index build is instant rather than a lock-holding operation on a live table.
CREATE INDEX idx_job_role_level ON job_role (job_level);
