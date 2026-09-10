-- SESSION-HISTORY-RECOVERY Batch 1: bind archives to exact persisted block occurrences.

ALTER TABLE t_tool_result_archive
    DROP CONSTRAINT IF EXISTS uq_tool_result_archive_session_tooluse;

ALTER TABLE t_tool_result_archive
    ADD COLUMN IF NOT EXISTS block_index INTEGER,
    ADD COLUMN IF NOT EXISTS canonical_payload_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS payload_hash_version SMALLINT;

-- A pre-V198 session_message_id did not carry block/hash identity and is therefore
-- an unclaimed legacy hint. Clear it rather than inventing a canonical occurrence.
UPDATE t_tool_result_archive
SET session_message_id = NULL
WHERE session_message_id IS NOT NULL
  AND block_index IS NULL
  AND canonical_payload_hash IS NULL
  AND payload_hash_version IS NULL;

ALTER TABLE t_tool_result_archive
    DROP CONSTRAINT IF EXISTS fk_tool_result_archive_message_occurrence;
ALTER TABLE t_tool_result_archive
    ADD CONSTRAINT fk_tool_result_archive_message_occurrence
        FOREIGN KEY (session_message_id, session_id)
        REFERENCES t_session_message(id, session_id) ON DELETE CASCADE;

ALTER TABLE t_tool_result_archive
    DROP CONSTRAINT IF EXISTS ck_tool_result_archive_occurrence_shape;
ALTER TABLE t_tool_result_archive
    ADD CONSTRAINT ck_tool_result_archive_occurrence_shape CHECK (
        (
            session_message_id IS NULL
            AND block_index IS NULL
            AND canonical_payload_hash IS NULL
            AND payload_hash_version IS NULL
        )
        OR
        (
            session_message_id IS NOT NULL
            AND block_index IS NOT NULL AND block_index >= 0
            AND canonical_payload_hash IS NOT NULL
            AND canonical_payload_hash ~ '^[0-9a-f]{64}$'
            AND payload_hash_version = 1
        )
    );

CREATE UNIQUE INDEX uq_tool_result_archive_occurrence
    ON t_tool_result_archive (session_id, session_message_id, block_index)
    WHERE session_message_id IS NOT NULL;

-- Transitional compatibility for the flags-off legacy writer. It never applies
-- to occurrence-owned rows and is removed with the Batch 6 writer cutover.
CREATE UNIQUE INDEX uq_tool_result_archive_legacy_session_tooluse
    ON t_tool_result_archive (session_id, tool_use_id)
    WHERE session_message_id IS NULL;

CREATE INDEX idx_tool_result_archive_session_tool_hash
    ON t_tool_result_archive (session_id, tool_use_id, canonical_payload_hash)
    WHERE canonical_payload_hash IS NOT NULL;

COMMENT ON COLUMN t_tool_result_archive.session_message_id IS
    'Immutable persisted message occurrence owner; null only for unclaimed legacy archive rows';
COMMENT ON COLUMN t_tool_result_archive.block_index IS
    'Zero-based ContentBlock ordinal within session_message_id';
COMMENT ON COLUMN t_tool_result_archive.canonical_payload_hash IS
    'Versioned exact scalar payload identity; lowercase SHA-256';
