# Tech Design — AUTOEVOLVING V1

> 状态：**Sprint 1 已实现 ship（commit `54941c3`，2026-05-29）**。以下设计文档是 spike 前草案，实际实现见 `com.skillforge.workflow` 包代码 + delivery-index。
>
> ⚠️ **Plan/spike 阶段修正的 3 处草案 bug（实现已用正确版）**：
> 1. §7 `chatService.runSubAgentForWorkflow(...)` **不存在** → 实际用 `AgentLoopEngine.run(def, prompt, null, sessionId, userId, lc)` 同步路径（复刻 ScenarioRunnerTool），见 `DefaultWorkflowAgentInvoker`
> 2. §6 `cx.initStandardObjects()` **错** → 实际用 `initSafeStandardObjects()`（不含 Java 桥接），见 `L1SandboxFactory`
> 3. §9 JournalCache 按 `created_at` 排序 **错位**（parallel 完成顺序非确定）→ stepIndex 在 invoke 时（workflow 单线程）确定性分配；Sprint 2 journal-replay 加 DB `step_index` 列
>
> **其它 Sprint 1 实现要点**（草案未覆盖 / 与草案不同）：humanApprove 用 **journal-replay**（非草案的 park-thread，Q3×Q4 ratify）但 Sprint 2 才实现；offload 并发模型详见 `/tmp/plan-v1-sprint1-r1.md` §2；Rhino continuation 不可用于 parallel（GitHub #1444 arrow function 限制）。
>
> 原 V0 draft 内容保留如下作历史参考。

## 1. 整体架构

```
                  ┌─────────────────────────────┐
                  │ Dashboard /autoevolving (FE) │
                  └────────────┬────────────────┘
                               │ REST + WS
                               ▼
┌──────────────────────────────────────────────────────────┐
│  WorkflowController (REST)                                │
│   GET    /api/workflows                                   │
│   POST   /api/workflows/{name}/run                        │
│   GET    /api/workflows/runs                              │
│   GET    /api/workflows/runs/{runId}                      │
│   POST   /api/workflows/runs/{runId}/approve              │
└────────────┬─────────────────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────────────────┐
│  WorkflowRunnerService                                    │
│   - acquire ConsolidationLock (per workflow name)         │
│   - startRun → write t_flywheel_run (loop_kind='workflow')│
│   - new Rhino Context + L1 sandbox                        │
│   - register 6 host bindings (agent/parallel/.../humanApprove) │
│   - evaluateString(workflowJs)                            │
│   - on humanApprove() → pause + write paused state        │
│   - on complete → write summary_json + emit WS            │
└────────────┬─────────────────────────────────────────────┘
             │
             ├──► WorkflowDefinitionRegistry
             │     - 监听 resources/workflows/*.workflow.js 文件改动
             │     - 解析 meta literal + 注册到内存
             │     - hot-reload on file change
             │
             ├──► HostBindings (6 原语 Java 实现)
             │     - agent(prompt, opts) → ChatService.chatAsync (复用)
             │     - parallel(thunks) → CompletableFuture barrier
             │     - pipeline(items, ...stages) → CompletableFuture pipeline (no barrier)
             │     - phase(title) → push WS event
             │     - log(msg) → push WS event
             │     - humanApprove(payload) → 暂停 + 写 DB + 推 WS + 等 user click
             │
             ├──► SchemaValidator (jakarta.validation / Jackson-based)
             │     - 每个 agent() 调用后用 schema 验证 result
             │     - 失败自动 retry (max 3)
             │
             └──► JournalCache
                   - 每个完成的 agent() write to t_flywheel_run_step.step_output_json (V125 复用)
                   - resume 时按 stepRunId 顺序查 cache 跳过已完成
```

## 2. Maven 依赖

```xml
<!-- skillforge-server/pom.xml -->
<dependency>
    <groupId>org.mozilla</groupId>
    <artifactId>rhino</artifactId>
    <version>1.7.14</version>
</dependency>
<!-- JSON Schema validator (复用 networknt) -->
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.5.0</version>
</dependency>
```

## 3. Schema 扩展（V1 不动 t_flywheel_run / t_flywheel_run_step 主 schema）

V124/V125 schema 复用：
- `t_flywheel_run`: loop_kind='workflow' / trigger_source='user_manual' / input_json=workflow args
- `t_flywheel_run_step`: step_kind='workflow_node' / step_input_json={phase, agentSlug, args} / step_output_json=result

V1 可选加 1 列（不强制）:
```sql
-- V126__add_workflow_def_id.sql (可选)
ALTER TABLE t_flywheel_run ADD COLUMN workflow_def_id VARCHAR(64);
ALTER TABLE t_flywheel_run ADD COLUMN workflow_def_version VARCHAR(32);
-- 关联到 workflow 文件 + version (V3+ DB 存储时强制)
```

V1 暂不加（用 input_json 携带 workflow name + 版本），V2 加正式列。

## 4. Java 包结构

```
skillforge-server/src/main/java/com/skillforge/workflow/
├── WorkflowController.java                  # REST endpoints
├── WorkflowRunnerService.java               # 核心 runner，单工作流执行
├── WorkflowDefinitionRegistry.java          # 文件监听 + 解析 + 注册
├── WorkflowDefinition.java                  # meta + js source POJO
├── WorkflowRunRepository.java               # 复用 FlywheelRunRepository
├── ConsolidationLock.java                   # PG advisory lock helper
├── sandbox/
│   ├── L1SandboxFactory.java                # ContextFactory + ClassShutter
│   ├── InstructionCounter.java              # 每 10k JS 指令回调
│   └── BudgetTracker.java                   # agent() call count + timeout
├── bindings/
│   ├── HostBindings.java                    # 注册 6 原语到 Rhino scope
│   ├── HostAgent.java                       # agent() impl → ChatService.chatAsync
│   ├── HostParallel.java                    # parallel() impl
│   ├── HostPipeline.java                    # pipeline() impl
│   ├── HostPhase.java                       # phase() impl
│   ├── HostLog.java                         # log() impl
│   └── HostHumanApprove.java                # humanApprove() impl
├── schema/
│   ├── SchemaValidator.java                 # JSON Schema 校验
│   └── SchemaViolationException.java
├── journal/
│   ├── JournalCache.java                    # resume 时按 stepRunId 跳过
│   └── ResumeContext.java
└── ws/
    └── WorkflowWsBroadcaster.java           # 推 WS 节点状态
```

## 5. Rhino L1 Sandbox 关键代码

```java
@Component
public class L1SandboxFactory {

    private static final long INSTRUCTION_CHECK_INTERVAL = 10_000;
    private static final long MAX_INSTRUCTIONS = 1_000_000;
    private static final long MAX_WORKFLOW_DURATION_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long MAX_AGENT_CALLS = 1000;

    private final ContextFactory factory;

    public L1SandboxFactory() {
        this.factory = new ContextFactory() {
            @Override
            protected Context makeContext() {
                Context cx = super.makeContext();
                // 禁所有 Java class 访问
                cx.setClassShutter(className -> false);
                // 解释模式 (不 JIT, 更好控制 instruction counter)
                cx.setOptimizationLevel(-1);
                // Stack depth 限制
                cx.setMaximumInterpreterStackDepth(50);
                // Instruction counter
                cx.setInstructionObserverThreshold((int) INSTRUCTION_CHECK_INTERVAL);
                return cx;
            }

            @Override
            protected void observeInstructionCount(Context cx, int instructionCount) {
                BudgetTracker tracker = (BudgetTracker) cx.getThreadLocal("budget");
                if (tracker == null) return;
                tracker.addInstructions(INSTRUCTION_CHECK_INTERVAL);
                if (tracker.totalInstructions() > MAX_INSTRUCTIONS) {
                    throw new WorkflowBudgetExceededException("instruction count > " + MAX_INSTRUCTIONS);
                }
                if (System.currentTimeMillis() - tracker.startedAt() > MAX_WORKFLOW_DURATION_MS) {
                    throw new WorkflowTimeoutException("workflow > " + MAX_WORKFLOW_DURATION_MS + "ms");
                }
            }
        };
    }

    public Context enterContext(BudgetTracker tracker) {
        Context cx = factory.enterContext();
        cx.putThreadLocal("budget", tracker);
        return cx;
    }
}
```

## 6. WorkflowRunner 主流程

```java
@Service
public class WorkflowRunnerService {

    public String startRun(String workflowName, Map<String, Object> args, Long userId) {
        // 1. 取 lock
        try (var lock = consolidationLock.acquire("workflow:" + workflowName)) {
            // 2. 取 workflow definition (from registry, hot-loaded from .js file)
            WorkflowDefinition def = registry.get(workflowName);
            if (def == null) throw new WorkflowNotFoundException(workflowName);

            // 3. 起 t_flywheel_run row
            String runId = flywheelRunService.startRun(
                "workflow", "user_manual", Map.of(
                    "workflow_name", workflowName,
                    "workflow_args", args
                ), agentIdForUser(userId), 1
            ).getId();

            // 4. 异步跑 (复用 chatLoopExecutor)
            workflowExecutor.execute(() -> runWorkflow(def, runId, args));

            return runId;
        } catch (LockHeldException e) {
            throw new WorkflowAlreadyRunningException(workflowName);
        }
    }

    private void runWorkflow(WorkflowDefinition def, String runId, Map<String, Object> args) {
        BudgetTracker tracker = new BudgetTracker(System.currentTimeMillis());
        Context cx = sandboxFactory.enterContext(tracker);
        try {
            ScriptableObject scope = cx.initStandardObjects();

            // 注册 6 host bindings
            hostBindings.register(scope, new WorkflowContext(runId, tracker, args));

            // 设 args 全局变量
            ScriptableObject.putProperty(scope, "args",
                cx.javaToJS(args, scope));

            // 跑 workflow JS
            cx.evaluateString(scope, def.jsSource(), workflowName, 1, null);

            // 完成 → mark run completed
            flywheelRunService.markCompleted(runId, /* summary_json from return value */);
        } catch (WorkflowPausedException pause) {
            // humanApprove 触发暂停，state 已写入 DB
            // workflow resume 由 user click 触发
        } catch (Exception e) {
            flywheelRunService.markError(runId, e.getMessage());
        } finally {
            Context.exit();
        }
    }
}
```

## 7. Host Bindings 示例（agent + parallel）

```java
public class HostAgent extends BaseFunction {
    private final ChatService chatService;
    private final SchemaValidator schemaValidator;
    private final BudgetTracker tracker;

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        String prompt = (String) args[0];
        Map<String, Object> opts = args.length > 1 ? (Map<String, Object>) Context.jsToJava(args[1], Map.class) : Map.of();

        tracker.incrementAgentCalls();  // 防超过 budget
        if (tracker.agentCalls() > MAX_AGENT_CALLS) {
            throw new WorkflowBudgetExceededException("agent calls > " + MAX_AGENT_CALLS);
        }

        String agentSlug = (String) opts.getOrDefault("agentSlug", "default");
        String model = (String) opts.getOrDefault("model", "sonnet");
        Object schema = opts.get("schema");

        // 用 ChatService.chatAsync 起子 session + 等结果
        // 子 session 跟 workflow run 关联 (sub_agent_session_id)
        SubAgentResult result = chatService.runSubAgentForWorkflow(
            agentSlug, prompt, model, currentRunId);

        // schema 强制 + 重试
        if (schema != null) {
            int retries = 0;
            while (retries < 3) {
                if (schemaValidator.validate(result.outputJson(), schema)) break;
                result = chatService.retrySubAgent(result.sessionId(),
                    "Your previous output failed schema validation. Retry strictly per schema: " + ctx.json(schema));
                retries++;
            }
            if (retries == 3) throw new SchemaViolationException(...);
        }

        return cx.javaToJS(result.outputJson(), scope);
    }
}

public class HostParallel extends BaseFunction {
    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        NativeArray thunks = (NativeArray) args[0];
        // 复用 chatLoopExecutor 跑并发
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (Object thunkObj : thunks) {
            Function thunk = (Function) thunkObj;
            futures.add(CompletableFuture.supplyAsync(() ->
                thunk.call(cx, scope, thisObj, new Object[]{}), workflowExecutor));
        }
        // Barrier wait
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 返 array of results (null for failures)
        NativeArray results = (NativeArray) cx.newArray(scope, futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.put(i, results, futures.get(i).getNow(null));
            } catch (Exception e) {
                results.put(i, results, null);
            }
        }
        return results;
    }
}
```

## 8. humanApprove 实现（journal-replay 模式，Q3+Q4 ratify）

```java
public class HostHumanApprove extends BaseFunction {
    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        Object payload = Context.jsToJava(args[0], Object.class);

        // resume 路径: 如果这个 humanApprove step 已有 decision (人之前点过), 直接返回继续
        Optional<Decision> existing = journalCache.getApproveDecision(currentRunId, tracker.nextStepIndex());
        if (existing.isPresent()) {
            return cx.javaToJS(existing.get(), scope);   // 继续往下 (journal-replay 命中)
        }

        // 首次到达: 写 paused step + 退出线程 (不 park)
        String stepRunId = flywheelRunService.appendStep(currentRunId,
            ctx.json(Map.of("kind", "human_approve", "payload", payload)));
        flywheelRunService.transitionStepStatus(stepRunId, "paused_for_human_approve", null, null);
        flywheelRunService.pauseRun(currentRunId, "human_approve_required");
        wsBroadcaster.broadcastHumanApproveRequired(currentRunId, stepRunId, payload);

        // 抛异常退出 Rhino 线程 (状态全在 DB, 不占线程, 扛 server 重启)
        throw new WorkflowPausedException(currentRunId, stepRunId);
    }
}
```

**Resume 流程**（人点 approve，即使隔天 / 中间 server 重启过）：

```
POST /api/workflows/runs/{runId}/approve {decision: 'approved', reason}
  ↓ BE 写 decision 到 t_flywheel_run_step (那个 paused step)
  ↓ WorkflowRunnerService.resume(runId)
  ↓ 重新 new Rhino context + 重跑 workflow JS from top
  ↓ 每个 agent() 调用 → journalCache 命中已完成的 → 跳过实际 LLM 调用直接返 cached result
  ↓ 跑到 humanApprove() → journalCache.getApproveDecision 命中 → 返 decision 继续往下
  ↓ 继续跑剩余 phase → 完成 → markCompleted
```

**关键**：humanApprove resume 靠 **journal-replay**（重跑 + cache 跳过），不是 park 线程。好处：
- 不占线程（人等多久都行）
- 扛 server 重启 / 部署（状态全在 DB）

## 9. Journal Cache（仅用于 humanApprove resume）

V1 journal cache **只服务 humanApprove resume**，不做 crash-recovery（Q3「异常挂了重跑」）：

```java
@Service
public class JournalCache {

    // humanApprove resume 时, agent() 调用前查: 这个 stepIndex 是否已完成?
    public Optional<Object> getCachedAgentResult(String runId, int stepIndex) {
        return stepsByRunIdOrdered(runId)
            .filter(s -> "workflow_node".equals(s.getStepKind()))
            .skip(stepIndex).findFirst()
            .filter(s -> "completed".equals(s.getStatus()))
            .map(s -> Json.parse(s.getStepOutputJson()));
    }

    // humanApprove 重放时拿之前人点的 decision
    public Optional<Decision> getApproveDecision(String runId, int stepIndex) {
        return stepsByRunIdOrdered(runId)
            .filter(s -> "human_approve".equals(s.getStepKind()))
            .skip(/* approve step index */).findFirst()
            .filter(s -> "completed".equals(s.getStatus()))
            .map(s -> Json.parse(s.getStepOutputJson(), Decision.class));
    }
}

// HostAgent 调用前 (仅 resume run 走 cache; 首次 run isResuming=false 不查)
public class HostAgent {
    public Object call(...) {
        int stepIndex = tracker.nextStepIndex();
        if (ctx.isResuming()) {
            Optional<Object> cached = journalCache.getCachedAgentResult(currentRunId, stepIndex);
            if (cached.isPresent()) return cached.get();   // skip 实际 LLM 调用
        }
        // ... 正常调 (写 step result, observability + 下次 resume 用)
    }
}
```

**crash / OOM / server 重启（非 humanApprove）→ 不自动 resume**：旧 run 标 failed，user 手动重新触发 = 新 runId 从头跑（`isResuming=false`，不读 journal cache）。

V2 才加：crash-recovery 自动 resume（startup sweeper 扫 running 状态 run 自动续）。

## 10. Dashboard `/autoevolving` FE 设计

### 文件结构

```
skillforge-dashboard/src/
├── pages/
│   └── Autoevolving.tsx                     # 主 page
├── api/
│   └── autoevolving.ts                      # REST wrapper
├── hooks/
│   ├── useAutoevolvingKpis.ts               # KPI 数据
│   ├── useWorkflowRuns.ts                   # workflow run list + polling
│   ├── useWorkflowRunsWS.ts                 # WS event handler
│   └── useAnomalyFeed.ts                    # 异常诊断聚合
└── components/
    └── autoevolving/
        ├── KpiCards.tsx                     # 顶部 KPI 卡
        ├── SignalSourcePanels.tsx           # 3 信号源面板容器
        ├── ProductionSignalPanel.tsx        # ① production
        ├── AutoResearchSignalPanel.tsx      # ② placeholder (V2 接入)
        ├── MemoryProposalSignalPanel.tsx    # ③ memory proposal
        ├── WorkflowDagPanel.tsx             # 复用 FlywheelObservability 思路
        ├── AnomalyDiagnosisPanel.tsx        # 异常诊断
        └── WorkflowTriggerButton.tsx        # 手动触发 modal
```

### 路由

```tsx
// App.tsx
<Route path="autoevolving" element={
  <ErrorBoundary context="Autoevolving"><Autoevolving /></ErrorBoundary>
} />
```

### Layout nav

```tsx
// Layout.tsx primaryNav
{ key: 'autoevolving', path: '/autoevolving', label: 'AutoEvolving' },
// 插在 'insights' 之后
```

### WorkflowDagPanel 复用现有 FlywheelObservability

```tsx
import FlywheelFlowchart from '../flywheel/FlywheelFlowchart';

// 把 FlywheelFlowchart 的数据源从 14-stage 节点换成 workflow phase + agent 节点
// 复用 reactflow + dagre + FlywheelNode + StepCard
```

## 11. WebSocket Events

| Event | Payload | 触发 |
|---|---|---|
| `workflow_run_status_changed` | `{runId, workflowName, status, oldStatus}` | run 状态变（pending→running→...→completed/failed/cancelled/paused）|
| `workflow_step_state_changed` | `{stepRunId, runId, phase, agentSlug, status, errorReason}` | 每个 host binding 调用前后 |
| `workflow_human_approve_required` | `{runId, stepRunId, payload, uiTemplate?}` | humanApprove() 触发 |
| `workflow_log` | `{runId, message, timestamp}` | log() 触发 |

复用现有 `UserWebSocketHandler.broadcastAll(...)` 模式。

## 12. Sprint 任务拆分

### Sprint 1 (1.5 周) — Rhino + L1 sandbox + 6 原语

| # | 任务 | 估时 |
|---|---|---|
| 1 | 加 Rhino + json-schema-validator Maven 依赖 + spike 跑通 hello-world | 0.5d |
| 2 | L1SandboxFactory（ClassShutter + instruction counter + budget tracker） | 1d |
| 3 | HostBindings + WorkflowContext + 注册到 scope | 0.5d |
| 4 | HostAgent（调 ChatService.chatAsync） | 1d |
| 5 | HostParallel + HostPipeline | 1d |
| 6 | HostPhase + HostLog + WS broadcaster | 0.5d |
| 7 | WorkflowDefinitionRegistry（文件监听 + 解析 meta literal + 注册） | 1d |
| 8 | WorkflowRunnerService 主流程 + ConsolidationLock | 0.5d |
| 9 | hello-world workflow + 集成测试 | 0.5d |
| 10 | 安全审计（恶意 workflow 单测）| 0.5d |

### Sprint 2 (1 周) — humanApprove + schema + journal

| # | 任务 | 估时 |
|---|---|---|
| 1 | SchemaValidator + 失败重试 + SchemaViolationException | 1d |
| 2 | HostHumanApprove + WorkflowPausedException + WS push | 1d |
| 3 | JournalCache + resume 路径 | 1d |
| 4 | REST endpoints (GET /api/workflows, POST run, POST approve) | 1d |
| 5 | ConsolidationLock PG advisory lock 实现 + 单测 | 0.5d |
| 6 | 单测 + 集成测试 | 0.5d |

### Sprint 3 (1 周) — OPT-REPORT demo + DAG viz

| # | 任务 | 估时 |
|---|---|---|
| 1 | 写 opt-report.workflow.js（移植 report-generator prompt 逻辑） | 1.5d |
| 2 | OPT-REPORT feature flag 控制 DSL workflow vs agent-driven fallback | 0.5d |
| 3 | WorkflowDagPanel.tsx 复用 FlywheelFlowchart 数据源换 | 2d |
| 4 | 真活验证：跑 1 个 OPT-REPORT DSL workflow + 对比 agent-driven 输出 | 1d |

### Sprint 4 (1 周) — Dashboard + dogfood

| # | 任务 | 估时 |
|---|---|---|
| 1 | Autoevolving.tsx 主 page + 路由 + nav | 0.5d |
| 2 | KpiCards + 数据聚合 endpoint | 0.5d |
| 3 | 3 信号源面板 (ProductionSignalPanel + AutoResearchSignalPanel placeholder + MemoryProposalSignalPanel) | 1.5d |
| 4 | AnomalyDiagnosisPanel + 后端聚合 endpoint | 1d |
| 5 | WorkflowTriggerButton + modal | 0.5d |
| 6 | dogfood 1 周 + 修迭代 | 1d |

## 13. 复用现有零件清单

| 复用 | 来自 | 用途 |
|---|---|---|
| `FlywheelRunService` run/step CRUD | Sprint 1 V124 已 ship | workflow run 持久化 |
| `t_flywheel_run` + `t_flywheel_run_step` schema | V124/V125 | workflow run + step 状态 |
| `ChatService.chatAsync` + `SubAgentRegistry` | 现有 sub-agent 调度 | `agent()` 原语调用 worker |
| `SubAgentRunEntity` + `SubAgentStartupRecovery` | 现有 | sub-agent 启动 / 恢复 |
| `FlywheelObservability` + `FlywheelFlowchart` + reactflow + dagre | 现有 `/insights > Optimization Loop` | workflow DAG viz panel |
| `LifecycleHookDispatcher` 模式 | 现有 hook framework | humanApprove 借鉴 lifecycle hook |
| `UserWebSocketHandler.broadcastAll` | 现有 WS broadcaster | workflow 状态实时推 |
| `ConsolidationLock` 概念（autoDream TS 版） | 借鉴现有 autoDream pattern | Java 端 PG advisory lock 重新实现 |
| `t_memory_proposal` approve 模式 | DREAMING V1 | humanApprove 流程参考 |
| `LlmCallContext` + 跨线程传递 | 现有 | workflow 内 sub-agent 调用 trace 关联 |
| `ScheduledTaskExecutor` | 现有 | V2+ cron 自动触发参考 |

## 14. 待 Plan 时深化

- humanApprove resume 时 JS 控制流的状态恢复细节（current phase / 已执行 statements / closure state 等）
- Hot-reload 时正在跑的 run 用旧版 vs 新触发用新版的并发隔离
- ConsolidationLock 跨 server 实例（多 PG 连接池场景）
- workflow 文件加载顺序（file system order vs 依赖关系）
- `agent()` 返回 schema-validated 对象 → JS 怎么 access（NativeObject vs Map）
- ResumeContext 跟 args 一致性校验（resume 必须传同 args）
- Iron Law 人审 agent 写的 workflow 流程具体设计（review 队列 / approve UI）
