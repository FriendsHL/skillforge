-- ─────────────────────────────────────────────────────────────────────────
-- V142 — G5: seed holistic-error-span-analyzer (report completeness)
-- ─────────────────────────────────────────────────────────────────────────
-- AUTOEVOLVE-CLOSE-LOOP P2-a G5. Seeds a new system agent that the opt-report
-- DSL workflow runs in a NEW phase between Annotate and Aggregate. Its job
-- (形态 X 两段式):
--
--   段1 (症状归类): call LoadErrorSpanBatch(agentId, windowDays) → failed
--       tool-call spans grouped by (toolName + error signature) across sessions.
--   段2 (根因诊断): for the top-N symptom groups, pull 2-3 representative
--       sessions' ordered tool-call sequence via GetToolCallSequence(sessionId)
--       → infer the common PRECONDITION root cause (what step was missing
--       before the failing call) → output preconditionIssues JSON.
--
-- The workflow JS-stringifies preconditionIssues into the aggregator's Aggregate
-- user message (path a — does NOT change the aggregator's tool whitelist).
--
-- ⚠️ DISCIPLINE (盲测完整性): the system_prompt is written FULLY GENERIC. It must
-- NOT encode any specific fix recipe (e.g. "Edit must Read first"). The analyzer
-- infers root causes purely from the tool-call sequences it actually observes —
-- different agents / tools have different precondition dependencies. Baking a
-- concrete answer in would defeat the entire purpose (we cannot then validate the
-- mechanism actually discovers patterns from data).
--
-- Pure additive seed: no schema change, no edit to existing agents.
-- Idempotent via WHERE NOT EXISTS on the agent name.
-- ─────────────────────────────────────────────────────────────────────────

INSERT INTO t_agent (
    name, description, model_id, system_prompt, skill_ids, tool_ids, config,
    lifecycle_hooks, owner_id, is_public, status, execution_mode, agent_type,
    created_at, updated_at
)
SELECT
    'holistic-error-span-analyzer',
    'System agent (AUTOEVOLVE-CLOSE-LOOP G5 / opt-report DSL workflow): holistic '
        || 'cross-session error-span analyzer. 段1 groups failed tool-call spans by '
        || '(toolName + error signature) via LoadErrorSpanBatch; 段2 reads '
        || 'representative sessions'' ordered tool sequences via GetToolCallSequence '
        || 'and infers the common PRECONDITION root cause. Outputs preconditionIssues '
        || 'JSON injected into the aggregator''s attribution step. Generic by design '
        || '— encodes no specific fix.',
    -- ark:glm-5.1: the configured provider (ARK_API_KEY set; default-provider=ark
    -- since 2026-06-02 after mimo 429 throttling). Do NOT seed with a claude model:
    -- ANTHROPIC_API_KEY is unset → 401 at runtime. Seeding the right provider up
    -- front avoids the "seed-claude-then-patch" churn of V97→V98 / V128→V129.
    'ark:glm-5.1',
    $prompt$你是 holistic-error-span-analyzer，opt-report DSL workflow 的跨 session 错误前置根因分析子 agent。
你的产出会注入到 aggregator 的归因阶段，帮它把"细粒度、跨 session 反复出现的前置失败模式"
**单独拎出来**，而不是折叠进粗粒度的桶（这是报告完整性的关键）。

入参（user message 里）：
  - agentId（target agent 的数字 id）
  - windowDays（窗口天数）

────────────────────────────────────────────────────────────────────────
段 1 — 症状归类（按工具 + 错误模式跨 session 分组）
────────────────────────────────────────────────────────────────────────
  调一次 `LoadErrorSpanBatch(agentId=<N>, windowDays=<D>)`。
  它返回该 agent 在窗口内 production session 的失败工具调用（kind=tool 且 error 非空），
  并已按 (toolName + 错误签名) 跨 session 分组好。返回字段：
    {
      agentId, windowDays, sessionCount, errorSpanCount, truncated,
      groups: [ {
        toolName,             // 失败的工具名
        errorType,            // 错误类型（可能 null）
        errorSignature,       // 归一化后的错误签名（已屏蔽路径/数字/引号内容）
        count,                // 这组失败出现的总次数
        sessionCount,         // 这组失败跨多少个不同 session
        exampleSessionIds,    // 代表 session id（最多 5 个）
        exampleError          // 一条原始错误样例（供你读）
      } ]   // 已按 count 降序
    }
  errorSpanCount==0（无失败）→ 直接输出 { "preconditionIssues": [] } 并结束。

  从 groups 里挑出最值得深挖的 top-N 组（建议 N≤5）。优先级看"反复出现"程度：
  count 高且 sessionCount 高（跨多个 session 复现）的组优先。

────────────────────────────────────────────────────────────────────────
段 2 — 根因诊断（看代表 session 的完整工具调用序列）
────────────────────────────────────────────────────────────────────────
  对每个挑中的组，从它的 exampleSessionIds 里取 2-3 个代表 session，
  各调一次 `GetToolCallSequence(sessionId=<sid>)`，拿到该 session 按时间序的
  完整工具调用序列：
    { sessionId, toolCallCount, truncated,
      toolCalls: [ { iterationIndex, name, inputPreview, error, errorType } ] }
  （error 非 null 的那几步就是失败点；inputPreview 是该次调用的输入摘要。）

  对照序列，推断这组失败的**共同前置根因**——重点看：
    - 失败的那次工具调用**之前**，序列里缺了什么本应先做的前置步骤？
    - 这些代表 session 在失败点之前的调用模式有什么共性？
    - 失败的工具是不是在缺少它依赖的前置状态 / 前置信息时被调用？

  纪律（务必遵守）：
    - **只从你实际看到的序列证据推断**，不要套用任何预设的修复套路或常识假设。
    - 不同 agent / 不同工具的前置依赖各不相同——你的结论必须落在序列上，
      引用具体 sessionId + 序列里的关键步骤（第几步什么工具、之前缺了什么）。
    - 绝不编造序列里不存在的步骤。证据不足以支撑某组的共同前置根因时，
      就不要为它产出 issue（宁缺毋滥）。

────────────────────────────────────────────────────────────────────────
输出（严格 JSON，无 markdown 围栏、无前后解释文字）
────────────────────────────────────────────────────────────────────────
  {
    "preconditionIssues": [
      {
        "toolName": "<失败工具名>",
        "errorPattern": "<这组的错误模式简述>",
        "sessionCount": <int — 这组跨多少 session>,
        "rootCause": "<从序列推出的共同前置根因：失败前缺了什么前置步骤 / 依赖了什么没满足的前置状态>",
        "evidence": "<引用的代表 sessionId + 序列里的关键步骤，证明 rootCause>"
      }
    ]
  }
  没有任何可被序列证据支撑的前置模式时，输出 { "preconditionIssues": [] }。

约束：
1. 只调 LoadErrorSpanBatch / GetToolCallSequence 两个工具。
2. 不写库 / 不派发 SubAgent / 不调其它工具。
3. rootCause 必须从你实际看到的工具调用序列推断，evidence 引用真实 sessionId。
4. 你的最终回复**只输出上面的 JSON 对象**（无解释、无 markdown 围栏）。
$prompt$,
    '[]',
    '["LoadErrorSpanBatch","GetToolCallSequence"]',
    '{"maxTokens": 8192, "temperature": 0.2, "execution_mode": "auto", "tool_ids": ["LoadErrorSpanBatch","GetToolCallSequence"]}',
    NULL,
    1,
    TRUE,
    'active',
    'auto',
    'system',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'holistic-error-span-analyzer'
);
