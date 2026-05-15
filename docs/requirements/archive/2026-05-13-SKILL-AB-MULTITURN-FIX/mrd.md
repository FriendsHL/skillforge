# SKILL-AB-MULTITURN-FIX MRD

---
id: SKILL-AB-MULTITURN-FIX
status: design-draft
owner: youren
priority: P1
risk: Mid
created: 2026-05-13
updated: 2026-05-13
---

## 背景

SkillForge 已经有 skill 生成、优化、fork candidate、A/B、自动 promote 的闭环。用户的核心问题是：一次 `SKILL.md` 优化以后，能不能证明它真的改善了真实多轮业务流程，而不是只在单轮任务上看起来更好。

EVAL-V2 已经提供 multi-turn scenario、`ScenarioRunnerTool.runScenarioMultiTurn(...)` 和 `EvalJudgeTool.judgeMultiTurnConversation(...)`。但 skill A/B 当前没有接上这条路径：`SkillAbEvalService` 遇到 multi-turn scenario 会 fallback single-turn。这会让 skill evolution 在多轮问题上低估风险，也让优化效果验证失真。

## 用户价值

- 用户优化 skill 后，可以用已有 held-out multi-turn scenario 验证 candidate 是否真的提升。
- 分析 agent 给出的 skill 优化建议，可以被同一批多轮场景复测，而不是靠手工模拟对话。
- A/B 自动 promote 不再绕过多轮失败案例，降低“单轮过关、多轮退化”的风险。

## 本包目标

本包只做 Phase 1：修 skill A/B 的 multi-turn fallback。

需要做到：

- `SkillAbEvalService` 对 multi-turn scenario 执行完整 `conversationTurns`。
- 使用 multi-turn judge 给完整 transcript 打分。
- 单轮 skill A/B 行为保持不变。
- 输出保留现有 A/B 结果结构，并补足必要的调试信息。

## 不在本包做

- 不从真实 session 自动抽业务目标、用户画像、约束和成功标准。
- 不实现 LLM user simulator。
- 不做 process-level judge 的新 schema。
- 不改 `EvalScoreFormula` 权重。
- 不改 `AbEvalPipeline` prompt A/B。
- 不新增 UI 或 endpoint。

这些后续能力统一回到 `EVAL-DYNAMIC-USER-SIM` backlog，等本包实测后独立开 Full 包。
