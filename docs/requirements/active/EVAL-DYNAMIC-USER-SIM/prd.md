# EVAL-DYNAMIC-USER-SIM PRD

---
id: EVAL-DYNAMIC-USER-SIM
status: design-draft
owner: youren
priority: P2
risk: High
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-16
updated: 2026-05-16
---

## 摘要

把"A/B 评测通过 = 改进有效"升级为"A/B + canary + dynamic sim 三因子全过 = 真有效"。新增 6 字段 scenario 抽取 + UserSimulatorAgent 多轮 sim + ProcessLevelJudge + 阶段性 auto_promote_after_dynamic_sim config。

## 已 Ratify 决策 (2026-05-16 与 user 完成)

| # | 决策 | 锁定 why |
|---|---|---|
| 1 | **trial × persona = 5 固定 persona × 1 trial** | 第一版 cost 可控 (5 trial × N turn × 2N+1 LLM call/trial 已经不便宜)；persona 池 dogfood 数据稳定后再加；不走随机 persona (难复现)；不走单 persona × 多 trial (单视角失败模式漏测) |
| 2 | **auto_promote_after_dynamic_sim 阶段性 config，默认 false** | 跟 V2 `auto_promote_after_ab` ratify pattern 一致；dogfood 期保守，UserSim 数据稳定后（与真用户 outcome 对照准确率 > 80%）改 true |
| 3 | **multi-trial 聚合 = worst case (任一 trial fail → 整 candidate 拒)** | 非确定 LLM 偶尔失败 = 生产真实失败率；conservative 起步；trial 数加到 10+ 后再考虑放松到 majority |
| 4 | **tau-bench V5 不引入，进 backlog** | 当前 framework 阶段没收益；cost 高；需 adapter (SessionScenario format ↔ tau-bench format)；等"需要对外横向对标 framework strength" 时挂一层 eval module adapter (User 原话: "能够评价我们框架强弱的一个事情，只不过这个成本较高，且对我们当前框架没什么收益 还需要增加接口进行一些适配") |

## 用户流程

1. operator 改 skill / prompt / behavior_rule (V4 三 surface)
2. candidate 生成 (V3 attribution path 或 manual draft)
3. **A/B 评测** (V4 AbstractAbEvalRunner) 跑 held-out scenario → 过 stage = `ab_passed`
4. **(NEW) Dynamic Sim 评测** (V5 本期): operator dashboard 点 "Run Dynamic Sim" 或 cron 自动 → UserSimulatorAgent × 5 persona × 1 trial × N turn → ProcessLevelJudge 评分 → 全过 → stage = `dynamic_sim_passed`
5. **三因子 Gate**: 看 stage = `ab_passed` AND `canary_passed` AND `dynamic_sim_passed`
   - `auto_promote_after_dynamic_sim=true` → 直接 promote
   - `auto_promote_after_dynamic_sim=false` (dogfood 默认) → operator 点 "Publish" 按钮
6. promote → 写 `t_optimization_event.stage='promoted'`

## 功能需求

### F1. SessionScenarioExtractorService 6 字段抽取

- `EvalScenarioEntity` 加 6 列 (V84 Flyway migration)：
  - `business_goal TEXT` — 用户真正要干啥
  - `success_criteria TEXT` — 完成的客观标准 (multi-line / JSON list 均可)
  - `user_persona TEXT` — persona 描述
  - `user_constraints TEXT` — 隐性约束
  - `failure_signals TEXT` — 失败信号
  - `expected_outcome TEXT` — 期望结果
- `SessionScenarioExtractorService.extractFromSession()` LLM prompt 扩抽 6 字段 → 写入新列
- 历史 scenario 6 字段允许 NULL (向后兼容)

### F2. UserSimulatorAgent system agent

- V85 Flyway seed `user-simulator` (owner_id=1 / is_public=TRUE / pattern 同 session-annotator / metrics-collector / attribution-curator)
- system prompt: "你是用户模拟器，按以下 persona 跟 agent 多轮对话直到任务完成 / 失败信号触发 / 超 max_turns"
- 输入 (per trial): `{businessGoal, persona, userConstraints, max_turns}` + candidate agent endpoint
- 输出: `{transcript, turnsUsed, terminationReason, observedFailureSignals}`
- 新 tool `RunSimulatorTrial(scenarioId, candidateAgentVersionId, persona) → SimulationTranscript`
- 新 tool `RecordSimulationResult(scenarioId, trialId, transcript, judgeOutput)` 写 DB

### F3. ProcessLevelJudge

- 新 service `ProcessLevelJudgeService` (类似 EvalJudgeTool 模式)
- 输入: `(scenario, transcript)`，scenario 含 successCriteria / userConstraints
- 输出 `ProcessJudgeOutput`:
  - `taskCompleted: boolean`
  - `constraintsSatisfied: Map<String, boolean>` per constraint
  - `repetitionCount: int` (agent 重复同信息几次)
  - `toolCallCount / toolCallFailures: int / int`
  - `turnsUsed: int`
  - `inputTokens / outputTokens / executionTimeMs`
  - `compositeScore: double` (0.0-1.0)
  - `passFail: PASS / FAIL`
- LLM judge prompt: "你看完整 transcript，按 6 个维度评分 + 综合 pass/fail"

### F4. AbstractAbEvalRunner 三因子 hook 扩展

- V4 抽象 `judgeAndCompare(baseline, candidate)` 现状只做 single-judge
- V5 加 hook `runDynamicSim(candidate, scenarios) → DynamicSimResult` (default impl = no-op)
- `SkillAbEvalService` / `PromptImproverService` / `BehaviorRuleImproverService` 各 override 接 dynamic sim path
- 5 persona × 1 trial = 5 transcript / candidate → 5 ProcessJudgeOutput → worst case 聚合 → `dynamic_sim_passed` 写入 `t_optimization_event.stage`

### F5. PromoteGate 三因子聚合

- 现有 `runCandidateEvalSet` 路径加 dynamic sim 阶段
- `t_optimization_event` 加新 stage `dynamic_sim_passed` / `dynamic_sim_failed`
- ALLOWED_TRANSITIONS Map 扩 2 edge (`ab_passed → dynamic_sim_running → dynamic_sim_passed/failed → promoted/canary_started`)
- `auto_promote_after_dynamic_sim` config 在 `application.yml` 默认 false

### F6. 生产数据隔离

- `t_session.origin` 加 `'user_sim'` 枚举值 (V86 migration 加 CHECK 约束)
- UserSimulatorAgent 创建的所有 session 必须 `origin='user_sim'`
- V1 PatternClusteringService / V2 CanaryMetricsService / V3 AttributionDispatcher 全部加 `WHERE origin != 'user_sim'` 过滤 (不污染聚类 / metric / attribution)

### F7. FE Dashboard

- 新 page `pages/DynamicSimResults.tsx` 路由 `/insights/dynamic-sim` (或 Insights 第 4 tab，跟 OptimizationEvents / BehaviorRuleEvolution 同构 — 待 spec 时拍)
- 列 dynamic sim run 列表 + 每 run 展开 5 transcript + 每 transcript 展开 turn-by-turn 对话 + ProcessJudge 6 维度评分
- "Run Dynamic Sim" 按钮 (per candidate manual 触发)
- 三因子 gate 在 `EventDetailDrawer` 已展示 stage timeline，加 `dynamic_sim_passed` 节点

## 非目标

- **不替换** V4 现有 A/B 评测 (V5 是第三因子)
- **不引入 tau-bench** (ratify #4)
- **不动 V5 Phase 1 multi-turn runner** (`runMultiTurnScenario` 保留)
- **不改 V2 `auto_promote_after_ab` 默认值** (V5 加独立 config)
- **不修 prompt canary** (V2 设计如此)
- **不动 V4 OptimizableSurface 7-method 接口** (扩展，不修改)
- **不破 Iron Law 核心 7+1 文件 + 3 FE 文件 = 0 diff**

## 验收标准

### 代码

- [ ] V84 migration 加 6 字段；V85 seed user-simulator；V86 t_session.origin CHECK 加 'user_sim'
- [ ] `SessionScenarioExtractorService.extractFromSession()` 6 字段 prompt + parse + 写 DB；历史 NULL 兼容
- [ ] `UserSimulatorAgent` system agent 真启动；`RunSimulatorTrial` + `RecordSimulationResult` 2 tool 注册
- [ ] `ProcessLevelJudgeService.judge(scenario, transcript) → ProcessJudgeOutput` 6 维度 + pass/fail
- [ ] AbstractAbEvalRunner `runDynamicSim` hook 默认 no-op + 3 surface 各 override
- [ ] `t_optimization_event` ALLOWED_TRANSITIONS 加 dynamic_sim 状态机 edge
- [ ] 5 固定 persona × 1 trial worst case 聚合实施
- [ ] V1 / V2 / V3 各路径加 `origin != 'user_sim'` 过滤防污染
- [ ] FE DynamicSim panel 展示 transcript + 6 维度评分
- [ ] `auto_promote_after_dynamic_sim` config 默认 false

### 测试

- [ ] `SessionScenarioExtractorServiceTest` 6 字段抽取 happy + null backward-compat
- [ ] `UserSimulatorAgentTest` MockMvc + LLM stub 验对话生成
- [ ] `ProcessLevelJudgeServiceTest` 6 维度评分 + worst case 聚合 + LLM stub
- [ ] `AbstractAbEvalRunnerDynamicSimTest` 三 surface override + default no-op
- [ ] `OptimizationEventDynamicSimStageTest` 状态机扩展
- [ ] V1 / V2 / V3 各加 `origin != 'user_sim'` regression test
- [ ] FE `DynamicSimResults` page tsc + npm build EXIT=0
- [ ] mvn 全套 BUILD SUCCESS / 0 fail / Iron Law 守住

### Dogfood

- [ ] 真 candidate skill 跑过完整 A/B + canary + dynamic sim 三因子，dashboard 显示 3 stage 全过 → operator 手动 publish (auto_promote_after_dynamic_sim=false 路径)
- [ ] 真 candidate 跑过 dynamic sim 失败案例，dashboard 显示具体 persona × constraint 不达标
- [ ] UserSim 跑出的 session `origin='user_sim'`，V1 pattern 聚类 / V2 canary metric / V3 attribution 都不收

## 后续 backlog (V5 后)

- **tau-bench adapter**: 等需对外横向对标 framework strength 时加 eval module adapter (4 ratify decision #4)
- **`auto_promote_after_dynamic_sim=true`**: 等 UserSim 与真用户 outcome 对照准确率 > 80% 后改默认值 (ratify #2)
- **persona 池扩到 10+**: 5 → 10 时考虑 trial 聚合改 majority 而非 worst case (ratify #1+#3)
- **prompt canary**: V2 ratify "默认一刀切"，多用户阶段才上
- **lifecycle_hook surface**: V4 ratify #1 推 V5+，编辑频率信号收集后评估
