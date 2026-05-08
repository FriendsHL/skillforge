-- MEMORY-DREAM-CONSOLIDATION: track the reason a memory transitioned to ARCHIVED.
--
-- Existing archive paths are produced by MemoryConsolidator (status lifecycle sweep,
-- capacity demote, dedup-merge). Without this column, ARCHIVED rows are
-- indistinguishable in the audit trail / future debugging.
--
-- Nullable: rows archived by older code paths or migrated from before this column
-- existed will have NULL, which is fine — callers only consult the field when present.

ALTER TABLE t_memory ADD COLUMN IF NOT EXISTS archived_reason VARCHAR(128);

COMMENT ON COLUMN t_memory.archived_reason IS 'MEMORY-DREAM-CONSOLIDATION: reason memory was archived (expired_ttl / capacity_demote / dedup_merge_with_<winnerId>); NULL for legacy rows.';
