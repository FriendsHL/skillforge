-- OBS-1 §3.4 (R3-WN1): Repeatable migration for legacy ETL.
-- mode 切换语义对称 — placeholder ${etl_mode} 让 Flyway 在替换后计算 hash，
-- mode 改变 → SQL 文本不同 → hash 不同 → 重跑（ON CONFLICT 保证幂等）。
-- legacy ETL mode at compile time: ${etl_mode}

DO $$
BEGIN
    -- Read mode directly from the Flyway placeholder injected by ObservabilityFlywayConfig.
    -- Earlier draft used a PG session GUC + FlywayMigrationStrategy, but no code path
    -- set that GUC, so the SQL always took the skip branch. Placeholder is sufficient
    -- because Flyway's hash already follows it (forces re-run on mode flip).
    IF '${etl_mode}' = 'flyway' THEN
        -- OBS-4: include root_trace_id (self-as-root, matches V45 backfill semantics).
        -- Required after V46 SET NOT NULL — legacy ETL never had cross-agent semantics, so
        -- each legacy AGENT_LOOP trace acts as its own root (degenerate single-trace tree).
        INSERT INTO t_llm_trace (
            trace_id, root_trace_id, session_id, agent_id, user_id, root_name,
            started_at, ended_at, total_input_tokens, total_output_tokens,
            total_cost_usd, source, created_at
        )
        SELECT DISTINCT ON (ts.id)
            ts.id, ts.id, ts.session_id, s.agent_id, s.user_id, ts.name,
            ts.start_time, ts.end_time, ts.input_tokens, ts.output_tokens,
            NULL, 'legacy', ts.start_time
        FROM t_trace_span ts
        LEFT JOIN t_session s ON s.id = ts.session_id
        WHERE ts.span_type = 'AGENT_LOOP'
        ON CONFLICT (trace_id) DO NOTHING;

        INSERT INTO t_llm_span (
            span_id, trace_id, parent_span_id, session_id, agent_id,
            provider, model, iteration_index, stream,
            input_summary, output_summary,
            input_blob_ref, output_blob_ref, raw_sse_blob_ref, blob_status,
            input_tokens, output_tokens, cache_read_tokens, usage_json,
            cost_usd, latency_ms, started_at, ended_at,
            finish_reason, request_id, reasoning_content,
            error, error_type, tool_use_id, attributes_json,
            source, created_at
        )
        SELECT
            ts.id,
            COALESCE(ts.parent_span_id, ts.id),
            ts.parent_span_id,
            ts.session_id,
            s.agent_id,
            CASE
                WHEN ts.model_id LIKE 'claude%'   THEN 'claude'
                WHEN ts.model_id LIKE 'gpt-%'     THEN 'openai'
                WHEN ts.model_id LIKE 'o1-%'      THEN 'openai'
                WHEN ts.model_id LIKE 'o3-%'      THEN 'openai'
                WHEN ts.model_id LIKE 'deepseek%' THEN 'deepseek'
                WHEN ts.model_id LIKE 'qwen%'     THEN 'bailian'
                WHEN ts.model_id LIKE 'vllm%'     THEN 'vllm'
                WHEN ts.model_id LIKE 'ollama:%'  THEN 'ollama'
                ELSE 'unknown'
            END,
            ts.model_id,
            ts.iteration_index,
            TRUE,
            ts.input,
            ts.output,
            NULL, NULL, NULL,
            'legacy',
            ts.input_tokens,
            ts.output_tokens,
            NULL, NULL, NULL,
            ts.duration_ms,
            ts.start_time, ts.end_time,
            NULL, NULL, NULL,
            ts.error, NULL, ts.tool_use_id, NULL,
            'legacy',
            ts.start_time
        FROM t_trace_span ts
        LEFT JOIN t_session s ON s.id = ts.session_id
        WHERE ts.span_type = 'LLM_CALL'
          -- Dedup (BE-W3 R3 tighten): skip when a live span already exists for the
          -- same (session_id, iteration_index) within ±1s of this call's start_time.
          -- The previous 5s window risked dropping genuine legacy rows when an
          -- agent issued multiple LLM calls in quick succession; iteration_index
          -- is identically set by both writers (see V36 comment) so it's a safe
          -- additional join key. Without this guard, sessions that received LLM
          -- calls after OBS-1's observer began writing would get a phantom legacy
          -- duplicate of every live row. See V36__dedup_legacy_llm_span.sql.
          AND NOT EXISTS (
              SELECT 1 FROM t_llm_span existing
              WHERE existing.session_id      = ts.session_id
                AND existing.source          = 'live'
                AND existing.iteration_index = ts.iteration_index
                AND ABS(EXTRACT(EPOCH FROM (existing.started_at - ts.start_time))) < 1
          )
        ON CONFLICT (trace_id, span_id) DO NOTHING;

        RAISE NOTICE 'OBS-1 legacy ETL: imported via R__migrate_legacy_llm_call (mode=flyway)';
    ELSE
        RAISE NOTICE 'OBS-1 legacy ETL: skipped (placeholder etl_mode=${etl_mode}, not flyway)';
    END IF;
END $$;
