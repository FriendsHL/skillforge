# EVAL-DYNAMIC-USER-SIM 技术方案

---
id: EVAL-DYNAMIC-USER-SIM
status: design-draft
prd: ./prd.md
risk: High
mode: full
created: 2026-05-16
updated: 2026-05-16
---

## TL;DR

V5 Phase 2/3 = 第三因子 (dynamic user sim) 加到 V4 飞轮 promote gate。落地 4 件：

1. `EvalScenarioEntity` 加 6 列业务语义 (V84 migration) + `SessionScenarioExtractorService` 抽取
2. 新 `user-simulator` system agent (V85 seed) + 2 个工具 (`RunSimulatorTrial` / `RecordSimulationResult`) 跟 candidate agent 多轮对话
3. 新 `ProcessLevelJudgeService` 6 维度评分 + worst case 聚合 + LLM judge
4. `AbstractAbEvalRunner` 加 `runDynamicSim` hook + `t_optimization_event` 加 `dynamic_sim_*` stage + 三因子 promote gate + `t_session.origin='user_sim'` 隔离 (V86 CHECK)

## 现状证据 (2026-05-16 grep)

### V5 Phase 1 已交付 (V4 后)
- `SkillAbEvalService.runMultiTurnScenario(...)` 在 `:880-1021`，复用 `buildSandboxRegistryWithSkills` 注入 candidate ✓
- `EvalJudgeTool.judgeMultiTurnConversation(...)` 已存在 (V5 Phase 1 不动)

### V4 抽象骨架 (V5 扩展点)
- `AbstractAbEvalRunner<V>` 4 abstract hook (`runEvalSet` protected, `judgeAndCompare` / `shouldPromote` / `promoteIfNeeded` abstract)
- `OptimizableSurface<V>` 7-method 接口 (V5 不动)
- 3 surface 实现: SkillSurface / PromptSurface / BehaviorRuleSurface (V5 各加 dynamic sim override)

### Scenario 抽取现状
- `SessionScenarioExtractorService` 351 行；`extractFromSession(SessionEntity)` 单 session 抽 `task` + `conversationTurns` 字段；用 `defaultProviderName` LLM
- `EvalScenarioEntity` 已有 18 字段 (id / agentId / name / description / category / split / task / oracleType / oracleExpected / status / sourceSessionId / extractionRationale / conversationTurns / version / parentScenarioId / createdAt / 等)；**缺 V5 需要的 6 业务语义字段**

### System agent pattern (V1/V2/V3 已落)
- V75 seed session-annotator
- V79 seed metrics-collector
- V81 seed attribution-curator
- V85 seed user-simulator (本期新加，同 pattern: owner_id=1 / is_public=TRUE / ScheduledTask 可选)

### Flyway 当前
- 最新 V83 (multi_surface_attribution_link)
- V5 用 V84+ 起步: V84 (6 字段) + V85 (user-simulator seed) + V86 (t_session.origin CHECK 加 'user_sim')

### t_session.origin 现状
- 现有枚举值: `'production'` / `'eval'` / `'analysis'` 等 (需 grep CHECK 约束精确确认)
- V86 加 `'user_sim'` 防 V1/V2/V3 聚类/metric/attribution 污染

## 范围决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 三因子 gate 接入点 | `AbstractAbEvalRunner` 加 `runDynamicSim` hook (default no-op) | V4 抽象正好是扩展点，不修接口、3 surface 各 override；零 V4 接口破坏 |
| Scenario 抽取扩展 | 直接扩 `SessionScenarioExtractorService.extractFromSession` LLM prompt | 不新建 service；6 字段 prompt 加进去；NULL 兼容旧 row |
| UserSimulatorAgent 形态 | system agent (`owner_id=1` / `is_public=TRUE`) + 2 个 internal tool | 跟 V1/V2/V3 pattern 完全一致；不引入新机制；agent loop 复用 |
| ProcessLevelJudge LLM | 复用 V4 LLM (default-provider) + 单独 judge prompt | 不新建 LLM 路由；prompt 写 6 维度评分 + 综合 pass/fail |
| t_session.origin 隔离 | V86 加 CHECK + V1/V2/V3 都加 `WHERE origin != 'user_sim'` 过滤 | 隔离边界明确；防 user_sim session 污染聚类 / metric / attribution |
| auto_promote_after_dynamic_sim | application.yml config，默认 false | 同 V2 `auto_promote_after_ab` ratify pattern |
| 5 persona × 1 trial worst case | 任一 trial fail → 整 candidate dynamic_sim_failed | ratify #3，conservative 起步；trial 数加多后再考虑 majority |
| persona 来源 | hardcoded 5 fixed persona 写在 system prompt 或 application.yml | 第一版可控；不引入 persona DB 表；dogfood 后看是否需要 |
| FE dashboard 接入 | Insights 新 4th tab (跟 BehaviorRuleEvolution 同构) | 不新建路由 + Layout nav 改 (V4 Phase 1.4 (B′) 经验) |

## 数据模型

### V84 `t_eval_scenario` 加 6 列

```sql
ALTER TABLE t_eval_scenario ADD COLUMN business_goal TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN success_criteria TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN user_persona TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN user_constraints TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN failure_signals TEXT;
ALTER TABLE t_eval_scenario ADD COLUMN expected_outcome TEXT;
```

所有 nullable，向后兼容；历史 row 6 列 NULL。

### V85 seed user-simulator

参考 V81 attribution-curator pattern：

```sql
INSERT INTO t_agent (
    name, display_name, system_prompt, llm_provider, llm_model,
    owner_id, is_public, status, tool_ids, ...
) VALUES (
    'user-simulator',
    'User Simulator (V5)',
    '<system prompt 模板见下>',
    'bailian', 'qwen-max', -- 或 default-provider config
    1, TRUE, 'active',
    '["RunSimulatorTrial", "RecordSimulationResult"]',
    ...
);
```

system prompt 模板（写在 `skillforge-server/src/main/resources/system-agents/user-simulator.system.md`）：

```
你是用户模拟器，扮演真实用户跟 AI agent 多轮对话直到业务任务完成或失败。

【你的 persona】（trial 输入指定，从 5 fixed persona 选）
- 销售经理急性子 / 数据分析师细心 / CEO 高高在上 / 实习生小白 / DBA 老手

【你的业务目标】（trial 输入）{businessGoal}

【约束】（trial 输入）{userConstraints}

【失败信号】当你检测到下列任一情况，触发 failureSignal:
{failureSignals}

【行为规则】
- 按 persona 性格说话（急性子 = 短句催进度；细心 = 多确认；CEO = 命令式；小白 = 问基础；老手 = 跳过初级解释）
- 每轮收到 agent 回复后判：
  - 业务目标达成 → 输出 [TASK_COMPLETED]
  - 触发 failureSignal → 输出 [FAILURE_SIGNAL: <信号>]
  - 否则按 persona 生成下一轮用户输入
- 不超过 max_turns 轮（默认 10）

【输出格式】
每轮: 一行用户输入文本 / 或 [TASK_COMPLETED] / 或 [FAILURE_SIGNAL: ...]
```

### V86 `t_session.origin` CHECK 扩展

```sql
ALTER TABLE t_session DROP CONSTRAINT IF EXISTS chk_session_origin;
ALTER TABLE t_session ADD CONSTRAINT chk_session_origin
    CHECK (origin IN ('production', 'eval', 'analysis', 'user_sim'));
```

（实际 enum 值需先 grep 现状 V49 / V67 等 migration 精确对照）

## 服务层设计

### 1. SessionScenarioExtractorService 6 字段扩展

```java
public EvalScenarioEntity extractFromSession(SessionEntity session) {
    String prompt = buildExtractionPrompt(session); // 加 6 字段 instruction
    LlmResponse resp = llmProvider.chat(...);
    Map<String, Object> parsed = parseLlmJson(resp.getContent());

    EvalScenarioEntity scenario = new EvalScenarioEntity();
    // ... 现有 13 字段映射 ...
    scenario.setBusinessGoal((String) parsed.get("business_goal"));
    scenario.setSuccessCriteria((String) parsed.get("success_criteria"));
    scenario.setUserPersona((String) parsed.get("user_persona"));
    scenario.setUserConstraints((String) parsed.get("user_constraints"));
    scenario.setFailureSignals((String) parsed.get("failure_signals"));
    scenario.setExpectedOutcome((String) parsed.get("expected_outcome"));
    return scenarioRepository.save(scenario);
}
```

LLM prompt 加 6 字段 instruction（用 JSON schema 引导）。

### 2. UserSimulatorAgent + 2 tool

**`RunSimulatorTrial` tool**:
```java
public class RunSimulatorTrial implements Tool {
    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        Long scenarioId = ...;
        String candidateAgentVersionId = ...; // skill / prompt / behavior_rule version
        String persona = ...; // 5 fixed 之一
        int maxTurns = (int) input.getOrDefault("max_turns", 10);

        // 1. Build sandboxed candidate agent endpoint (复用 V4 BehaviorRuleSurface
        //    sessionId-keyed inject 模式 / V2 buildSandboxRegistryWithSkills /
        //    V3 PromptSurface inject)
        // 2. Loop max_turns:
        //    - UserSim 生成 user msg (按 persona+goal+constraints+history)
        //    - candidate agent 跑一轮 (engine.run(...))
        //    - 检测 [TASK_COMPLETED] / [FAILURE_SIGNAL: ...] 终止条件
        // 3. Persist transcript to t_session (origin='user_sim') + t_simulator_trial
        // 4. Return SimulationTranscript
    }
}
```

**`RecordSimulationResult` tool**: 把 ProcessLevelJudge 输出写 DB (供 dashboard 查询)。

### 3. ProcessLevelJudgeService

```java
public class ProcessLevelJudgeService {
    public ProcessJudgeOutput judge(EvalScenarioEntity scenario, SimulationTranscript transcript) {
        String prompt = buildJudgePrompt(scenario, transcript);
        // 喂 successCriteria + userConstraints + transcript 全文给 LLM
        // 要求输出 JSON:
        // {
        //   "task_completed": bool,
        //   "constraints_satisfied": {<constraint>: bool, ...},
        //   "repetition_count": int,
        //   "tool_call_count": int,
        //   "tool_call_failures": int,
        //   "turns_used": int,
        //   "composite_score": double,
        //   "pass_fail": "PASS" | "FAIL",
        //   "reasoning": string
        // }
        LlmResponse resp = llmProvider.chat(...);
        return parseProcessJudgeOutput(resp.getContent());
    }
}
```

### 4. AbstractAbEvalRunner.runDynamicSim hook

```java
public abstract class AbstractAbEvalRunner<V> {
    // ... 现有 4 abstract hook ...

    // V5 新加 hook，default no-op (向后兼容 V4)
    protected DynamicSimResult runDynamicSim(
            String abRunId,
            V candidate,
            List<EvalScenarioEntity> scenarios,
            SimulationContext ctx) {
        return DynamicSimResult.skipped(); // V4 路径不触发
    }

    public final LoopResult run(String abRunId, V baseline, V candidate,
                                  EvalContext ctx) {
        // ... 现有 run() 流程 ...
        // Phase 1.5 加: 如果 ctx.isDynamicSimEnabled() → runDynamicSim(...)
    }
}
```

3 surface override (`SkillAbEvalService.runDynamicSim` / `PromptImproverService.runDynamicSim` / `BehaviorRuleSurface.runDynamicSim`)：

- 拿 5 fixed persona
- 每 persona 调 `RunSimulatorTrial(scenarioId, candidateVersionId, persona)` × 1 trial
- 拿 transcript → ProcessLevelJudgeService.judge → ProcessJudgeOutput
- worst case 聚合: 任一 trial pass_fail=FAIL → DynamicSimResult.failed; 全 PASS → DynamicSimResult.passed
- 写 `t_optimization_event.stage='dynamic_sim_passed'` 或 `'dynamic_sim_failed'`

### 5. State machine 扩 ALLOWED_TRANSITIONS

V3 `AttributionApprovalService.ALLOWED_TRANSITIONS` 加 4 edge：

- `ab_passed → dynamic_sim_running`
- `dynamic_sim_running → dynamic_sim_passed`
- `dynamic_sim_running → dynamic_sim_failed`
- `dynamic_sim_passed → canary_started` (现有 ab_passed → canary_started 仍保留：dynamic sim 是 opt-in 第三步)

### 6. 生产数据隔离 (V1 / V2 / V3 各路径加 origin 过滤)

| 路径 | 加过滤 |
|---|---|
| V1 `SessionAnnotationService` (cluster pattern 输入) | `WHERE s.origin != 'user_sim'` |
| V1 `PatternClusteringService.cluster()` | `WHERE s.origin != 'user_sim'` |
| V2 `CanaryMetricsService.recompute(Duration)` | `WHERE s.origin != 'user_sim'` |
| V3 `AttributionDispatcherService` filter 阶段 | session 来源已是 V1 pattern → 已隔离 (传递性) |
| `t_optimization_event` 写入路径 | 不需过滤（V3 attribution path 不创建 user_sim session） |

每路径加 regression test 锁不变量。

### 7. FE Dashboard

按 V4 Phase 1.4 (B′) 经验：嵌入 `Insights.tsx` 第 4 tab `'dynamic-sim'`：

- 新建 `components/dynamicSim/DynamicSimResultsPanel.tsx` (~300 行)
  - 顶部 trial 列表（per candidate 5 trial × N turn）
  - 中部展开任一 trial → turn-by-turn 对话 + ProcessJudge 6 维度评分
  - 底部 worst case 聚合显示（哪个 persona × constraint 不达标）
- 新建 `api/dynamicSim.ts` (~80 行) 调 BE endpoint
- 新建 `pages/DynamicSim.tsx` (~120 行) agent 选择器 + panel 嵌入
- 改 `pages/Insights.tsx` (+15) INSIGHTS_TABS 加 'dynamic-sim'
- 三因子 gate 在现有 `EventDetailDrawer` 已展示 stage timeline，加 `dynamic_sim_*` 节点 (~10 行)

## 实施计划

### Phase 1.0 — 证伪 + 设计微调 (1 天)

- grep 现状 `t_session.origin` 实际 enum 值
- grep `SkillAbEvalService.runDynamicSim` 是否已存在 (probably no)
- 红测试：写一个失败的 dynamic sim path test 锁现状（V4 后无 dynamic sim）
- spec 微调反映 Phase 1.0 发现

### Phase 1.1 — BE Schema + Scenario 抽取 (2-3 天)

- V84 migration 加 6 列
- `EvalScenarioEntity` 加 6 字段 getter/setter + null 兼容
- `SessionScenarioExtractorService.extractFromSession` LLM prompt 扩 + parse 6 字段
- Test: `SessionScenarioExtractorServiceTest` 加 6 字段抽取 happy + null backward-compat case

### Phase 1.2 — BE UserSimulator + tools (2-3 天)

- V85 seed user-simulator system agent (`UserSimulatorBootstrap` 复用 V81 AttributionCuratorBootstrap idempotent pattern)
- `user-simulator.system.md` system prompt 写完
- `RunSimulatorTrial` tool + `RecordSimulationResult` tool
- 新建 `t_simulator_trial` table (V85 同 migration 或单独 V85.5): trial_id / scenario_id / candidate_version_id / persona / transcript_json / created_at
- `SimulatorTrialPersistenceIT` JPA IT (Testcontainers PG)

### Phase 1.3 — BE ProcessLevelJudge + hook (2-3 天)

- 新 `ProcessLevelJudgeService` + judge prompt 工程
- `AbstractAbEvalRunner.runDynamicSim` hook 加 (default no-op)
- 3 surface override (`SkillAbEvalService` / `PromptImproverService` / `BehaviorRuleSurface`)
- 5 persona constants 写在 application.yml 或常量类
- `worst case 聚合` 实施
- Test: `ProcessLevelJudgeServiceTest` 6 维度 + worst case + LLM stub; `AbstractAbEvalRunnerDynamicSimTest` 三 surface override + default no-op

### Phase 1.4 — Gate + 隔离 + FE (2-3 天)

- `AttributionApprovalService.ALLOWED_TRANSITIONS` 加 4 edge
- `application.yml` 加 `auto_promote_after_dynamic_sim: false`
- V86 migration: `t_session.origin` CHECK 加 'user_sim'
- V1 (SessionAnnotationService / PatternClusteringService) + V2 (CanaryMetricsService) 加 origin 过滤 + regression test
- FE DynamicSim page + panel + Insights tab embed + api/dynamicSim.ts
- FE tsc + npm build 双绿

### Phase 1.5 — e2e dogfood verification (1-2 天)

- 启 server，跑一个真 candidate skill 走完整 A/B + canary + dynamic sim 三因子
- 验证 5 persona × 1 trial 全过 → stage=dynamic_sim_passed → operator 手动 publish (auto_promote_after_dynamic_sim=false)
- 跑一个失败案例（candidate 在某 persona 下退化）→ dynamic_sim_failed → 不 promote
- 检 t_session.origin='user_sim' 隔离生效（V1 pattern 聚类时不收）
- LLM cost 实测：单 candidate dynamic sim 真实成本

### Phase Final — 归档 (30 min)

- 需求包 active → archive
- delivery-index.md 加 V5 Phase 2/3 交付行
- todo.md / README.md 同步

## 风险与边界

### High Risk
- **LLM cost 失控**：UserSim N 轮 + candidate N 轮 + judge 1 轮 = 2N+1 LLM call/trial × 5 trial = 10N+5 LLM call/candidate. 10 turn 平均 → ~105 LLM call/candidate. 跑 10 candidate 就 ~$50。**Phase 1.5 实测后看是否需要 budget cap**。
- **ProcessLevelJudge prompt 不稳**：LLM judge 6 维度评分非确定，可能 trial 间分数波动。需 prompt iteration + 加 reasoning 字段方便 debug。
- **5 persona 选错**：第一版 fixed persona 若不代表真实用户分布，dynamic sim 误判 candidate。需 dogfood 后调 persona pool。

### Mid Risk
- **state machine regression**：V3 经验已修过 2 次 BLOCKER（@Transactional rollback-only）。新加 4 edge 时 reviewer 显式审 transition 兼容性。
- **t_session.origin 过滤遗漏**：V1/V2/V3 多路径要全加过滤，若漏一处，user_sim session 污染聚类/metric/attribution。需 regression test 锁。
- **transcript 持久化形态**：选 t_session 行 vs 单独 t_simulator_trial blob？倾向单独表 (transcript 长 + 索引方便 dashboard 展示)。

### Low Risk
- **persona prompt 工程**：5 fixed persona 写法不难。
- **FE 复用 V4 Insights tab 经验**：已有 OptimizationEvents / BehaviorRuleEvolution 同构。

## Iron Law 全程守住

核心 7+1 BE 文件 + 3 FE 文件 git diff = 0 全程不变。V5 触碰范围：

- BE 新建: 1 service (ProcessLevelJudge) + 2 tool (RunSimulatorTrial / Record) + 1 bootstrap (UserSimulatorBootstrap) + 1 system prompt md + 3 migration (V84 / V85 / V86) + 1 system agent seed SQL
- BE 修改: SessionScenarioExtractorService (6 字段抽) + EvalScenarioEntity (6 字段) + AbstractAbEvalRunner (1 hook) + 3 surface impl (override) + AttributionApprovalService (ALLOWED_TRANSITIONS) + V1/V2 path (origin 过滤) + application.yml
- FE 新建: api/dynamicSim.ts + DynamicSimResultsPanel.tsx + DynamicSim.tsx
- FE 修改: Insights.tsx (+15) + EventDetailDrawer (stage timeline +10)

## 测试计划

- BE 全套 mvn test → 预期 1671 + ~30-50 new = ~1700+ BUILD SUCCESS
- FE tsc + npm build EXIT=0
- 核心 7+1 + 3 文件 0 diff
- Dogfood e2e：三因子 stage timeline 完整 + cost 实测 + 隔离验证

## 评审记录

- 2026-05-16 创建 design-draft
- 4 ratify 决策已 pre-ratify (见 prd.md)
