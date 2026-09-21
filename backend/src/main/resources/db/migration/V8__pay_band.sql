-- V8 — pay_band (requirements.md section 6.2), the input to FR-4.4 pay-band adherence.
-- Keyed by role AND location: a band is meaningless without a market (section 6.3, decision 6).
-- No DEFAULT nextval(...) on id -- see V2__department.sql.

CREATE SEQUENCE pay_band_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE pay_band (
    id             BIGINT PRIMARY KEY,
    job_role_id    BIGINT        NOT NULL,
    location_id    BIGINT        NOT NULL,
    currency_code  CHAR(3)       NOT NULL,
    min_amount     NUMERIC(15,2) NOT NULL,
    mid_amount     NUMERIC(15,2) NOT NULL,
    max_amount     NUMERIC(15,2) NOT NULL,
    effective_from DATE          NOT NULL,
    effective_to   DATE,

    CONSTRAINT fk_pay_band_job_role FOREIGN KEY (job_role_id) REFERENCES job_role (id),
    CONSTRAINT fk_pay_band_location FOREIGN KEY (location_id) REFERENCES location (id),
    CONSTRAINT uq_pay_band_role_location_from UNIQUE (job_role_id, location_id, effective_from),
    CONSTRAINT chk_pay_band_min_mid_max CHECK (min_amount <= mid_amount AND mid_amount <= max_amount)
);

ALTER SEQUENCE pay_band_id_seq OWNED BY pay_band.id;
