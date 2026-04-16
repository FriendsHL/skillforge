# SkillForge Self-Improve Pipeline — 最终设计方案

> 由 Plan A + Plan B 双方案 + Reviewer A/B 双评审 + Judge 仲裁综合产出。
> 更新于：2026-04-16

---

## 0. 设计原则 & 版本范围

### 核心原则

1. **测量纯粹性高于通过率好看**：eval 动作不注入 hint；baseline 和 with_hint 分开存储。
2. **线程安全高于架构优雅**：eval 必须使用独立 executor，绝不复用 ForkJoinPool.commonPool()。
3. **v1.0 边界清晰**：Phase 1 目标 = 2 周内可工作的端到端 eval pipeline；自动化改进闭环推 Phase 2。
4. **VETO 优先于归因矩阵**：`engineThrewException=true` 跳过矩阵计算，直接触发人工审核。

### 版本范围

| Phase       | 目标                                                                           | 时间   |
| ----------- | ---------------------------------------------------------------------------- | ---- |
| **Phase 1** | 端到端 eval pipeline 可运行：场景加载 → ScenarioRunnerSkill → EvalJudgeSkill → 持久化 → 归因 | 2 周  |
| **Phase 2** | PromptImprover + A/B 测试（delta ≥ 15% 阈值） + 自动晋升 + Δ 漂移监控                      | 后续迭代 |
| **Phase 3** | Adversarial 场景演化、Boundary Probe Batch、Skill Spec 生成、Welch t-test（场景库 ≥ 100）  | 长期   |

**Phase 1 明确不做**：Prompt A/B 自动晋升、Welch t-test、Adversarial 场景演化、Contrast Pair Memory（涉及核心文件清单 memoryService，红灯）、Plan B multi-judge（4 次 LLM/场景，与 30s timeout 根本冲突）、Session → Scenario 自动转换（Phase 2）。

**Phase 1 补充**：

- `/eval` 独立前端页面（Phase 1 必须，不复用 Teams 页面）
- 研究型任务的 oracle 标准：耗时 + 成功与否 + 工具调用情况，人工查看决策；不强制 llm_judge
- 真实 session 可手动转为评测场景（Phase 2 自动化）

---

## 1. 失败归因框架

### 归因信号清单（7 个有效信号 + 1 个否决信号）

```
否决信号（出现即跳过矩阵，标记 VETO_EXCEPTION，人工审核）:
  - engineThrewException: boolean

有效信号（进入矩阵计算）:
  s1. taskCompletionOraclePass:   boolean  — oracle 是否判定通过
  s2. skillExecutionFailed:       boolean  — 任一 skill 执行返回 error/exception
  s3. skillOutputWasMalformed:    boolean  — skill 返回格式/类型不符合 schema
  s4. hitLoopLimit:               boolean  — loopCount >= maxLoops
  s5. nearPassOracle:             boolean  — oracle 连续得分 40–60（差点就过）
  s6. outputFormatCorrect:        boolean  — 最终输出格式符合场景规定（静态检查）
  s7. slowExecution:              boolean  — executionTimeMs > scenario.performanceThresholdMs

移除的信号（不采集）:
  - agentReasoningWasCorrect  [删除：无法零成本可靠采集]
  - toolsRequired             [解耦为文档 hint，不采集]
```

### 归因类别（5 类）

```
SKILL_MISSING            — 所需工具不存在或被 Agent 忽略
SKILL_EXECUTION_FAILURE  — 工具被调用但执行失败
PROMPT_QUALITY           — 任务指令歧义、格式要求不清晰
CONTEXT_OVERFLOW         — 循环超限或上下文撑爆
PERFORMANCE              — 任务完成但效率低下
VETO_EXCEPTION           — engineThrewException，跳过矩阵，标人工审核
NONE                     — 全部信号正常，满分通过
```

### 权重矩阵（7 × 5）

```
                        SKILL    SKILL_EXEC  PROMPT  CONTEXT  PERF
                        MISSING  FAILURE     QUALITY OVERFLOW
s1. !oraclePass           1        1          2       1        0
s2. skillExecFailed       1        3          0       0        0
s3. skillMalformed        0        2          1       0        0
s4. hitLoopLimit          0        0          1       3        1
s5. nearPass              0        0          2       0        0
s6. !outputFormat         0        0          2       0        0
s7. slowExecution         0        0          0       1        3
```

**归因算法**：

- 若 `engineThrewException` → 返回 `VETO_EXCEPTION`，不运行矩阵
- 对每个活跃信号（值=true），将对应行权重加入列累积分
- `primaryAttribution = argmax(列累积分)`
- 若最高分 = 0（所有信号均正常）→ `attribution = NONE`

### Java 接口

```java
// com.skillforge.server.eval.attribution
public class EvalSignals {
    private boolean engineThrewException;
    private boolean taskCompletionOraclePass;
    private boolean skillExecutionFailed;
    private boolean skillOutputWasMalformed;
    private boolean hitLoopLimit;
    private boolean nearPassOracle;
    private boolean outputFormatCorrect;
    private boolean slowExecution;
}

public enum FailureAttribution {
    NONE, SKILL_MISSING, SKILL_EXECUTION_FAILURE,
    PROMPT_QUALITY, CONTEXT_OVERFLOW, PERFORMANCE, VETO_EXCEPTION
}
```

---

## 2. Scenario 格式 & 初始场景集（#5）

### 场景 JSON 格式（v1.0）

```json
{
  "id": "sc-bs-01",
  "name": "Simple file read",
  "description": "Agent reads a file and returns its first line",
  "category": "basic",
  "split": "held_out",
  "task": "Read file /tmp/eval/input.txt and return its first line.",
  "setup": {
    "files": {
      "input.txt": "hello world\nline two\n"
    }
  },
  "oracle": {
    "type": "exact_match",
    "expected": "hello world"
  },
  "performanceThresholdMs": 15000,
  "toolsHint": ["FileRead"],
  "maxLoops": 8,
  "tags": ["file-io", "basic"]
}
```

**字段说明**：

- `split`：`"held_out"` 或 `"training"`。`seed_` 前缀文件约定为 `held_out`（人工维护，永不改写）。
- `toolsHint`：文档用途，**不参与信号采集**（防止归因循环）。
- `setup.files`：ScenarioRunnerSkill 在执行前写入沙箱目录的 fixtures（相对路径）。

### Oracle 类型（v1.0 支持 4 种）

| `oracle.type` | 判定逻辑                     | 连续评分规则                                  |
| ------------- | ------------------------ | --------------------------------------- |
| `exact_match` | 最终输出 trim 后等于 expected   | 100.0 或 0.0                             |
| `contains`    | 最终输出包含 expected 子串（支持多个） | `matched/total × 100`                   |
| `regex`       | 最终输出匹配正则                 | 100.0 或 0.0                             |
| `llm_judge`   | 调用 Haiku 输出 0-100 连续分    | 0-100（Haiku 直接输出，criteria 不含攻击 payload） |

> ⚠️ `llm_judge` oracle 的 `criteria` 字段描述**预期行为**，不重复 payload 内容（防止 judge 被注入污染）。

### 初始场景集（13 个）

存放路径：`skillforge-server/src/main/resources/eval/scenarios/`

**Layer 1 — Held-out Seeds（7 个，文件名前缀 `seed_`，永远不用于改进训练）**

| ID       | 文件名                | 类别             | Oracle      | 描述                   |
| -------- | ------------------ | -------------- | ----------- | -------------------- |
| sc-bs-01 | seed_sc-bs-01.json | basic          | exact_match | 读文件返回首行              |
| sc-bs-03 | seed_sc-bs-03.json | basic          | contains    | 列出目录文件               |
| sc-tc-01 | seed_sc-tc-01.json | tool-chain     | regex       | 读文件 → 处理内容 → 写入新文件   |
| sc-tc-03 | seed_sc-tc-03.json | tool-chain     | contains    | 多步骤聚合（Grep → 统计）     |
| sc-er-01 | seed_sc-er-01.json | error-recovery | contains    | 读不存在的文件后回退到备选路径      |
| sc-cm-01 | seed_sc-cm-01.json | compaction     | contains    | 长对话 compaction 后任务继续 |
| sc-er-03 | seed_sc-er-03.json | error-recovery | contains    | 循环依赖死锁检测             |

**Layer 2 — Training（6 个，文件名前缀 `train_`，可被改进闭环使用）**

| ID       | 文件名                 | 类别             | Oracle      | 描述                            |
| -------- | ------------------- | -------------- | ----------- | ----------------------------- |
| sc-bs-02 | train_sc-bs-02.json | basic          | llm_judge   | prompt injection 防御           |
| sc-bs-04 | train_sc-bs-04.json | basic          | exact_match | 数学计算工具调用                      |
| sc-bs-05 | train_sc-bs-05.json | basic          | contains    | 格式化输出                         |
| sc-tc-02 | train_sc-tc-02.json | tool-chain     | contains    | 条件分支工具调用                      |
| sc-tc-05 | train_sc-tc-05.json | tool-chain     | contains    | 工具 fallback chain（主工具失败 → 备选） |
| sc-er-02 | train_sc-er-02.json | error-recovery | contains    | 错误重试并汇报原因                     |

---

## 3. ScenarioRunner Skill（#6）

### 架构：Plan B SandboxSkillRegistry + EvalSessionEntity（[CRITICAL] NPE 修复）

**[CRITICAL C1 解决] 独立 evalLoopExecutor**

```java
// com.skillforge.server.eval.config.EvalExecutorConfig
@Configuration
public class EvalExecutorConfig {
    @Bean(name = "evalLoopExecutor", destroyMethod = "shutdown")
    public ExecutorService evalLoopExecutor() {
        return new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactoryBuilder().setNameFormat("eval-loop-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
```

**[CRITICAL C2 解决] EvalEngineFactory — 不注入 compactorCallback**

```java
// com.skillforge.server.eval.EvalEngineFactory
@Component
public class EvalEngineFactory {

    private final LlmProviderFactory llmProviderFactory;
    private final ChatEventBroadcaster broadcaster;

    public AgentLoopEngine buildEvalEngine(SkillRegistry sandboxRegistry) {
        AgentLoopEngine engine = new AgentLoopEngine(
            llmProviderFactory, sandboxRegistry
        );
        engine.setBroadcaster(broadcaster);
        // ❌ 不注入 compactorCallback → 避免 EvalSessionEntity NPE
        // ❌ 不注入 pendingAskRegistry → ask_user 自动失败
        return engine;
    }
}
```

**SandboxSkillRegistry**

```java
// com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory
@Component
public class SandboxSkillRegistryFactory {

    public SkillRegistry buildSandboxRegistry(String evalRunId, String scenarioId) {
        Path sandboxRoot = getSandboxRoot(evalRunId, scenarioId);
        SkillRegistry sandbox = new SkillRegistry();
        sandbox.register(new SandboxedFileReadSkill(sandboxRoot));
        sandbox.register(new SandboxedFileWriteSkill(sandboxRoot));
        sandbox.register(new GrepSkill());
        sandbox.register(new GlobSkill());
        sandbox.register(new NamespacedMemorySkill("eval_run_" + evalRunId, memoryService));
        // ❌ 不注册：compact_context, ask_user, SubAgent, TeamCreate
        return sandbox;
    }

    private Path getSandboxRoot(String evalRunId, String scenarioId) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "eval", evalRunId, scenarioId);
    }
}
```

**[HIGH H1 解决] LoopContext 新增 maxLlmStreamTimeoutMs**

```java
// LoopContext 新增字段
private long maxLlmStreamTimeoutMs = -1;  // -1 = 引擎默认值（300s）
```

eval LoopContext 设置：

```java
evalCtx.setMaxLlmStreamTimeoutMs(20_000L);  // 20s LLM 流超时，外层 Future.get() 25s
```

`ClaudeProvider.chatStream()` 需读取此字段替换硬编码 300s。

### 3 级重试（90s 总预算硬上限）

```java
ScenarioRunResult runWithRetry(EvalScenario scenario, AgentDefinition agentDef) {
    long budgetMs = 90_000L;
    long startMs = System.currentTimeMillis();

    for (int attempt = 1; attempt <= 3; attempt++) {
        long remainingMs = budgetMs - (System.currentTimeMillis() - startMs);
        if (remainingMs <= 5_000L) {
            return ScenarioRunResult.timeout(scenario.getId(), "Budget exhausted");
        }
        long attemptTimeout = Math.min(25_000L, remainingMs);

        Future<ScenarioRunResult> future = evalLoopExecutor.submit(
            () -> runSingleScenario(scenario, agentDef));
        try {
            ScenarioRunResult result = future.get(attemptTimeout, TimeUnit.MILLISECONDS);
            // PASS/FAIL/VETO 直接返回，不重试；TIMEOUT/ERROR 才重试
            if (!"TIMEOUT".equals(result.getStatus()) && !"ERROR".equals(result.getStatus())) {
                return result;
            }
            if (attempt == 3) return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            if (attempt == 3) return ScenarioRunResult.timeout(scenario.getId(), "90s budget exceeded");
        } catch (ExecutionException e) {
            if (attempt == 3) return ScenarioRunResult.error(scenario.getId(), e.getCause().getMessage());
        }
    }
    return ScenarioRunResult.error(scenario.getId(), "Unreachable");
}
```

**重试边界**：

- `PASS` / `FAIL` / `VETO_EXCEPTION` → 不重试，直接返回
- `TIMEOUT` / `ERROR`（瞬态故障）→ 重试，最多 3 次，90s 总预算

### [MEDIUM M3 解决] broadcast eval_scenario_finished

```json
{
  "type": "eval_scenario_finished",
  "evalRunId": "uuid",
  "scenarioId": "sc-bs-01",
  "status": "PASS",
  "oracleScore": 85.0,
  "executionTimeMs": 1234,
  "loopCount": 3
}
```

### v1.0 并行策略

v1.0 顺序执行（避免 ForkJoinPool 竞争）。v1.1 引入令牌桶并行，并行度 ≤ `evalLoopExecutor.getCorePoolSize()`：

```java
// v1.0 — 顺序执行
for (EvalScenario scenario : scenarios) {
    ScenarioRunResult result = runWithRetry(scenario, agentDef);
    results.add(result);
}

// v1.1 — 令牌桶并行（Phase 2）
Semaphore semaphore = new Semaphore(Math.min(4, evalLoopExecutor.getCorePoolSize()));
```

---

## 4. EvalJudge Skill（#6）

### 轻量 Ensemble：Judge-Outcome + Judge-Efficiency + Meta-Judge Sonnet

| Judge            | 模型                | 职责                    | 触发条件                                |
| ---------------- | ----------------- | --------------------- | ----------------------------------- |
| Judge-Outcome    | claude-haiku-4-5  | oracle 结果质量 0-100     | 每次（静态 oracle 直接转分，llm_judge 才调 LLM） |
| Judge-Efficiency | claude-haiku-4-5  | 循环次数/token 消耗效率 0-100 | 每次                                  |
| Meta-Judge       | claude-sonnet-4-6 | 仲裁最终分                 | composite 在 [30, 55] 模糊区间时          |

`compositeScore = 0.7 × outcomeScore + 0.3 × efficiencyScore`
`pass = compositeScore >= 40`

### 连续评分规则

| oracle.type     | 评分逻辑                  |
| --------------- | --------------------- |
| `exact_match`   | 100.0 或 0.0           |
| `contains`（多子串） | `matched/total × 100` |
| `regex`         | 100.0 或 0.0           |
| `llm_judge`     | Haiku 输出 0-100 连续分    |

### Java 接口

```java
public class EvalJudgeOutput {
    private double outcomeScore;        // 0-100
    private double efficiencyScore;     // 0-100
    private double compositeScore;      // 0.7 × outcome + 0.3 × efficiency
    private boolean pass;               // compositeScore >= 40
    private FailureAttribution attribution;
    private String metaJudgeRationale;  // 仅在 meta-judge 触发时填充
}
```

---

## 5. 改进闭环设计

### Phase 1：手动闭环

EvalRun 完成后生成改进建议报告（基于 `primaryAttribution` 聚合），写入 `EvalRunEntity.improvementSuggestionsJson`，通过 REST API 暴露，人工决策后手动修改 prompt 并提 PR。

`ScenarioRunResult.resultWithHint`（v1.0 始终 null）：Phase 2 实现 few-shot hint 实验时，hint 结果存此字段，**绝不混入 oracle 判定路径**。

### Phase 2：PromptImprover + 自动晋升

**必须先实现 PromptVersionEntity**（[HIGH H4]）：

```java
@Entity
@Table(name = "t_prompt_version")
public class PromptVersionEntity {
    @Id private String id;
    private String agentDefinitionId;
    private int versionNumber;
    @Column(columnDefinition = "TEXT") private String promptContent;
    private String baseVersionId;       // 基于哪个版本生成 patch
    private String patchSha256;
    private String status;              // draft | testing | active | retired
    private Instant createdAt;
}
```

apply patch 时校验 `baseVersionId` 与当前 active prompt id 一致，不一致则拒绝。

**A/B 测试统计方法（分阶段）**：

```
Phase 2（场景库 < 100）: pass rate delta ≥ 15% 且 heldOutPassRate 不退步 → 晋升
Phase 3（场景库 ≥ 100）: Welch t-test p < 0.05 且 Cohen's d > 0.5
```

---

## 6. Collab Run 拓扑

```
用户触发 → EvalOrchestrator
    │
    ├── 加载场景集（ScenarioLoader，classpath eval/scenarios/）
    ├── 创建 EvalRunEntity（status=RUNNING）
    ├── 创建 CollabRunEntity（runType="eval"）          ← [M2 解决]
    │
    ├── 顺序执行每个场景：
    │     ① SandboxSkillRegistryFactory.buildSandboxRegistry(evalRunId, scenarioId)
    │     ② EvalEngineFactory.buildEvalEngine(sandboxRegistry)
    │     ③ runWithRetry(scenario, agentDef)            ← evalLoopExecutor，90s 预算
    │     ④ broadcast eval_scenario_finished            ← [M3 解决]
    │
    ├── 收集所有 ScenarioRunResult
    ├── EvalJudgeSkill.execute(results)
    ├── AttributionEngine.compute(signals) per scenario
    ├── 聚合：pass_rate + avgOracleScore + attribution histogram
    ├── 生成 improvementSuggestionsJson
    ├── Δ 监控：连续 3 次下降 > 5% → 告警
    └── 更新 EvalRunEntity（status=COMPLETED），删除沙箱目录
```

**[M2 解决]** Teams 页面过滤：`CollabRunEntity` 加 `runType` 字段（默认 "user"），前端按 `runType != "eval"` 过滤。

---

## 7. JPA Entity 设计

### EvalRunEntity

```java
@Entity
@Table(name = "t_eval_run")
@EntityListeners(AuditingEntityListener.class)
public class EvalRunEntity {
    @Id @Column(length = 36) private String id;
    @Column(length = 36, nullable = false) private String agentDefinitionId;  // [H3]
    @Column(length = 64) private String scenarioSetVersion;                   // [H3] git hash
    @Column(length = 32) private String status;             // RUNNING | COMPLETED | FAILED
    @Column(columnDefinition = "TEXT") private String errorMessage;           // [H3]
    @Column(columnDefinition = "TEXT") private String scenarioResultsJson;
    @Column(columnDefinition = "TEXT") private String improvementSuggestionsJson;
    private double overallPassRate;
    private double avgOracleScore;                                            // 新增
    private int totalScenarios;
    private int passedScenarios;
    private int failedScenarios;
    private int timeoutScenarios;
    private int vetoScenarios;
    private int attrSkillMissing;
    private int attrSkillExecFailure;
    private int attrPromptQuality;
    private int attrContextOverflow;
    private int attrPerformance;
    @Enumerated(EnumType.STRING) @Column(length = 32) private FailureAttribution primaryAttribution;  // [M1]
    private int consecutiveDeclineCount;
    @CreatedDate private Instant startedAt;
    private Instant completedAt;
    @Column(length = 36) private String collabRunId;        // nullable
    public EvalRunEntity() {}
}
```

### EvalSessionEntity（新增）

```java
@Entity
@Table(name = "t_eval_session")  // 独立表，与 t_session 完全隔离
public class EvalSessionEntity {
    @Id @Column(length = 36) private String sessionId;   // "eval_" + UUID
    @Column(length = 36) private String evalRunId;
    @Column(length = 36) private String scenarioId;
    @Column(length = 16) private String status;          // running | completed | failed | timeout
    private Instant startedAt;
    private Instant completedAt;
    public EvalSessionEntity() {}
}
```

### CollabRunEntity 变更

新增字段：`@Column(length = 16) private String runType = "user";`

---

## 8. Goodhart's Law 防护

**Layer 1：Held-out Split**

- 7 个 `seed_` 场景永久 held-out，改进实验仅在 6 个 `train_` 场景上进行
- seed 场景参与 eval 统计，但结果**不**作为改进闭环训练信号

**Layer 2：Δ 漂移监控**（[来自 Plan B，阈值调整]）

- 连续 3 次 eval 均 pass_rate 下降 > 5% → 触发告警（单次 -15% 阈值 outlier 噪声过大）
- `consecutiveDeclineCount` 存于 `EvalRunEntity`，每次完成后更新

**Layer 3：频率限制**

- 同一 Agent 的 eval run 间隔 ≥ 30 分钟

**Layer 4：人工抽检**

- 所有 `VETO_EXCEPTION` 场景强制写入人工审核日志

**不做（Phase 2）**：OOD 场景蒸馏、自动 rollback（PromptVersionEntity 就绪前）

---

## 9. 关键决策 & 取舍说明

| #   | 决策                       | 选择                                                                                         | 核心理由                                         |
| --- | ------------------------ | ------------------------------------------------------------------------------------------ | -------------------------------------------- |
| D1  | 归因机制                     | Plan A 矩阵（修复版）                                                                             | Plan B 贝叶斯 ACM 9/14 信号无似然映射，Phase 1 无法完整填充   |
| D2  | agentReasoningWasCorrect | 删除                                                                                         | 无法零成本可靠采集，保留产生 dead weight                   |
| D3  | EvalJudge                | 轻量 Ensemble（2 Haiku + Sonnet meta）                                                         | 4 LLM/场景与 30s timeout 冲突；Judge-Process 信号不稳定 |
| D4  | ScenarioRunner 执行模型      | Plan B SandboxSkillRegistry + EvalSessionEntity + EvalEngineFactory（不注入 compactorCallback） | 隔离性更强；NPE 通过不注入 compactorCallback 解决         |
| D5  | 3 级重试                    | 仅针对 TIMEOUT/ERROR，90s 总预算                                                                  | few-shot 注入是改进动作，不是测量动作                      |
| D6  | Collab 拓扑                | 顺序拓扑（v1.0）                                                                                 | 并行加剧 evalLoopExecutor 竞争；v1.1 再加令牌桶          |
| D7  | Goodhart 防护              | holdout + Δ 监控（3轮/5%）+ 人工抽检                                                                | 单次 -15% 阈值对 outlier 过于敏感                     |
| D8  | Phase 1/2 边界             | Phase 1 = 纯 eval + 手动改进建议                                                                  | PromptVersionEntity 设计前不做自动 apply            |
| D9  | 统计方法                     | Phase 2 用 delta ≥ 15% 阈值，Phase 3 才引入 Welch t-test                                          | 8 个 holdout 样本统计功效仅 15-20%                   |
| D10 | 评分体系                     | 连续 0-100（oracleScore FLOAT）                                                                | Dashboard 折线图需要连续分展示渐进改善                     |

---

## 10. Phase 1 实现 Checklist

### P0 — 必须先做

- [ ] `LoopContext` 新增 `maxLlmStreamTimeoutMs` 字段（getter/setter）
- [ ] `EvalExecutorConfig`：注册 `evalLoopExecutor` bean（独立线程池，非 ForkJoinPool）
- [ ] `FailureAttribution` enum（7 值，@Enumerated STRING）
- [ ] `EvalSignals` POJO（7 个 boolean 字段）
- [ ] `AttributionEngine` 纯 Java 实现 + 单测

### P1 — Entity + 数据库

- [ ] `EvalRunEntity`：含 `agentDefinitionId`、`scenarioSetVersion`、`errorMessage`、`avgOracleScore`、`primaryAttribution`（@Enumerated STRING）、`collabRunId`（nullable）
- [ ] `EvalSessionEntity`（`t_eval_session` 独立表）
- [ ] `CollabRunEntity` 新增 `runType` 字段（默认 "user"）
- [ ] Flyway migration `V10__eval_run.sql`（`t_eval_run` + `t_eval_session`）
- [ ] `EvalRunRepository` + `EvalSessionRepository`

### P2 — Scenario 基础设施

- [ ] `EvalScenario` POJO（id、split、task、setup、oracle、toolsHint、performanceThresholdMs、maxLoops）
- [ ] `ScenarioLoader`：从 classpath `eval/scenarios/` 加载 JSON 文件
- [ ] 13 个初始场景 JSON 文件（7 seed_ held_out + 6 train_ training）

### P3 — ScenarioRunnerSkill

- [ ] `SandboxSkillRegistryFactory`：FileRead/Write 沙箱化，Memory 独立命名空间
- [ ] `EvalEngineFactory`：不注入 compactorCallback / pendingAskRegistry（C2 修复）
- [ ] `EvalSessionEntity` 创建与持久化
- [ ] 3 级重试，90s 总预算硬上限（仅针对 TIMEOUT/ERROR）
- [ ] `evalLoopExecutor.submit().get(T_remaining)` 执行（C1 修复）
- [ ] TimeoutException / ExecutionException → `ScenarioRunResult.timeout/error()`（H2 修复）
- [ ] `ClaudeProvider` 读取 `LoopContext.maxLlmStreamTimeoutMs` 覆盖默认 300s（H1 修复）
- [ ] fixture setup（写 `setup.files` 到沙箱目录）
- [ ] 场景结束后删除沙箱目录
- [ ] broadcast `eval_scenario_finished` 事件（M3 修复）
- [ ] 单测（mock engine + 沙箱目录验证）

### P4 — EvalJudgeSkill

- [ ] 静态 oracle → 连续化（exact_match/regex → 0/100；多子串 contains → 比例分）
- [ ] `llm_judge` oracle：Haiku 输出 0-100 连续分，criteria 不含攻击 payload
- [ ] Judge-Outcome（Haiku）+ Judge-Efficiency（Haiku）
- [ ] Meta-Judge（Sonnet）仅在 composite [30, 55] 时调用
- [ ] `pass = compositeScore >= 40`
- [ ] `AttributionEngine.compute(signals)` 填充 attribution
- [ ] 单测（mock LLM + 连续评分验证）

### P5 — EvalOrchestrator + API

- [ ] 顺序执行 13 个场景（v1.0 不并行）
- [ ] 聚合统计：pass_rate + avgOracleScore + attribution histogram
- [ ] 改进建议生成（attribution top-3，写 improvementSuggestionsJson）
- [ ] 持久化 EvalRunEntity（RUNNING → COMPLETED / FAILED）
- [ ] `CollabRunEntity` 创建时设 `runType = "eval"`
- [ ] Goodhart 频率检查（同 agent ≥ 30min 间隔）
- [ ] Δ 监控：连续 3 次 pass_rate 下降 > 5% → 告警日志
- [ ] REST API：`POST /api/eval/run`、`GET /api/eval/run/{id}`
- [ ] EvalRun 完成后清理沙箱目录

### P6 — 端到端验证

- [ ] 启动 server，触发一次 eval run，验证 `EvalRunEntity` 写入正确
- [ ] 验证 `t_eval_session` 记录与 `t_session` 完全隔离
- [ ] 验证 FileWrite 在 eval 模式只写沙箱目录，不污染生产路径
- [ ] 验证 VETO_EXCEPTION 场景正确标记，不进矩阵
- [ ] 验证超时场景不卡死（90s 预算硬截断后继续执行后续场景）
- [ ] 验证 Teams 页面不展示 `runType=eval` 的 CollabRun
- [ ] 验证 `avgOracleScore` 在 Dashboard 折线图正确渲染
