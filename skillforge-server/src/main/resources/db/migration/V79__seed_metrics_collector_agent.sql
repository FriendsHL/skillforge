-- V79__seed_metrics_collector_agent.sql — SKILL-CANARY-ROLLOUT V2 Phase 1.1 seed.
--
-- Two idempotent INSERTs (V75 mirror):
--   1) t_agent row `metrics-collector` (system agent, owner_id=1, is_public=TRUE,
--      lifecycle_hooks=NULL, status=active). system_prompt is the
--      SEE_FILE:metrics-collector-system-prompt.md sentinel, swapped at boot by
--      MetricsCollectorBootstrap from
--      classpath:metrics-collector-system-prompt.md.
--   2) t_scheduled_task row `metrics-collector-hourly` driving the above agent.
--      cron 0 30 * * * * (every hour at :30 — intentionally offset from V75's
--      session-annotator-hourly 0 0 * * * * so the two flywheel jobs don't
--      collide on top-of-hour resource spikes). session_mode=new,
--      enabled=TRUE, concurrency_policy=skip-if-running. creator_user_id=0
--      (SYSTEM marker, same convention as V69 / V75 / SkillSelfImproveLoop).
--
-- Conventions (V75 mirror):
--   * owner_id = 1 (admin) + is_public = TRUE — adopted in PROD-LABEL-CLUSTER V1.
--   * enabled = TRUE — dogfood path is the only canary metrics path; no V1
--     legacy @Scheduled competitor exists. Operator can flip via /schedules.
--   * lifecycle_hooks = NULL — no SESSION_END broadcast required (dashboard
--     polls metric_snapshot rows on visit; auto-rollback alerts come via
--     CanaryRolloutService.rollback path, not lifecycle hooks).
--
-- Tools list: ["RecomputeMetrics"] (Phase 1.4 implements the tool; this seed
-- declares the agent toolbox up-front so allowlist filtering works once the
-- tool registers).
--
-- Phase 1.1 校对 vs tech-design.md §3.2 / §4 (字段 BE-Dev 抄准):
--   * metrics-collector is a system AGENT (not a service @Scheduled) because
--     t_scheduled_task.agent_id is NOT NULL (V59 schema). The agent + tool
--     wrapping is the V1 V69 / V75 ratified pattern for system cron jobs.
--   * Default model claude-sonnet-4-6 matches V75 (no LLM reasoning happens
--     in this agent — single-step deterministic tool call — but the model id
--     is still required by the AgentEntity schema).

-- ---------- 1. metrics-collector system agent ----------
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
    created_at,
    updated_at
)
SELECT
    'metrics-collector',
    'System agent: hourly aggregation of canary rollout metrics. '
        || 'Calls RecomputeMetrics tool once per invocation; the tool finds active '
        || 'canary rollouts, scans t_session_annotation outcome+canary_group entries '
        || 'from the last hour, computes 4-dim score deltas + outcome distributions, '
        || 'writes bucket rows to t_canary_metric_snapshot, and triggers '
        || 'auto-rollback signal checks. Drives SKILL-CANARY-ROLLOUT V2 metrics flow.',
    'claude-sonnet-4-6',
    -- Placeholder swapped at boot by MetricsCollectorBootstrap from
    -- classpath:metrics-collector-system-prompt.md.
    'SEE_FILE:metrics-collector-system-prompt.md',
    '[]',
    '["RecomputeMetrics"]',
    -- Low temperature: no creative LLM judgement; the prompt instructs the
    -- agent to call exactly one tool and return its output.
    '{"temperature": 0.0, "maxTokens": 1024}',
    NULL,
    1,
    TRUE,
    'active',
    'auto',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'metrics-collector'
);

-- ---------- 2. ScheduledTask: hourly trigger for metrics-collector ----------
INSERT INTO t_scheduled_task (
    name,
    creator_user_id,
    agent_id,
    cron_expr,
    timezone,
    prompt_template,
    session_mode,
    enabled,
    concurrency_policy,
    status,
    created_at,
    updated_at
)
SELECT
    'metrics-collector-hourly',
    0,
    (SELECT id FROM t_agent WHERE name = 'metrics-collector'),
    -- 6-field Spring cron (sec min hr dom mon dow): hourly at :30, intentionally
    -- offset from V75 session-annotator-hourly (':00') to spread load.
    '0 30 * * * *',
    'Asia/Shanghai',
    'Aggregate canary metrics for active rollouts in the last hour. '
        || 'Call RecomputeMetrics(window_hours=1). The tool is fully '
        || 'deterministic; no LLM reasoning required.',
    'new',
    TRUE,
    'skip-if-running',
    'idle',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_scheduled_task WHERE name = 'metrics-collector-hourly'
)
AND EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'metrics-collector'
);
