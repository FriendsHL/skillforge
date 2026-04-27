-- Memory v2 PR-1: schema baseline for status / importance / scoring + session extraction cursor.
--
-- This migration only adds columns / indexes / data backfill. It does NOT change any business
-- logic; default values are chosen so existing behaviour is preserved (status='ACTIVE' means
-- every existing memory is still considered active and will not be filtered out by future
-- PR-2 status='ACTIVE' WHERE clauses).
--
-- Companion changes in this PR:
--   - MemoryEntity / SessionEntity / MemorySnapshotEntity gain matching Java fields
--   - MemoryService.toSnapshot/restoreFromSnapshot copy the new columns so batch rollback
--     does not lose status/importance/score state
-- Subsequent PRs (PR-2..PR-5) own all behavioural changes.

-- 1. t_memory: status state machine + importance + cached score
ALTER TABLE t_memory ADD COLUMN status         VARCHAR(16)      NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE t_memory ADD COLUMN archived_at    TIMESTAMPTZ      NULL;
ALTER TABLE t_memory ADD COLUMN importance     VARCHAR(8)       NOT NULL DEFAULT 'medium';
ALTER TABLE t_memory ADD COLUMN last_score     DOUBLE PRECISION NULL;
ALTER TABLE t_memory ADD COLUMN last_scored_at TIMESTAMPTZ      NULL;

-- 2. t_session: incremental extraction cursor (must be BIGINT to match SessionMessageEntity.seqNo:long)
ALTER TABLE t_session ADD COLUMN last_extracted_message_seq BIGINT NOT NULL DEFAULT 0;

-- 3. t_memory_snapshot: mirror the new columns so rollbackExtractionBatch can restore them.
--    Existing snapshot rows keep the safe defaults (ACTIVE / medium); new snapshots take the
--    live MemoryEntity values via MemoryService.toSnapshot in this PR.
ALTER TABLE t_memory_snapshot ADD COLUMN status         VARCHAR(16)      NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE t_memory_snapshot ADD COLUMN archived_at    TIMESTAMPTZ      NULL;
ALTER TABLE t_memory_snapshot ADD COLUMN importance     VARCHAR(8)       NOT NULL DEFAULT 'medium';
ALTER TABLE t_memory_snapshot ADD COLUMN last_score     DOUBLE PRECISION NULL;
ALTER TABLE t_memory_snapshot ADD COLUMN last_scored_at TIMESTAMPTZ      NULL;

-- 4. Indexes for upcoming recall / eviction paths (PR-2 / PR-5 will read these).
CREATE INDEX idx_memory_user_status_score
    ON t_memory (user_id, status, last_score DESC NULLS LAST);

-- Partial index helps PR-5 sweep ARCHIVED -> physical delete after 90 days.
CREATE INDEX idx_memory_archived_at
    ON t_memory (archived_at)
    WHERE status = 'ARCHIVED';

-- 5. Backfill status/importance from CSV tags.
--    tags is a comma-separated VARCHAR; we must match token boundaries to avoid catching
--    substrings such as "destale_thing" or "importance:highish". Four cases per token:
--      tags = 'X'           -- only token
--      tags LIKE 'X,%'      -- leading
--      tags LIKE '%,X,%'    -- middle
--      tags LIKE '%,X'      -- trailing
UPDATE t_memory SET status = 'STALE'
 WHERE status = 'ACTIVE'
   AND ( tags = 'stale'
      OR tags LIKE 'stale,%'
      OR tags LIKE '%,stale,%'
      OR tags LIKE '%,stale' );

UPDATE t_memory SET importance = 'high'
 WHERE ( tags = 'importance:high'
      OR tags LIKE 'importance:high,%'
      OR tags LIKE '%,importance:high,%'
      OR tags LIKE '%,importance:high' );

UPDATE t_memory SET importance = 'medium'
 WHERE ( tags = 'importance:medium'
      OR tags LIKE 'importance:medium,%'
      OR tags LIKE '%,importance:medium,%'
      OR tags LIKE '%,importance:medium' );

UPDATE t_memory SET importance = 'low'
 WHERE ( tags = 'importance:low'
      OR tags LIKE 'importance:low,%'
      OR tags LIKE '%,importance:low,%'
      OR tags LIKE '%,importance:low' );

-- 5b. Propagate the just-backfilled memory state onto pre-existing snapshot rows.
--     Snapshot columns added in step 3 default to ACTIVE/medium for every existing row,
--     but the matching live memory may have just been promoted to STALE / importance=high
--     in step 5. If we leave snapshots at their column defaults, a rollback of an in-flight
--     pre-V29 batch would silently revert STALE → ACTIVE (the snapshot's default value),
--     producing the self-contradictory state `tags='stale' AND status='ACTIVE'` that
--     PR-2's status='ACTIVE' filter would treat as live. Mirror status / importance now,
--     before the chk_* constraints lock the schema in step 6.
UPDATE t_memory_snapshot s
   SET status     = m.status,
       importance = m.importance
  FROM t_memory m
 WHERE s.memory_id = m.id;

-- 6. Domain constraints. Added LAST so the backfill above cannot fail mid-flight.
ALTER TABLE t_memory ADD CONSTRAINT chk_memory_status
    CHECK (status IN ('ACTIVE', 'STALE', 'ARCHIVED'));
ALTER TABLE t_memory ADD CONSTRAINT chk_memory_importance
    CHECK (importance IN ('high', 'medium', 'low'));

ALTER TABLE t_memory_snapshot ADD CONSTRAINT chk_memory_snapshot_status
    CHECK (status IN ('ACTIVE', 'STALE', 'ARCHIVED'));
ALTER TABLE t_memory_snapshot ADD CONSTRAINT chk_memory_snapshot_importance
    CHECK (importance IN ('high', 'medium', 'low'));
