-- FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (2026-05-25): give every EvalScenario
-- an applicable_agent_roles JSONB tag list that BehaviorRuleAbEvalService
-- consumes to split a dataset into (target subset = scenarios matching
-- rule_owner_agent's role) + (regression subset = scenarios tagged
-- 'general'). Mirrors V114 rule_trigger_hints pattern (JSONB + GIN partial
-- index + ?| operator → jsonb_exists_any() function call to avoid Spring
-- Data JPA placeholder conflict, see cc7286b hot-fix).
--
-- Backfill the 49 existing scenarios via name heuristic on t_agent (V1 uses
-- agent.name LIKE patterns; V2 may add t_agent.agent_role real column).
-- Expected distribution (verified via dogfood query 2026-05-25):
--   30 ['general']         (agent_id IS NULL + source_type='benchmark')
--   11 ['design']          (Design Agent dogfood)
--    6 ['main_assistant']  (Main Assistant dogfood)
--    1 ['code']            (Code Agent dogfood)
--    1 ['research']        (Research Agent dogfood)
--    -----
--   49 total → AC-1 enforce via DO $$ RAISE EXCEPTION if any row stays empty

ALTER TABLE t_eval_scenario
    ADD COLUMN IF NOT EXISTS applicable_agent_roles JSONB NOT NULL DEFAULT '[]'::jsonb;

-- GIN partial index — same pattern as V114 rule_trigger_hints. Partial because
-- vast majority of empty arrays would bloat the index for no use.
CREATE INDEX IF NOT EXISTS idx_eval_scenario_applicable_agent_roles_gin
    ON t_eval_scenario USING GIN (applicable_agent_roles)
    WHERE jsonb_array_length(applicable_agent_roles) > 0;

-- ─── Backfill (idempotent, JSONB || concat + NOT @> guard, V116 pattern) ───

-- 1) Generic benchmark scenarios (agent_id IS NULL + source_type='benchmark')
--    → 'general'. 30 rows expected.
UPDATE t_eval_scenario
   SET applicable_agent_roles = applicable_agent_roles || '["general"]'::jsonb
 WHERE agent_id IS NULL AND source_type = 'benchmark'
   AND NOT (applicable_agent_roles @> '["general"]'::jsonb);

-- 2) Design Agent dogfood → 'design'. 11 rows expected.
UPDATE t_eval_scenario s
   SET applicable_agent_roles = s.applicable_agent_roles || '["design"]'::jsonb
  FROM t_agent a
 WHERE a.id::text = s.agent_id
   AND a.name ILIKE '%design%'
   AND NOT (s.applicable_agent_roles @> '["design"]'::jsonb);

-- 3) Code Agent dogfood → 'code'. 1 row expected.
UPDATE t_eval_scenario s
   SET applicable_agent_roles = s.applicable_agent_roles || '["code"]'::jsonb
  FROM t_agent a
 WHERE a.id::text = s.agent_id
   AND a.name ILIKE '%code%'
   AND NOT (s.applicable_agent_roles @> '["code"]'::jsonb);

-- 4) Research Agent dogfood → 'research'. 1 row expected.
UPDATE t_eval_scenario s
   SET applicable_agent_roles = s.applicable_agent_roles || '["research"]'::jsonb
  FROM t_agent a
 WHERE a.id::text = s.agent_id
   AND a.name ILIKE '%research%'
   AND NOT (s.applicable_agent_roles @> '["research"]'::jsonb);

-- 5) Main Assistant dogfood → 'main_assistant'. 6 rows expected.
--    Negative ILIKE guards prevent matching "Design", "Code", "Research"
--    that may contain "main" substring (none currently do; defensive).
UPDATE t_eval_scenario s
   SET applicable_agent_roles = s.applicable_agent_roles || '["main_assistant"]'::jsonb
  FROM t_agent a
 WHERE a.id::text = s.agent_id
   AND (a.name ILIKE '%main%' OR a.name ILIKE '%assistant%')
   AND a.name NOT ILIKE '%design%'
   AND a.name NOT ILIKE '%code%'
   AND a.name NOT ILIKE '%research%'
   AND NOT (s.applicable_agent_roles @> '["main_assistant"]'::jsonb);

-- AC-1 enforce: ALL scenarios must have non-empty applicable_agent_roles.
-- Migration fails loudly if any row slipped through the 5 UPDATE patterns —
-- forces operator to extend heuristics before deploy (no silent backfill loss).
DO $$
DECLARE missing INTEGER; total INTEGER;
BEGIN
    SELECT COUNT(*) INTO missing FROM t_eval_scenario
        WHERE jsonb_array_length(applicable_agent_roles) = 0;
    SELECT COUNT(*) INTO total FROM t_eval_scenario;
    IF missing > 0 THEN
        RAISE EXCEPTION '[V117] AC-1 violation: % / % scenarios lack applicable_agent_roles. '
                        'Check ILIKE pattern coverage + t_agent.name conventions.', missing, total;
    END IF;
    RAISE NOTICE '[V117] backfill complete: %/% scenarios tagged (AC-1 OK)', total - missing, total;
END $$;
