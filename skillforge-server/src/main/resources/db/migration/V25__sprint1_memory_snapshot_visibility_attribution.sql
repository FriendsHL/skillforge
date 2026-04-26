-- Sprint 1: memory extraction rollback batches, nullable agent visibility payloads,
-- and memory-specific eval attribution counters.

ALTER TABLE t_agent ALTER COLUMN is_public DROP NOT NULL;

ALTER TABLE t_memory ADD COLUMN extraction_batch_id VARCHAR(36);
CREATE INDEX idx_memory_extraction_batch ON t_memory (extraction_batch_id, user_id);

CREATE TABLE t_memory_snapshot (
    id BIGSERIAL PRIMARY KEY,
    extraction_batch_id VARCHAR(36) NOT NULL,
    memory_id BIGINT,
    user_id BIGINT,
    type TEXT,
    title TEXT,
    content TEXT,
    tags TEXT,
    source_extraction_batch_id VARCHAR(36),
    recall_count INTEGER NOT NULL DEFAULT 0,
    last_recalled_at TIMESTAMPTZ,
    memory_created_at TIMESTAMP,
    memory_updated_at TIMESTAMP,
    snapshot_at TIMESTAMPTZ
);

CREATE INDEX idx_memory_snapshot_batch_user
    ON t_memory_snapshot (extraction_batch_id, user_id);

ALTER TABLE t_eval_run
    ADD COLUMN attr_memory_interference INTEGER NOT NULL DEFAULT 0;
ALTER TABLE t_eval_run
    ADD COLUMN attr_memory_missing INTEGER NOT NULL DEFAULT 0;
