ALTER TABLE t_chat_attachment
    ADD COLUMN IF NOT EXISTS origin VARCHAR(24),
    ADD COLUMN IF NOT EXISTS source_tool_use_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS caption VARCHAR(1000);

UPDATE t_chat_attachment
SET origin = 'user_upload'
WHERE origin IS NULL;

ALTER TABLE t_chat_attachment
    ALTER COLUMN origin SET DEFAULT 'user_upload',
    ALTER COLUMN origin SET NOT NULL;

ALTER TABLE t_chat_attachment
    DROP CONSTRAINT IF EXISTS ck_chat_attachment_origin;
ALTER TABLE t_chat_attachment
    ADD CONSTRAINT ck_chat_attachment_origin
        CHECK (origin IN ('user_upload', 'agent_generated'));

ALTER TABLE t_chat_attachment
    DROP CONSTRAINT IF EXISTS ck_chat_attachment_status;
ALTER TABLE t_chat_attachment
    ADD CONSTRAINT ck_chat_attachment_status
        CHECK (status IN ('uploaded', 'bound', 'published'));

ALTER TABLE t_chat_attachment
    DROP CONSTRAINT IF EXISTS ck_chat_attachment_sha256;
ALTER TABLE t_chat_attachment
    ADD CONSTRAINT ck_chat_attachment_sha256
        CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_attachment_session_tool_use
    ON t_chat_attachment(session_id, source_tool_use_id)
    WHERE source_tool_use_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_chat_attachment_origin_status_created
    ON t_chat_attachment(origin, status, created_at);

UPDATE t_agent
SET tool_ids = (tool_ids::jsonb || '["PublishChatArtifact"]'::jsonb)::text,
    updated_at = NOW()
WHERE name = 'Main Assistant'
  AND NULLIF(BTRIM(tool_ids), '') IS NOT NULL
  AND jsonb_typeof(tool_ids::jsonb) = 'array'
  AND jsonb_array_length(tool_ids::jsonb) > 0
  AND NOT (tool_ids::jsonb ? 'PublishChatArtifact');
