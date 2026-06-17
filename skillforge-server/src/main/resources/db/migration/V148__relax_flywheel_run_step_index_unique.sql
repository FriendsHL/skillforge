-- ─────────────────────────────────────────────────────────────────────────
-- V148 — relax the t_flywheel_run_step (run_id, step_index) unique index to
--        include step_kind: (run_id, step_kind, step_index)
-- ─────────────────────────────────────────────────────────────────────────
-- AUTOEVOLVE-CLOSE-LOOP P1 (evolve-loop workflow re-architecture).
--
-- WHY. The evolve-loop workflow run (loop_kind=evolve) now carries BOTH:
--   (a) the deterministic workflow DAG steps written by the new tool() / agent()
--       host bindings — step_kind in {tool_call, subagent_dispatch}, whose
--       step_index = WorkflowContext.nextStepIndex() (0,1,2,3,...); and
--   (b) the evolve_iteration LEDGER steps written by RecordIteration via
--       FlywheelRunService.appendEvolveIterationStep — step_kind=evolve_iteration,
--       whose step_index = the 1-based ITERATION number (1,2,3,...).
--
-- These two namespaces overlap (e.g. the iter-1 ledger row has step_index=1, and
-- the iter-1 GetCandidateDiff tool_call row ALSO has step_index=1). The V127
-- unique index on (run_id, step_index) WHERE step_index IS NOT NULL treats them as
-- a collision → DataIntegrityViolation on insert. Before this re-architecture an
-- evolve run had ONLY ledger steps (the orchestrator was a top-level agent, not a
-- workflow), so the collision could not occur — it is new to the workflow path.
--
-- FIX. Make the natural key (run_id, step_kind, step_index) — which is ALREADY
-- the lookup key the journal-replay cache uses
-- (FlywheelRunStepRepository.findByRunIdAndStepIndexAndStepKind). Within a single
-- step_kind the index is still unique per run (nextStepIndex never repeats within
-- the workflow kinds; iteration numbers never repeat within evolve_iteration), so
-- this RELAXES the constraint without weakening any single-kind guarantee. No
-- consumer queries by (run_id, step_index) without step_kind.
--
-- Safe / additive: index-only change, no data migration. Idempotent guards.
-- ─────────────────────────────────────────────────────────────────────────

DROP INDEX IF EXISTS ux_flywheel_run_step_run_idx;

CREATE UNIQUE INDEX ux_flywheel_run_step_run_idx
    ON t_flywheel_run_step (run_id, step_kind, step_index)
    WHERE step_index IS NOT NULL;
