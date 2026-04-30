ALTER TABLE t_session_message
    ADD COLUMN IF NOT EXISTS message_type VARCHAR(32) NOT NULL DEFAULT 'normal',
    ADD COLUMN IF NOT EXISTS control_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS answered_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_session_message_control
    ON t_session_message (session_id, message_type, control_id);
