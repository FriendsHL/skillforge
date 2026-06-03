# AUTOEVOLVE-CLOSE-LOOP — 闭环采纳 + 对靶改进 + benchmark 验证

> **创建**：2026-06-03
> **状态**：prd/tech-design draft（待用户 ratify 后分期 Full pipeline）
> **父需求包**：[AUTOEVOLVE-AGENT-LEVEL-BUNDLE](../2026-06-02-AUTOEVOLVE-AGENT-LEVEL-BUNDLE/)（agent 整体爬坡 V1，Phase 1-4 已交付）
> **参考依据**：`/Users/youren/myspace/research-docs/research/agent-harness-wiki/`（AHE `papers/agentic-harness-engineering.md` + `deep/claude-code-insights.md` + `concept/attribution.md`）

## 30 秒摘要（用户原话拆解）

> "把 run 了很多轮**真实有效的提升列出来** → **加按钮一键改 agent 配置**（整个闭环就差不多了）→ 然后**调效果 + 加真实 benchmark 看到底有没有效果**。"

evolve V1（Phase 1-4）把"机制"做齐了，但**端到端不可用 + 提升不一定真/对靶**。本包闭三个缺口：

1. **闭环采纳**：evolve 跑完 → dashboard 列出赢家 bundle + diff → **一键 Approve & Adopt** → 各面分别 promote 改 agent 配置（人触发、走守卫）。**这是"闭环就差不多了"的那块**。
2. **对靶改进**：让 evolve 产的提升**真且对靶** —— 候选 grounding 接到**可复现 eval 失败场景** + 带 **predicted_impact** + report 加丰富 facets + **MULTIPLE TIMES 重复加权**。
3. **benchmark 验证**：用 held-out benchmark 衡量 agent 质量，采纳前后 + 跨时间看**到底有没有真效果**。

## 为什么（证据，本会话调查得出）

- **采纳缺口**：`EvolveController` 只有 `/run`，无 promote 端点；`PromoteCandidate(surface=agent)` 明确拒绝；dashboard evolve 面板无 approve 按钮。**evolve 跑出赢家也落不了地。**
- **不对靶**：agent 3 的 eval 有 **9 个可复现真实失败**（GAIA/AgentBench/dogfood），但 evolve 从 **opt-report session issue** 出候选（跟那 9 个失败**脱节**），还拿**无关固定场景**当尺子 → 候选不对靶（实测 tool-budget 候选 75→75 平手、还把高调用任务砍坏）。
- **report 太固定**（用户判断 + 参考印证）：我们 5 enum + 一行 suggestion vs Claude Code `/insights` **6 维 55 enum + MULTIPLE TIMES 重复加权**；trace 在到出候选 LLM 前断了 4 跳。
- **没真 benchmark north-star**：36 eval 场景在，但没把"agent benchmark 分随时间涨没涨"当效果度量。

## 三期（详见 prd.md / tech-design.md）

| Phase | 内容 | 对应用户原话 |
|---|---|---|
| **P1 — 闭环采纳** | 后端 adopt 端点（各面分别 promote → 改 agent 配置）+ 前端赢家 bundle 展示 + diff + Approve&Adopt 按钮 | "列出提升 + 一键改配置 = 闭环差不多" |
| **P2 — 对靶改进** | 候选 grounding 接可复现失败场景 + predicted_impact + report 丰富 facets + MULTIPLE TIMES | "调效果"（让提升真且对靶）|
| **P3 — benchmark 验证** | benchmark north-star（agent 质量分 pre/post 采纳 + 跨时间趋势）+ infra 失败摘出分母 | "加真实 benchmark 看有没有效果" |

## 文档
- [`prd.md`](prd.md) · [`tech-design.md`](tech-design.md)
