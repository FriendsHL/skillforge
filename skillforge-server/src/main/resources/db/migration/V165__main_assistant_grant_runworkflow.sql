-- V165__main_assistant_grant_runworkflow.sql
--
-- Grant the "Main Assistant" agent (id=3) access to the RunWorkflow tool so it can
-- kick off / resume DSL workflows on the user's behalf (deterministic multi-agent
-- orchestration). The agent already carries 34 tools (TeamSend / SendChannelFile /
-- WebFetch / Memory / Read / Bash / SubAgent / ...); this only ADDS "RunWorkflow",
-- it must not drop or reorder the existing set.
--
-- tool_ids is a JSON-array STRING (not a jsonb column), so we append via jsonb
-- operations and write the result back as text. The NOT LIKE guard makes the
-- migration idempotent: re-running (or a row that already has RunWorkflow) is a
-- no-op rather than a duplicate entry.
--
-- Keyed on BOTH id=3 and name='Main Assistant' as a belt-and-suspenders guard so a
-- mis-seeded environment cannot silently grant the tool to the wrong agent.

UPDATE t_agent
SET tool_ids = (COALESCE(NULLIF(tool_ids, '')::jsonb, '[]'::jsonb)
                   || '["RunWorkflow"]'::jsonb)::text,
    updated_at = NOW()
WHERE id = 3
  AND name = 'Main Assistant'
  AND (tool_ids IS NULL OR tool_ids NOT LIKE '%RunWorkflow%');

-- FR-C7 (r1 hardening, db-W): composite index for the per-agent rolling-window
-- budget query. TriggerAbEvalTool now runs countEvolveIterationStepsByAgentIdSince
-- on EVERY trigger (previously, once an agent's lifetime count hit the cap it froze
-- and stopped querying). That query joins t_flywheel_run_step.run_id ->
-- t_flywheel_run.id and filters t_flywheel_run on (agent_id, loop_kind='evolve');
-- this index lets the planner resolve the run-side predicate without a scan.
-- IF NOT EXISTS keeps the migration idempotent.
CREATE INDEX IF NOT EXISTS idx_flywheel_run_agent_loop_kind
    ON t_flywheel_run (agent_id, loop_kind);
