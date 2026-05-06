-- EVAL-V2 M3a (b2): per-case row-level eval item table.
--
-- Why a new table (vs continuing to dump scenarioResultsJson into t_eval_task):
--   * Case-level filtering / sorting (UI Items tab, M3b list view). jsonb path
--     queries on t_eval_task.scenario_results_json work but are awkward and
--     not indexable as cleanly as a real row.
--   * FK from item.session_id → t_session.id lets OBS trace UI jump from a
--     failed eval item directly to the chat session + root_trace_id (D7
--     re-uses the OBS trace pipeline). This requires real eval sessions
--     (M3a-b2 ScenarioRunnerTool change), not the synthetic "eval_<UUID>"
--     ids the legacy code used.
--   * Per-case attribution / judge_rationale / agent_final_output queries
--     (M5 closed-loop attribution drawer) become ordinary JPQL.
--
-- FK on task_id ON DELETE CASCADE:
--   * Aligns with EvalController#deleteEvalRun semantics (delete task → drop
--     its items). No orphan items.
--
-- root_trace_id:
--   * Mirror of t_session.active_root_trace_id snapshot at scenario completion.
--     Lets the OBS spans drawer pre-filter to "this eval case's spans" without
--     re-deriving from session_id (which can have multiple roots if the agent
--     spawned sub-trees). Nullable because legacy / migrated rows don't have it.
--
-- Indexes:
--   * idx_task_item_task : Items tab list query (WHERE task_id=...).
--   * idx_task_item_scenario : "recent runs of scenario X" (replaces V51 jsonb
--     scan path eventually — for now both coexist).
--   * idx_task_item_session : OBS trace drill-down (jump from session to its
--     eval item).

CREATE TABLE IF NOT EXISTS t_eval_task_item (
    id                   BIGSERIAL PRIMARY KEY,
    task_id              VARCHAR(36) NOT NULL,
    scenario_id          VARCHAR(64) NOT NULL,
    scenario_source      VARCHAR(16),
    session_id           VARCHAR(36),
    root_trace_id        VARCHAR(36),
    composite_score      NUMERIC(5,2),
    status               VARCHAR(16) NOT NULL,
    loop_count           INT,
    tool_call_count      INT,
    latency_ms           BIGINT,
    attribution          VARCHAR(64),
    judge_rationale      TEXT,
    agent_final_output   TEXT,
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_item_task FOREIGN KEY (task_id)
        REFERENCES t_eval_task(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_task_item_task     ON t_eval_task_item (task_id);
CREATE INDEX IF NOT EXISTS idx_task_item_scenario ON t_eval_task_item (scenario_id);
CREATE INDEX IF NOT EXISTS idx_task_item_session
    ON t_eval_task_item (session_id)
    WHERE session_id IS NOT NULL;
