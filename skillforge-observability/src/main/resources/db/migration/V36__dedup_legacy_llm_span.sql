-- OBS-1 §3.4 follow-up: One-shot cleanup of duplicate legacy LLM spans.
--
-- The original R__migrate_legacy_llm_call.sql did not check whether a corresponding
-- live span (source='live', written by TraceLlmCallObserver) already existed before
-- importing legacy rows from t_trace_span. Sessions that received new LLM calls AFTER
-- the OBS-1 observer started writing therefore ended up with TWO t_llm_span rows per
-- logical LLM call: source='live' (random UUID span_id) and source='legacy'
-- (t_trace_span.id reused as span_id), surfacing as duplicates in the merged
-- SessionDetail timeline.
--
-- R__migrate_legacy_llm_call.sql has been amended with a NOT EXISTS subquery to
-- prevent this in future runs; this V36 cleans up the duplicates produced by the
-- single ETL run that happened during initial OBS-1 rollout (2026-04-29).
--
-- Match rule: same session_id, source='live', started_at within ±5s. We keep
-- legacy rows for sessions that never received a live counterpart (genuine
-- pre-OBS-1 history) and only drop the legacy rows that shadow a live record.

DELETE FROM t_llm_span L
WHERE L.source = 'legacy'
  AND EXISTS (
      SELECT 1 FROM t_llm_span V
      WHERE V.session_id  = L.session_id
        AND V.source      = 'live'
        AND ABS(EXTRACT(EPOCH FROM (V.started_at - L.started_at))) < 5
  );
