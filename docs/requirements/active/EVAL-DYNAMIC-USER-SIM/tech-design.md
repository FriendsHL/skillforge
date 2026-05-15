# EVAL-DYNAMIC-USER-SIM 技术方案

---
id: EVAL-DYNAMIC-USER-SIM
status: design-draft
prd: ./prd.md
risk: Mid
mode: full
created: 2026-05-16
updated: 2026-05-16
---

## TL;DR

V5 只做 **2 件事**：

1. **F1 升级 scenario 抽取** — `SessionScenarioExtractorService` 扩 6 字段 (businessGoal / successCriteria / userPersona / userConstraints / failureSignals / expectedOutcome) + 切 xiaomi-mimo / mimo-v2.5-pro hardcoded constant (bailian 现已过期未续费)
2. **F2 UserSimulatorAgent system agent** — V85 seed user-simulator (owner_id=1 / is_public=TRUE，同 V1/V3 pattern)；按 (scenario 6 字段 + 5 fixed persona 选 1) 跟 candidate agent 多轮对话；产 transcript 写 t_session (origin='user_sim') + t_simulator_trial 元数据

**不做**：judge / attribution / promote gate / 三因子合成 / 自动喂下游评测系统。Transcript 怎么被消费 = V5 完成后看实测决定的 follow-up（V5.5 / V5.6 / 全新 package）。

## 现状证据 (2026-05-16 grep)

### F1 抽取现状 (需要修复)

- `SessionScenarioExtractorService.java:351 行` — `private final String defaultProviderName;` 字段
- 服务通过 `defaultProviderName` 调 LLM 但 **bailian (qwen-max) 已过期未续费**，实际不可用
- **V5 借机修复**: 改用 hardcoded `xiaomi-mimo` + `mimo-v2.5-pro` (per `SkillDraftService.java:65-66` 同款)
- 现有 `EvalScenarioEntity` 18 字段 (id / agentId / name / description / category / split / task / oracleType / oracleExpected / status / sourceSessionId / extractionRationale / conversationTurns / version / parentScenarioId / createdAt / 等) — **缺 V5 需要的 6 业务语义字段**

### F2 UserSimulator system agent pattern (复用 V1/V3)

V5 seed pattern 完全复用现有：

| 版本 | seed migration | system agent name | bootstrap class |
|---|---|---|---|
| V1 | V75 | session-annotator | SessionAnnotatorBootstrap |
| V2 | V79 | metrics-collector | MetricsCollectorBootstrap |
| V3 | V81 | attribution-curator | AttributionCuratorBootstrap |
| **V5** | **V85** | **user-simulator** | **UserSimulatorBootstrap** |

### V4 抽象骨架 (V5 不动)

- `OptimizableSurface<V>` 7-method 接口 — **V5 零修改**
- `AbstractAbEvalRunner<V>` 4 abstract hook + protected runEvalSet — **V5 零修改**
- `SkillAbEvalService.runMultiTurnScenario` (`:880-1021`) — **V5 零修改** (V5 Phase 1 已成熟)

### Flyway 当前
- 最新 V83 (multi_surface_attribution_link)
- V5 用 V84+：
  - **V84** — `t_eval_scenario` 加 6 列
  - **V85** — `t_simulator_trial` table + seed user-simulator system agent
  - **V86** — `t_session.origin` CHECK 扩 'user_sim'

### t_session.origin 现状

需 grep 当前 enum 值精确确认（可能 `'production'` / `'eval'` / `'analysis'`）；V86 ALTER + 加 'user_sim'。

## 范围决策

| 决策 | 结论 | 理由 |
|---|---|---|
| F1 抽取形态 | **LLM 写死 (service.chat)**，不走 agent | ratify #5c；纯 transform 任务，agent loop 过度设计；保 V5 ~1 周 scope |
| F1 LLM provider | **xiaomi-mimo / mimo-v2.5-pro hardcoded constant** | ratify #5b；bailian 过期；跟 SkillDraftService.EXTRACT_PROVIDER_NAME/EXTRACT_MODEL 同款 |
| F2 UserSim 形态 | **System agent + agent loop** | 必须 agent (要维持多轮对话 context + persona 扮演) |
| F2 LLM provider | **xiaomi-mimo / mimo-v2.5-pro** (V85 seed `t_agent.llm_provider/llm_model`) | ratify #5；跟 F1 同款 |
| transcript 持久化 | **双写 t_session (origin='user_sim') + t_simulator_trial 元数据** | ratify #8；复用 ChatWindow / SessionList viewer + dashboard query 方便 |
| 5 persona 存储 | **application.yml** `skillforge.eval.user-simulator.personas: [...]` 5 字符串数组 | ratify #4；第一版直接写死，dogfood 后调 |
| 触发方式 | **Dashboard "Run Trial" 按钮 manual** | ratify #7；cron 推后 |
| V5 不喂 judge / attribution | **零触碰 SkillAbEvalService / EvalJudgeTool / AttributionApprovalService** | ratify #6；transcript 后续消费 = V5 完成后再决 |
| 5th hook 豁免 | **不需要** —— V5 简化后零修改 AbstractAbEvalRunner | 简化版 spec 后 ratify #3 4-hook 锁实质 + 字面双守住 |
| FE 接入点 | **Insights.tsx 第 4 tab `'dynamic-sim'`** | 跟 V4 BehaviorRuleEvolution / V3 OptimizationEvents 同构；不新增路由 |

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

全部 nullable，历史 row 6 列 NULL 兼容。

### V85 `t_simulator_trial` table + user-simulator seed

```sql
CREATE TABLE t_simulator_trial (
    trial_id VARCHAR(36) PRIMARY KEY,
    scenario_id VARCHAR(36) NOT NULL,
    candidate_agent_version_id VARCHAR(64),
    candidate_surface_type VARCHAR(32),       -- 'skill' / 'prompt' / 'behavior_rule'
    persona TEXT NOT NULL,
    session_id VARCHAR(36) NOT NULL,          -- FK 到 t_session (origin='user_sim')
    turns_used INT NOT NULL,
    termination_reason VARCHAR(64),           -- 'task_completed' / 'failure_signal' / 'max_turns' / 'error'
    observed_failure_signals TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_simulator_trial_scenario ON t_simulator_trial(scenario_id);
CREATE INDEX idx_simulator_trial_candidate ON t_simulator_trial(candidate_agent_version_id, candidate_surface_type);
CREATE INDEX idx_simulator_trial_session ON t_simulator_trial(session_id);

-- user-simulator system agent seed
INSERT INTO t_agent (
    name, display_name, system_prompt, llm_provider, llm_model,
    owner_id, is_public, status, tool_ids, ...
) VALUES (
    'user-simulator',
    'User Simulator (V5)',
    -- system_prompt 从 classpath system-agents/user-simulator.system.md 读 (类似 V81 pattern)
    '<SEE: system-agents/user-simulator.system.md>',
    'xiaomi-mimo', 'mimo-v2.5-pro',
    1, TRUE, 'active',
    '["RunSimulatorTrial", "RecordSimulationResult"]',
    ...
);
```

### V86 `t_session.origin` CHECK 扩展

```sql
ALTER TABLE t_session DROP CONSTRAINT IF EXISTS chk_session_origin;
ALTER TABLE t_session ADD CONSTRAINT chk_session_origin
    CHECK (origin IN ('<existing>', 'user_sim'));
-- existing 实际值需 grep V1/V40-V80 migration 精确确认 (可能 'production', 'eval', 'analysis' 等)
```

## 服务层设计

### 1. SessionScenarioExtractorService 改造 (F1)

```java
@Service
public class SessionScenarioExtractorService {
    // 替换原 defaultProviderName 字段为硬编码 (per ratify #5b/c)
    private static final String EXTRACT_PROVIDER_NAME = "xiaomi-mimo";
    private static final String EXTRACT_MODEL = "mimo-v2.5-pro";

    public EvalScenarioEntity extractFromSession(SessionEntity session) {
        String prompt = buildExtractionPrompt(session); // 扩 6 字段 instruction (JSON schema)
        LlmProvider provider = llmProviderFactory.getProvider(EXTRACT_PROVIDER_NAME);
        if (provider == null) {
            // Fallback to defaultProvider for graceful degrade
            provider = llmProviderFactory.getProvider(defaultProviderName);
            log.warn("Extractor provider '{}' unavailable, fallback to '{}'",
                    EXTRACT_PROVIDER_NAME, defaultProviderName);
        }
        LlmRequest req = LlmRequest.builder()
                .model(EXTRACT_MODEL)
                .messages(...)
                .build();
        LlmResponse resp = provider.chat(req);
        Map<String, Object> parsed = parseLlmJson(resp.getContent());

        EvalScenarioEntity scenario = new EvalScenarioEntity();
        // 现有 13 字段映射不变 ...
        scenario.setBusinessGoal((String) parsed.get("business_goal"));
        scenario.setSuccessCriteria((String) parsed.get("success_criteria"));
        scenario.setUserPersona((String) parsed.get("user_persona"));
        scenario.setUserConstraints((String) parsed.get("user_constraints"));
        scenario.setFailureSignals((String) parsed.get("failure_signals"));
        scenario.setExpectedOutcome((String) parsed.get("expected_outcome"));
        return scenarioRepository.save(scenario);
    }
}
```

**LLM prompt 扩 6 字段 (JSON schema 引导)**:

```
你是评测场景抽取器。给一段 session 对话历史，输出 JSON：
{
  "name": "...",              // 现有
  "description": "...",       // 现有
  "task": "...",              // 现有
  "oracleExpected": "...",    // 现有
  "conversationTurns": [...], // 现有
  "business_goal": "...",     // V5 新加: 用户真正要达成的业务目标 (1 句话)
  "success_criteria": "...",  // V5 新加: 完成的客观可验证标准 (multi-line / list)
  "user_persona": "...",      // V5 新加: 用户画像 (角色 / 性格 / 技术水平)
  "user_constraints": "...",  // V5 新加: 隐性约束 (不能做的事 / 必须遵守的规则)
  "failure_signals": "...",   // V5 新加: 失败信号 (用户什么样的行为代表放弃 / 不满意)
  "expected_outcome": "..."   // V5 新加: 期望最终结果 (理想路径)
}
```

### 2. UserSimulatorAgent (F2) — system agent + 2 tool

#### 2.1 system prompt (`system-agents/user-simulator.system.md`)

```
你是用户行为模拟器，扮演真实用户跟 AI agent 多轮对话直到业务任务完成或失败。

【本次扮演 persona】(本 trial 输入指定，从 application.yml 5 fixed personas 选 1)
{persona}

【你的业务目标】(本 trial 输入)
{businessGoal}

【成功标准】(本 trial 输入)
{successCriteria}

【约束】(本 trial 输入)
{userConstraints}

【失败信号触发条件】(本 trial 输入)
{failureSignals}

【行为规则】
1. 按 persona 性格说话:
   - 销售经理急性子 → 短句催进度、商业语言
   - 数据分析师细心 → 多确认、问细节
   - CEO 高高在上 → 命令式、不解释
   - 实习生小白 → 问基础概念
   - DBA 老手 → 直接问深问题，对效率敏感
2. 每收到 agent 回复后判断:
   - 业务目标达成 (满足 successCriteria) → 调 RecordSimulationResult tool 传 termination_reason='task_completed' 然后输出 [TERMINATE]
   - 触发任一 failureSignal → 调 RecordSimulationResult tool 传 termination_reason='failure_signal' + observed_signals 然后输出 [TERMINATE]
   - 否则按 persona 生成下一轮用户输入 (单段，不超过 200 字)
3. 不超过 max_turns 轮 (默认 10)；超过则 RecordSimulationResult 传 termination_reason='max_turns'

【输出格式】
每轮: 一段用户输入文本 (按 persona 性格)。终止时调 tool + 输出 [TERMINATE]。
```

#### 2.2 RunSimulatorTrial tool

```java
public class RunSimulatorTrial implements Tool {
    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        Long scenarioId = Long.parseLong((String) input.get("scenarioId"));
        String candidateVersionId = (String) input.get("candidateAgentVersionId");
        String surfaceType = (String) input.get("candidateSurfaceType"); // 'skill' / 'prompt' / 'behavior_rule'
        String persona = (String) input.get("persona");
        int maxTurns = (int) input.getOrDefault("max_turns", 10);

        // 1. Load scenario from DB
        EvalScenarioEntity scenario = scenarioRepository.findById(scenarioId).orElseThrow();

        // 2. Build sandbox candidate agent (复用 V4 OptimizableSurface inject 模式)
        //    - SkillSurface.injectForSandbox / PromptSurface.injectForSandbox / BehaviorRuleSurface.injectForSandbox
        //    - 拿一个 SessionId (origin='user_sim') 让 sandbox isolation 生效

        String userSimSessionId = sessionService.createSession(
                "User Sim Trial - " + scenario.getName(),
                /*ownerId*/ 1L,
                /*origin*/ "user_sim",
                /*agentDef*/ candidateAgentDef);

        // 3. UserSim agent loop:
        //    UserSim 自己跑 (用 t_agent.llm_provider='xiaomi-mimo') ←→ candidate agent 跑
        //    通过 SubAgentDispatchService 或类似机制让 UserSim 跟 candidate 互相 ping-pong
        //    每轮 UserSim 输出 → 喂给 candidate → candidate 输出 → 喂回 UserSim → ...

        SimulationTranscript transcript = orchestrateMultiTurnSimulation(
                userSimSessionId, scenario, persona, maxTurns);

        // 4. Return (但持久化由 RecordSimulationResult tool 完成 — UserSim agent 自己调)
        return SkillResult.success(Map.of(
                "trialId", transcript.getTrialId(),
                "sessionId", userSimSessionId,
                "turnsUsed", transcript.getTurnsUsed(),
                "terminationReason", transcript.getTerminationReason()
        ));
    }
}
```

#### 2.3 RecordSimulationResult tool

```java
public class RecordSimulationResult implements Tool {
    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        String trialId = (String) input.get("trialId");
        String terminationReason = (String) input.get("terminationReason");
        List<String> observedSignals = (List<String>) input.get("observedFailureSignals");
        int turnsUsed = (int) input.get("turnsUsed");

        SimulatorTrialEntity trial = trialRepository.findById(trialId).orElseThrow();
        trial.setTerminationReason(terminationReason);
        trial.setObservedFailureSignals(String.join(",", observedSignals));
        trial.setTurnsUsed(turnsUsed);
        trialRepository.save(trial);

        return SkillResult.success(Map.of("ok", true));
    }
}
```

### 3. 生产数据隔离 (F4) — 各路径加 origin filter

```java
// V1 SessionAnnotationService.findUnannotatedSessions
@Query("SELECT s FROM SessionEntity s WHERE s.annotated = false AND s.origin != 'user_sim'")

// V1 PatternClusteringService.cluster()
// JOIN t_session 时加 origin != 'user_sim'

// V2 CanaryMetricsService.recompute(Duration)
// JOIN t_session_annotation s ON s.session_id = t.id WHERE s.origin != 'user_sim'

// V3 AttributionDispatcher
// 不需要直接加 — V1 pattern 已过滤 (传递性隔离)
```

### 4. REST endpoint (F6)

```java
@RestController
@RequestMapping("/api/dynamic-sim")
public class DynamicSimController {

    @PostMapping("/trials")
    public CreateTrialResponse createTrials(@RequestBody CreateTrialRequest req) {
        // 异步派发 user-simulator agent (类似 V3 attribution dispatcher chatAsync pattern)
        // req 含 scenarioId / candidateAgentVersionId / candidateSurfaceType / personas (5 选 N 或 all)
        // 返回 trialIds + sessionIds (供 FE poll status)
    }

    @GetMapping("/trials")
    public Page<SimulatorTrialResponse> listTrials(
            @RequestParam Long scenarioId,
            @RequestParam(required = false) String candidateAgentVersionId,
            @RequestParam(required = false) String candidateSurfaceType,
            Pageable pageable) {
        // ...
    }

    @GetMapping("/trials/{trialId}")
    public SimulatorTrialResponse getTrial(@PathVariable String trialId) {
        // 返回 trial detail + 关联 session_id (供 FE 跳 SessionDetail viewer)
    }
}
```

## 实施计划

### Phase 1.0 — 证伪 + 设计微调 (0.5 天)

- grep `t_session.origin` 现状 enum 值 (V1/V40-V83 migration)
- 红测试：写一个失败的 dynamic sim path 锁现状 (V5 之前无 UserSim)
- spec 微调反映 Phase 1.0 grep 发现

### Phase 1.1 — BE Schema + Scenario 抽取 (1-1.5 天)

- V84 migration 加 6 列 (t_eval_scenario)
- `EvalScenarioEntity` 加 6 字段 getter/setter
- `SessionScenarioExtractorService` 改用 hardcoded xiaomi-mimo + mimo-v2.5-pro + 扩 6 字段 LLM prompt + parse
- Test: `SessionScenarioExtractorServiceTest` 加 6 字段抽取 + null backward-compat case

### Phase 1.2 — BE UserSimulator + 5 persona + 2 tool (2 天)

- V85 migration: t_simulator_trial table + seed user-simulator system agent
- `user-simulator.system.md` system prompt 写完
- 5 persona 配 `application.yml` `skillforge.eval.user-simulator.personas`
- `UserSimulatorBootstrap` (V81 AttributionCuratorBootstrap idempotent pattern)
- `RunSimulatorTrial` tool + `RecordSimulationResult` tool 注册到 SkillForgeConfig
- `SimulatorTrialEntity` JPA entity + `SimulatorTrialRepository`
- Test: `RunSimulatorTrialToolTest` + `RecordSimulationResultToolTest` + `SimulatorTrialPersistenceIT`

### Phase 1.3 — BE 隔离 + V86 + REST endpoint (1 天)

- V86 migration: t_session.origin CHECK 加 'user_sim'
- V1 / V2 各路径加 `WHERE origin != 'user_sim'` 过滤
- `DynamicSimController` 3 REST endpoint + DTO + MockMvc test
- V1/V2 路径 regression test 锁 origin filter

### Phase 1.4 — FE Dashboard (1-1.5 天)

- `pages/Insights.tsx` 加第 4 tab `'dynamic-sim'`
- `components/dynamicSim/DynamicSimPanel.tsx`: candidate 选择器 + scenario 选择器 + persona checkbox + Run Trial 按钮 + trial 列表
- `api/dynamicSim.ts` 调 BE endpoint
- transcript viewer 跳 SessionDetail page (复用 ChatWindow)
- tsc + npm build 双绿

### Phase 1.5 — e2e dogfood (0.5 天)

- 启 server，dashboard 选 1 candidate skill + 1 scenario + all 5 persona → Run Trial
- 验证: 5 transcript 写 t_session (origin='user_sim') + t_simulator_trial 5 行
- 验证: V1 pattern 聚类 / V2 metric 都不收 user_sim session
- 验证: LLM cost 实测 1 candidate × 5 trial 真实花费 (记录供 V5 完成后 cron 触发可行性评估)

### Phase Final — 归档 (0.5 天)

- 需求包 active → archive
- delivery-index.md 加 V5 Phase 2/3 交付行
- todo.md / README.md 同步

## 风险与边界

### Mid Risk
- **xiaomi-mimo / mimo-v2.5-pro 也过期 / 不可用** —— V5 部分实施时如果 xiaomi-mimo 也失效，会卡住。Fallback 到 defaultProvider，但 defaultProvider (bailian) 已过期 → 死循环。**实施前先 health-check** `xiaomi-mimo / mimo-v2.5-pro` 真能调通
- **UserSim ↔ candidate 双 agent 互相 ping-pong 编排** —— SubAgentDispatchService 现有模式可能不直接支持"两个 agent 互发"，需 design 一个 simple orchestration (UserSim agent loop 一轮 → 拿输出 → 喂给 candidate engine.run → 拿输出 → 喂回 UserSim 下一轮)。**Phase 1.2 实施前 grep SubAgent / engine 派发现状 + 设计 orchestrator 草图**
- **transcript 双写一致性** —— t_session + t_simulator_trial 双表，需事务一致 (REQUIRES_NEW 或同 tx)

### Low Risk
- **5 persona 选错** —— application.yml 改一改即可调，dogfood 后修
- **6 字段 LLM prompt 抽不准** —— prompt 工程迭代；第一版 80% 准确率够用
- **origin filter 漏一处** —— regression test 锁 V1/V2 路径

## Iron Law 全程守住

- 核心 7+1 BE 文件 (AgentLoopEngine / Message / ChatService / SessionService / CompactionService / SessionEntity / SessionMessageEntity / GetTraceTool) git diff = 0
- 核心 3 FE 文件 (ChatWindow / Chat / LifecycleHooksEditor) git diff = 0
- V4 OptimizableSurface / AbstractAbEvalRunner 接口零修改 (4-hook ratify 字面 + 实质双守住)
- V5 Phase 1 SkillAbEvalService / EvalJudgeTool 零修改

## V5 触碰范围 (审计用)

**BE 新建** (~6 文件 + 2 migration):
- skillforge-server/.../entity/SimulatorTrialEntity.java
- skillforge-server/.../repository/SimulatorTrialRepository.java
- skillforge-server/.../tool/sim/RunSimulatorTrial.java
- skillforge-server/.../tool/sim/RecordSimulationResult.java
- skillforge-server/.../bootstrap/UserSimulatorBootstrap.java
- skillforge-server/.../controller/DynamicSimController.java + DTO 2 个
- skillforge-server/src/main/resources/system-agents/user-simulator.system.md
- skillforge-server/src/main/resources/db/migration/V84__add_scenario_business_fields.sql
- skillforge-server/src/main/resources/db/migration/V85__create_simulator_trial_seed_user_simulator.sql
- skillforge-server/src/main/resources/db/migration/V86__add_user_sim_origin.sql

**BE 修改**:
- SessionScenarioExtractorService.java (改 LLM provider + 扩 6 字段抽取)
- EvalScenarioEntity.java (加 6 字段)
- application.yml (加 5 persona + user-simulator config)
- SessionAnnotationService / PatternClusteringService (加 origin filter)
- CanaryMetricsService (加 origin filter)
- SkillForgeConfig (注册 2 个 tool)

**FE 新建**:
- skillforge-dashboard/src/api/dynamicSim.ts
- skillforge-dashboard/src/components/dynamicSim/DynamicSimPanel.tsx
- (可选) skillforge-dashboard/src/pages/DynamicSim.tsx (若不嵌入 Insights tab)

**FE 修改**:
- skillforge-dashboard/src/pages/Insights.tsx (+15: 加 'dynamic-sim' tab)

## 测试计划

- BE 全套 mvn test → 预期 1671 + ~20-30 new = ~1700 BUILD SUCCESS
- FE tsc + npm build EXIT=0
- 核心 7+1 + 3 FE 文件 0 diff
- Dogfood e2e: 5 persona trial 写表 + origin filter 验证

## 评审记录

- 2026-05-16 创建 design-draft
- 2026-05-16 简化 scope (砍 ProcessLevelJudge / runDynamicSim hook / state machine 扩 / 三因子 gate)
- 2026-05-16 8 ratify 决策全部 与 user 锁定 (见 prd.md)
- 2026-05-16 F1 抽取也切 xiaomi-mimo (bailian 过期) + F1 用 LLM 写死不走 agent
