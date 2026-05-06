-- EVAL-V2 M3a (b2): rename t_eval_run → t_eval_task + add 7 task-shape fields.
--
-- Why rename:
--   * "Run" was historically tied to the legacy single-step eval workflow
--     (one batch == one run). In M3a the model becomes "task" — an attempted
--     evaluation against a (possibly filtered) dataset, with per-case items
--     in t_eval_task_item (V53). The renamed table gains:
--       * scenario_count / pass_count / fail_count / composite_avg : aggregates
--         (composite_avg displaces overall_pass_rate as the primary score
--         signal; pass_rate stays as legacy compat).
--       * dataset_filter (TEXT, JSON-encoded) : which subset of the dataset the
--         task ran (e.g. {"split":"held_out"} or {"tags":["multi-turn"]}).
--       * attribution_summary / improvement_suggestion : LLM-generated
--         post-run analysis surface (M5/M6 feed); kept TEXT because the
--         shape is still being iterated.
--       * analysis_session_id : optional FK to the analysis chat session
--         opened from the task drawer (M5 closed-loop).
--   * passed_scenarios → pass_count : symmetric with new fail_count column,
--     and Java callers were already calling it 'pass count' verbally.
--
-- Backward-compat VIEW t_eval_run:
--   * Read-only mirror so any external SQL caller (BI dashboards, ad-hoc psql,
--     legacy DB queries) keeps working until M3a-b3+1 sprint when we drop it.
--   * SELECT-only — INSERT/UPDATE on the VIEW would error. The Java side fully
--     migrates to EvalTaskEntity in this same commit, so app paths stop
--     touching the legacy name.
--   * pass_count is aliased back to passed_scenarios in the VIEW so external
--     reads of the legacy column name still work.
--
-- IF NOT EXISTS / IF EXISTS guards: align with V40/V41/V43/V45/V48/V50 convention,
-- making the migration safe to re-run on environments where a partial earlier
-- attempt may have applied some of the ALTERs. Flyway still records V52 as
-- applied on first success.

-- 1. Rename the table.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.tables
         WHERE table_name = 't_eval_run'
           AND table_type = 'BASE TABLE'
    ) THEN
        ALTER TABLE t_eval_run RENAME TO t_eval_task;
    END IF;
END $$;

-- 2. Rename the legacy column.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_name = 't_eval_task'
           AND column_name = 'passed_scenarios'
    ) THEN
        ALTER TABLE t_eval_task RENAME COLUMN passed_scenarios TO pass_count;
    END IF;
END $$;

-- 3. Add the 7 new task-shape columns.
ALTER TABLE t_eval_task ADD COLUMN IF NOT EXISTS attribution_summary    TEXT NULL;
ALTER TABLE t_eval_task ADD COLUMN IF NOT EXISTS improvement_suggestion TEXT NULL;
ALTER TABLE t_eval_task ADD COLUMN IF NOT EXISTS analysis_session_id    VARCHAR(36) NULL;
ALTER TABLE t_eval_task ADD COLUMN IF NOT EXISTS dataset_filter         TEXT NULL;
ALTER TABLE t_eval_task ADD COLUMN IF NOT EXISTS scenario_count         INT NULL;
ALTER TABLE t_eval_task ADD COLUMN IF NOT EXISTS fail_count             INT NOT NULL DEFAULT 0;
ALTER TABLE t_eval_task ADD COLUMN IF NOT EXISTS composite_avg          NUMERIC(5,2) NULL;

-- 4. Backward-compat VIEW so any caller still using the legacy "t_eval_run"
--    name keeps working. Read-only — no INSERT/UPDATE expected on the legacy
--    name from this point. The pass_count column is exposed as
--    passed_scenarios in the VIEW so legacy SQL keeps the old column name.
CREATE OR REPLACE VIEW t_eval_run AS
    SELECT
        id,
        agent_definition_id,
        scenario_set_version,
        status,
        error_message,
        scenario_results_json,
        improvement_suggestions_json,
        overall_pass_rate,
        avg_oracle_score,
        total_scenarios,
        pass_count        AS passed_scenarios,
        failed_scenarios,
        timeout_scenarios,
        veto_scenarios,
        attr_skill_missing,
        attr_skill_exec_failure,
        attr_prompt_quality,
        attr_context_overflow,
        attr_performance,
        attr_memory_interference,
        attr_memory_missing,
        primary_attribution,
        consecutive_decline_count,
        triggered_by_user_id,
        started_at,
        completed_at,
        collab_run_id
    FROM t_eval_task;

COMMENT ON VIEW t_eval_run IS
    'EVAL-V2 M3a-b2 backward-compat — read-only view, drop after callers migrate '
    '(target: M3a-b3 + 1 sprint). pass_count exposed as passed_scenarios for legacy SQL.';
