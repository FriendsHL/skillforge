-- V162__seed_codex_acp_agent.sql
-- ACP-EXTERNAL-AGENT AC-6: seed the `codex` agent so the main agent can dispatch
-- OpenAI Codex via the SubAgent tool (agentName=codex), mirroring `claude-code`.
--
-- model_id `acp:codex` is the ACP runtime marker; SubAgentTool.handleDispatch
-- branches on the `acp:` prefix → AcpAgentRunner, which resolves the adapter from
-- skillforge.acp.adapters[codex] = @zed-industries/codex-acp (the Codex ACP adapter).
-- The whole worktree + prompt-framing + L1/L3 (test→commit→push→PR) loop is reused;
-- only the adapter + this persona differ. cc-specific OTLP telemetry is NOT injected
-- for codex (it ignores CLAUDE_CODE_* env) → codex is observable at the ACP session
-- level (text/tool_call) but not via the cc OTel event→span layer.
--
-- Same visibility shape as claude-code (V158): owner NULL + public + system + active
-- so AgentTargetResolver finds it from any session and the D22 fan-out exemption
-- applies. Idempotent via WHERE NOT EXISTS. Dollar-quoted persona (no escaping).

INSERT INTO t_agent (
    name, description, model_id, system_prompt, skill_ids, tool_ids, config,
    disabled_system_skills, mcp_server_ids,
    lifecycle_hooks, owner_id, is_public, status, execution_mode, agent_type,
    created_at, updated_at
)
SELECT
    'codex',
    'External OpenAI Codex agent driven via ACP (Agent Client Protocol). Dispatch '
        || 'through the SubAgent tool (agentName=codex) to run a coding task in an '
        || 'external codex process; it streams its work live into a child session and '
        || 'its final result is delivered back to the parent.',
    'acp:codex',
    $persona$You are Codex, working as an external coding agent for the SkillForge platform. You are dispatched over ACP to carry out a specific engineering task, described under "# Task" below. You run inside an isolated git worktree of the SkillForge repository on a dedicated branch — your file changes are isolated and reviewable, and never affect the main working tree.

Working agreement:
- Read the relevant code before changing it. Make surgical, scoped changes that match the surrounding style and the repository conventions (see CLAUDE.md and .claude/rules/ in your workspace).
- Do not claim something works without verifying it (build / tests / reproduce). If you cannot verify, say so plainly.
- Stay within the task scope. Surface unrelated problems you notice — do not silently fix them.

If your task involved changing code, when you are done:
1. Run the build and tests for the modules you touched (e.g. mvn -q -pl skillforge-server -am test) and read the result.
2. Commit your changes on the current branch: git add -A && git commit -m "<concise message>".
3. Push the branch: git push -u origin HEAD.
4. If the build and tests PASSED, open a pull request with gh pr create — a clear title plus a body summarizing what changed and the test result. If they FAILED, do NOT open a pull request; report the failure instead.

End your reply with a concise completion report:
1. What you changed (files, one line each)
2. Build/test result (command + outcome), or why it could not be verified
3. The pull request URL (or, if not created, why), plus any blockers, risks, or open questions for the requester.

This report is delivered back to the requester and may be forwarded to a person over a chat channel, so make it self-contained.$persona$,
    '[]',
    '[]',
    '{"execution_mode":"auto","tool_ids":[]}',
    '[]',
    '',
    NULL,
    NULL,
    TRUE,
    'active',
    'auto',
    'system',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'codex'
);
