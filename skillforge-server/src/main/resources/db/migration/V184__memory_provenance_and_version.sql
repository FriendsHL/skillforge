ALTER TABLE t_memory
    ADD COLUMN IF NOT EXISTS provenance_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
    ADD COLUMN IF NOT EXISTS confirmation_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE t_memory
    DROP CONSTRAINT IF EXISTS chk_memory_confirmation_status;
ALTER TABLE t_memory
    ADD CONSTRAINT chk_memory_confirmation_status
        CHECK (confirmation_status IN ('UNVERIFIED', 'CONFIRMED', 'REJECTED'));

ALTER TABLE t_memory
    DROP CONSTRAINT IF EXISTS chk_memory_confidence;
ALTER TABLE t_memory
    ADD CONSTRAINT chk_memory_confidence
        CHECK (confidence IS NULL OR (confidence >= 0.0 AND confidence <= 1.0));

COMMENT ON COLUMN t_memory.provenance_source IS
    'Evidence authority, e.g. USER_EXPLICIT, USER_TRANSCRIPT, AGENT_SUGGESTED, SYNTHESIS_APPROVED';
COMMENT ON COLUMN t_memory.confirmation_status IS
    'Whether the user/operator has confirmed the current memory content';
COMMENT ON COLUMN t_memory.version IS
    'JPA optimistic-lock token preventing silent concurrent overwrite';

ALTER TABLE t_memory_snapshot
    ADD COLUMN IF NOT EXISTS provenance_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
    ADD COLUMN IF NOT EXISTS confirmation_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION;
