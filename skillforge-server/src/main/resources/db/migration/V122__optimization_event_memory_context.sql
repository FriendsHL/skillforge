ALTER TABLE t_optimization_event
    ADD COLUMN IF NOT EXISTS memory_context_hash varchar(64),
    ADD COLUMN IF NOT EXISTS memory_context_memory_ids jsonb;

COMMENT ON COLUMN t_optimization_event.memory_context_hash IS
    'SHA-256 hash of memory context considered by attribution or candidate generation.';

COMMENT ON COLUMN t_optimization_event.memory_context_memory_ids IS
    'JSON array of active memory ids considered by attribution or candidate generation.';
