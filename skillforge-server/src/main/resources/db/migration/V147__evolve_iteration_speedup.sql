-- V147__evolve_iteration_speedup.sql
--
-- Iteration-time optimization (AUTOEVOLVE-CLOSE-LOOP, after BC-M2b live verification).
-- Measured on the first fully-successful live evolve iteration (run 5eb9c71e,
-- 2026-06-05): one iteration = ~25.6 min, split almost evenly between
--   - opt-report attribution : 12.3 min (parallel curator fan-out ~6.3 min +
--                              a single final aggregator step ~6.1 min), and
--   - A/B eval               : 11.4 min (50 scenarios x 2 arms = 100 sandbox
--                              loops @ scenario-concurrency 6, 0 timeouts, 0 429s).
-- candidate-gen was negligible (~1.1 min). The "稳健" speedup tier touches only
-- INFRA models (attribution/budget), never the unit under test (the target agent's
-- model nor the scenario set), so A/B fidelity is unchanged.
--
-- Change 1 — opt-report-aggregator model: ark:glm-5.1 -> ark:doubao-seed-2.0-pro.
--   The aggregator is the single slowest step of opt-report (~6.1 min for ONE
--   sub-agent call = 25% of the whole iteration). It runs on glm-5.1, a REASONING
--   model that burns wall-clock on internal reasoning. The aggregator only
--   SYNTHESIZES the curators' findings into a report (it does not do the deep
--   per-case analysis — the curators do, and they stay on glm-5.1), so a capable
--   NON-reasoning model is the right fit. doubao-seed-2.0-pro is non-reasoning
--   (same family as the judge's doubao-seed-2.0-lite; glm/kimi/minimax are the
--   reasoning models per application.yml ark notes) and "pro" tier handles the
--   8192-token report synthesis. Curators (attribution-curator) are intentionally
--   left on glm-5.1 to preserve attribution quality. config is unchanged.
--
-- Change 2 — evolve-orchestrator max_duration_seconds: 1800 -> 2400 AND persist it.
--   The orchestrator session is the long-running outer loop; it must stay alive long
--   enough to drive maxIter iterations. The 1800s (30 min) value was set live (not in
--   a migration) during BC-M2b verification, so a fresh DB rebuild would silently fall
--   back to AgentLoopEngine's 600s default. This migration persists it and bumps to
--   2400s (40 min) so that — with the faster opt-report + A/B above (~26 -> ~16 min/iter)
--   — at least 2 iterations fit, and a 3-iteration run needs only a modest further bump.
--   All other config fields (maxTokens / temperature / execution_mode / tool_ids, the
--   9-tool set incl. GetOptReport restored in V146) are preserved verbatim.
--
-- Idempotent UPDATEs keyed on name; system_prompt / tool_ids column untouched.

UPDATE t_agent
SET updated_at = NOW(),
    model_id = 'ark:doubao-seed-2.0-pro'
WHERE name = 'opt-report-aggregator';

UPDATE t_agent
SET updated_at = NOW(),
    config = '{"tool_ids": ["RunWorkflow","GetOptReport","GenerateCandidate","TriggerAbEval","GetAbResult","RecordIteration","PromoteCandidate","ListActiveHarvestedScenarios","ReconcilePrediction"], "maxTokens": 32768, "temperature": 0.0, "execution_mode": "auto", "max_duration_seconds": 2400}'
WHERE name = 'evolve-orchestrator';
