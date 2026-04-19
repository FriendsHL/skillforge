	# SkillForge Self-Improve Pipeline — 最终设计方案

> 由 Plan A + Plan B 双方案 + Reviewer A/B 双评审 + Judge 仲裁综合产出。
> 更新于：2026-04-19（第二轮 Reviewer A/B + Judge 审查完成，累计修复 28 处问题）

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
  s1. taskCompletionOraclePass:   boolean  — outcomeScore >= 60（oracle 层通过，非 compositeScore）
  s2. skillExecutionFailed:       boolean  — 任一 skill 执行返回 error/exception
  s3. skillOutputWasMalformed:    boolean  — skill 返回格式/类型不符合 schema
  s4. hitLoopLimit:               boolean  — loopCount >= maxLoops
  s5. nearPassOracle:             boolean  — outcomeScore 在 [40, 60)（差点就过，不含 60；60 是 oracle 通过阈值）
  s6. outputFormatCorrect:        boolean  — 最终输出格式符合场景规定（静态检查）
  s7. slowExecution:              boolean  — executionTimeMs > scenario.performanceThresholdMs

⚠️ EvalSignals 构造时序：EvalSignals 由 EvalJudgeSkill 在计算 outcomeScore 之后构造，
再传入 AttributionEngine。ScenarioRunnerSkill 阶段只采集 s2/s3/s4/s6/s7（执行期可知的信号），
s1/s5 依赖 outcomeScore，必须在 EvalJudgeSkill 内部填充。详见 Section 4。

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

### EvalSignals 构造时序（关键：必须在 outcomeScore 计算之后）

```java
// EvalJudgeSkill 内部执行顺序
double outcomeScore = computeOracleScore(scenario, result);         // 先算 oracle 分
double efficiencyScore = computeEfficiency(result, scenario);        // 再算效率分
double compositeScore = 0.7 * outcomeScore + 0.3 * efficiencyScore;

// EvalSignals：s1/s5 依赖 outcomeScore，必须在此处填充；s2/s3/s4/s6/s7 来自 ScenarioRunResult
EvalSignals signals = new EvalSignals();
signals.setEngineThrewException(result.isEngineThrewException());
signals.setTaskCompletionOraclePass(outcomeScore >= 60);   // ← s1：oracle 层通过阈值
signals.setNearPassOracle(outcomeScore >= 40 && outcomeScore < 60);  // ← s5（upper bound exclusive：60 已是 oraclePass，不应重叠）
signals.setSkillExecutionFailed(result.isSkillExecutionFailed());
signals.setSkillOutputWasMalformed(result.isSkillOutputWasMalformed());
signals.setHitLoopLimit(result.getLoopCount() >= scenario.getMaxLoops());
signals.setOutputFormatCorrect(result.isOutputFormatCorrect());
signals.setSlowExecution(result.getExecutionTimeMs() > scenario.getPerformanceThresholdMs());

FailureAttribution attribution = attributionEngine.compute(signals);
```

> ⚠️ ScenarioRunnerSkill **只** 在 `ScenarioRunResult` 里记录执行期信号（s2/s3/s4/s6/s7），
> 不构造 EvalSignals，也不调用 AttributionEngine。

---

## 5. 改进闭环设计

### Phase 1：手动闭环

EvalRun 完成后生成改进建议报告（基于 `primaryAttribution` 聚合），写入 `EvalRunEntity.improvementSuggestionsJson`，通过 REST API 暴露，人工决策后手动修改 prompt 并提 PR。

`ScenarioRunResult.resultWithHint`（v1.0 始终 null）：Phase 2 实现 few-shot hint 实验时，hint 结果存此字段，**绝不混入 oracle 判定路径**。

#### improvementSuggestionsJson Schema（Phase 1 必须完整持久化，Phase 2 读取此结构）

```json
{
  "primaryAttribution": "PROMPT_QUALITY",
  "attributionCounts": {
    "PROMPT_QUALITY": 4,
    "CONTEXT_OVERFLOW": 2,
    "SKILL_MISSING": 0,
    "SKILL_EXECUTION_FAILURE": 0,
    "PERFORMANCE": 0
  },
  "failedScenarios": [
    {
      "scenarioId": "sc-bs-01",
      "scenarioName": "Simple file read",
      "task": "Read file /tmp/eval/input.txt and return its first line.",
      "agentFinalOutput": "I was unable to read the file...(truncated 300 chars)",
      "oracleExpected": "hello world",
      "attribution": "PROMPT_QUALITY",
      "compositeScore": 35.0,
      "split": "held_out"
    }
  ],
  "passedScenarios": [
    {
      "scenarioId": "sc-bs-03",
      "task": "List files in the /tmp/eval directory.(80 chars max)",
      "agentFinalOutput": "file1.txt file2.txt ...(truncated 200 chars)",
      "split": "held_out"
    }
  ]
}
```

> ⚠️ `failedScenarios.agentFinalOutput` 截断为 300 字符，`passedScenarios.agentFinalOutput` 截断为 200 字符。passedScenarios 必须保留 agentFinalOutput，LLM prompt 改进时需知道"通过时的正确输出是什么"以避免退步。`split` 字段在 Phase 2 中用于确认 held-out 场景基准一致性。

### Phase 2：PromptImprover + A/B 评测 + 自动晋升

> 本节为最终设计方案，由 Plan A + Plan B 双方案 + Reviewer A/B 双轮交叉挑战 + 裁判整合产出。
> 更新于：2026-04-16

---

#### 2.1 架构总览

```
用户在 Eval Drawer 点击 "Improve Prompt"
  │
  POST /api/agents/{agentId}/prompt-improve  { evalRunId }
  │
  PromptImproverService（异步，promptImprovExecutor）
  ├── 资格检查（attribution + 冷却期 + Goodhart 暂停状态）
  ├── 调用 Sonnet LLM 生成候选 prompt（只处理 PROMPT_QUALITY / CONTEXT_OVERFLOW）
  ├── 保存 t_prompt_version（status=candidate）
  │
  AbEvalPipeline（abEvalCoordinatorExecutor）
  ├── 加载 held-out 场景（split=held_out，即 7 个 seed_ 场景）
  ├── 从 baseline evalRun.scenarioResultsJson 计算 heldOutBaselineRate（同一子集）
  ├── 用 abEvalLoopExecutor 顺序执行 7 个场景（候选 prompt）
  ├── 存储 per-scenario 对比结果到 t_prompt_ab_run.ab_scenario_results_json
  ├── 计算 Δ = candidatePassRate - heldOutBaselineRate
  │
  PromptPromotionService（@Transactional）
  ├── Goodhart Guard 检查（4 层）
  ├── 晋升 or 丢弃候选
  ├── 发布 PromptPromotedEvent（@TransactionalEventListener AFTER_COMMIT 广播 WS）
  └── 更新 t_agent 字段
```

**核心约束（不可违反）**：

- `EvalOrchestrator.java` 零改动（红灯文件）
- `ScenarioRunnerSkill.java` 零改动（AbEvalPipeline 自己组装底层执行链）
- AB eval 使用独立线程池，与正常 eval 完全隔离

---

#### 2.2 数据库设计

> ⚠️ **Flyway 版本说明**：核心表已通过 **V3–V6** 落地（V3=eval tables, V4=prompt_version tables, V5=eval_scenario DB, V6=widen eval_session_id）。但 V4（已提交，不可修改）**缺少**两个必要条目，Phase 2 实现时必须补充 **V12** migration（见下方 V12 块）：
>
> - `CREATE UNIQUE INDEX uq_pv_agent_active` — V4 未包含，并发晋升防护缺失
> - `t_agent.consecutive_eval_decline_count` 列 — V4 ALTER TABLE 未添加，Goodhart Layer 2 无法持久化
>
> 若 Phase 2/3 需其他字段，新 migration 必须从 **V13** 起编号（V7–V11 已被其他功能占用，V12 保留给以上两个修复）。

```sql
-- 已落地于 V4__prompt_version_tables.sql（参考）

-- ─────────────────────────────────────────────────────────
-- 1. Prompt 版本表
-- ─────────────────────────────────────────────────────────
CREATE TABLE t_prompt_version (
    id                    VARCHAR(36)  PRIMARY KEY,
    agent_id              VARCHAR(36)  NOT NULL,
    content               TEXT         NOT NULL,
    version_number        INTEGER      NOT NULL,
    status                VARCHAR(32)  NOT NULL DEFAULT 'candidate',
    -- candidate: 等待 A/B 测试
    -- active:    当前使用中（每个 agent 只有一个）
    -- deprecated: 已被新版本替换
    -- failed:    A/B 测试未通过（delta < 15pp）或生成失败
    source                VARCHAR(32)  NOT NULL DEFAULT 'auto_improve',
    -- auto_improve: 由 PromptImproverService 生成
    -- manual:       用户手动创建/回滚
    source_eval_run_id    VARCHAR(36),           -- 触发本次改进的 full eval run id
    ab_run_id             VARCHAR(36),           -- 验证此版本的 A/B run id（完成后回填）
    delta_pass_rate       DOUBLE PRECISION,      -- Δ = candidate_rate - baseline_rate（百分制）
    baseline_pass_rate    DOUBLE PRECISION,      -- 控制组通过率快照（held-out 子集，百分制）
    improvement_rationale TEXT,                  -- LLM 生成的改动说明
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    promoted_at           TIMESTAMP,
    deprecated_at         TIMESTAMP,
    CONSTRAINT uq_agent_version UNIQUE (agent_id, version_number)
);

CREATE INDEX idx_pv_agent_id     ON t_prompt_version (agent_id);
CREATE INDEX idx_pv_agent_status ON t_prompt_version (agent_id, status);
-- ⚠️ uq_pv_agent_active 未包含在 V4，已补入 V12（见下方）
-- 并发语义：PostgreSQL 默认 READ COMMITTED 隔离级别下，晋升事务内的"无 active 版本"中间态
--   对并发读者不可见（读者只看到提交后的快照）。此 index 的作用是防并发写，不防中间态读。
--   若未来升级为 SERIALIZABLE 隔离，需重新评估。

-- ─────────────────────────────────────────────────────────
-- 2. A/B 测试运行表（关注点分离：version 是什么 vs 怎么被验证）
-- ─────────────────────────────────────────────────────────
CREATE TABLE t_prompt_ab_run (
    id                       VARCHAR(36)  PRIMARY KEY,
    agent_id                 VARCHAR(36)  NOT NULL,
    prompt_version_id        VARCHAR(36)  NOT NULL,
    baseline_eval_run_id     VARCHAR(36)  NOT NULL,  -- 控制组数据来源
    status                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    -- PENDING / RUNNING / COMPLETED / FAILED
    baseline_pass_rate       DOUBLE PRECISION,       -- held-out 子集通过率（百分制）
    candidate_pass_rate      DOUBLE PRECISION,       -- 候选 prompt 通过率（百分制）
    delta_pass_rate          DOUBLE PRECISION,       -- 最终 Δ（百分制）
    promoted                 BOOLEAN      NOT NULL DEFAULT FALSE,
    skip_reason              VARCHAR(128),           -- NO_ELIGIBLE_FAILURES / COOLDOWN_ACTIVE 等
    failure_reason           TEXT,
    ab_scenario_results_json TEXT,                   -- per-scenario 对比结果（JSON 数组）
    triggered_by_user_id     BIGINT,
    started_at               TIMESTAMP,
    completed_at             TIMESTAMP
);

CREATE INDEX idx_par_agent_id          ON t_prompt_ab_run (agent_id);
CREATE INDEX idx_par_prompt_version_id ON t_prompt_ab_run (prompt_version_id);

-- 并发防护：同一 agent 最多一个 PENDING/RUNNING 的 ab_run
-- INSERT 冲突时抛 DataIntegrityViolationException → 返回 409
CREATE UNIQUE INDEX uq_ab_run_agent_active
    ON t_prompt_ab_run (agent_id)
    WHERE status IN ('PENDING', 'RUNNING');

-- ─────────────────────────────────────────────────────────
-- 3. t_agent 扩展（Goodhart 状态 + 版本指针）
-- ─────────────────────────────────────────────────────────
-- ⚠️ 以下 ALTER TABLE 对应 V4 实际内容（已提交，包含 4 个字段）
ALTER TABLE t_agent
    ADD COLUMN IF NOT EXISTS active_prompt_version_id VARCHAR(36),
    -- 指向当前激活的 t_prompt_version.id（NULL = 未进入版本管理）
    ADD COLUMN IF NOT EXISTS auto_improve_paused      BOOLEAN   NOT NULL DEFAULT FALSE,
    -- Goodhart 停止信号：ab_decline_count >= 3 后置为 true
    ADD COLUMN IF NOT EXISTS ab_decline_count         INTEGER   NOT NULL DEFAULT 0,
    -- AB eval 连续 delta < 0 次数（区别于 t_eval_run.consecutive_decline_count 的全量 eval 趋势）
    ADD COLUMN IF NOT EXISTS last_promoted_at         TIMESTAMP;
    -- 最后一次晋升时间，用于 24h 冷却校验
```

> ⚠️ **V4 缺失项补丁（必须在 Phase 2 实现前创建 V12__fix_missing_phase2_schema.sql）**：

```sql
-- V12__fix_missing_phase2_schema.sql
-- ─────────────────────────────────────────────────────────
-- 1. 每个 agent 最多一个 active 版本（防并发晋升写入两行 active）
--    V4 未包含此 index，实现 PromptPromotionService 前必须创建
-- ─────────────────────────────────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uq_pv_agent_active
    ON t_prompt_version (agent_id) WHERE status = 'active';

-- ─────────────────────────────────────────────────────────
-- 2. Goodhart Layer 2 跨 run 持久化（full eval 连续下降次数）
--    V4 ALTER TABLE 未添加，添加 AgentEntity.consecutiveEvalDeclineCount 字段前必须先跑此 migration
-- ─────────────────────────────────────────────────────────
ALTER TABLE t_agent
    ADD COLUMN IF NOT EXISTS consecutive_eval_decline_count INTEGER NOT NULL DEFAULT 0;
```

**字段命名说明**：

| 字段                          | 表            | 含义                                        | 触发条件                        |
| --------------------------- | ------------ | ----------------------------------------- | --------------------------- |
| `consecutive_decline_count` | `t_eval_run` | **全量 eval（13 场景）**连续下降次数                  | full eval pass rate 下降 ≥ 5% |
| `ab_decline_count`          | `t_agent`    | **AB eval（7 held-out 场景）**连续 delta < 0 次数 | AB eval delta < 0 时递增       |

---

#### 2.3 线程池设计（三套完全隔离）

```java
// AbEvalExecutorConfig.java（新增）
@Configuration
public class AbEvalExecutorConfig {

    // Outer：AB 改进流程协调（LLM 调用 + pipeline 串联）
    @Bean("abEvalCoordinatorExecutor")
    public ExecutorService abEvalCoordinatorExecutor() {
        return new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10),
            r -> new Thread(r, "ab-eval-coord-" + ...),
            new ThreadPoolExecutor.AbortPolicy()  // 超载直接 503，不阻塞 HTTP 线程
        );
    }

    // Inner：AB eval 场景执行（与 evalLoopExecutor 完全隔离，互不抢占）
    @Bean("abEvalLoopExecutor")
    public ExecutorService abEvalLoopExecutor() {
        return new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20),
            r -> new Thread(r, "ab-eval-loop-" + ...),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
```

三套线程池职责：

| 线程池                         | 用途                | 备注                         |
| --------------------------- | ----------------- | -------------------------- |
| `evalOrchestratorExecutor`  | 正常 full eval 外层协调 | Phase 1 已有                 |
| `evalLoopExecutor`          | 正常 eval 场景执行      | Phase 1 已有，AB eval **不复用** |
| `abEvalCoordinatorExecutor` | AB 改进流程协调         | Phase 2 新增                 |
| `abEvalLoopExecutor`        | AB eval 场景执行      | Phase 2 新增，完全独立            |

---

#### 2.4 PromptImproverService

```java
@Service
public class PromptImproverService {

    private static final Set<FailureAttribution> ELIGIBLE = Set.of(
        FailureAttribution.PROMPT_QUALITY, FailureAttribution.CONTEXT_OVERFLOW);

    /**
     * 异步启动改进流程，立即返回 abRunId。
     * 后续通过 WS 事件推送进度。
     */
    public ImprovementStartResult startImprovement(String agentId, String evalRunId, long userId) {
        EvalRunEntity evalRun = evalRunRepository.findById(evalRunId).orElseThrow();
        AgentEntity agent = agentRepository.findById(agentId).orElseThrow();

        checkEligibility(agent, evalRun, agentId);  // 抛 ImprovementIneligibleException

        // 生成候选 PromptVersion（status=candidate，content 待 LLM 填充）
        PromptVersionEntity version = createCandidateVersion(agent, evalRunId);
        // 并发防护：INSERT t_prompt_ab_run(status=PENDING)，unique partial index 兜底
        PromptAbRunEntity abRun = createAbRun(agentId, version.getId(), evalRunId, userId);

        try {
            abEvalCoordinatorExecutor.submit(() -> runImprovementAsync(version, abRun, evalRun, agent));
        } catch (RejectedExecutionException e) {
            // 线程池满（AbortPolicy），返回 429 而非 500
            // ⚠️ 若 startImprovement 有外层 @Transactional，catch 块内的 save 会随抛出的 RuntimeException 一起回滚。
            // 审计要求：FAILED 记录落库 → 用 REQUIRES_NEW 在独立事务中持久化后再抛出
            saveFailedAbRunInNewTx(abRun, "improve_queue_full");   // @Transactional(propagation = REQUIRES_NEW)
            throw new ImprovementQueueFullException("IMPROVE_QUEUE_FULL");
            // Controller 层捕获 ImprovementQueueFullException → HTTP 429 + Retry-After: 30
        }

        return new ImprovementStartResult(abRun.getId(), version.getId(), "PENDING");
    }

    private void checkEligibility(AgentEntity agent, EvalRunEntity evalRun, String agentId) {
        // 0. [SECURITY] evalRun 必须归属于 agentId（防止跨 Agent 污染攻击）
        if (!agentId.equals(evalRun.getAgentDefinitionId()))
            throw new ImprovementIneligibleException("EVAL_RUN_AGENT_MISMATCH");

        // 1. Eval 必须是 COMPLETED 状态的 full eval（非 AB eval）
        if (!"COMPLETED".equals(evalRun.getStatus()))
            throw new ImprovementIneligibleException("EVAL_NOT_COMPLETED");

        // 2. Attribution 必须可处理
        if (!ELIGIBLE.contains(evalRun.getPrimaryAttribution()))
            throw new ImprovementIneligibleException("INELIGIBLE_ATTRIBUTION");

        // 3. 24h 冷却
        if (agent.getLastPromotedAt() != null) {
            long hours = Duration.between(agent.getLastPromotedAt(), Instant.now()).toHours();
            if (hours < 24) throw new ImprovementIneligibleException("COOLDOWN_ACTIVE");
        }

        // 4. Goodhart 暂停
        if (agent.isAutoImprovePaused())
            throw new ImprovementIneligibleException("AUTO_IMPROVE_PAUSED");
    }

    private void runImprovementAsync(PromptVersionEntity version, PromptAbRunEntity abRun,
                                      EvalRunEntity evalRun, AgentEntity agent) {
        try {
            // Step 1: LLM 生成候选 prompt（Sonnet，temperature=0.3）
            String candidateContent = generateCandidatePrompt(evalRun, agent);
            version.setContent(candidateContent);
            promptVersionRepository.save(version);

            // Step 2: A/B eval（独立 pipeline，不经过 EvalOrchestrator）
            abEvalPipeline.run(abRun, version, evalRun, agent);

            // Step 3: 晋升决策（在 PromptPromotionService 内完成）
            promotionService.evaluateAndPromote(abRun.getId(), agent.getId());

        } catch (DataIntegrityViolationException e) {
            // unique partial index 触发，另一个 ab_run 并发插入
            throw new ImprovementConflictException("ALREADY_IMPROVING");
        } catch (Exception e) {
            abRun.setStatus("FAILED");
            abRun.setFailureReason(e.getMessage());
            abRunRepository.save(abRun);
        }
    }
}
```

**LLM Prompt 结构（Sonnet，只传 PROMPT_QUALITY/CONTEXT_OVERFLOW 的失败场景）**：

```
[System]
You are a prompt engineer. Analyze failure patterns and produce an improved system prompt.
Return ONLY the raw prompt text — no markdown, no explanation, no prefix.

[User]
CURRENT SYSTEM PROMPT:
---
{agent.systemPrompt}
---
PRIMARY FAILURE ATTRIBUTION: {primaryAttribution}

FAILED SCENARIOS (eligible attributions only):
[{scenarioName}]
Task: {task}
Agent Output (truncated 300 chars): {agentFinalOutput}
Expected: {oracle.expected}
Attribution: {failureAttribution}

PASSED SCENARIOS (preserve this behavior):
- {scenarioName}: {task, 80 chars}

GUIDANCE:
{if CONTEXT_OVERFLOW: "Require agent to summarize state at each step before proceeding."}
{if PROMPT_QUALITY: "Clarify task boundaries and output format requirements."}

Produce an improved system prompt addressing the failures without regressing the passing scenarios.
```

---

#### 2.5 AbEvalPipeline（不改 EvalOrchestrator / ScenarioRunnerSkill）

```java
@Component
public class AbEvalPipeline {

    // 注意：直接使用底层组件，不经过 ScenarioRunnerSkill（避免 retry/cleanup/session 逻辑混入）
    @Autowired private SandboxSkillRegistryFactory sandboxFactory;
    @Autowired private EvalEngineFactory evalEngineFactory;
    @Autowired private EvalJudgeSkill evalJudgeSkill;
    @Qualifier("abEvalLoopExecutor") @Autowired private ExecutorService loopExecutor;

    public void run(PromptAbRunEntity abRun, PromptVersionEntity candidate,
                    EvalRunEntity baselineRun, AgentEntity originalAgent) {
        abRun.setStatus("RUNNING");
        abRunRepository.save(abRun);

        // 1. 加载 held-out 场景（split=held_out，7 个 seed_ 场景）
        List<EvalScenario> heldOutScenarios = scenarioLoader.loadAll().stream()
            .filter(s -> "held_out".equals(s.getSplit()))
            .toList();

        // 2. 场景版本一致性校验（防止 baseline/candidate 分母不一致导致 delta 失真）
        String currentVersion = scenarioLoader.currentScenarioSetVersion();
        if (!currentVersion.equals(baselineRun.getScenarioSetVersion())) {
            // ⚠️ 候选版本必须同时标记为 failed，否则它永远停在 candidate 状态（孤儿 prompt version）
            candidate.setStatus("failed");
            promptVersionRepository.save(candidate);
            abRun.setStatus("FAILED");
            abRun.setSkipReason("BASELINE_SCENARIO_VERSION_MISMATCH");
            abRunRepository.save(abRun);
            return;  // 场景集版本不一致，跳过 A/B，等下次 full eval 后重触发
        }

        // 3. 计算 baseline（从 baselineRun.scenarioResultsJson 过滤同一子集，保证分母一致）
        double baselineRate = computeHeldOutPassRate(baselineRun.getScenarioResultsJson());

        // 4. 构造候选 AgentEntity（transient 拷贝，永不 save，显式不复制 @Id 防 JPA 污染）
        AgentEntity candidateDef = copyWithPrompt(originalAgent, candidate.getContent());

        // 5. 逐场景执行（自组装底层调用，30s timeout，无 retry）
        List<AbScenarioResult> results = new ArrayList<>();
        for (EvalScenario scenario : heldOutScenarios) {
            AbScenarioResult result = runSingleScenario(scenario, candidateDef, abRun.getId());
            results.add(result);
            broadcastScenarioProgress(abRun, result);  // WS 推送 ab_scenario_finished
        }

        // 6. 计算 Δ
        double candidateRate = results.stream().filter(r -> r.pass()).count()
            / (double) results.size() * 100.0;
        double delta = candidateRate - baselineRate;

        // 7. 回填结果
        abRun.setBaselinePassRate(baselineRate);
        abRun.setCandidatePassRate(candidateRate);
        abRun.setDeltaPassRate(delta);
        abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(results));
        abRun.setStatus("COMPLETED");
        abRunRepository.save(abRun);

        candidate.setDeltaPassRate(delta);
        candidate.setBaselinePassRate(baselineRate);
        candidate.setAbRunId(abRun.getId());
        promptVersionRepository.save(candidate);
    }

    private AbScenarioResult runSingleScenario(EvalScenario scenario, AgentEntity agentDef, String abRunId) {
        String sandboxPath = buildSandboxPath(abRunId, scenario.getId());
        try {
            SandboxSkillRegistry registry = sandboxFactory.createSandbox(scenario, abRunId, scenario.getId());
            AgentLoopEngine engine = evalEngineFactory.buildEngine(agentDef, registry);
            LoopResult result = loopExecutor.submit(() -> engine.run(buildCtx(scenario, agentDef)))
                .get(30, TimeUnit.SECONDS);  // AB eval 单场景 30s（不需要 90s budget）
            EvalJudgeOutput judge = evalJudgeSkill.judge(scenario, result);
            return AbScenarioResult.from(scenario, result, judge);
        } catch (TimeoutException e) {
            return AbScenarioResult.timeout(scenario);
        } finally {
            sandboxFactory.cleanupSandbox(sandboxPath);
        }
    }

    /** held_out == seed_：同一批 7 个场景，两种称呼统一用 split=held_out */
    private double computeHeldOutPassRate(String scenarioResultsJson) {
        // 从 JSON 过滤 split=held_out 的场景，计算通过率（百分制）
        // 保证与 AB eval 用相同子集，delta 分母严格对齐
        ...
    }

    /**
     * 构造用于 AB eval 的 transient AgentEntity 拷贝。
     * ⚠️ 不复制 @Id 字段，确保此对象不被 JPA 托管（永不 persist/merge）。
     * 调用方禁止将返回值传给任何 save()/saveAndFlush()/merge()。
     */
    private AgentEntity copyWithPrompt(AgentEntity original, String newPrompt) {
        AgentEntity copy = new AgentEntity();
        // id 不复制 → JPA 不认为它是 detached entity，不会触发意外 UPDATE
        copy.setName(original.getName());
        copy.setModelId(original.getModelId());
        copy.setSystemPrompt(newPrompt);           // 替换为候选 prompt
        copy.setSkillIds(original.getSkillIds());
        copy.setToolIds(original.getToolIds());
        copy.setMaxLoops(original.getMaxLoops());
        copy.setBehaviorRules(original.getBehaviorRules());
        copy.setConfig(original.getConfig());
        // 其他运行时无关字段不复制（activePromptVersionId / abDeclineCount 等）
        return copy;
    }
}
```

---

#### 2.6 PromptPromotionService（Goodhart 四层防护）

```java
@Service
public class PromptPromotionService {

    // 晋升阈值：百分制（overallPassRate 存储为 0–100），15 个百分点
    private static final double PROMOTION_DELTA_THRESHOLD_PP = 15.0;

    @Transactional
    public PromotionResult evaluateAndPromote(String abRunId, String agentId) {
        PromptAbRunEntity abRun = abRunRepository.findById(abRunId).orElseThrow();
        AgentEntity agent = agentRepository.findById(agentId).orElseThrow();
        PromptVersionEntity candidate = promptVersionRepository
            .findById(abRun.getPromptVersionId()).orElseThrow();

        // Guard 1: Δ 阈值（百分制，15pp）
        if (abRun.getDeltaPassRate() < PROMOTION_DELTA_THRESHOLD_PP) {
            candidate.setStatus("failed");
            promptVersionRepository.save(candidate);
            updateAbDeclineTracking(agent, abRun.getDeltaPassRate());
            return PromotionResult.rejected("DELTA_BELOW_THRESHOLD");
        }

        // Guard 2: 每天最多晋升 1 次（无状态，实时查询，无定时任务依赖）
        if (hasPromotedToday(agentId)) return PromotionResult.rejected("DAILY_LIMIT");

        // Guard 3: 24h 冷却
        if (agent.getLastPromotedAt() != null) {
            long hours = Duration.between(agent.getLastPromotedAt(), Instant.now()).toHours();
            if (hours < 24) return PromotionResult.rejected("COOLDOWN_ACTIVE");
        }

        // Guard 4: AB eval 连续下降 >= 3 次暂停
        if (agent.isAutoImprovePaused()) return PromotionResult.rejected("AUTO_IMPROVE_PAUSED");

        // ── 原子晋升（@Transactional 保证）─────────────────────────
        // a. 旧 active → deprecated
        promptVersionRepository.findByAgentIdAndStatus(agentId, "active")
            .forEach(v -> { v.setStatus("deprecated"); v.setDeprecatedAt(Instant.now()); });

        // b. 候选 → active
        candidate.setStatus("active");
        candidate.setPromotedAt(Instant.now());

        // c. 更新 t_agent
        agent.setSystemPrompt(candidate.getContent());
        agent.setActivePromptVersionId(candidate.getId());
        agent.setLastPromotedAt(Instant.now());
        agent.setAbDeclineCount(0);  // 成功晋升后清零
        agentRepository.save(agent);

        abRun.setPromoted(true);
        abRunRepository.save(abRun);

        // d. 发布事件（AFTER_COMMIT 广播，事务回滚时不触发）
        // ⚠️ 必须传入第 5 个参数 triggeredByUserId：异步线程 SecurityContextHolder 为空，只能从 abRun 取
        eventPublisher.publishEvent(new PromptPromotedEvent(
            agentId, candidate.getId(), abRun.getDeltaPassRate(), candidate.getVersionNumber(),
            abRun.getTriggeredByUserId()));  // ← 5th field，否则 record 构造器编译失败

        return PromotionResult.promoted(candidate.getId());
    }

    private boolean hasPromotedToday(String agentId) {
        // 实时查询，无状态，无定时任务
        // ⚠️ 使用参数化区间查询，不要用 CAST(...AS LocalDate)（非标准 JPQL，Hibernate 抛异常）
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
        // ⚠️ JPQL 不支持 SELECT COUNT(r) > 0（Hibernate 解析阶段抛 QueryException）
        // PromptAbRunRepository 声明：
        //   @Query("SELECT COUNT(r) FROM PromptAbRunEntity r WHERE r.agentId = :agentId
        //           AND r.promoted = true AND r.completedAt >= :startOfDay
        //           AND r.completedAt < :endOfDay")
        //   long countPromotedInRange(@Param("agentId") String agentId,
        //                             @Param("startOfDay") Instant startOfDay,
        //                             @Param("endOfDay") Instant endOfDay);
        return promptAbRunRepository.countPromotedInRange(agentId, startOfDay, endOfDay) > 0;
        // > 0 比较在 Java 层完成，不在 JPQL 内
    }

    private void updateAbDeclineTracking(AgentEntity agent, double delta) {
        // 只在真实下降（delta < 0）时递增 ab_decline_count。
        // 设计说明：0 <= delta < 15pp 被视为"正向探索中"，不计为下降，不递增计数器。
        // 已知边界：这意味着持续生成"有改善但不足以晋升"的候选 prompt 不会触发自动暂停。
        // 如需防范无限低效探索，Phase 2 可引入独立的 ineffective_ab_count 每日上限，但 v1.0 暂不实现。
        if (delta < 0) {
            int newCount = agent.getAbDeclineCount() + 1;
            agent.setAbDeclineCount(newCount);
            if (newCount >= 3) {
                agent.setAutoImprovePaused(true);
                eventPublisher.publishEvent(new ImprovePausedEvent(agent.getId(), newCount));
            }
            agentRepository.save(agent);
        }
    }
}

// WS 广播在事务提交后执行（不在事务内做 I/O）
@Component
public class PromptEventBroadcaster {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPromoted(PromptPromotedEvent e) {
        // userId 来源：PromptPromotionService 从 abRun.getTriggeredByUserId() 取值注入 event
        // 异步线程中 SecurityContextHolder 为空，必须通过 event 字段显式传递 userId
        broadcaster.userEvent(e.triggeredByUserId(), Map.of(
            "type", "prompt_promoted",
            "agentId", e.agentId(),
            "versionId", e.versionId(),
            "deltaPassRate", e.deltaPassRate(),
            "versionNumber", e.versionNumber()
        ));
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaused(ImprovePausedEvent e) {
        broadcaster.broadcast(Map.of("type", "improve_paused", "agentId", e.agentId()));
    }
}

// PromptPromotedEvent record（必须携带 triggeredByUserId）
public record PromptPromotedEvent(
    String agentId,
    String versionId,
    double deltaPassRate,
    int versionNumber,
    Long triggeredByUserId  // 从 abRun.getTriggeredByUserId() 注入，不依赖 SecurityContextHolder
) {}
```

---

#### 2.7 REST API

```
# 触发改进（improve 是 agent 级操作，evalRunId 是触发上下文）
POST   /api/agents/{agentId}/prompt-improve
Body:  { "evalRunId": "xxx" }
→ 202 { "abRunId": "...", "promptVersionId": "...", "status": "PENDING" }
→ 409 { "error": "ALREADY_IMPROVING" }  ← unique partial index 触发
→ 422 { "error": "EVAL_RUN_AGENT_MISMATCH" }  ← evalRunId 不属于该 agentId（安全校验）
→ 422 { "error": "INELIGIBLE_ATTRIBUTION" | "COOLDOWN_ACTIVE" | "AUTO_IMPROVE_PAUSED" | "EVAL_NOT_COMPLETED" }
→ 429 { "error": "IMPROVE_QUEUE_FULL", "retryAfterSeconds": 30 }  ← abEvalCoordinatorExecutor AbortPolicy 触发

# 查询 A/B 测试状态（含 per-scenario 明细）
GET    /api/agents/{agentId}/prompt-improve/{abRunId}
→ { abRunId, status, deltaPassRate, candidatePassRate, baselinePassRate,
    promoted, completedScenariosCount, totalScenariosCount,
    scenarioResults: [ { scenarioId, scenarioName,
    baseline: { status, oracleScore }, candidate: { status, oracleScore } } ] }

# 查询最近一次 AB run（前端 mount 时调用，用于状态恢复）
# ⚠️ 不限制 2h 时间窗口，始终返回最近一次 abRun 快照（无论多久之前）
# 前端根据 status 和 completedAt 自行决定是否展示/折叠
GET    /api/agents/{agentId}/prompt-improve/latest
→ 200 { abRunId, promptVersionId, status, deltaPassRate, promoted,
        completedScenariosCount, totalScenariosCount, completedAt }
→ 204 No Content（从未触发过改进）

# Prompt 版本历史列表
GET    /api/agents/{agentId}/prompt-versions?page=0&size=10
→ [ { id, versionNumber, status, source, deltaPassRate, baselinePassRate,
       improvementRationale, createdAt, promotedAt, deprecatedAt } ]

# 单个版本详情（含完整 content）
GET    /api/agents/{agentId}/prompt-versions/{versionId}
→ { ...同上 + content }

# 手动回滚（仅历史 deprecated 版本；必须 @Transactional；直接生效不走 AB 测试）
# 注意：rollback 不更新 last_promoted_at（rollback 不算"晋升"，不触发冷却）
# 注意：rollback 不重置 abDeclineCount（防止用户通过反复 rollback 绕过暂停机制）
# rollback 后 t_agent.systemPrompt 更新为目标版本 content，active_prompt_version_id 指向目标版本
POST   /api/agents/{agentId}/prompt-versions/{versionId}/rollback
→ { "success": true, "newActiveVersionId": "..." }

# 恢复被 Goodhart 暂停的改进功能（显式操作，需用户确认）
POST   /api/agents/{agentId}/prompt-improve/resume
→ { "success": true }
# 清零 ab_decline_count，设置 auto_improve_paused=false，记录操作日志
```

---

#### 2.8 前端交互

**ImprovePromptButton 状态机（WS 事件驱动 + onopen 快照同步）**：

```typescript
type ImproveState =
  | { phase: 'ineligible'; reason: string }
  | { phase: 'idle' }
  | { phase: 'generating' }                              // LLM 生成候选中
  | { phase: 'ab_testing'; progress: number }            // AB eval 中（N/7）
  | { phase: 'success'; delta: number; promoted: boolean }
  | { phase: 'skipped'; reason: string }
  | { phase: 'failed'; error: string };

// 组件 mount / WS 断线重连时做快照同步（防止状态卡住）
// 使用 /latest 而非 /active，不受 2h 窗口限制
useEffect(() => {
  api.getLatestAbRun(agentId).then(snapshot => {
    if (snapshot) syncStateFromSnapshot(snapshot);
    // snapshot.completedScenariosCount 用于恢复 ab_testing progress（断线后不从 0 开始）
  });
}, [agentId]);

ws.onopen = () => {
  api.getLatestAbRun(agentId).then(syncStateFromSnapshot);
};

// WS 事件驱动状态转换
// ab_test_started → generating→ab_testing
// ab_scenario_finished → progress++
// ab_test_completed → success（promoted/not）
// improve_paused → ineligible
```

**Prompt History Panel（Agent 详情页）**：

- 竖排时间线，active 版本置顶绿色 badge
- 每行：`v{N} · status · source · Δ{deltaPassRate}pp · {createdAt}`
- "View" → Modal 展示完整 prompt（`<pre>` 纯文本，防 XSS）
- "Rollback" → 仅对 deprecated 版本显示，二次确认后直接生效
- AB 结果区域：per-scenario 对比表（FAIL→PASS 绿色，PASS→FAIL 红色）

---

#### 2.9 新增/修改文件清单（Phase 2）

**新增文件（14 个）**：

| 文件                                                | 行数   | 说明                               |
| ------------------------------------------------- | ---- | -------------------------------- |
| `V4__prompt_version_tables.sql`                   | ~70  | Flyway migration                 |
| `entity/PromptVersionEntity.java`                 | ~80  | JPA entity                       |
| `entity/PromptAbRunEntity.java`                   | ~70  | JPA entity                       |
| `repository/PromptVersionRepository.java`         | ~30  | Spring Data                      |
| `repository/PromptAbRunRepository.java`           | ~30  | Spring Data + hasPromotedInRange（区间查询，替代非法 CAST JPQL） |
| `improve/PromptImproverService.java`              | ~180 | 触发/资格检查/LLM 调用                   |
| `improve/AbEvalPipeline.java`                     | ~200 | AB eval 独立执行链                    |
| `improve/PromptPromotionService.java`             | ~150 | 晋升 + 4 层 Goodhart 防护             |
| `improve/event/PromptPromotedEvent.java`          | ~20  | Spring ApplicationEvent          |
| `improve/event/ImprovePausedEvent.java`           | ~15  | Spring ApplicationEvent          |
| `improve/event/PromptEventBroadcaster.java`       | ~40  | @TransactionalEventListener      |
| `eval/event/EvalRunCompletedEvent.java`           | ~15  | EvalOrchestrator 完成后发布            |
| `eval/ConsecutiveDeclineTracker.java`             | ~50  | @TransactionalEventListener，追踪 decline + 告警 |
| `config/AbEvalExecutorConfig.java`                | ~40  | 两个线程池 bean                       |
| `controller/PromptImproveController.java`         | ~120 | REST 端点                          |
| `dashboard/src/components/PromptHistoryPanel.tsx` | ~200 | 版本列表 + AB 明细                     |

**修改文件（5 个）**：

| 文件                                        | 改动                                                                                       |
| ----------------------------------------- | ---------------------------------------------------------------------------------------- |
| `entity/AgentEntity.java`                 | +5 字段（active_prompt_version_id, auto_improve_paused, ab_decline_count, last_promoted_at，已在 V4 落地）+ consecutive_eval_decline_count（需在 V12 migration 执行后再添加字段，否则 Hibernate validate 报列不存在） |
| `dashboard/src/pages/Eval.tsx`            | +ImprovePromptButton，Drawer 底部                                                           |
| `dashboard/src/pages/AgentDetail.tsx`     | +Prompt History tab                                                                      |
| `dashboard/src/api/index.ts`              | +6 API 函数                                                                                |
| `dashboard/src/hooks/useImprovePrompt.ts` | 新建（ImprovePromptButton 逻辑提取）                                                             |

**零改动（红灯文件）**：`EvalOrchestrator.java`、`ScenarioRunnerSkill.java`

---

#### 2.10 A/B 测试统计方法

```
Phase 2（当前）：delta ≥ 15pp（百分制），held-out 子集不退步 → 晋升
Phase 3（场景库 ≥ 100）：Welch t-test p < 0.05 且 Cohen's d > 0.5
```

**注意**：`held_out == seed_`，指的是同一批 7 个场景（文件名前缀 `seed_`，JSON 字段 `split=held_out`）。代码中统一使用 `split=held_out` 过滤，不依赖文件名。

**已知设计权衡：held-out 场景覆盖盲区**

> AB eval 使用 held-out 场景评估泛化性，而非 training 场景的改善程度。
> 如果 Agent 的失败仅出现在 training 场景（held-out 全通过），delta 可能偏低，导致有价值的候选 prompt 被 delta < 15pp 拒绝。
> 这是**有意为之**：防止对 training 场景过拟合。Phase 3 通过扩大场景库（≥100 + Welch t-test）缓解此问题。

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
    // ⚠️ "eval_" (5字符) + UUID (36字符) = 41字符，必须用 VARCHAR(64)
    // V6__widen_eval_session_id.sql 已将列宽扩为 VARCHAR(64)
    @Id @Column(length = 64) private String sessionId;   // "eval_" + UUID
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

- 连续 3 次 full eval pass_rate 下降 > 5% → 触发告警（单次 -15% 阈值对 outlier 过于敏感）
- **跨 run 持久化机制**：`consecutiveDeclineCount` 存于 **`t_agent`**（与 `ab_decline_count` 对称，命名 `consecutiveEvalDeclineCount`），不存于 `EvalRunEntity`（EvalRunEntity 是只读快照，不做跨 run 累积）。
  ⚠️ V4 migration **未包含** `consecutive_eval_decline_count` 列，**必须在 V12 migration 执行后**才能向 `AgentEntity.java` 添加此字段（详见 Section 2.2 V12 块）。
- **实现路径（EvalOrchestrator 零改动约束）**：`EvalOrchestrator.java` 是红灯文件，decline 追踪逻辑必须通过事件驱动解耦：
  - EvalOrchestrator 在完成节点发布 `EvalRunCompletedEvent`（单行 `publishEvent`，不改核心引擎逻辑）
  - 新增 `ConsecutiveDeclineTracker`（`@TransactionalEventListener AFTER_COMMIT`）接收事件，查上一条 completed full eval，下降 > 5% 则递增 `consecutiveEvalDeclineCount`，否则清零；`>= 3` 时发布 `EvalDeclineAlertEvent` + 写告警日志

  ```java
  @Component
  public class ConsecutiveDeclineTracker {
      @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
      public void onEvalCompleted(EvalRunCompletedEvent event) {
          EvalRunEntity prev = evalRunRepository
              .findLastCompletedBefore(event.agentId(), event.evalRunId());
          AgentEntity agent = agentRepository.findById(event.agentId()).orElseThrow();
          if (prev != null && event.overallPassRate() < prev.getOverallPassRate() - 5.0) {
              int count = agent.getConsecutiveEvalDeclineCount() + 1;
              agent.setConsecutiveEvalDeclineCount(count);
              if (count >= 3) eventPublisher.publishEvent(new EvalDeclineAlertEvent(event.agentId(), count));
          } else {
              agent.setConsecutiveEvalDeclineCount(0);
          }
          agentRepository.save(agent);
      }
  }
  ```
  
- `EvalRunEntity.consecutiveDeclineCount` 字段保留为**快照值**（写入时的 t_agent 值），仅供历史审计。

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
| D11 | AbortPolicy 处理           | catch RejectedExecutionException → 429（不穿透到 500）                                          | 用户可重试；避免前端无法区分业务拒绝和服务崩溃                   |
| D12 | hasPromotedToday 实现      | 参数化区间查询（startOfDay/endOfDay Instant）取代 CAST JPQL                                           | 标准 JPQL 不支持 CAST...AS LocalDate，Hibernate 抛异常  |
| D13 | EvalSignals 构造时序         | EvalJudgeSkill 内部（outcomeScore 之后），不在 ScenarioRunnerSkill                                  | nearPassOracle / taskCompletionOraclePass 依赖 outcomeScore |
| D14 | consecutiveDeclineCount 持久化 | 存于 t_agent（跨 run 累积），EvalRunEntity 保留快照                                               | EvalRunEntity 是只读历史，不做有状态累积                    |
| D15 | rollback 不更新 last_promoted_at | rollback 不算晋升，不触发 24h 冷却                                                              | 防止用户回滚后被意外锁住，无法快速 improve                   |
| D16 | t_prompt_version active 约束 | 新增 `UNIQUE(agent_id) WHERE status='active'` 防并发晋升双写                                    | 与 `uq_ab_run_agent_active` 对称，数据库层强制一致性          |
| D17 | evalRunId 归属校验           | checkEligibility 首步校验 `evalRun.agentDefinitionId == agentId`                              | OWASP A01：防止跨 Agent prompt 污染攻击                  |
| D18 | /active → /latest 重命名   | 去掉 2h 窗口限制，始终返回最近一次 abRun                                                             | 2h 后结果消失，用户不知道改进结果，体验差且浪费资源                |
| D19 | nearPassOracle 上界        | `[40, 60)` 排他上界（`outcomeScore < 60`）                                                    | 60 是 taskCompletionOraclePass 通过阈值；两个信号同时为 true 时矩阵权重叠加，导致 PROMPT_QUALITY 虚高 |
| D20 | V12 migration 顺序约束      | `AgentEntity.consecutiveEvalDeclineCount` 字段必须在 V12 执行后才能添加                             | Hibernate `validate` 在启动时校验列存在；先改 Entity 不跑 migration 会导致启动失败                |
| D21 | passedScenarios 含 agentFinalOutput | passedScenarios 也携带 agentFinalOutput（200 chars）                                  | LLM 改进 prompt 时需要知道"通过时的正确输出"以避免回归；否则改进建议可能破坏已通过场景            |
| D22 | 候选版本孤儿防护              | BASELINE_SCENARIO_VERSION_MISMATCH 时同步设 candidate.status="failed"                         | 若只设 abRun.status="FAILED"，candidate 永久停在 "candidate" 状态，影响版本历史展示和并发防护逻辑 |
| D23 | consecutiveDeclineCount 实现路径 | 事件驱动（EvalRunCompletedEvent + ConsecutiveDeclineTracker），EvalOrchestrator 只加单行 publishEvent | 红灯文件约束与"在 EvalOrchestrator 内追踪 decline"直接矛盾；事件监听方案满足零改动核心引擎逻辑的约束 |
| D24 | hasPromotedToday JPQL        | Repository 返回 `long countPromotedInRange(...)`，Java 层做 `> 0` 比较                          | JPQL 不支持 `SELECT COUNT(r) > 0` 语法，Hibernate 解析即抛 QueryException，不是运行时错误  |
| D25 | FAILED abRun 事务隔离         | `saveFailedAbRunInNewTx` 用 `REQUIRES_NEW` 传播级别，独立事务提交                                  | 若外层 `@Transactional` 回滚，catch 块内 save 会随之回滚，审计记录消失                         |

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
