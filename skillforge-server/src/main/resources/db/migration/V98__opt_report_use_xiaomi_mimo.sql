-- OPT-REPORT-V1 followup (2026-05-22): switch the two new system agents
-- from claude:claude-sonnet-4-20250514 to xiaomi-mimo:mimo-v2.5-pro.
--
-- Why: V97 seeded report-generator + session-batch-annotator on Claude
-- Sonnet, but the local dogfood instance does not have ANTHROPIC_API_KEY
-- configured, so the agent loop crashes immediately on the first LLM call
-- (HTTP 401 from Anthropic provider) before producing any messages. All
-- other system agents (session-annotator, attribution-dispatcher,
-- attribution-curator, ...) already run on xiaomi-mimo successfully via the
-- bailian key, so aligning here keeps the OPT-REPORT chain runnable on the
-- existing key set without touching environment config.
--
-- Trade-off: xiaomi-mimo:mimo-v2.5-pro is the cross-system-agent default;
-- if someone later wants Claude Sonnet for the report-generator
-- specifically (better long-context reasoning for aggregation), set
-- ANTHROPIC_API_KEY and UPDATE this row back via the dashboard or a future
-- migration. The model_id field is dashboard-editable so no further migration
-- is needed for routine model swaps.

UPDATE t_agent
SET model_id = 'xiaomi-mimo:mimo-v2.5-pro',
    updated_at = NOW()
WHERE name IN ('report-generator', 'session-batch-annotator');

-- Clean up the one stuck "running" row produced by the failed initial trigger
-- so the dashboard does not surface a perpetually-running report. The error
-- reason documents the cause so the operator can correlate it with this
-- migration without grep'ing the BE log.
UPDATE t_opt_report
SET status = 'error',
    error_reason = 'generator session crashed: ANTHROPIC_API_KEY missing; switched to xiaomi-mimo in V98',
    updated_at = NOW()
WHERE status = 'running'
  AND created_at < NOW();
