-- V71__add_agent_multimodal_model_id.sql — MULTIMODAL-MVP agent multimodal model config.
--
-- Adds nullable column. Independent of `model_id`; only consulted when the
-- current user turn carries image_ref / pdf_ref blocks. See
-- docs/requirements/active/MULTIMODAL-MVP/tech-design.md §"effective model".

ALTER TABLE t_agent ADD COLUMN IF NOT EXISTS multimodal_model_id VARCHAR(64);
