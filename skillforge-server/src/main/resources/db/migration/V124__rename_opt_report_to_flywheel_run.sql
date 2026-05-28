-- V124__rename_opt_report_to_flywheel_run.sql
-- OPT-LOOP-FRAMEWORK Sprint 1 (2026-05-28):
--   Rename t_opt_report → t_flywheel_run and t_opt_report_batch → t_flywheel_run_step,
--   add loop_kind / trigger_source / input_json columns so the same table can
--   record runs of every flywheel orchestrator (memory-curator, attribution,
--   metrics, ...), and create backward-compat SQL views so existing
--   OPT-REPORT-V1 consumers (legacy queries / scripts) keep working for one
--   release.
--
-- Ratified decisions (PRD §7):
--   * Q1=ALTER RENAME (no data copy; PG metadata op runs in ms)
--   * Q5=trigger_source 4 enum values (cron/user_manual/api/event)
--   * Q5=loop_kind 5 enum values (opt_report/memory_curation/attribution/
--                                 metrics_collection/custom)
--   * Q5=input_json free schema per loop_kind
--
-- Java-side rename: OptReportEntity → FlywheelRunEntity in package
-- com.skillforge.server.flywheel.run; OptReportBatchEntity → FlywheelRunStepEntity.
-- OptReportService keeps its public API (back-compat) and internally delegates
-- to the new FlywheelRunService.

-- ─────────────────────────────────────────────────────────────────────────
-- Phase 1: Rename tables
-- ─────────────────────────────────────────────────────────────────────────
ALTER TABLE t_opt_report RENAME TO t_flywheel_run;
ALTER TABLE t_opt_report_batch RENAME TO t_flywheel_run_step;

-- Also rename the old CHECK constraints so future operators don't see
-- "chk_opt_report_*" pinned to the renamed tables.
ALTER TABLE t_flywheel_run
    RENAME CONSTRAINT chk_opt_report_status TO chk_flywheel_run_status;
ALTER TABLE t_flywheel_run_step
    RENAME CONSTRAINT chk_opt_report_batch_status TO chk_flywheel_run_step_status;

-- Existing indices follow the table they're on; rename them so dashboards /
-- pg_stat_activity show the new names too. (PG keeps the underlying tree;
-- this is a pure metadata rename.)
ALTER INDEX idx_opt_report_agent_created RENAME TO idx_flywheel_run_agent_created;
ALTER INDEX idx_opt_report_batch_report RENAME TO idx_flywheel_run_step_report;

-- r1 DB W1 fix: rename the auto-generated FK constraint names. V97 declared
-- both FKs inline (`REFERENCES t_xxx(id)`) without an explicit CONSTRAINT
-- clause, so Postgres assigned them the default `<table>_<column>_fkey`
-- pattern (`t_opt_report_agent_id_fkey` on the run table,
-- `t_opt_report_batch_report_id_fkey` on the step table). PG does not
-- auto-rename FK names when the underlying table renames, so without this
-- step `\d t_flywheel_run_step` would still show the stale OPT-REPORT name
-- pointing at a column that's also been renamed (report_id → run_id below).
ALTER TABLE t_flywheel_run
    RENAME CONSTRAINT t_opt_report_agent_id_fkey TO t_flywheel_run_agent_id_fkey;
ALTER TABLE t_flywheel_run_step
    RENAME CONSTRAINT t_opt_report_batch_report_id_fkey TO t_flywheel_run_step_run_id_fkey;

-- ─────────────────────────────────────────────────────────────────────────
-- Phase 2: Add 3 new columns to t_flywheel_run
-- ─────────────────────────────────────────────────────────────────────────
-- All three default to OPT-REPORT-V1 historical semantics so existing rows
-- backfill to a sane state without needing a separate UPDATE for these enums.
ALTER TABLE t_flywheel_run
    ADD COLUMN trigger_source VARCHAR(32) NOT NULL DEFAULT 'user_manual'
        CONSTRAINT chk_flywheel_run_trigger_source
        CHECK (trigger_source IN ('cron', 'user_manual', 'api', 'event'));

ALTER TABLE t_flywheel_run
    ADD COLUMN input_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE t_flywheel_run
    ADD COLUMN loop_kind VARCHAR(32) NOT NULL DEFAULT 'opt_report'
        CONSTRAINT chk_flywheel_run_loop_kind
        CHECK (loop_kind IN ('opt_report', 'memory_curation', 'attribution',
                             'metrics_collection', 'custom'));

-- ─────────────────────────────────────────────────────────────────────────
-- Phase 3: Add step_kind + rename step columns for generalization
-- ─────────────────────────────────────────────────────────────────────────
ALTER TABLE t_flywheel_run_step
    ADD COLUMN step_kind VARCHAR(32) NOT NULL DEFAULT 'subagent_dispatch';

-- Rename the OPT-REPORT-specific column names to neutral step semantics.
-- The legacy view below maps them back to the old names for any consumer
-- still on the t_opt_report_batch view.
ALTER TABLE t_flywheel_run_step RENAME COLUMN report_id TO run_id;
ALTER TABLE t_flywheel_run_step RENAME COLUMN session_ids_json TO step_input_json;
ALTER TABLE t_flywheel_run_step RENAME COLUMN annotations_written_count TO step_output_count;

-- ─────────────────────────────────────────────────────────────────────────
-- Phase 4: Backfill input_json for existing OPT-REPORT rows
-- ─────────────────────────────────────────────────────────────────────────
-- OPT-REPORT-V1 rows were inserted with (agent_id, window_start, window_end)
-- as their canonical input. Reconstruct the equivalent input_json so
-- /api/flywheel/runs/{id} consumers can read it uniformly across all
-- loop_kinds.  window_start/window_end serialized as ISO-8601 text via to_char
-- — JSONB will keep them as plain strings (FE parses them with the existing
-- Instant ISO conventions).
UPDATE t_flywheel_run
SET input_json = jsonb_build_object(
        'agentId',     agent_id,
        'windowDays',  GREATEST(1, ROUND(EXTRACT(EPOCH FROM (window_end - window_start)) / 86400.0)::int),
        'windowStart', to_char(window_start AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'windowEnd',   to_char(window_end   AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
    )
WHERE loop_kind = 'opt_report'
  AND input_json = '{}'::jsonb;

-- ─────────────────────────────────────────────────────────────────────────
-- Phase 5: Indices on new columns
-- ─────────────────────────────────────────────────────────────────────────
-- Filter-by-loop_kind path (dashboard "All Flywheel Runs" page + compat views).
CREATE INDEX idx_flywheel_run_loop_kind
    ON t_flywheel_run (loop_kind, created_at DESC);

-- Partial index for the "live runs" filter on the dashboard.
CREATE INDEX idx_flywheel_run_status
    ON t_flywheel_run (status, created_at DESC)
    WHERE status IN ('pending', 'running');

-- Step lookup ordered chronologically per run (run-detail timeline render).
CREATE INDEX idx_flywheel_run_step_run_id
    ON t_flywheel_run_step (run_id, created_at);

-- ─────────────────────────────────────────────────────────────────────────
-- Phase 6: Backward-compat views for OPT-REPORT-V1 consumers
-- ─────────────────────────────────────────────────────────────────────────
-- These exist so any external script / SQL dashboard / one-off query still
-- referencing the old table names keeps working. The Java code itself stops
-- using these (it has switched to the new FlywheelRun* entities), so the
-- views are pure tomb-stones — to be dropped one release after rollout.
--
-- The view shapes match the OPT-REPORT-V1 columns exactly (same column names,
-- same order, only loop_kind='opt_report' / step_kind='subagent_dispatch'
-- rows are visible).
CREATE VIEW t_opt_report AS
SELECT id,
       agent_id,
       window_start,
       window_end,
       status,
       content_md,
       summary_json,
       error_reason,
       generator_session_id,
       created_at,
       updated_at
FROM t_flywheel_run
WHERE loop_kind = 'opt_report';

CREATE VIEW t_opt_report_batch AS
SELECT id,
       run_id                  AS report_id,
       sub_agent_session_id,
       step_input_json         AS session_ids_json,
       status,
       step_output_count       AS annotations_written_count,
       error_reason,
       created_at,
       updated_at
FROM t_flywheel_run_step
WHERE step_kind = 'subagent_dispatch';

COMMENT ON VIEW t_opt_report IS
    'OPT-LOOP-FRAMEWORK Sprint 1 backward-compat view. Drop one release after rollout.';
COMMENT ON VIEW t_opt_report_batch IS
    'OPT-LOOP-FRAMEWORK Sprint 1 backward-compat view. Drop one release after rollout.';
