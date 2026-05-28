# Tech Design — OPT-LOOP-FRAMEWORK

> 技术方案：飞轮编排框架抽取 + Run 实体泛化（骨架，PRD ratify 后深化）
> 2026-05-27

---

## 1. 方案概述

- **基于**：OPT-REPORT-V1 已 ship 的"orchestrator agent + Run + Dashboard + WS push" 模式（V97-V107 7 个 migration 演进经验）
- **抽出来**：把 OptReportEntity rename FlywheelRunEntity + OptReportBatchEntity rename FlywheelRunStepEntity + 抽 OrchestratorAgentExecutor Java framework + 新 DispatchOrchestrationStep Tool
- **扩展到**：V1 让 memory-curator 复用 framework 验证可行性；V2+ 让 attribution-dispatcher / report-generator 也复用
- **不引入**：YAML DSL / 新外部依赖 / 新编排语言 / GUI 编辑
- **借鉴**：Claude Code Workflow 设计哲学（schema-driven / parallel fanout / Run 实体 / Live status push）— 详 [research-docs/research/claude code 源码/08 Workflow 工具与编排指南.md](../../../../../research-docs/research/claude%20code%20%E6%BA%90%E7%A0%81/08%20Workflow%20%E5%B7%A5%E5%85%B7%E4%B8%8E%E7%BC%96%E6%8E%92%E6%8C%87%E5%8D%97.md)

---

## 2. 模块边界（草拟）

```
skillforge-server/src/main/java/com/skillforge/server/
├── flywheel/
│   ├── run/                            # NEW (Run 实体 + Repository + Service)
│   │   ├── FlywheelRunEntity.java      # 改名自 OptReportEntity，补 3 列
│   │   ├── FlywheelRunStepEntity.java  # 改名自 OptReportBatchEntity
│   │   ├── FlywheelRunRepository.java
│   │   ├── FlywheelRunStepRepository.java
│   │   ├── FlywheelRunService.java     # CRUD + state machine
│   │   ├── FlywheelRunController.java  # GET /api/flywheel/runs[/{id}]
│   │   └── FlywheelRunDto.java
│   ├── orchestrator/                   # NEW (framework core)
│   │   ├── OrchestratorAgentExecutor.java   # 核心 Java framework
│   │   ├── OrchestratorWorkerSpec.java       # record (agentName, task, input, timeoutSeconds)
│   │   ├── StepResult.java                   # record (stepRunId, status, output, errorReason)
│   │   ├── StepStateChangedEvent.java        # Spring event for WS push
│   │   └── WorkerCompletionListener.java     # 监听 SubAgent 完成回调
│   └── (legacy) optreport/             # 保留，但 Service 内部委托给 flywheel/run/
│       ├── OptReportService.java       # 内部调 FlywheelRunService
│       ├── OptReportController.java    # 不变（DTO 稳定）
│       └── OptReportToEventBridge.java # convertIssueToEvent 不变（指向 FlywheelRunStepEntity）
└── (existing) ...

skillforge-tools/src/main/java/com/skillforge/tools/flywheel/
└── DispatchOrchestrationStep.java      # NEW Tool

skillforge-dashboard/src/
├── pages/
│   └── FlywheelRuns.tsx                # NEW page /flywheel-runs
├── api/
│   └── flywheelRun.ts                  # NEW typed wrapper
└── hooks/
    └── useFlywheelRuns.ts              # NEW hook (polling + WS event)
```

### 边界守则（参考旧 FLYWHEEL-ORCHESTRATION-DSL W1 教训）

- `flywheel/orchestrator/` 跑 step 时**不直接** Repository 写 step_run 行——通过 `FlywheelRunService.transitionStep()` 写
- `flywheel/run/Service` 单一职责：所有 FlywheelRunEntity / Step state CRUD
- `flywheel/orchestrator/Executor` 只负责"调度 + 等齐 + 错误处理"，不写 DB（依赖 FlywheelRunService）

---

## 3. 关键设计点

### 3.1 Run 实体 schema 演进

```sql
-- V??_rename_opt_report_to_flywheel_run.sql

-- Phase 1: rename table
ALTER TABLE t_opt_report RENAME TO t_flywheel_run;
ALTER TABLE t_opt_report_batch RENAME TO t_flywheel_run_step;

-- Phase 2: 加 3 列到 run 表
ALTER TABLE t_flywheel_run
  ADD COLUMN trigger_source VARCHAR(32) NOT NULL DEFAULT 'user_manual'
    CHECK (trigger_source IN ('cron','user_manual','api','event'));

ALTER TABLE t_flywheel_run
  ADD COLUMN input_json JSONB NOT NULL DEFAULT '{}';

ALTER TABLE t_flywheel_run
  ADD COLUMN loop_kind VARCHAR(32) NOT NULL DEFAULT 'opt_report'
    CHECK (loop_kind IN ('opt_report','memory_curation','attribution','metrics_collection','custom'));

-- Phase 3: 加 step 表新字段（语义泛化）
ALTER TABLE t_flywheel_run_step
  ADD COLUMN step_kind VARCHAR(32) NOT NULL DEFAULT 'subagent_dispatch';

-- 字段语义重命名（保留旧名作为 deprecated alias, V2+ drop）
ALTER TABLE t_flywheel_run_step RENAME COLUMN session_ids_json TO step_input_json;
ALTER TABLE t_flywheel_run_step RENAME COLUMN annotations_written_count TO step_output_count;
-- TODO Q5 ratify: step_output_summary JSONB 是否新加列存通用输出

-- Phase 4: Backfill 已有 OPT-REPORT 数据的 input_json
UPDATE t_flywheel_run
SET input_json = jsonb_build_object(
  'agentId', agent_id,
  'windowDays', EXTRACT(DAY FROM (window_end - window_start))::int,
  'windowStart', window_start::text,
  'windowEnd', window_end::text
)
WHERE loop_kind = 'opt_report';

-- Phase 5: 索引
CREATE INDEX idx_flywheel_run_loop_kind ON t_flywheel_run (loop_kind, created_at DESC);
CREATE INDEX idx_flywheel_run_status ON t_flywheel_run (status, created_at DESC)
  WHERE status IN ('pending','running');
CREATE INDEX idx_flywheel_run_step_run_id ON t_flywheel_run_step (run_id, created_at);

-- Phase 6: 兼容视图（兜底外部依赖）
CREATE VIEW t_opt_report AS
  SELECT id, agent_id, window_start, window_end, status, content_md, summary_json,
         generator_session_id, created_at, updated_at
  FROM t_flywheel_run
  WHERE loop_kind = 'opt_report';

CREATE VIEW t_opt_report_batch AS
  SELECT id, run_id AS report_id, sub_agent_session_id,
         step_input_json AS session_ids_json,
         status, step_output_count AS annotations_written_count, error_reason,
         created_at, updated_at
  FROM t_flywheel_run_step;
```

**migration 跑时**：
- `ALTER TABLE RENAME` 在 PG 是 metadata 操作（毫秒级，不锁数据）
- 加列 with DEFAULT 也是 metadata（PG 11+）
- 视图创建 0 数据复制
- 整体 migration 单事务可在 ms 级跑完

### 3.2 OrchestratorAgentExecutor 设计

```java
package com.skillforge.server.flywheel.orchestrator;

@Service
public class OrchestratorAgentExecutor {

    private final FlywheelRunService runService;
    private final SubAgentTool subAgentTool;
    private final ApplicationEventPublisher eventPublisher;

    public OrchestratorAgentExecutor(
        FlywheelRunService runService,
        SubAgentTool subAgentTool,
        ApplicationEventPublisher eventPublisher
    ) {
        this.runService = runService;
        this.subAgentTool = subAgentTool;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 入口方法：创建一个 FlywheelRun。
     * Service 调用方（MemoryConsolidationScheduler / OptReportService.startReport / 等）用此 method 启动一个 loop。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FlywheelRunEntity startRun(
        String loopKind,
        String triggerSource,
        Map<String, Object> inputJson,
        Long agentId,
        String generatorAgentType
    ) {
        // INSERT FlywheelRunEntity (status='pending', loop_kind, trigger_source, input_json, agent_id)
        // 不 spawn session，由 caller 负责
        // 返 entity 让 caller 拿 runId 传给 orchestrator session
    }

    /**
     * 编排核心：派发 N 个 worker SubAgent 并等齐 barrier。
     * 由 DispatchOrchestrationStep 工具调用，不直接对外。
     *
     * @return 等齐后的 List<StepResult>，按 workers 顺序对应
     */
    public CompletableFuture<List<StepResult>> dispatchSubAgents(
        UUID parentRunId,
        List<OrchestratorWorkerSpec> workers
    ) {
        // 1. 写 N 行 FlywheelRunStepEntity (status='pending')
        // 2. FOR EACH worker: subAgentTool.dispatch(...)
        // 3. 监听 worker session 完成（通过 WorkerCompletionListener 监听 SubAgent done event）
        // 4. UPDATE step_run.status (completed / failed)
        // 5. 触发 StepStateChangedEvent → WS push
        // 6. 等齐 → 返 List<StepResult>
    }

    /**
     * 终止一个 run（status → completed / failed）+ 触发 WS push
     */
    public void finalizeRun(UUID runId, String finalStatus, Map<String, Object> outputJson, String errorMessage) {
        // UPDATE FlywheelRunEntity
        // 触发 WS event
    }
}

public record OrchestratorWorkerSpec(
    String agentName,             // worker SystemAgent agent_type (e.g., "session-batch-annotator")
    String task,                  // task description for SubAgent
    Map<String, Object> input,    // input args
    int timeoutSeconds            // worker timeout
) {}

public record StepResult(
    UUID stepRunId,
    String status,                // 'completed' / 'failed' / 'timeout'
    JsonNode output,
    String errorReason            // null if success
) {}
```

**关键设计点**：
- `startRun` 用 `@Transactional(REQUIRES_NEW)`，独立 tx 保证 entity 在 spawn session 之前 commit（参考 OptReportService 已踩过的"Session not found" tx 教训，OptReportService.java:88-95）
- `dispatchSubAgents` **不持事务**，每个 step_run 状态变更走独立 short tx（避免长事务）
- worker 完成回调通过 `WorkerCompletionListener` 监听 SubAgent 现有完成事件（不发明新机制）
- WS push 通过 `ApplicationEventPublisher` 异步发布 `StepStateChangedEvent` → 由 `broadcaster` 监听后推（解耦）

### 3.3 DispatchOrchestrationStep Tool

```java
@Component
public class DispatchOrchestrationStep implements Tool {

    private final OrchestratorAgentExecutor executor;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "DispatchOrchestrationStep"; }

    @Override
    public String getDescription() {
        return "Dispatch parallel SubAgents as flywheel run steps. " +
               "Automatically writes step_run rows + waits for barrier + returns results. " +
               "Use this in orchestrator system agents' STEP X SubAgent fanout sections " +
               "(replaces manual SubAgent(action='dispatch', ...) calls).";
    }

    @Override
    public ToolSchema getToolSchema() {
        // input: { parentRunId: UUID, workers: [{agentName, task, input: {...}, timeoutSeconds}] }
        // output: { stepResults: [{stepRunId, status, output, errorReason}], allSucceeded: bool }
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        UUID parentRunId = UUID.fromString((String) input.get("parentRunId"));
        List<Map<String, Object>> workersRaw = (List<Map<String, Object>>) input.get("workers");
        List<OrchestratorWorkerSpec> workers = workersRaw.stream()
            .map(w -> new OrchestratorWorkerSpec(...))
            .toList();

        CompletableFuture<List<StepResult>> future = executor.dispatchSubAgents(parentRunId, workers);
        List<StepResult> results = future.join();  // 同步等齐 barrier

        return SkillResult.success(objectMapper.valueToTree(Map.of(
            "stepResults", results,
            "allSucceeded", results.stream().allMatch(r -> "completed".equals(r.status()))
        )));
    }
}
```

**vs SubAgentTool 区别**：

| 维度 | SubAgentTool | DispatchOrchestrationStep |
|---|---|---|
| 调用模式 | 单 worker dispatch + fire-and-forget | N worker 并行 + 等齐 barrier |
| 自动写 step_run | ✗ | ✓ |
| 关联 parent run | ✗ | ✓ (parentRunId) |
| 适用场景 | chat 用户主动 dispatch / 非 flywheel | orchestrator agent prompt 内 fanout |
| 完成时 WS push | ✗ | ✓ flywheel_run_status_changed |

### 3.4 memory-curator V95 prompt 改造

```sql
-- V??_update_memory_curator_use_framework.sql
UPDATE t_agent
SET system_prompt = $prompt$
你是 memory-curator. 每天凌晨被触发整理用户记忆.

STEP 1: 调 ListActiveUsers 列出最近 7 天活跃用户.

STEP 2: 对每个 userId, 调 DispatchOrchestrationStep 派发 sub-worker:
  DispatchOrchestrationStep(
    parentRunId="${input.runId}",
    workers=[
      {
        agentName: "memory-curator-sub-worker",
        task: "整理 userId=" + uid + " 的记忆: ListMemoryCandidates → ClusterMemories → CreateMemoryProposal",
        input: {userId: uid},
        timeoutSeconds: 600
      }
      for uid in users
    ]
  )

STEP 3: 等所有 sub-worker 完成 (DispatchOrchestrationStep 内部已等齐).

STEP 4: 汇总 step_results 中的 status / output, 写入 final summary.
$prompt$
WHERE name = 'memory-curator';
```

`memory-curator-sub-worker` 是新 worker agent（如果 memory-curator 原来 dispatch 给自己 sub-session，要拆出 worker agent；如果 dispatch 给现有 agent，复用现有）。Q3 待 ratify。

### 3.5 兼容视图 + OPT-REPORT-V1 兼容

`OptReportEntity` Java 类 2 选 1：

- (a) **保留为 deprecated alias**：`@Entity @Table(name = "t_opt_report")` + `@Immutable @Deprecated` → 还能 SELECT 查（走视图），但 INSERT/UPDATE 走 FlywheelRunEntity
- (b) **直接 rename**：`OptReportEntity → FlywheelRunEntity`，Java 引用全替；OptReportService 内部用 FlywheelRunEntity

**推荐**：(b) 直接 rename，OptReportService 改成内部委托：

```java
@Service
public class OptReportService {  // 类名保留 (OPT-REPORT-V1 backward compat)
    private final FlywheelRunService runService;

    public OptReportEntity startReport(Long agentId, int windowDays) {
        FlywheelRunEntity run = runService.startRun(
            "opt_report",
            "user_manual",
            Map.of("agentId", agentId, "windowDays", windowDays),
            agentId,
            SystemAgentNames.REPORT_GENERATOR
        );
        // ... spawn report-generator session ...
        return mapToOptReportEntity(run);  // DTO 兼容
    }
}
```

`OptReportEntity` 类降级为 view-only DTO（仅用于现有 OptReportController response），不再持久化（持久化走 FlywheelRunEntity）。

---

## 4. 待 ratify 决策（PRD ratify 后填实）

详见 [`prd.md` Q1-Q5](prd.md#7-q-澄清)：

- Q1 rename 路径 → 推荐 (a) `ALTER TABLE RENAME`
- Q2 OrchestratorAgentExecutor 实现模式 → 推荐 (a) 单 class + method API
- Q3 DispatchOrchestrationStep 替代 SubAgentTool 范围 → 推荐 (a) 仅 orchestrator 用
- Q4 All Flywheel Runs page vs Reports tab → 推荐 (a) V1 独立 + (c) V2 加联动
- Q5 trigger_source + input_json + loop_kind schema → 推荐 (a)+(b)+(c) 自由 input_json + 枚举字段

---

## 5. 风险

详见 [`prd.md` 第 10 节风险表](prd.md#10-风险与缓解) R1-R8。简列：

1. **R1**: rename migration 破坏 OPT-REPORT-V1 → 兼容视图 + regression test 全通过
2. **R2**: 跟 DREAMING V1 改 memory-curator prompt 冲突 → 前置约束 DREAMING 先 ship
3. **R3**: SubAgentTool vs DispatchOrchestrationStep 双工具混乱 → tech-design 工具职责对比表
4. **R4**: framework V1 只 1 orchestrator 验证可能 over-abstract → 接受 risk，V2+ 迭代改 framework contract
5. **R5**: dashboard 数据源切换破坏 OPT-REPORT page → 共存策略（D5 Q4 选项 a）
6. **R6**: AOP 行为契约（@Transactional / @Async）跟 framework → tech-design 加 AOP 行为契约小节
7. **R7**: 兼容视图查询性能 → 加 loop_kind 索引
8. **R8**: migration 期间 OPT-REPORT 写入失败 → `ALTER TABLE RENAME` 是 ms 级 metadata op

---

## 6. 测试计划

### IT 矩阵（详见 [`prd.md` AC-8](prd.md)）

8 个 IT case：

1. rename migration + existing OPT-REPORT data 完整可查（含兼容视图）
2. OPT-REPORT-V1 startReport 整链路（不破坏）
3. OrchestratorAgentExecutor minimal use case (2 worker 并行 + 等齐)
4. Executor 错误处理（worker 失败 → step failed + run failed）
5. memory-curator 用 DispatchOrchestrationStep 真活跑
6. dashboard GET /api/flywheel/runs endpoint
7. WS event flywheel_run_status_changed 推送
8. convertIssueToEvent 经 rename 后仍跑通

### 单测

- FlywheelRunService CRUD + state machine
- OrchestratorAgentExecutor startRun / dispatchSubAgents / finalizeRun
- DispatchOrchestrationStep Tool execute
- 兼容视图 SELECT 验证
- migration rollback（PG metadata op 可 ALTER 改回）

### Dogfood

memory-curator 切 framework 跑 1 周：
- `t_memory_proposal` 行数 diff < 5%
- `t_flywheel_run` 含 7 行 memory_curation loop_kind run
- `t_flywheel_run_step` 含 N 行 (per user) step

### 真活验证

- dashboard `/flywheel-runs` 看 OPT-REPORT + Memory runs，latency < 2s
- OPT-REPORT-V1 Reports tab page regression 0
- convertIssueToEvent 跑通

---

## 7. V2+ 路线（不在 V1 范围）

- **V2**: attribution-dispatcher / report-generator 接入 OrchestratorAgentExecutor framework（独立需求包 `OPT-LOOP-FRAMEWORK-V2-ATTRIBUTION-REPORT`）
  - report-generator V97 prompt 改造（STEP 3 改 DispatchOrchestrationStep）
  - attribution-dispatcher V95 prompt 改造（STEP 3 改 DispatchOrchestrationStep）
  - OptReportService.startReport 改成 runService.startRun → loop_kind='opt_report'
  - AttributionDispatcherService 调度入口写 FlywheelRunEntity → loop_kind='attribution'
- **V3**: dashboard panel 完善（OPT-REPORT page 和 All Flywheel Runs page 数据源统一 / FlywheelFlowchart 数据源切到 FlywheelRunEntity）
- **V4**: Variant fork — 新 endpoint `POST /api/flywheel/runs/{id}/fork` 复制 input_json + 改某字段重跑，对比执行结果（用户痛点 3）
- **V5**: behavior_rule / Skill AB 也接入 framework（统一 OptimizationEvent 链路）
- **V6**: cron 体系统一（`t_scheduled_task` + Spring `@Scheduled` 都跑 framework）+ retention policy（completed >90 天 archive）

---

## 8. 依赖 / 顺序约束

- **前置**：[`active/2026-05-26-DREAMING-MEMORY-EXTENSION/`](../2026-05-26-DREAMING-MEMORY-EXTENSION/index.md) V1 必须先 ship + 稳定 1 周（避免 memory-curator prompt 双重改造冲突）
- **后置**：V2+ 走独立需求包，每个独立 ship + 灰度
- **OPT-REPORT-V1 关系**：本需求基于 OPT-REPORT-V1 的成熟模式扩展，**不破坏 OPT-REPORT 现有功能**（兼容视图 + regression test 保护）
