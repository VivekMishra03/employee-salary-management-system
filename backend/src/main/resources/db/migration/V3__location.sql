-- V3 — location (requirements.md section 6.2).
--
-- The link between an employee and their local currency (FR-3.4) and the axis FR-4.2 groups pay
-- comparisons by country. currency_code is not a foreign key to a currency table -- the ISO 4217
-- code is the identifier and exchange_rate (a later migration) references it the same way.

-- No DEFAULT nextval(...) on the id column -- see the comment in V2__department.sql. Every insert
-- must go through the JPA repository.
CREATE SEQUENCE location_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE location (
    id            BIGINT PRIMARY KEY,
    country_code  CHAR(2)      NOT NULL,
    country_name  VARCHAR(100) NOT NULL,
    city          VARCHAR(100) NOT NULL,
    currency_code CHAR(3)      NOT NULL,

    CONSTRAINT uq_location_country_city UNIQUE (country_code, city)
);

ALTER SEQUENCE location_id_seq OWNED BY location.id;
