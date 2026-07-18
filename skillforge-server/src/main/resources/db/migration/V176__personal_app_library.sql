ALTER TABLE t_chat_attachment
    ADD COLUMN IF NOT EXISTS source_message_seq BIGINT;

ALTER TABLE t_chat_attachment
    DROP CONSTRAINT IF EXISTS chk_chat_attachment_source_message_seq;
ALTER TABLE t_chat_attachment
    ADD CONSTRAINT chk_chat_attachment_source_message_seq
        CHECK (source_message_seq IS NULL OR source_message_seq >= 0);

CREATE INDEX IF NOT EXISTS idx_chat_attachment_session_source_message
    ON t_chat_attachment (session_id, source_message_seq)
    WHERE source_message_seq IS NOT NULL;

CREATE TABLE IF NOT EXISTS t_personal_app_preference (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    attachment_id VARCHAR(36) NOT NULL,
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    last_opened_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_personal_app_preference_user_attachment
        UNIQUE (user_id, attachment_id),
    CONSTRAINT chk_personal_app_preference_user
        CHECK (user_id > 0),
    CONSTRAINT fk_personal_app_preference_attachment
        FOREIGN KEY (attachment_id) REFERENCES t_chat_attachment(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_personal_app_preference_user_favorite
    ON t_personal_app_preference (user_id, updated_at DESC, attachment_id DESC)
    WHERE favorite = TRUE;

CREATE INDEX IF NOT EXISTS idx_personal_app_preference_user_recent
    ON t_personal_app_preference (user_id, last_opened_at DESC, attachment_id DESC)
    WHERE last_opened_at IS NOT NULL;

-- The library query also uses this immutable exception-safe parser so one
-- malformed legacy manifest cannot fail an otherwise valid bounded page.
CREATE OR REPLACE FUNCTION public.skillforge_try_parse_jsonb(value TEXT)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
AS '
BEGIN
    RETURN value::jsonb;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
';

-- Historical message content is normally an array. Guard jsonb_array_elements so
-- malformed/scalar/object/null legacy content is ignored. If a surviving history
-- contains duplicate refs, the latest assistant occurrence is the canonical live
-- source after rewrite/restore reconciliation.
WITH parsed_messages AS (
    SELECT session_id,
           seq_no,
           public.skillforge_try_parse_jsonb(content_json) AS parsed_content
    FROM t_session_message
    WHERE role = 'assistant'
), message_arrays AS (
    SELECT session_id,
           seq_no,
           CASE
               WHEN jsonb_typeof(parsed_content) = 'array'
                   THEN parsed_content
               ELSE '[]'::jsonb
           END AS blocks
    FROM parsed_messages
), refs AS (
    SELECT messages.session_id,
           COALESCE(block ->> 'attachment_id', block ->> 'attachmentId') AS attachment_id,
           MAX(messages.seq_no) AS source_message_seq
    FROM message_arrays messages
    CROSS JOIN LATERAL jsonb_array_elements(messages.blocks) AS block
    WHERE block ->> 'type' = 'interactive_artifact_ref'
      AND COALESCE(block ->> 'attachment_id', block ->> 'attachmentId') IS NOT NULL
    GROUP BY messages.session_id,
             COALESCE(block ->> 'attachment_id', block ->> 'attachmentId')
)
UPDATE t_chat_attachment attachment
SET source_message_seq = refs.source_message_seq,
    status = 'published'
FROM refs
WHERE attachment.id = refs.attachment_id
  AND attachment.session_id = refs.session_id
  AND attachment.kind = 'interactive'
  AND attachment.origin = 'agent_generated';

CREATE INDEX IF NOT EXISTS idx_chat_attachment_personal_apps_hot
    ON t_chat_attachment (user_id, created_at DESC, id DESC)
    WHERE kind = 'interactive'
      AND origin = 'agent_generated'
      AND status = 'published'
      AND source_message_seq IS NOT NULL;
