ALTER TABLE t_chat_attachment
    DROP CONSTRAINT IF EXISTS ck_chat_attachment_status;

ALTER TABLE t_chat_attachment
    ADD CONSTRAINT ck_chat_attachment_status
        CHECK (status IN ('uploaded', 'bound', 'publishing', 'deleting', 'published'));
