# SKILL-AB-MULTITURN-FIX PRD

---
id: SKILL-AB-MULTITURN-FIX
status: design-draft
owner: youren
priority: P1
risk: Mid
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-13
updated: 2026-05-13
---

## 摘要

修复 skill A/B 评测的现存 multi-turn fallback：当 held-out scenario 带有 `conversationTurns` 时，`SkillAbEvalService` 必须执行完整多轮对话并用 multi-turn judge 评分，不能只拿 `scenario.task` 跑 single-turn。

本包是 `EVAL-DYNAMIC-USER-SIM` 的 Phase 1 拆分包；后续 session 场景抽取、LLM user simulator、process-level judge 留在 backlog，独立评审。

## 已 Ratify 决策

1. **只接 `SkillAbEvalService`。** 本包服务 skill evolution / skill A/B 的 candidate 验证；`AbEvalPipeline` prompt A/B 仍保留 EVAL-V2 后续项，不在本包改。
2. **不改评分权重。** 轮数、loops、tokens、latency 只作为附加观测指标进入结果和日志，不进入 `EvalScoreFormula` 权重调整。M4_V2 刚完成 latency `not_measured` 重归一，本包不改综合分算法。
3. **runner 复用以 candidate skill 注入为前提。** `SkillAbEvalService.runSingleScenario(...)` 当前通过 `buildSandboxRegistryWithSkills(...)` 注入 candidate skill；多轮修复必须保持这点，不能直接调用只构建普通 sandbox registry 的 `ScenarioRunnerTool.runScenarioMultiTurn(...)`。

## 用户流程

1. 用户创建或导入 skill，并在真实业务会话中使用。
2. 用户或分析 agent 根据失败 session 优化 `SKILL.md`，形成 candidate skill。
3. SkillForge 沿用现有触发入口执行评测：skill evolution 自动触发 `SkillAbEvalService.createAndTrigger(...)`，也可通过现有 manual A/B、`POST /api/skills/{id}/evaluate` 和 scheduled evaluator 路径产生 baseline / 后续评测数据。
4. 对包含 `conversationTurns` 的 held-out scenario，A/B 执行完整多轮对话。
5. A/B 输出 candidate 的 pass/fail、score、status 和可追溯 session/trace 信息。
6. 现有 promote 策略继续使用 pass rate / delta 阈值，不新增动态模拟直接 promote。

## 功能需求

- `SkillAbEvalService` 必须识别 `scenario.isMultiTurn()`。
- multi-turn scenario 必须执行 `scenario.getConversationTurns()`，而不是降级使用 `scenario.getTask()`。
- multi-turn 执行必须在同一 eval session 内跨 turn 保留对话历史和 sandbox。
- candidate skill 必须注入到该 scenario 的 sandbox registry，保证跑的是 candidate `SKILL.md`。
- multi-turn scenario 必须调用 `EvalJudgeTool.judgeMultiTurnConversation(...)` 或同等共享逻辑。
- A/B 结果 JSON 必须保持现有字段兼容：`scenarioId`、`scenarioName`、baseline result、candidate result。
- candidate result 必须保留现有 wire 字段 `status`、`oracleScore`，并尽量追加 `sessionId`、`rootTraceId`、`loopCount`、`inputTokens`、`outputTokens`、`executionTimeMs`，便于排查。
- single-turn scenario 行为保持不变。
- timeout/error scenario 只影响该 scenario result，不应污染整个 A/B run 的持久化状态。
- candidate skill 修改导致 prompt cache stable prefix hash 变化是预期 cache miss，不做特殊规避。

## 非目标

- 不接 `AbEvalPipeline`。
- 不新增 REST endpoint。
- 不新增 dashboard 页面。
- 不新增 migration。
- 不引入 LLM user simulator。
- 不从 session 自动抽 `businessGoal` / `successCriteria` / `userPersona`。
- 不改变 `EvalScoreFormula`、promotion threshold、baseline pass rate 语义。
- 不让非确定性动态模拟结果参与自动 promote。

## 验收标准

- [ ] 有一个红测试能稳定复现当前 multi-turn fallback warning。
- [ ] 修复后，同一测试证明 multi-turn scenario 不再调用 single-turn 路径。
- [ ] multi-turn skill A/B 调用 multi-turn judge，并生成 candidate score / pass-fail。
- [ ] candidate skill definition 通过 sandbox registry 注入，测试能证明多轮执行使用 candidate 而不是 parent。
- [ ] single-turn skill A/B 现有测试继续通过。
- [ ] A/B run 失败场景有明确 per-scenario error result，run 状态和事件不回退。
- [ ] 无 schema migration、无新 endpoint、无前端行为变更。

## 后续 Backlog

`EVAL-DYNAMIC-USER-SIM` 独立 Full 包再处理：

- session 分析抽取业务目标、约束、用户画像、失败信号和成功标准。
- LLM `UserSimulatorAgent` 动态生成下一轮用户输入。
- process-level judge 对完整 transcript 评分。
- 多 trial / 成本控制 / 非确定性结果解释。
- UI 展示模拟 transcript 和改进前后差异。
