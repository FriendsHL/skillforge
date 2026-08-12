ALTER TABLE t_chat_attachment
    DROP CONSTRAINT IF EXISTS chk_chat_attachment_derivation_operation;

ALTER TABLE t_chat_attachment
    ADD CONSTRAINT chk_chat_attachment_derivation_operation
    CHECK (derivation_operation IS NULL OR derivation_operation IN (
        'EDIT_IMAGE',
        'REVISE_INTERACTIVE'
    ));
