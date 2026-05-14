-- V70__chat_attachments.sql — MULTIMODAL-MVP attachment metadata.
--
-- MULTIMODAL-MVP r2:
--   - W1: TIMESTAMPTZ (not TIMESTAMP) so Hibernate Instant <→ DB roundtrip
--     preserves UTC instant identity across timezones (java.md footgun #2).
--   - W2: FK session_id → t_session(id) ON DELETE CASCADE per tech-design
--     §"数据模型". When a session is deleted, attachment rows go with it.
--     Files on disk are cleaned by a separate orphan-sweep job (Phase 2
--     backlog); that's an OS-level concern, not enforced by the FK.

CREATE TABLE IF NOT EXISTS t_chat_attachment (
    id              VARCHAR(36) PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,
    user_id         BIGINT NOT NULL,
    seq_no          BIGINT,
    kind            VARCHAR(16) NOT NULL,
    mime_type       VARCHAR(128) NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    page_count      INTEGER,
    storage_path    TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'uploaded',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    bound_at        TIMESTAMPTZ,
    CONSTRAINT fk_chat_attachment_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chat_attachment_session
    ON t_chat_attachment(session_id, created_at);

CREATE INDEX IF NOT EXISTS idx_chat_attachment_session_seq
    ON t_chat_attachment(session_id, seq_no);
