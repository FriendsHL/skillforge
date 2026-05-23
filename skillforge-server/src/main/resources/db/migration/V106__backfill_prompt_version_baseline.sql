-- PROMPT-IMPROVER-GENESIS-BASELINE fix (2026-05-23): backfill v1 baseline
-- for every active user agent that still has no t_prompt_version row.
--
-- Why: V6 FLYWHEEL-LOOP-CLOSURE's attribution path used to write the LLM-
-- generated candidate as v1 directly (since no prior version existed). Later,
-- when OptimizationEventAutoTriggerListener kicks runAbTestAgainst on
-- candidate_ready, runAbTestAgainst's genesis path tries to also write v1
-- baseline → uq_agent_version (agent_id, version_number) UNIQUE conflict →
-- DataIntegrityViolationException → A/B never starts → flywheel deadlocks.
--
-- PromptImproverService.startImprovementFromAttribution has been patched to
-- materialize v1 baseline first so new agents won't hit this. This migration
-- mirrors that genesis path for any agent already in the existing state
-- ("user agent, has system_prompt, no t_prompt_version rows"), so the next
-- attribution-triggered A/B for those agents starts with baseline v1 already
-- in place.
--
-- Idempotency: WHERE NOT EXISTS guard means re-running this migration on a
-- partially-migrated DB is a no-op for any agent that already has at least
-- one prompt_version row. agent_id is VARCHAR(36) per V4 schema, hence the
-- a.id::text cast.

INSERT INTO t_prompt_version (
    id,
    agent_id,
    version_number,
    status,
    source,
    content,
    created_at
)
SELECT
    gen_random_uuid()::text,
    a.id::text,
    1,
    'active',
    'genesis_baseline',
    a.system_prompt,
    NOW()
FROM t_agent a
WHERE a.agent_type = 'user'
  AND a.status = 'active'
  AND a.system_prompt IS NOT NULL
  AND a.system_prompt <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM t_prompt_version pv
      WHERE pv.agent_id = a.id::text
  );

-- Patch t_agent.active_prompt_version_id for the same set of agents so
-- runAbTestAgainst's baseline-resolution (agent.activePromptVersionId path)
-- finds the newly-materialized v1 instead of falling into its in-line genesis
-- branch. Match on (agent_id, version_number=1, source='genesis_baseline') to
-- only touch agents we just backfilled.
UPDATE t_agent a
SET active_prompt_version_id = pv.id
FROM t_prompt_version pv
WHERE pv.agent_id = a.id::text
  AND pv.version_number = 1
  AND pv.source = 'genesis_baseline'
  AND a.active_prompt_version_id IS NULL;
