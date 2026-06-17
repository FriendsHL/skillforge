-- V135__seed_evolve_editor_agent.sql
--
-- AUTOEVOLVE-AGENT-FLYWHEEL reflection (config + history aware evolve-editor) —
-- seed the 'evolve-editor' system agent whose system_prompt becomes the DECISION
-- BRAIN for prompt-candidate generation during the auto-evolving hill-climb.
--
-- This agent is NOT run as a conversational loop / sub-agent: it owns no tools
-- (tool_ids = []) per the user-ratified Option A (orchestrator-fed, editor is
-- passive). PromptImproverService.generateCandidatePromptFromAttribution reads
-- THIS row's system_prompt (via agentRepository.findFirstByName("evolve-editor"))
-- and uses it as the LLM system prompt when an EvolveEditorContext is present.
-- Defensive fallback in the service keeps a fresh install working if this row is
-- somehow absent.
--
-- Column shape mirrors V131 (evolve-orchestrator seed). model_id matches the
-- other system agents' default provider in the reference deployment. Idempotent:
-- WHERE NOT EXISTS guards re-runs / flyway repair.

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
    'evolve-editor',
    'System agent: the decision brain for prompt-candidate generation in the '
        || 'agent-driven auto-evolving flywheel. Given the opt-report direction + the '
        || 'target agent config + the prompt being edited + what changed last round + '
        || 'last round''s eval report, it outputs the next improved system prompt. '
        || 'Orchestrator-fed (Option A): owns no tools. Drives AUTOEVOLVE-AGENT-FLYWHEEL reflection.',
    'xiaomi-mimo:mimo-v2.5-pro',
    '你是 evolve-editor：在 agent 自动进化中决定下一步怎么改 prompt。' || chr(10) ||
    '输入：优化报告方向 + 目标 agent 当前配置 + 你正在改的 prompt + 上一轮改了什么 + 上一轮评测报告（哪些 case 提升/腐化 + 原因 + 整体涨跌）。' || chr(10) ||
    '只输出改进后的完整 system prompt 文本，不要解释。' || chr(10) ||
    '规则：保留原意图与能力；针对失败模式收敛；参考上轮评测——把腐化的 case 原因当反面教材、把提升的方向保留扩大。',
    '[]',
    '[]',
    '{"maxTokens": 2000, "temperature": 0.3, "execution_mode": "auto", "tool_ids": []}',
    NULL,
    1,
    TRUE,
    'active',
    'auto',
    'system',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'evolve-editor'
);
