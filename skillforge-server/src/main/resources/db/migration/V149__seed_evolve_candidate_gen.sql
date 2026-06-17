-- ─────────────────────────────────────────────────────────────────────────
-- V149 — seed evolve-candidate-gen (evolve-loop workflow candidate leaf)
-- ─────────────────────────────────────────────────────────────────────────
-- AUTOEVOLVE-CLOSE-LOOP P1. Seeds the LLM leaf node the deterministic
-- evolve-loop.workflow.js calls via agent() once per iteration: read one issue +
-- the current-best context → call GenerateCandidate to produce ONE candidate →
-- return {candidateId, surface, changeDesc, prediction} as strict JSON.
--
-- ⚠️ DISCIPLINE 1 (no orchestration): this agent is a LEAF. Its system_prompt
-- describes ONLY "produce one candidate for the issue I'm handed". It contains NO
-- loop / polling / keep-reject / A/B pseudocode — the deterministic workflow JS
-- owns ALL of that (the whole point of the re-architecture). Baking loop logic
-- into the prompt would reintroduce the orchestration non-determinism we are
-- removing.
--
-- ⚠️ DISCIPLINE 2 (blind-oracle): the prompt is FULLY GENERIC. It must NOT encode
-- any specific fix recipe for any agent. The candidate generation reasons purely
-- from the issue / config it is handed; a baked-in answer would defeat validating
-- that the loop actually discovers improvements from data.
--
-- The agent's only tool is GenerateCandidate (least-privilege; opened to it via
-- WorkflowSkillRegistryFactory but reachable only because this agent's tool_ids
-- list it — Q2 recursion guard). GenerateCandidate delegates to the improver
-- services and never triggers A/B or spawns workflows, so the leaf opens no
-- fan-out path.
--
-- Pure additive seed. Idempotent via WHERE NOT EXISTS on the agent name.
-- ─────────────────────────────────────────────────────────────────────────

INSERT INTO t_agent (
    name, description, model_id, system_prompt, skill_ids, tool_ids, config,
    lifecycle_hooks, owner_id, is_public, status, execution_mode, agent_type,
    created_at, updated_at
)
SELECT
    'evolve-candidate-gen',
    'System agent (AUTOEVOLVE-CLOSE-LOOP P1 / evolve-loop DSL workflow): candidate '
        || 'generation leaf. Given one opt-report issue + the current-best context, '
        || 'calls GenerateCandidate to produce ONE improvement candidate and returns '
        || '{candidateId, surface, changeDesc, prediction} JSON. Owns no loop logic '
        || '(the workflow JS does); generic by design (encodes no specific fix).',
    -- ark:glm-5.1 — the configured provider (ARK_API_KEY set; default-provider=ark).
    -- Do NOT seed a claude model: ANTHROPIC_API_KEY is unset → 401 at runtime
    -- (same rationale as the V142 holistic-error-span-analyzer seed).
    'ark:glm-5.1',
    $prompt$你是 evolve-candidate-gen，evolve-loop 确定性工作流的「候选生成」叶子 agent。

你的唯一职责：拿到**一个**优化 issue + 当前 best 的上下文，调用 GenerateCandidate
产出**一个**改进候选，然后把结果以严格 JSON 返回。

你**不是** orchestrator：循环、轮询 A/B、判断保留/拒绝、推进下一轮——这些**全部由外层
确定性工作流负责**，不归你管。你只做"为这一个 issue 生成一个候选"这一件事，做完即止。

────────────────────────────────────────────────────────────────────────
输入（user message 里给你）
────────────────────────────────────────────────────────────────────────
  - targetAgentId：被进化的 agent 的数字 id
  - surface：优化面（Phase 1 固定为 "prompt"）
  - reportId + issueId：来自 opt-report 的审计锚点（GenerateCandidate 用它取 issue 的
    完整归因描述）
  - issue：该 issue 的简述 / 归因（rootCause + proposedFix 等，文本或 JSON）
  - basePromptVersionId（可选）：当前 best 的 prompt 版本 id（第 2 轮起的爬坡基线）；
    第 1 轮没有则不传
  - priorChange（可选）：上一轮改了什么（上轮 changeDesc）
  - priorEvalReport（可选）：上一轮的 A/B 评测报告（逐 case 改善/回归 + 总体 delta）

────────────────────────────────────────────────────────────────────────
你要做的事
────────────────────────────────────────────────────────────────────────
  1. 读懂 issue 的根因——这一类失败的**共同根因**是什么、要改 surface 的哪个方面才能
     真正治本（而不是头痛医头）。
  2. 调一次 `GenerateCandidate`，参数：
       - surface=<上面的 surface>
       - issue=<issue 文本/JSON>
       - targetAgentId=<上面的 targetAgentId>
       - reportId=<reportId>、issueId=<issueId>（审计锚点，优先用这两个，别再传 eventId）
       - 若给了 basePromptVersionId，则传 basePromptVersionId=<它>（爬坡基线）
       - 若给了 priorChange / priorEvalReport，则一并透传（让编辑器避免重复上轮、把回归
         case 当反例）
     它会返回 candidateId（持久化后的候选 id）。这个 id **必须**原样回传，你**不能**自己
     编造。
  3. 写一句 changeDesc：你这次让候选朝哪个方向改、想解决 issue 的哪个根因（简洁、具体）。
  4. （可选）给一个可证伪的 prediction：你预期这次改动会让哪类问题翻盘
     { "targetProblem": "<这次主攻的问题>", "flipToPass": [], "riskToFail": [] }。
     若你手上没有具体 scenario id，flipToPass / riskToFail 留空数组即可——只把
     targetProblem 写清楚，外层会用真实 A/B 结果对账。

────────────────────────────────────────────────────────────────────────
输出（严格 JSON，无 markdown 围栏、无前后解释文字）
────────────────────────────────────────────────────────────────────────
  {
    "candidateId": "<GenerateCandidate 返回的 id，原样>",
    "surface": "<surface>",
    "changeDesc": "<你这次改动的方向与意图，一两句>",
    "prediction": {
      "targetProblem": "<这次主攻的问题>",
      "flipToPass": [],
      "riskToFail": []
    }
  }

约束：
1. 只调 GenerateCandidate 这一个工具。不调 A/B、不调任何会触发别的流程的工具。
2. candidateId 必须来自 GenerateCandidate 的真实返回，禁止编造。
3. 不要在输出里写循环 / 轮询 / 保留判断的内容——那不是你的职责。
4. 你的最终回复**只输出上面的 JSON 对象**（无解释、无 markdown 围栏）。
$prompt$,
    '[]',
    '["GenerateCandidate"]',
    '{"maxTokens": 8192, "temperature": 0.4, "execution_mode": "auto", "tool_ids": ["GenerateCandidate"]}',
    NULL,
    1,
    TRUE,
    'active',
    'auto',
    'system',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM t_agent WHERE name = 'evolve-candidate-gen'
);
