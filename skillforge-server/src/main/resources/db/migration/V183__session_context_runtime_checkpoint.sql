ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS context_runtime_json TEXT;

COMMENT ON COLUMN t_session.context_runtime_json IS
    'Versioned content-free checkpoint for deferred Tool discovery and invoked Skill references';
