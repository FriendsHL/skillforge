-- PROMPT-CACHE-MVP Phase 4 — add Anthropic cache_creation_input_tokens to t_llm_span.
--
-- The existing cache_read_tokens column (V33) was a stub never written by any provider;
-- this migration introduces the symmetric write-side counter and the OBS-1 dashboard
-- learns to surface both alongside hit-rate computation.
--
-- INV-11: column is nullable so legacy rows pre-V62 (and providers without write tokens —
-- DeepSeek / Qwen / OpenAI / mimo all auto-cache server-side and don't expose creation
-- counts) keep returning NULL → the dashboard renders 0 / "—" without a backfill step.
ALTER TABLE t_llm_span
    ADD COLUMN IF NOT EXISTS cache_creation_tokens INTEGER;

COMMENT ON COLUMN t_llm_span.cache_creation_tokens IS 'Anthropic prompt-cache write tokens (cache_creation_input_tokens). Other provider families (DeepSeek / Qwen / OpenAI / mimo) leave NULL - they auto-cache server-side without surfacing a creation counter.';
