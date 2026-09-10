-- SESSION-HISTORY-RECOVERY Batch 1: timeline epoch and checkpoint control-plane runtime.

ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS history_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE t_session
    DROP CONSTRAINT IF EXISTS ck_session_history_epoch;
ALTER TABLE t_session
    ADD CONSTRAINT ck_session_history_epoch CHECK (history_epoch >= 0);

ALTER TABLE t_session_compaction_checkpoint
    ADD COLUMN IF NOT EXISTS runtime_snapshot_json TEXT;

-- A sequence-backed sidecar timeline is required because equal transcript frontiers are valid and
-- neither created_at nor checkpoint UUID establishes a trustworthy order. Add the nullable columns
-- before installing the default so checkpoints already present in a populated V195 database remain
-- explicitly unordered; branch/restore fail closed when such a legacy row is selected.
CREATE SEQUENCE IF NOT EXISTS t_session_compaction_checkpoint_sidecar_watermark_seq
    AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

ALTER TABLE t_session_compaction_checkpoint
    ADD COLUMN IF NOT EXISTS sidecar_watermark BIGINT,
    ADD COLUMN IF NOT EXISTS summary_id_watermark BIGINT;

ALTER TABLE t_session_compaction_checkpoint
    ALTER COLUMN sidecar_watermark SET DEFAULT
        nextval('t_session_compaction_checkpoint_sidecar_watermark_seq');

ALTER SEQUENCE t_session_compaction_checkpoint_sidecar_watermark_seq
    OWNED BY t_session_compaction_checkpoint.sidecar_watermark;

ALTER TABLE t_session_compaction_checkpoint
    DROP CONSTRAINT IF EXISTS ck_scc_sidecar_watermark_positive;
ALTER TABLE t_session_compaction_checkpoint
    ADD CONSTRAINT ck_scc_sidecar_watermark_positive
        CHECK (sidecar_watermark IS NULL OR sidecar_watermark > 0);

ALTER TABLE t_session_compaction_checkpoint
    DROP CONSTRAINT IF EXISTS ck_scc_summary_id_watermark_nonnegative;
ALTER TABLE t_session_compaction_checkpoint
    ADD CONSTRAINT ck_scc_summary_id_watermark_nonnegative
        CHECK (summary_id_watermark IS NULL OR summary_id_watermark >= 0);

ALTER TABLE t_session_compaction_checkpoint
    DROP CONSTRAINT IF EXISTS ck_scc_watermark_pair_complete;
ALTER TABLE t_session_compaction_checkpoint
    ADD CONSTRAINT ck_scc_watermark_pair_complete
        CHECK (sidecar_watermark IS NULL OR summary_id_watermark IS NOT NULL);

CREATE UNIQUE INDEX IF NOT EXISTS uq_scc_session_sidecar_watermark
    ON t_session_compaction_checkpoint (session_id, sidecar_watermark)
    WHERE sidecar_watermark IS NOT NULL;

COMMENT ON COLUMN t_session.history_epoch IS
    'Durable same-Session timeline generation; destructive restore/rewrite increments exactly once';
COMMENT ON COLUMN t_session_compaction_checkpoint.runtime_snapshot_json IS
    'Versioned content-free Tool/Skill registry references captured at the checkpoint';
COMMENT ON COLUMN t_session_compaction_checkpoint.sidecar_watermark IS
    'DB-assigned monotonic sidecar timeline; NULL marks an unordered legacy checkpoint';
COMMENT ON COLUMN t_session_compaction_checkpoint.summary_id_watermark IS
    'Highest t_session_summary id visible when this checkpoint was committed';
