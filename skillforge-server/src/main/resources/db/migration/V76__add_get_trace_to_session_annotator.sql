-- V76__add_get_trace_to_session_annotator.sql — PROD-LABEL-CLUSTER V1 Phase 1.3.
--
-- Goal: wire the LLM-annotation step (STEP 2 of the session-annotator agent
-- pipeline) by extending the agent's toolbox with:
--   * GetTrace        — reuse the existing read-only trace inspector
--                       (skillforge-server/.../tool/GetTraceTool.java) instead
--                       of building a new SessionFetch tool. User-confirmed
--                       decision (Option D) — STEP 2.1 of the pipeline calls
--                       GetTrace(action='list_traces' / 'get_trace') to pull
--                       trace summary + capped span tree, then STEP 2.2 calls
--                       AnnotateSession with the LLM's judgment.
--   * AnnotateSession — new thin tool (Phase 1.3) that writes source='llm'
--                       rows into t_session_annotation.
--
-- Two effects of this migration:
--   1) UPDATE t_agent.tool_ids — adds GetTrace + AnnotateSession to the
--      session-annotator toolbox in dependency order:
--        DetectSignal → GetTrace (read trace) → AnnotateSession (write llm)
--          → RecomputeClusters
--      Note: RecomputeClusters is still a stub Bean unused until Phase 1.4 —
--      already present in V75 tool_ids; we keep it so the prompt's STEP 3
--      flow stays consistent. The agent's CONSTRAINT "if a tool returns an
--      error, log it and proceed" covers the partial-deploy window.
--
--   2) RESET t_agent.system_prompt back to the SEE_FILE:* sentinel — the
--      prompt md file was edited (STEP 2 split into 2.1 fetch + 2.2 judge)
--      and SessionAnnotatorBootstrap only re-loads when the column still
--      starts with 'SEE_FILE:'. Resetting lets the next boot re-swap the
--      placeholder for the updated prompt content. (Bootstrap currently
--      treats non-sentinel values as "operator edit — leave alone"; resetting
--      is the cheapest way to force a re-read without touching Bootstrap.)
--
-- Idempotency: UPDATE is naturally idempotent (running V76 twice produces
-- the same row). WHERE name='session-annotator' guards against any future
-- agent rename collision. updated_at bumped so audit columns reflect the
-- migration.
--
-- Known boundary (V1 dogfood, single-tenant):
--   GetTraceTool.assertSessionAccessible enforces session.userId == ctx.userId.
--   In V1 the agent runs as user_id=1 (admin) and all production sessions are
--   also under user_id=1 → the check is a no-op. V2+ multi-tenant will need a
--   "system-agent context bypass" — tracked as backlog (not in this package).

UPDATE t_agent
SET tool_ids = '["DetectSignalAnnotations","GetTrace","AnnotateSession","RecomputeClusters"]',
    system_prompt = 'SEE_FILE:session-annotator-system-prompt.md',
    updated_at = NOW()
WHERE name = 'session-annotator';
