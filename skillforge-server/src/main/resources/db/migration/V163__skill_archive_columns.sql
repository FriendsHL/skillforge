-- V163__skill_archive_columns.sql — SKILL-CURATOR V1
--
-- Adds archival bookkeeping columns to t_skill so the SkillConsolidator (a nightly
-- curator mirroring the Memory consolidation pattern) can flag/archive non-system,
-- old, rarely-used skills out of the agent's tool-selection surface.
--
-- v1 defaults to DRY-RUN (skillforge.skill.consolidation.dry-run=true): no row is
-- actually disabled until an operator flips the flag. These columns therefore stay
-- NULL on every row until a real (non-dry-run) curator pass archives a skill.
--
-- Column semantics:
--   archived_at    — NULL = not archived; set to the curator run time when a skill
--                    is archived (skill is also disabled=false at that point).
--   archive_reason — short machine tag for why it was archived ('low_usage_curator').
--
-- Idempotent ADD COLUMN IF NOT EXISTS, consistent with other additive migrations.

-- TIMESTAMPTZ (not bare TIMESTAMP) per V70+ convention for Instant-mapped columns
-- (matches t_skill.updated_at V14); bare TIMESTAMP offsets when JVM/PG TZ != UTC.
ALTER TABLE t_skill
    ADD COLUMN IF NOT EXISTS archived_at    TIMESTAMPTZ NULL;

ALTER TABLE t_skill
    ADD COLUMN IF NOT EXISTS archive_reason VARCHAR(64) NULL;
