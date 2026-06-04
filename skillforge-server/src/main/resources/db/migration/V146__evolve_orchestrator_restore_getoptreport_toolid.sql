-- V146__evolve_orchestrator_restore_getoptreport_toolid.sql
--
-- REGRESSION FIX. V144 (AUTOEVOLVE-CLOSE-LOOP 子轮2) rewrote the evolve-orchestrator
-- tool_ids wholesale to ADD ListActiveHarvestedScenarios, but in doing so it
-- silently DROPPED "GetOptReport" from the list (both the tool_ids column AND the
-- config JSON tool_ids). V145 (子轮3) copied V144's list verbatim (+ ReconcilePrediction),
-- so it stayed dropped.
--
-- Effect (found via live transcript of evolve session d5473b52, 2026-06-05): the
-- orchestrator's system prompt tells it to call GetOptReport to read the opt-report,
-- but GetOptReport was NOT in its tool allowlist — so it physically could not call it.
-- The model's reasoning text said "use GetOptReport" while every tool_use emitted
-- RunWorkflow instead; it looped RunWorkflow ~20x (each rejected "Workflow already
-- running: opt-report"), exhausted its loop budget, and the whole evolve run failed
-- at step 1 — 0 iterations, on every model (glm-5.1 AND mimo). It is NOT a model or
-- prompt-clarity problem: it is a missing tool.
--
-- This restores GetOptReport (the full 9-tool set) to BOTH the column and the config
-- JSON (kept in sync, see V131). The system_prompt is intentionally NOT touched
-- (V145's prompt is correct). Also raises maxTokens 8192 -> 32768: the orchestrator
-- runs on a reasoning model whose internal reasoning consumes the budget before it
-- emits the tool_call/content; 8192 was too tight for the V145-era per-turn workload
-- and truncated turns (empty partial -> continuation could not recover -> run died).
-- Idempotent UPDATE keyed on name; system_prompt / model_id untouched.

UPDATE t_agent
SET updated_at = NOW(),
    tool_ids = '["RunWorkflow","GetOptReport","GenerateCandidate","TriggerAbEval","GetAbResult","RecordIteration","PromoteCandidate","ListActiveHarvestedScenarios","ReconcilePrediction"]',
    config = '{"maxTokens": 32768, "temperature": 0.0, "execution_mode": "auto", "tool_ids": ["RunWorkflow","GetOptReport","GenerateCandidate","TriggerAbEval","GetAbResult","RecordIteration","PromoteCandidate","ListActiveHarvestedScenarios","ReconcilePrediction"]}'
WHERE name = 'evolve-orchestrator';
