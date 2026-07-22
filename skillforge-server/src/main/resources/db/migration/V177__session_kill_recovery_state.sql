ALTER TABLE t_session
    ADD COLUMN IF NOT EXISTS recovery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recovery_state VARCHAR(16) NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS recovery_reason VARCHAR(64),
    ADD COLUMN IF NOT EXISTS recovery_started_at TIMESTAMPTZ;

ALTER TABLE t_session
    ADD CONSTRAINT ck_session_recovery_attempts
        CHECK (recovery_attempts >= 0 AND recovery_attempts <= 3),
    ADD CONSTRAINT ck_session_recovery_state
        CHECK (recovery_state IN ('none', 'recovering', 'interrupted', 'wedged'));

CREATE INDEX IF NOT EXISTS idx_session_recovery_scan
    ON t_session (origin, runtime_status, recovery_state)
    WHERE runtime_status IN ('running', 'waiting_user');
