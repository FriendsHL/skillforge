-- V158__seed_claude_code_acp_agent.sql
-- ACP-EXTERNAL-AGENT P1c-1: seed the `claude-code` agent so the main agent can
-- dispatch cc via the existing SubAgent tool (SubAgent dispatch agentName=claude-code).
--
-- Spec: docs/requirements/active/2026-06-19-ACP-EXTERNAL-AGENT/tech-design.md
--       (P1c 重塑设计 — Seam 1: cc 做成 SubAgent 的 "ACP runtime" agent 类型)
--
-- The `acp:` prefix on model_id marks this agent as an EXTERNAL ACP runtime
-- (mirroring the provider-prefix convention, e.g. `ark:glm-5.2`). At dispatch,
-- SubAgentTool.handleDispatch branches on this prefix and routes to
-- AcpAgentRunner.runAsSubAgent (which spawns cc via the ACP client) instead of
-- the SkillForge engine (ChatService.chatAsync). The model_id is NOT a real
-- LLM model — it is the runtime marker; the actual cc model is chosen by the
-- cc adapter (session/new default), so the SkillForge LlmProviderFactory never
-- sees this value (the engine path is bypassed).
--
-- Visibility: owner_id NULL + is_public TRUE + status 'active' so that
-- AgentTargetResolver.resolveVisibleTarget can find it from any user session
-- (same shape as other seeded SYSTEM agents — see V93). The D22 fan-out
-- exemption in SubAgentTool.detectRecursiveDispatch (SYSTEM agent, depth 0 → 1)
-- also applies, so a top-level agent can dispatch cc without tripping the
-- lineage guard.
--
-- Idempotent: WHERE NOT EXISTS guards re-seed on repeated migrate.

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
    'claude-code',
    'External Claude Code agent driven via ACP (Agent Client Protocol). '
        || 'Dispatch through the SubAgent tool (agentName=claude-code) to run a '
        || 'coding task in an external cc process; cc streams its work live into '
        || 'a child session and its final result is delivered back to the parent.',
    -- `acp:` runtime marker (NOT a real LLM model id) — see header.
    'acp:claude-code',
    -- cc is driven by its own system prompt inside the adapter; SkillForge does
    -- not inject one (the engine path is bypassed). Kept minimal but non-empty.
    'External Claude Code agent driven via ACP. The actual behavior is governed by '
        || 'the Claude Code runtime; this prompt is unused on the ACP path.',
    '[]',
    '[]',
    -- The engine path is bypassed for this agent (dispatch routes to AcpAgentRunner),
    -- so config is not functionally read here. But echo the fleet convention (V93+)
    -- so config stays in sync with the top-level execution_mode/tool_ids columns —
    -- dashboard tooling / AgentLoopEngine config readers consult config.tool_ids /
    -- config.execution_mode and a bare '{}' would mislead them. tool_ids stays []
    -- (cc carries no SkillForge tools); execution_mode mirrors the 'auto' column.
    '{"execution_mode":"auto","tool_ids":[]}',
    NULL,
    -- SYSTEM agent: no owner, publicly visible/dispatchable.
    NULL,
    TRUE,
    'active',
    'auto',
    'system',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'claude-code'
);
