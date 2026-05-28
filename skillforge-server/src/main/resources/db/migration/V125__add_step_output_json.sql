-- OPT-LOOP-FRAMEWORK Sprint 2 (2026-05-28): step_output_json column on
-- t_flywheel_run_step. Lets the new framework-side
-- RecordOrchestrationStepResult tool persist a free-schema JSON payload that
-- the parent OrchestratorAgentExecutor surfaces in StepResult.outputJson.
--
-- nullable on purpose:
--   * OPT-REPORT-V1 historical rows (13 batches as of V124 backfill audit)
--     don't have an output_json shape; they keep using step_output_count.
--   * RecordBatchAnnotationsTool path is unaffected; only the new
--     RecordOrchestrationStepResult tool populates this column.
--
-- JSONB chosen for parity with the run-level summary_json column
-- (FlywheelRunEntity#summary_json) — operators / dashboards can use the same
-- jsonb operators (->, ->>) for ad-hoc queries.

ALTER TABLE t_flywheel_run_step
    ADD COLUMN IF NOT EXISTS step_output_json JSONB NULL;

COMMENT ON COLUMN t_flywheel_run_step.step_output_json IS
    'OPT-LOOP-FRAMEWORK Sprint 2 (V125): free-schema JSONB written by '
    'RecordOrchestrationStepResult tool; OPT-REPORT-V1 rows leave NULL.';

-- r2 fix W-5 (database-reviewer W1): fallback listener
-- WorkerCompletionListener.onSessionLoopFinishedFallback fires
-- findBySubAgentSessionIdAndStatus on every session termination — without an
-- index this is an O(N) seq scan at production scale (one scan per finished
-- session). Partial index because the column is null on every OPT-REPORT
-- batch row that was inserted before the framework spawned its child, and
-- only sub_agent_session_id IS NOT NULL rows ever match the listener's query.
-- (status, sub_agent_session_id) compound order matches the query predicate
-- exactly so PG can satisfy both ANDed columns from the index without a
-- recheck on the heap.
CREATE INDEX IF NOT EXISTS idx_flywheel_run_step_sub_agent_session
    ON t_flywheel_run_step (sub_agent_session_id, status)
    WHERE sub_agent_session_id IS NOT NULL;

COMMENT ON INDEX idx_flywheel_run_step_sub_agent_session IS
    'OPT-LOOP-FRAMEWORK Sprint 2 (V125 W-5 fix): O(1) lookup for fallback '
    'listener WorkerCompletionListener.onSessionLoopFinishedFallback.';
