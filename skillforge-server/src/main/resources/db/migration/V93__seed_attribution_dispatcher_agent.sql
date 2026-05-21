-- V93__seed_attribution_dispatcher_agent.sql
-- ATTRIBUTION-DISPATCHER-AGENT (Full pipeline): seed dedicated dispatcher
-- agent so cron #6 attribution-dispatcher-hourly actually triggers
-- AttributionDispatcherService logic via DispatchAttributionPatterns tool.
--
-- Spec: docs/requirements/active/ATTRIBUTION-DISPATCHER-AGENT/index.md
--
-- Pre-V93 bug: cron #6 fires attribution-curator directly with a generic
-- prompt ("Attribution dispatcher: scan unprocessed patterns..."). The
-- curator's STEP 1 (PatternRead) requires a patternId that the generic
-- prompt does not carry, so the curator immediately stops (verified via
-- trace 7c1aa5cc-ae9f-4de2-b53c-b7188a81aa3e, 2026-05-21 03:06 UTC).
--
-- V93 wires a dedicated 'attribution-dispatcher' system agent whose single
-- tool DispatchAttributionPatterns invokes
-- AttributionDispatcherService.dispatchPendingPatterns(maxDispatch) once per
-- cron tick, then async-fans-out attribution-curator sessions per eligible
-- pattern. Cron #6 is repointed to the new dispatcher agent (single agent
-- per cron — no behavior change to the existing curator wiring at V81).
--
-- enabled=FALSE conservative seed: operator manually flips on after Phase
-- Final verifies cron #6 wires + Bootstrap prompt swap + DispatchAttribution
-- Patterns tool registration all completed at boot. Once flipped on, the
-- cron fires hourly at :15 (V81 inherited cron expression, unchanged).

-- ---------- 1. Insert attribution-dispatcher system agent ----------
INSERT INTO t_agent (
    name,
    description,
    model_id,
    system_prompt,
    skill_ids,
    tool_ids,
    config,
    lifecycle_hooks,
    owner_id,
    is_public,
    status,
    execution_mode,
    agent_type,
    created_at,
    updated_at
)
SELECT
    'attribution-dispatcher',
    'System agent: hourly cron entry for the attribution flywheel. '
        || 'Calls DispatchAttributionPatterns once to scan t_session_pattern, apply 4 filters '
        || '(surface allowlist / member threshold / 24h cooldown / no active event), '
        || 'and async-dispatch attribution-curator per eligible pattern. '
        || 'Drives PROD-OPTIMIZATION-FLYWHEEL V3 step (2) (dispatcher routing).',
    -- B1 fix: align with the 5 other system agents (V69/V75/V79/V81/V85)
    -- whose default model is now xiaomi-mimo:mimo-v2.5-pro. The dispatcher
    -- itself only emits one tool_use + one final-summary JSON, so it does
    -- not need a high-end model — using the project-standard system-agent
    -- default keeps cost predictable and matches the rest of the fleet.
    'xiaomi-mimo:mimo-v2.5-pro',
    -- Placeholder swapped at boot by AttributionDispatcherBootstrap from
    -- classpath:attribution-dispatcher-system-prompt.md.
    'SEE_FILE:attribution-dispatcher-system-prompt.md',
    '[]',
    -- B2 fix part (a): top-level tool_ids column for V81 column-based
    -- filtering compatibility (and any future code that reads the column
    -- directly).
    '["DispatchAttributionPatterns"]',
    -- B2 fix part (b): config JSON also carries tool_ids so ChatService:742
    -- agentDef.getConfig().get("tool_ids") gates the actual allowlist used
    -- by the AgentLoop engine. Both writes must stay in sync.
    -- B5 fix: maxTokens=1024 is plenty for one DispatchAttributionPatterns
    -- tool_use round-trip plus a short final JSON summary — bumping above
    -- 1024 wastes budget; bumping below risks reasoning_content truncation.
    -- W1 fix: execution_mode duplicated inside config so the canonical
    -- config-driven runtime path matches the top-level column.
    '{"maxTokens": 1024, "temperature": 0.0, "execution_mode": "auto", "tool_ids": ["DispatchAttributionPatterns"]}',
    NULL,
    1,
    TRUE,
    'active',
    -- Top-level execution_mode column (kept in sync with V81/V79/V75 seed
    -- shape; the actual runtime read path goes via config but legacy
    -- callers/views may still consult the column).
    'auto',
    -- B3 fix: explicitly set agent_type='system' in the seed so the row is
    -- correctly typed from insert. AttributionDispatcherBootstrap's self-heal
    -- is defense-in-depth, but Bootstrap runs after ApplicationReadyEvent —
    -- any pre-Bootstrap reader (a fast monitor query, a hot dashboard load
    -- right at boot) would otherwise see agent_type='user' for ~seconds.
    'system',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'attribution-dispatcher'
);

-- ---------- 2. Rewire t_scheduled_task #6 to new dispatcher agent ----------
-- The V81 seed created the cron pointing at attribution-curator; this UPDATE
-- atomically swaps to the new dedicated dispatcher agent. enabled=FALSE
-- gives the operator one final "Phase Final verified, ready to flip on"
-- gate — V93 lands the wiring, operator flips on after smoke test.
--
-- Idempotent: the WHERE clauses guarantee the UPDATE is a no-op if either
-- the scheduled task row or the new dispatcher agent row is missing.
UPDATE t_scheduled_task
SET
    agent_id        = (SELECT id FROM t_agent WHERE name = 'attribution-dispatcher'),
    prompt_template = 'Dispatch attribution patterns',
    enabled         = FALSE,
    updated_at      = NOW()
WHERE name = 'attribution-dispatcher-hourly'
  AND EXISTS (SELECT 1 FROM t_agent WHERE name = 'attribution-dispatcher');
