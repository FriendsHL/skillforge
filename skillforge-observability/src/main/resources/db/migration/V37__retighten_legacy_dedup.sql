-- OBS-1 BE-W3 R3 follow-up: Re-tighten legacy/live dedup window from 5s → 1s
-- AND add iteration_index dimension.
--
-- V36 used `session_id + ABS(started_at diff) < 5s` to delete legacy spans that
-- shadowed live spans. The 5s window was Code Review R2's W3 finding: an agent
-- that issues several LLM calls within hundreds of milliseconds (common during
-- iterative tool loops) could see a legacy span erroneously matched against an
-- adjacent (different) live call's iteration, deleting genuine pre-OBS-1 history.
--
-- This migration applies a tighter rule:
--   - same session_id
--   - same iteration_index (set identically by both writers — live observer reads
--     AgentLoopEngine.loopCtx.getLoopCount(); legacy ETL copies t_trace_span.iteration_index)
--   - started_at within ±1s (live + legacy are physically the same call ⇒ tens of ms diff)
--
-- We can't modify V36 in place (Flyway versioned migrations are immutable after
-- apply — checksum mismatch crashes startup), so this V37 runs as an additional
-- pass over already-processed data. ON CONFLICT/idempotency: pure DELETE, safe to
-- re-run; if V36 already removed everything matching, V37 is a no-op.
--
-- R__migrate_legacy_llm_call.sql has been updated separately to use the same
-- tightened (session_id, iteration_index, ±1s) guard for any future re-runs
-- (Repeatable migration — checksum changes are expected and trigger replay).

DELETE FROM t_llm_span L
WHERE L.source = 'legacy'
  AND EXISTS (
      SELECT 1 FROM t_llm_span V
      WHERE V.session_id      = L.session_id
        AND V.source          = 'live'
        AND V.iteration_index = L.iteration_index
        AND ABS(EXTRACT(EPOCH FROM (V.started_at - L.started_at))) < 1
  );
