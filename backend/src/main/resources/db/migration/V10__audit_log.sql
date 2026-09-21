-- V10 — audit_log (requirements.md section 6.2, FR-3.7, NFR-7).
-- Append-only: the spec says no update or delete path exists *in code*, which the entity and
-- repository enforce (no setters; a repository exposing only save and reads).
-- No DEFAULT nextval(...) on id -- see V2__department.sql.

CREATE SEQUENCE audit_log_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE audit_log (
    id            BIGINT PRIMARY KEY,
    entity_type   VARCHAR(50)  NOT NULL,
    entity_id     BIGINT       NOT NULL,
    action        VARCHAR(10)  NOT NULL,
    -- NOT NULL: NFR-7 requires every mutation be attributable to a user.
    actor_user_id BIGINT       NOT NULL,
    changed_at    TIMESTAMPTZ  NOT NULL,
    -- Nullable by nature: a CREATE has no before-state, a DELETE has no after-state.
    before_state  JSONB,
    after_state   JSONB,

    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id),
    CONSTRAINT chk_audit_log_action_valid CHECK (action IN ('CREATE', 'UPDATE', 'DELETE'))
);

ALTER SEQUENCE audit_log_id_seq OWNED BY audit_log.id;

-- The only read this table serves is "what happened to this entity", newest first.
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id, changed_at DESC);
