ALTER TABLE t_chat_attachment
    ADD COLUMN IF NOT EXISTS interactive_manifest_json TEXT;

ALTER TABLE t_chat_attachment
    DROP CONSTRAINT IF EXISTS chk_chat_attachment_interactive_manifest;

ALTER TABLE t_chat_attachment
    ADD CONSTRAINT chk_chat_attachment_interactive_manifest CHECK (
        (kind = 'interactive'
            AND mime_type = 'text/html'
            AND interactive_manifest_json IS NOT NULL)
        OR
        (kind <> 'interactive'
            AND interactive_manifest_json IS NULL)
    );
