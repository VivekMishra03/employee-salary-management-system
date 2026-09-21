-- V9 — exchange_rate (requirements.md section 6.2, FR-3.5).
-- A stored, dated table: never a hardcoded constant and never a live third-party call at request
-- time. NUMERIC(18,8) because FX precision differs from money precision.
-- No DEFAULT nextval(...) on id -- see V2__department.sql.

CREATE SEQUENCE exchange_rate_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE exchange_rate (
    id             BIGINT PRIMARY KEY,
    from_currency  CHAR(3)       NOT NULL,
    to_currency    CHAR(3)       NOT NULL,
    rate           NUMERIC(18,8) NOT NULL,
    effective_date DATE          NOT NULL,
    source         VARCHAR(50)   NOT NULL,

    CONSTRAINT uq_exchange_rate_pair_date UNIQUE (from_currency, to_currency, effective_date)
);

ALTER SEQUENCE exchange_rate_id_seq OWNED BY exchange_rate.id;
