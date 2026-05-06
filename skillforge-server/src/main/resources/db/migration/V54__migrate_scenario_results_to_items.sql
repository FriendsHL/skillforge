-- EVAL-V2 M3a (b2): one-shot data migration of historical scenarioResultsJson
-- blobs into row-level t_eval_task_item entries.
--
-- Why PG-specific DO block + jsonb_array_elements:
--   * scenario_results_json is plain TEXT in t_eval_task (legacy V3 chose
--     TEXT, not JSONB) but the content is always a JSON array. We cast inline
--     via ::jsonb in the loop; if a row's payload is malformed the EXCEPTION
--     handler skips that task (with WARNING) so bad data doesn't fail the
--     entire migration.
--   * SkillForge runs PostgreSQL everywhere (Zonky embedded in dev, real PG
--     in prod, Testcontainers PG in IT — no H2 in any test path), so PG-only
--     DO BLOCK + jsonb_array_elements is safe. Verified by AbstractPostgresIT
--     and EmbeddedPostgresConfig.
--
-- Idempotency:
--   * Only migrate rows where status='COMPLETED' and scenario_results_json is
--     non-null/non-'null', and skip any task that already has at least one
--     item row. This makes the DO BLOCK safe to re-run after a partial
--     migration attempt.
--   * Within a task we still keep ON CONFLICT DO NOTHING as belt-and-suspenders,
--     but t_eval_task_item.id is BIGSERIAL with no business unique key, so the
--     task-level NOT EXISTS guard is the effective retry protection today.
--
-- Field mapping (jsonb → column):
--   * scenarioId           → scenario_id     (NOT NULL — guaranteed by EvalOrchestrator)
--   * compositeScore       → composite_score (NULLIF empty string → NUMERIC)
--   * status               → status          (PASS/FAIL/TIMEOUT/VETO/ERROR; default 'fail' fallback)
--   * loopCount            → loop_count
--   * executionTimeMs      → latency_ms
--   * attribution          → attribution     (FailureAttribution.name() string)
--   * judgeRationale       → judge_rationale
--   * agentFinalOutput     → agent_final_output
--   * (nothing for session_id / root_trace_id / scenario_source / tool_call_count)
--     — these columns are new in M3a; migrated rows have NULL. UI must
--     gracefully handle NULL for legacy items.

DO $$
DECLARE
    r                    RECORD;
    elem                 JSONB;
    parse_err_count      INT := 0;
    migrated_task_count  INT := 0;
    migrated_item_count  INT := 0;
BEGIN
    FOR r IN
        SELECT id, scenario_results_json
        FROM t_eval_task
        WHERE status = 'COMPLETED'
          AND scenario_results_json IS NOT NULL
          AND scenario_results_json <> ''
          AND scenario_results_json <> 'null'
          AND NOT EXISTS (
              SELECT 1
              FROM t_eval_task_item existing
              WHERE existing.task_id = t_eval_task.id
          )
    LOOP
        BEGIN
            FOR elem IN
                SELECT jsonb_array_elements(r.scenario_results_json::jsonb)
            LOOP
                INSERT INTO t_eval_task_item (
                    task_id,
                    scenario_id,
                    composite_score,
                    status,
                    loop_count,
                    latency_ms,
                    attribution,
                    judge_rationale,
                    agent_final_output,
                    created_at
                ) VALUES (
                    r.id,
                    elem->>'scenarioId',
                    NULLIF(elem->>'compositeScore', '')::NUMERIC,
                    COALESCE(elem->>'status', 'FAIL'),
                    NULLIF(elem->>'loopCount', '')::INT,
                    NULLIF(elem->>'executionTimeMs', '')::BIGINT,
                    elem->>'attribution',
                    elem->>'judgeRationale',
                    elem->>'agentFinalOutput',
                    now()
                )
                ON CONFLICT DO NOTHING;

                migrated_item_count := migrated_item_count + 1;
            END LOOP;

            migrated_task_count := migrated_task_count + 1;
        EXCEPTION WHEN OTHERS THEN
            parse_err_count := parse_err_count + 1;
            RAISE WARNING 'V54: failed to migrate task_id=% — error=%', r.id, SQLERRM;
        END;
    END LOOP;

    RAISE NOTICE 'V54 complete: migrated_tasks=%, migrated_items=%, parse_err_count=%',
        migrated_task_count, migrated_item_count, parse_err_count;
END $$;
