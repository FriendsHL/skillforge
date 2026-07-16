CREATE TABLE IF NOT EXISTS t_mobile_push_token (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES t_mobile_device(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    token_ciphertext TEXT NOT NULL,
    environment VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    last_registered_at TIMESTAMPTZ NOT NULL,
    invalidated_at TIMESTAMPTZ,
    CONSTRAINT chk_mobile_push_environment CHECK (environment IN ('development', 'production')),
    CONSTRAINT chk_mobile_push_token_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT uq_mobile_push_device_environment UNIQUE (device_id, environment),
    CONSTRAINT uq_mobile_push_token_hash_environment UNIQUE (token_hash, environment)
);

CREATE INDEX IF NOT EXISTS idx_mobile_push_token_active_device
    ON t_mobile_push_token(device_id, last_registered_at DESC)
    WHERE status = 'active';

CREATE TABLE IF NOT EXISTS t_mobile_notification (
    id UUID PRIMARY KEY,
    task_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(36) NOT NULL REFERENCES t_session(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    body VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_mobile_notification_kind CHECK (kind IN ('completed', 'error', 'action_required')),
    CONSTRAINT uq_mobile_notification_task_kind UNIQUE (task_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_mobile_notification_user_created
    ON t_mobile_notification(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS t_mobile_notification_delivery (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES t_mobile_notification(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES t_mobile_device(id) ON DELETE CASCADE,
    push_token_id UUID NOT NULL REFERENCES t_mobile_push_token(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(256),
    apns_id VARCHAR(64),
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_mobile_notification_delivery_status
        CHECK (status IN ('pending', 'sending', 'delivered', 'failed')),
    CONSTRAINT chk_mobile_notification_delivery_attempts CHECK (attempts >= 0),
    CONSTRAINT uq_mobile_notification_device UNIQUE (notification_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_mobile_notification_delivery_pending
    ON t_mobile_notification_delivery(next_attempt_at, created_at)
    WHERE status IN ('pending', 'sending');
