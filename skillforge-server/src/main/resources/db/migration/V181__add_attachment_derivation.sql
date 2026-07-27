ALTER TABLE t_chat_attachment
    ADD COLUMN IF NOT EXISTS derived_from_attachment_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS derivation_operation VARCHAR(32);

ALTER TABLE t_chat_attachment
    ADD CONSTRAINT fk_chat_attachment_derived_from
    FOREIGN KEY (derived_from_attachment_id)
    REFERENCES t_chat_attachment(id)
    ON DELETE SET NULL;

ALTER TABLE t_chat_attachment
    ADD CONSTRAINT chk_chat_attachment_derivation_operation
    CHECK (derivation_operation IS NULL OR derivation_operation IN ('EDIT_IMAGE'));

CREATE INDEX IF NOT EXISTS idx_chat_attachment_derived_from
    ON t_chat_attachment(derived_from_attachment_id);
