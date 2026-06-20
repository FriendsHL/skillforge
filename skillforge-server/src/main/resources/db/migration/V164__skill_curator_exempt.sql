-- V164__skill_curator_exempt.sql — SKILL-CURATOR human-in-loop
--
-- Adds a curator-exemption marker to t_skill. When true, the SkillConsolidator
-- (the low-usage curator) MUST never pick the row as an archival candidate.
--
-- Set to true when an operator manually restores an archived skill via the
-- dashboard (POST /api/skills/{id}/restore): a human deliberately brought the
-- skill back, so the nightly/manual curator must not silently re-archive it.
--
-- Default false preserves existing behaviour for every current row (nothing is
-- exempt until a human restores it). Idempotent ADD COLUMN IF NOT EXISTS,
-- consistent with the other additive t_skill migrations (V163 etc.).

ALTER TABLE t_skill
    ADD COLUMN IF NOT EXISTS curator_exempt BOOLEAN NOT NULL DEFAULT false;
