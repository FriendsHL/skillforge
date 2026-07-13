CREATE TABLE t_mobile_device (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_name VARCHAR(128) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    app_version VARCHAR(64),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    scopes_json TEXT NOT NULL DEFAULT '[]',
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_mobile_device_platform CHECK (platform IN ('ios')),
    CONSTRAINT chk_mobile_device_status CHECK (status IN ('active', 'revoked'))
);

CREATE INDEX idx_mobile_device_user_status
    ON t_mobile_device(user_id, status, created_at DESC);

CREATE TABLE t_mobile_pairing_request (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    secret_hash VARCHAR(64) NOT NULL,
    setup_code_hash VARCHAR(64) NOT NULL,
    server_name VARCHAR(128) NOT NULL,
    endpoints_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_device_id UUID REFERENCES t_mobile_device(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    CONSTRAINT chk_mobile_pairing_status
        CHECK (status IN ('pending', 'claimed', 'expired', 'cancelled'))
);

CREATE INDEX idx_mobile_pairing_user_status
    ON t_mobile_pairing_request(user_id, status, created_at DESC);

CREATE INDEX idx_mobile_pairing_expires
    ON t_mobile_pairing_request(expires_at)
    WHERE status = 'pending';
