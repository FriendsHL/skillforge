-- V138__agent_evolve_ab_run_subset_rates.sql — AUTOEVOLVE-AGENT-LEVEL-BUNDLE
-- Phase 3 line A item 4.
--
-- The whole-agent A/B already computes per-subset pass-rates to derive the
-- vs-best target_delta_pp / regression_delta_pp (§8 子点②). Phase 3's orchestrator
-- seed needs the ABSOLUTE candidate_general_rate each round to enforce the
-- vs-ORIGINAL general anchor (candidate_general ≥ original_general − ANCHOR, where
-- original_general = the first round's baseline_general_rate). Deriving absolutes
-- from deltas is error-prone for the LLM, so we persist the 4 absolute rates the
-- service already has and expose them via GetAbResult.readAgent.
--
-- All 4 are nullable DOUBLE in [0,100]; target rates are NULL in regression-only
-- mode (no target subset). Additive — existing rows get NULL.

ALTER TABLE t_agent_evolve_ab_run
    ADD COLUMN IF NOT EXISTS candidate_target_rate   DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS candidate_general_rate  DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS baseline_target_rate    DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS baseline_general_rate   DOUBLE PRECISION;
