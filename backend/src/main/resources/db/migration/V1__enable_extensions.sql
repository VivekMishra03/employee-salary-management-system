-- V1 — PostgreSQL extensions the schema depends on.
--
-- Separated from the table migrations deliberately: these are capabilities of the database rather
-- than part of the domain model, and if an environment cannot provide them we want that failure on
-- the very first migration with an obvious cause, not buried in a CREATE TABLE three files later.
--
-- Migrations are immutable once committed (CLAUDE.md section 7). Fix forward with a new file.

-- btree_gist lets a GiST exclusion constraint mix an equality column with a range column, i.e.
--     EXCLUDE USING gist (employee_id WITH =, daterange(effective_from, effective_to) WITH &&)
-- That constraint is how FR-3.2 -- "salary intervals for one employee never overlap" -- is enforced
-- by the database itself, so no code path, migration or manual correction can corrupt an employee's
-- compensation history. requirements.md section 11 lists its absence as a project risk.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- pg_trgm provides trigram indexing for the free-text employee search in FR-2.3, which has to stay
-- responsive across 10,000 rows (NFR-1) without a LIKE '%...%' sequential scan.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
