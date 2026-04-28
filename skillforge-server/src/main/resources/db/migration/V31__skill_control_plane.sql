-- =====================================================================
-- V31__skill_control_plane.sql
-- Source: P1 plan r2 — Skill Control Plane (B-5 + W-4)
-- Order matters:
--   (1) ALTER TABLE add columns
--   (2) DEDUP existing rows by (owner_id, name) keeping max(id)
--   (3) CREATE UNIQUE INDEX on (COALESCE(owner_id, -1), name)
--   (4) ALTER TABLE t_agent add disabled_system_skills
--
-- Note (Phase 4 fix): originally used "NULLS NOT DISTINCT" (PG 15+ syntax),
-- but the project's zonky-embedded-postgres ships with PG 14, which does not
-- support that clause. We use a COALESCE expression index instead — same
-- semantics ("NULL owner_id treated as a single bucket"), portable to PG 12+.
-- The {@code SystemSkillLoader.upsertSystemSkillRow} ON CONFLICT clause MUST
-- match this index expression list.
-- =====================================================================

-- (1) t_skill new columns
ALTER TABLE t_skill ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE t_skill ADD COLUMN failure_count BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_t_skill_is_system ON t_skill(is_system);

-- (2) DEDUP existing duplicate rows. Keep max(id), delete the rest.
--     COALESCE owner_id NULL → -1 sentinel for grouping, since we want
--     NULL to be treated as a single bucket consistent with NULLS NOT DISTINCT.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY COALESCE(owner_id, -1), name
               ORDER BY id DESC
           ) AS rn
    FROM t_skill
)
DELETE FROM t_skill
WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- (3) UNIQUE constraint — PG 14-compatible expression index.
--     COALESCE(owner_id, -1) sentinel-izes NULL owner_id (system skills) so the
--     standard UNIQUE index treats them as a single bucket (semantically equivalent
--     to PG 15's NULLS NOT DISTINCT, but portable). The ON CONFLICT clause in
--     SystemSkillLoader.upsertSystemSkillRow uses the same expression list.
CREATE UNIQUE INDEX uq_t_skill_owner_name
    ON t_skill (COALESCE(owner_id, -1), name);

-- (4) t_agent new column for per-agent disabled system skills (JSON array string)
ALTER TABLE t_agent ADD COLUMN disabled_system_skills TEXT NOT NULL DEFAULT '[]';

-- =====================================================================
-- Appendix B (per r2 plan) — manual verification SQL for dev databases.
-- Run BEFORE applying V31 to capture the (owner_id, name, ids) of any
-- duplicates that this migration will delete:
--
--   SELECT COALESCE(owner_id, -1) AS owner_or_null, name,
--          count(*) AS dup_cnt, array_agg(id ORDER BY id) AS ids
--   FROM t_skill
--   GROUP BY 1, 2
--   HAVING count(*) > 1;
--
-- Record any non-zero results in the commit message.
-- =====================================================================
