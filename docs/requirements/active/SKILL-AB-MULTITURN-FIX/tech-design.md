# SKILL-AB-MULTITURN-FIX 技术方案

---
id: SKILL-AB-MULTITURN-FIX
status: design-draft
prd: ./prd.md
risk: Mid
created: 2026-05-13
updated: 2026-05-13
---

## TL;DR

本包只修 `SkillAbEvalService` 的 EVAL-V2 M2 R5 fallback：multi-turn held-out scenario 必须走多轮执行 + 多轮 judge。实现时不能直接复用 `ScenarioRunnerTool.runScenarioMultiTurn(...)` 的现有入口，因为它只构建普通 sandbox registry；skill A/B 必须沿用 `runSingleScenario(...)` 的 candidate skill override 注入方式。

## 现状证据

- `skillforge-server/src/main/java/com/skillforge/server/improve/SkillAbEvalService.java:387-393` 明确标注 EVAL-V2 M2 R5：multi-turn 不支持，warning 后 fallback single-turn。
- `skillforge-server/src/main/java/com/skillforge/server/improve/SkillAbEvalService.java:398` fallback 后仍调用 `runSingleScenario(...)`。
- `skillforge-server/src/main/java/com/skillforge/server/improve/SkillAbEvalService.java:655-660` 的 single-turn A/B 通过 `sandboxFactory.buildSandboxRegistryWithSkills(abRunId, scenarioId, List.of(candidateSkillDef))` 注入 candidate skill。
- `skillforge-server/src/main/java/com/skillforge/server/eval/ScenarioRunnerTool.java:132-146` 的 multi-turn runner 会自己调用 `sandboxFactory.buildSandboxRegistry(evalRunId, scenarioId)`，没有 candidate skill override 参数。
- `skillforge-server/src/main/java/com/skillforge/server/eval/EvalOrchestrator.java:159-185` 已证明普通 eval 路径能对 multi-turn scenario 使用 `runScenarioMultiTurn(...)` + `judgeMultiTurnConversation(...)`。

## 范围决策

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| A/B 通路 | 只改 `SkillAbEvalService` | 本缺陷发生在 skill evolution / skill A/B；`AbEvalPipeline` prompt A/B 保留 EVAL-V2 后续项 |
| 评分权重 | 不改 `EvalScoreFormula` | M4_V2 latency `not_measured` 刚 ratify；本包只附加指标，不改综合分语义 |
| runner 复用 | 抽可注入 candidate skills 的共享 helper | 直接调现有 `runScenarioMultiTurn(...)` 会跑不到 candidate skill |
| endpoint/UI | 不新增 | 沿用现有触发链路和 A/B 结果结构 |
| prompt cache | 不处理 | candidate `SKILL.md` 变化导致 stable prefix cache miss 是预期行为 |

## 目标链路

```text
SkillAbEvalService.runAbTestAsync
  -> build candidateSkillDef
  -> for each held_out scenario
      if scenario.isMultiTurn()
        -> runMultiTurnScenarioForSkillAb(abRunId, scenario, agentDef, candidateSkillDef)
           -> buildSandboxRegistryWithSkills(..., candidateSkillDef)
           -> one eval session, shared history, same sandbox across turns
        -> EvalJudgeTool.judgeMultiTurnConversation(...)
        -> project score/pass/status into existing A/B result
      else
        -> existing runSingleScenario(...)
        -> EvalJudgeTool.judge(...)
```

## Runner 设计

### 推荐实现

新增 `SkillAbEvalService.runMultiTurnScenario(...)`，逻辑从 `ScenarioRunnerTool.runScenarioMultiTurn(...)` 复制最小必要部分，但保留 skill A/B 的 candidate registry 注入：

- 使用 `sandboxFactory.buildSandboxRegistryWithSkills(abRunId, scenario.getId(), List.of(candidateSkillDef))`。
- 使用 `evalEngineFactory.buildEvalEngine(sandboxRegistry)`。
- 为整个 scenario 创建一个 eval session id。
- 先写 fixture files，替换 `/tmp/eval/` 到 sandbox root。
- 逐个 user turn 调 `engine.run(evalDef, userMsg, history, evalSessionId, null, ctx)`。
- 非 user turn 只进入 `MultiTurnTranscript`，assistant placeholder 由真实 agent response 填充。
- 聚合 loop count、tokens、tool calls、execution time、sessionId、rootTraceId。
- finally cleanup sandbox。

这样代码重复会比抽新服务更直接，但要把重复控制在一个私有方法内。若实现时发现重复超过可维护阈值，再抽 `EvalScenarioExecutionService`，接口必须显式支持 `List<SkillDefinition> skillOverrides`：

```java
ScenarioRunResult runMultiTurn(
    String evalRunId,
    EvalScenario scenario,
    AgentDefinition agentDef,
    Long agentId,
    Long userId,
    List<SkillDefinition> skillOverrides,
    MultiTurnTranscript transcriptOut
)
```

### 不采用

不直接调用现有 `ScenarioRunnerTool.runScenarioMultiTurn(...)`，除非先扩展它的签名支持 skill override。否则 A/B 实际跑的是 parent/registry 里的旧 skill，不是 candidate。

## Judge 设计

- multi-turn 使用 `EvalJudgeTool.judgeMultiTurnConversation(scenario, runResult, transcript)`。
- 将 `EvalJudgeMultiTurnOutput` 投影到现有 A/B result：
  - `candidate.oracleScore = compositeScore`
  - pass/fail 用 `isPass()`
  - `status` 从 `ScenarioRunResult` 晋升为 `PASS` / `FAIL` / `ERROR` / `TIMEOUT`
  - rationale/attribution 若已有字段承载则进入兼容 JSON metadata，不改 schema
- single-turn 保持 `EvalJudgeTool.judge(...)`。

## A/B Result 兼容字段

现有 `AbScenarioResult.RunResult` 字段是 `status` 和 `oracleScore`，必须保持该 wire shape。可在不破坏旧 JSON 读取的前提下追加 metadata：

```json
{
  "multiTurn": true,
  "sessionId": "...",
  "rootTraceId": "...",
  "turnCount": 4,
  "loopCount": 12,
  "inputTokens": 3456,
  "outputTokens": 789,
  "executionTimeMs": 23000
}
```

若当前 record 不便追加字段，Phase 1 可先只补内部日志和测试，避免为了展示信息扩大模型改动。

## 实施计划

- [ ] **Phase 1.0：证伪与红测试**
  - 跑现有 skill A/B multi-turn scenario，截取 `Skill AB eval skipping multi-turn execution... falling back to single-turn` warning。
  - 在测试注释中引用 `SkillAbEvalService.java:387-393` 和 `:398`。
  - 写红测试证明 multi-turn scenario 目前仍调用 single-turn path，且没有调用 multi-turn judge。
- [ ] Phase 1.1：实现 `runMultiTurnScenario(...)` 或抽共享 runner，必须支持 candidate skill override。
- [ ] Phase 1.2：`runAbTestAsync(...)` 分支到 multi-turn runner + multi-turn judge。
- [ ] Phase 1.3：补 error/timeout 映射和可选 metadata。
- [ ] Phase 1.4：回归 single-turn skill A/B。
- [ ] Phase 1.5：验证无 migration、无 endpoint、无 frontend 行为变更。

## 测试计划

- [ ] `SkillAbEvalServiceTest`：multi-turn scenario 修复前红测 fallback，修复后绿。
- [ ] `SkillAbEvalServiceTest`：candidate skill override 在 multi-turn runner 中生效。
- [ ] `SkillAbEvalServiceTest`：multi-turn judge 被调用，single-turn judge 不被误用。
- [ ] `SkillAbEvalServiceTest`：ERROR/TIMEOUT 只落到 per-scenario result。
- [ ] 现有 single-turn A/B 测试保持通过。
- [ ] 后端定向测试：skill/eval/improve 相关 test。
- [ ] 后端全量：`mvn -pl skillforge-server -am test`。

## 风险

- **candidate 注入错误**：最大风险。如果 runner 没使用 `buildSandboxRegistryWithSkills(...)`，评测会静默跑 parent skill，结果不可用。
- **重复 runner 逻辑漂移**：若私有 multi-turn runner 复制太多 `ScenarioRunnerTool` 逻辑，后续维护成本升高。实现时优先收敛为共享 helper。
- **多轮成本增加**：同一 scenario 会跑多个 turn，token/latency 上升。沿用已有 per-turn timeout 和 case-level budget。
- **评分波动**：multi-turn judge 是 LLM judge；本包只复用现有能力，不新增非确定性自动 promote 规则。
- **prompt cache miss**：candidate `SKILL.md` 变化会改变 stable prefix hash，cache miss 是预期，不作为 bug 处理。

## 后续拆分

`EVAL-DYNAMIC-USER-SIM` 后续 Full 包再设计：

- session -> scenario 的业务目标 / 成功标准 / 失败信号抽取。
- `UserSimulatorAgent` 动态用户模拟。
- process-level judge、trial 结果、UI transcript 对比。
- 字段进 column 还是 JSON metadata。
- 动态结果是否只参考、是否进入 promote gate。

## 评审记录

2026-05-13：按 review 意见将原 `SKILL-CONVERSATION-EVAL` 三阶段包拆分。本包只保留 Phase 1，risk 从 Full 调整为 Mid；Phase 2/3 与 `EVAL-DYNAMIC-USER-SIM` 合并回 backlog。
