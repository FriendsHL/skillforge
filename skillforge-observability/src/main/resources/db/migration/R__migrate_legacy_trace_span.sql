-- OBS-2 §M2 — Repeatable migration for legacy t_trace_span data → new tables.
-- See: docs/requirements/active/2026-05-02-OBS-2-trace-data-model-unification/
--      prd.md §M2  +  tech-design.md §3
--
-- Mode flip semantics (mirrors R__migrate_legacy_llm_call):
-- placeholder ${etl_trace_span_mode} drives Flyway's hash → toggling 'off' ↔ 'flyway'
-- triggers a re-run. One-shot ETL: switch mode=flyway in application.yml, restart,
-- verify, then revert to 'off' so subsequent restarts don't re-run.
--
-- Three sections:
--   1) AGENT_LOOP rows update existing legacy t_llm_trace rows with lifecycle /
--      aggregate fields (status / error / total_duration_ms / agent_name /
--      tool_call_count / event_count). Filtered to source='legacy' AND
--      status='running' so live rows owned by AgentLoopEngine lifecycle are
--      never overwritten and the update is idempotent on re-run.
--   2) TOOL_CALL rows → t_llm_span (kind='tool', source='legacy').
--   3) ASK_USER / INSTALL_CONFIRM / COMPACT / AGENT_CONFIRM rows
--      → t_llm_span (kind='event', event_type=LOWER(span_type), source='legacy').
--
-- Idempotency: ON CONFLICT (trace_id, span_id) DO NOTHING is the primary guard.
-- M1 dual-write aligns t_trace_span.id = t_llm_span.span_id (AgentLoopEngine
-- propagates the same UUID into both rows), so re-runs don't double-insert.
-- A NOT EXISTS clause keyed on (session_id, kind, source='live', iteration_index,
-- ±1s) guards the rare case where an M1 live span beat the ETL but did not
-- propagate the unified ID (defense-in-depth / mirrors OBS-1 R__ pattern).
--
-- ─────────────────────────────────────────────────────────────────────────────
-- DEPLOYMENT RUNBOOK (W1 from review-r1, addresses C4 operational gap):
--
-- Section 1 is UPDATE-only: it can only set aggregate fields on EXISTING
-- t_llm_trace rows. AGENT_LOOP rows in t_trace_span that have NO matching
-- t_llm_trace row will be silently skipped. R__migrate_legacy_llm_call.sql
-- is the only ETL that INSERTs AGENT_LOOP → t_llm_trace.
--
-- Required ordering when activating this ETL in any environment with pre-M1
-- historical data:
--   1. Run R__migrate_legacy_llm_call (toggle etl.legacy.mode=flyway → restart)
--      to backfill all AGENT_LOOP rows into t_llm_trace.
--   2. Verify 0 orphan AGENT_LOOPs remain:
--        SELECT count(*) FROM t_trace_span ts
--        LEFT JOIN t_llm_trace lt ON lt.trace_id = ts.id
--        WHERE ts.span_type='AGENT_LOOP' AND lt.trace_id IS NULL;
--      Expected result: 0. Non-zero ⇒ stop and rerun step 1 / investigate.
--   3. Run this ETL (toggle etl.trace_span.mode=flyway → restart).
--   4. Revert both modes to 'off' to prevent unintended re-runs.
--
-- Skipping step 2 risks the M3 read-path showing an incomplete trace list.
-- ─────────────────────────────────────────────────────────────────────────────
--
-- placeholder at compile time: ${etl_trace_span_mode}

DO $$
BEGIN
    IF '${etl_trace_span_mode}' = 'flyway' THEN
        ----------------------------------------------------------------------
        -- Section 1: AGENT_LOOP → t_llm_trace lifecycle / aggregate backfill
        ----------------------------------------------------------------------
        --
        -- Filters guarantee:
        --   * lt.source='legacy'  — never touch live rows (M1 lifecycle owns them).
        --   * lt.status='running' — first-time backfill only; after a successful
        --                            run rows transition to 'ok' / 'error', so a
        --                            re-run is a no-op (idempotent).
        --
        -- agent_name uses COALESCE so any caller that pre-populated the column
        -- wins; otherwise we copy t_trace_span.name (root_name semantics).
        UPDATE t_llm_trace lt
        SET status            = CASE WHEN ts.success THEN 'ok' ELSE 'error' END,
            error             = ts.error,
            total_duration_ms = ts.duration_ms,
            agent_name        = COALESCE(lt.agent_name, ts.name),
            tool_call_count   = (
                SELECT count(*) FROM t_trace_span c
                WHERE c.parent_span_id = ts.id
                  AND c.span_type      = 'TOOL_CALL'
            ),
            event_count       = (
                SELECT count(*) FROM t_trace_span c
                WHERE c.parent_span_id = ts.id
                  AND c.span_type IN ('ASK_USER','INSTALL_CONFIRM','COMPACT','AGENT_CONFIRM')
            )
        FROM t_trace_span ts
        WHERE ts.span_type = 'AGENT_LOOP'
          AND ts.id        = lt.trace_id
          AND lt.source    = 'legacy'
          AND lt.status    = 'running';

        ----------------------------------------------------------------------
        -- Section 2: TOOL_CALL → t_llm_span (kind='tool')
        ----------------------------------------------------------------------
        INSERT INTO t_llm_span (
            span_id, trace_id, parent_span_id, session_id, agent_id,
            kind, name, tool_use_id,
            input_summary, output_summary, blob_status,
            iteration_index, latency_ms, started_at, ended_at,
            error, source, created_at
        )
        SELECT
            ts.id,
            ts.parent_span_id,                        -- trace_id = AGENT_LOOP id
            ts.parent_span_id,                        -- parent_span_id = AGENT_LOOP id
            ts.session_id,
            s.agent_id,
            'tool',
            ts.name,
            ts.tool_use_id,
            ts.input,
            ts.output,
            'legacy',
            ts.iteration_index,
            ts.duration_ms,
            ts.start_time,
            ts.end_time,
            ts.error,
            'legacy',
            ts.start_time
        FROM t_trace_span ts
        LEFT JOIN t_session s ON s.id = ts.session_id
        WHERE ts.span_type     = 'TOOL_CALL'
          AND ts.start_time IS NOT NULL              -- t_llm_span.started_at NOT NULL
          AND ts.parent_span_id IS NOT NULL          -- t_llm_span.trace_id NOT NULL
          -- Defense-in-depth: skip when an M1 live tool span exists for the same
          -- (session_id, iteration_index) within ±1s. Mirrors R__migrate_legacy_llm_call
          -- (V36 / V37). M1 also aligns t_trace_span.id with t_llm_span.span_id, so
          -- ON CONFLICT below is the primary protection; this NOT EXISTS protects
          -- against any pre-M1 partial dual-write that drifted IDs.
          AND NOT EXISTS (
              SELECT 1 FROM t_llm_span existing
              WHERE existing.session_id      = ts.session_id
                AND existing.kind            = 'tool'
                AND existing.source          = 'live'
                AND existing.iteration_index = ts.iteration_index
                AND ABS(EXTRACT(EPOCH FROM (existing.started_at - ts.start_time))) < 1
          )
        ON CONFLICT (trace_id, span_id) DO NOTHING;

        ----------------------------------------------------------------------
        -- Section 3: 4 event types → t_llm_span (kind='event')
        ----------------------------------------------------------------------
        --
        -- LOWER(span_type) maps:
        --   ASK_USER         → ask_user
        --   INSTALL_CONFIRM  → install_confirm
        --   COMPACT          → compact
        --   AGENT_CONFIRM    → agent_confirm
        -- Matches the event_type values written by AgentLoopEngine.writeEventSpanSafe.
        INSERT INTO t_llm_span (
            span_id, trace_id, parent_span_id, session_id, agent_id,
            kind, event_type, name, tool_use_id,
            input_summary, output_summary, blob_status,
            iteration_index, latency_ms, started_at, ended_at,
            error, source, created_at
        )
        SELECT
            ts.id,
            ts.parent_span_id,
            ts.parent_span_id,
            ts.session_id,
            s.agent_id,
            'event',
            LOWER(ts.span_type),
            ts.name,
            ts.tool_use_id,
            ts.input,
            ts.output,
            'legacy',
            ts.iteration_index,
            ts.duration_ms,
            ts.start_time,
            ts.end_time,
            ts.error,
            'legacy',
            ts.start_time
        FROM t_trace_span ts
        LEFT JOIN t_session s ON s.id = ts.session_id
        WHERE ts.span_type IN ('ASK_USER','INSTALL_CONFIRM','COMPACT','AGENT_CONFIRM')
          AND ts.start_time      IS NOT NULL
          AND ts.parent_span_id  IS NOT NULL
          -- Same defense-in-depth as Section 2, plus event_type matching:
          -- multiple distinct event types can share an iteration, so we additionally
          -- key on event_type to allow both an ASK_USER and a COMPACT in the same
          -- loop iteration.
          AND NOT EXISTS (
              SELECT 1 FROM t_llm_span existing
              WHERE existing.session_id      = ts.session_id
                AND existing.kind            = 'event'
                AND existing.source          = 'live'
                AND existing.event_type      = LOWER(ts.span_type)
                AND existing.iteration_index = ts.iteration_index
                AND ABS(EXTRACT(EPOCH FROM (existing.started_at - ts.start_time))) < 1
          )
        ON CONFLICT (trace_id, span_id) DO NOTHING;

        RAISE NOTICE 'OBS-2 M2 ETL: imported via R__migrate_legacy_trace_span (mode=flyway)';
    ELSE
        RAISE NOTICE 'OBS-2 M2 ETL: skipped (placeholder etl_trace_span_mode=${etl_trace_span_mode}, not flyway)';
    END IF;
END $$;
