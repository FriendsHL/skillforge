-- OPT-REPORT-V1 V1.1 followup (2026-05-23): tighten t_session_annotation UNIQUE
-- so LLM/Human annotations are unique per (session_id, type, source) — not per
-- (session_id, type, value, source).
--
-- Why: report 5421d55d's own output flagged "Annotation Competition" — re-running
-- generate-report against the same session produces conflicting outcome rows
-- (failure 0.82 + success 0.80; partial_success 0.70 + success 0.85). The
-- existing UNIQUE includes annotation_value, so different values are allowed,
-- which is what causes the conflict. Tightening the key forces one outcome
-- (and one suspect_surface, one top_failing_tool) per session per source.
--
-- Signal source is unaffected: each signal_reason is a distinct annotation_type
-- (high_token / multi_turn / tool_failure / ...) with value 'true', so the new
-- key doesn't conflict for signal rows.

-- Step 1: collapse existing duplicates for source='llm' (keep highest confidence,
-- tie-break newest). Same for 'human' though it's rare in dogfood.
DELETE FROM t_session_annotation a
USING t_session_annotation b
WHERE a.id <> b.id
  AND a.session_id = b.session_id
  AND a.annotation_type = b.annotation_type
  AND a.source = b.source
  AND a.source IN ('llm', 'human')
  AND b.source IN ('llm', 'human')
  AND (
      a.confidence < b.confidence
      OR (a.confidence = b.confidence AND a.created_at < b.created_at)
      OR (a.confidence = b.confidence AND a.created_at = b.created_at AND a.id < b.id)
  );

-- Step 2: drop old constraint and add new typed-unique one
ALTER TABLE t_session_annotation
    DROP CONSTRAINT IF EXISTS uq_session_annotation;

ALTER TABLE t_session_annotation
    ADD CONSTRAINT uq_session_annotation_typed
    UNIQUE (session_id, annotation_type, source);
