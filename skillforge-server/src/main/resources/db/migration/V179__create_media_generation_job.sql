CREATE TABLE IF NOT EXISTS t_media_generation_job (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    agent_id BIGINT,
    source_tool_use_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    provider_job_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    request_json TEXT NOT NULL,
    provider_metadata_json TEXT,
    result_attachment_id VARCHAR(36),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_poll_at TIMESTAMPTZ,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    submitted_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_media_job_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_media_job_tool_use UNIQUE (session_id, source_tool_use_id),
    CONSTRAINT chk_media_job_type CHECK (media_type IN ('video', 'audio')),
    CONSTRAINT chk_media_job_status CHECK (status IN (
        'CREATED', 'SUBMITTING', 'QUEUED', 'RUNNING', 'DOWNLOADING', 'PROCESSING', 'READY',
        'SUBMIT_FAILED', 'GENERATION_FAILED', 'DOWNLOAD_FAILED', 'PROCESSING_FAILED',
        'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_media_job_ready_attachment CHECK (
        status <> 'READY' OR result_attachment_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_media_job_poll
    ON t_media_generation_job(status, next_poll_at);
CREATE INDEX IF NOT EXISTS idx_media_job_session_created
    ON t_media_generation_job(session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_job_user_created
    ON t_media_generation_job(user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_media_job_provider_task
    ON t_media_generation_job(provider, provider_job_id)
    WHERE provider_job_id IS NOT NULL;

UPDATE t_agent
SET tool_ids = (tool_ids::jsonb || '["GenerateVideo"]'::jsonb)::text,
    updated_at = NOW()
WHERE agent_type = 'user' AND status = 'active'
  AND NULLIF(BTRIM(tool_ids), '') IS NOT NULL
  AND jsonb_typeof(tool_ids::jsonb) = 'array'
  AND jsonb_array_length(tool_ids::jsonb) > 0
  AND NOT (tool_ids::jsonb ? 'GenerateVideo');
