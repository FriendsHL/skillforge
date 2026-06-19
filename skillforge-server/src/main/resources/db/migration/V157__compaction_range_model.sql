-- V157__compaction_range_model.sql — COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P1
--
-- Additive foundation for the range-based compaction model (storage-redesign.md §2.2).
-- P1 is flag-gated (skillforge.compact.range-model.enabled, default false): this migration
-- only creates the schema; no production read/write behavior changes until the flag is on.
--
-- t_session_summary holds range summaries (model-view-only; the user view never reads it).
-- t_session_message.compacted_by_summary_id is a denormalized marker (recomputable from
-- summary ranges in P2's rewrite path — INV-5) so the user view can flag covered rows while
-- the model view skips them.

CREATE TABLE t_session_summary (
    id                      BIGSERIAL    NOT NULL PRIMARY KEY,
    session_id              VARCHAR(36)  NOT NULL,
    start_seq               BIGINT       NOT NULL,
    end_seq                 BIGINT       NOT NULL,
    summary_text            TEXT         NOT NULL,
    level                   VARCHAR(16)  NOT NULL,
    source                  VARCHAR(32),
    tokens_before           INT,
    tokens_after            INT,
    compacted_message_count INT,
    recovery_payload        TEXT,        -- Q4: recovery info lives here (not a message row); read on restart, no recompute
    superseded_by           BIGINT,      -- Q3: rolling-summary merge — old summary points at the new one; NULL = active
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_ss_session
        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE
);

-- Active summaries (superseded_by IS NULL) looked up by session ordered by start_seq.
CREATE INDEX idx_ss_session_active
    ON t_session_summary (session_id, start_seq)
    WHERE superseded_by IS NULL;

-- Denormalized "covered by summary" marker on the real message rows.
ALTER TABLE t_session_message ADD COLUMN compacted_by_summary_id BIGINT;

CREATE INDEX idx_session_message_compacted
    ON t_session_message (session_id, compacted_by_summary_id)
    WHERE compacted_by_summary_id IS NOT NULL;
