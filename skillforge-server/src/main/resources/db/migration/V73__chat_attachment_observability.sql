-- V73__chat_attachment_observability.sql — MULTIMODAL-MVP Phase 1 follow-up:
--   MULTIMODAL-OBSERVABILITY-COLUMNS (Wave 1-A).
--
-- Phase 1 tech-design originally specced 4 observability fields on
-- t_chat_attachment but they never made it into V70 (the MVP rushed to ship
-- and they were deferred to a follow-up). Without them, PDF parse failures /
-- image processing failures / mime-magic mismatches all live in unstructured
-- BE log lines only, so ops have to `grep server.log` to debug "this PDF
-- didn't extract".
--
-- All 4 columns are nullable (no DB-level default). The service layer
-- populates them on success / failure paths:
--   - processing_mode      'IMAGE_BLOCK_INLINE' (image upload) /
--                          'PDF_TEXT' | 'PDF_TEXT_TRUNCATED' | 'PDF_TEXT_EMPTY'
--                          (refined when materializeForProvider extracts text)
--                          Future Wave 2 PDF-SCAN-FALLBACK adds
--                          'PDF_PAGE_IMAGE'; future IMAGE-COMPRESSION adds
--                          'IMAGE_BLOCK_COMPRESSED'.
--   - error_code           short stable code populated by failure paths in
--                          Wave 2 / Wave 3 (e.g. PDF_PARSE_FAILED,
--                          PDF_TEXT_EMPTY_NEEDS_VISION, IMAGE_TOO_LARGE).
--   - error_message        free-text human-readable details (truncated to
--                          ~1KB at write site to avoid blowing up the row).
--   - extracted_text_chars informational counter (only PDF path uses today),
--                          used by admin endpoint to spot suspicious 0-char
--                          PDFs that need OCR fallback.
--
-- Wire field names use camelCase (errorCode / errorMessage / processingMode /
-- extractedTextChars) — see java.md footgun #6 (FE-BE contract drift).
--
-- Idempotent (IF NOT EXISTS) so re-running migration on a partially upgraded
-- db is safe; matches V72 style.

ALTER TABLE t_chat_attachment
    ADD COLUMN IF NOT EXISTS error_code           VARCHAR(80),
    ADD COLUMN IF NOT EXISTS error_message        TEXT,
    ADD COLUMN IF NOT EXISTS processing_mode      VARCHAR(50),
    ADD COLUMN IF NOT EXISTS extracted_text_chars INTEGER;
