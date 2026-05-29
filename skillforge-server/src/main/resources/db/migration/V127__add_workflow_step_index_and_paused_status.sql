-- V127__add_workflow_step_index_and_paused_status.sql
--
-- AUTOEVOLVING V1 — Sprint 2: humanApprove pause/resume + journal-replay.
--
-- 1) t_flywheel_run_step.step_index — the deterministic invoke-order index a
--    workflow assigns to each agent()/humanApprove() call (WorkflowContext
--    .nextStepIndex). Sprint 2 journal-replay looks up a cached step BY THIS
--    COLUMN (not by created_at, which is non-deterministic under parallel()).
--    nullable: every pre-existing OPT-REPORT / orchestrator step row stays null
--    and the legacy queries (findByRunId / findByRunIdOrderByCreatedAtAsc) are
--    unaffected. A partial UNIQUE index guards against a replay double-append
--    writing two rows at the same (run_id, step_index).
--
-- 2) t_flywheel_run.status — add 'paused' to the allow-list so a workflow run
--    can park on a humanApprove() gate. The CHECK constraint name is
--    chk_flywheel_run_status (V97 declared it as chk_opt_report_status, V124
--    renamed it). Original allowed values: pending / running / completed / error.
--    'paused' (6 chars) fits the existing status VARCHAR(16) — no column-width
--    change needed (the full 'paused_for_human_approve' label lives only in WS
--    payloads, never in the column).
--
-- step_kind needs no CHECK change: V124 added step_kind as a bare
-- ADD COLUMN ... DEFAULT 'subagent_dispatch' with NO CHECK constraint, so the
-- new 'human_approve' value is already accepted at the DB level.
--
-- Additive + backward compatible: no existing row's data changes.
--
-- Locking note (db W1): each ALTER TABLE ... DROP/ADD CONSTRAINT takes a brief
-- ACCESS EXCLUSIVE lock on t_flywheel_run; the CHECK re-validation scans
-- existing rows. t_flywheel_run is low-volume (one row per loop run) so the
-- window is negligible. DROP uses IF EXISTS for idempotency (re-runnable on a
-- partially migrated DB / flyway repair).

ALTER TABLE t_flywheel_run_step
    ADD COLUMN step_index INT;

CREATE UNIQUE INDEX ux_flywheel_run_step_run_idx
    ON t_flywheel_run_step (run_id, step_index)
    WHERE step_index IS NOT NULL;

ALTER TABLE t_flywheel_run
    DROP CONSTRAINT IF EXISTS chk_flywheel_run_status;

ALTER TABLE t_flywheel_run
    ADD CONSTRAINT chk_flywheel_run_status
        CHECK (status IN ('pending', 'running', 'completed', 'error', 'paused'));
