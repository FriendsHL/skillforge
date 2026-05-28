# OPT-LOOP-FRAMEWORK V2 — DSL Reset

> **创建**：2026-05-28 user review V1 Sprint 4 ship 后
> **状态**：brainstorm 阶段，未开 dev
> **触发**：user 指出 V1 形态跟 mental model 不一致："workflow 在哪？看不到" → 承认 V1 推 push back 走偏

---

## 1. V1 形态反思（怎么走偏的）

### user 真实 mental model（2026-05-27 brainstorm 首日就说过）

```
[orchestrator: 获取 sessions]
        ↓
[annotator: batch 标注]
        ↓
[aggregator: 聚合]
        ↓
[attributor: 归因]
        ↓
[human-in-the-loop: approve]
        ↓
─ ─ ─（断点：approve 之后 A/B + analyzer 后续 brainstorm）─ ─ ─
```

- 节点 = agent
- 边 = 控制流（含 fanout）
- workflow **形态由 DSL 文件定义**（参考 Claude Code Workflow 形式：`phase()` / `agent()` / `parallel()` / `pipeline()` / `humanApprove()` 原语）
- DSL 存储 **先 YAML 文件 / 后 DB**

### V1 实际 ship 形态（Sprint 1+2+4）

- workflow **不存在显式定义**，散落在 orchestrator agent 的 prompt 里
- LLM 读 prompt → 决定调几次 `DispatchOrchestrationStep` Tool → BE 创 worker session 跑
- 数据层有 `t_flywheel_run` / `t_flywheel_run_step` 但 step 之间**无 depends_on 字段**，无拓扑信息
- dashboard `/flywheel-runs` 只能 trace 倒推已发生的 step list，看不出 fanout/串行结构

### 走偏经过

1. 2026-05-27 brainstorm D3 决策：user 说"DSL 可视化" → 我评估"DSL parser 工程量大"→ push back 改成 OPT-REPORT-V1 已有 orchestrator pattern 抽象（"最小 cost / 最大复用"）
2. user 当时接受 → 我**没回头确认是否还跟 mental model 一致**
3. Sprint 1+2+4 全程按"prompt-driven framework"实现，没有任何 DSL 元素
4. 2026-05-28 Sprint 4 ship 后 user review dashboard：mental model 不一致暴露

---

## 2. 实际 ship 资产盘点（grep 数据 2026-05-28）

### Sprint 1 commit `aa2cd7c`（V124 migration + run 实体抽象）

| 文件 | 用途 | V1 在用？ | V2 还用？ |
|---|---|---|---|
| `V124__rename_opt_report_to_flywheel_run.sql` | schema migration | ✅ OPT-REPORT-V1 通过 OptReportService → FlywheelRunService 间接读写 | ✅ |
| `FlywheelRunEntity` + `FlywheelRunStepEntity` | JPA entity | ✅ | ✅ |
| `FlywheelRunRepository` + `FlywheelRunStepRepository` | data access | ✅ | ✅ |
| `FlywheelRunService` | CRUD + 状态机 + WS broadcast | ✅ | ✅ |
| 兼容视图 `t_opt_report` / `t_opt_report_batch` | OPT-REPORT-V1 兜底 | ✅ 1 release 兜底 | 可删（V2 上线 + 灰度后） |

### Sprint 2 commit `fb037bf`（"framework" 真活上线）+ 2.1 hot-fix `86e0b6b`

**grep 关键发现：0 个 t_agent 配置 `DispatchOrchestrationStep` / `RecordOrchestrationStepResult`，OPT-REPORT-V1 的 `report-generator` agent (id=13) tool_ids 是 `["GetAgentConfig","LoadSessionBatch","SubAgent","WriteOptReport"]` — 走的是老 `SubAgent` Tool 路径，从未切到 Sprint 2 新 framework。**

| 文件 | V1 在用？ | V2 还用？ | 处置 |
|---|---|---|---|
| `OrchestratorAgentExecutor.java` (~280 行) | ❌ dead code | ❌（V2 用 DSL runtime 不用 prompt-driven dispatcher） | **删** |
| `WorkerCompletionListener.java` | ❌ dead | ❌ | **删** |
| `FlywheelStepWsBroadcaster.java` | ❌ dead | ❌ | **删** |
| `OrchestratorWorkerSpec.java` | ❌ dead | ❌ | **删** |
| `OrchestrationStepCompletedEvent.java` | ❌ dead | ❌ | **删** |
| `StepStateChangedEvent.java` | ❌ dead | ❌ | **删** |
| `StepResult.java` | ❌ dead | ❌ | **删** |
| `DispatchOrchestrationStep.java` (Tool) | ❌ dead (0 agent 配置) | ❌ | **删** |
| `RecordOrchestrationStepResult.java` (Tool) | ❌ dead | ❌ | **删** |
| `SkillForgeConfig` 里这 2 Tool 的 Bean 注册 | ❌ dead | ❌ | **删** |
| `V125__add_step_output_json.sql` | ❌ dead column (step 没 worker 跑过) | ✅（V2 runner 写）| **保留** |
| Sprint 2.1 hot-fix prompt header injection | ❌ 跟 dispatchOneWorker 一起删 | ❌ | **跟 OrchestratorAgentExecutor 一起删** |
| 5 个 IT 测试（OrchestratorAopContractTest / OrchestratorAgentExecutorTest / WorkerCompletionListenerTest / DispatchOrchestrationStepTest / RecordOrchestrationStepResultTest）| 跟着上面 dead code | ❌ | **删** |
| `FlywheelRunService` 里 step CRUD 方法 (`appendStep` / `attachStepSubAgentSession` / `transitionStepStatus`) | ❌ dead (没人调) | ✅（V2 DSL runner 调） | **保留作 V2 API** |
| `FlywheelRunServiceStepCrudIT` | ❌ dead | ✅（V2 复用作 step API IT） | **保留** |

### Sprint 4 commit `9f86e39`（dashboard `/flywheel-runs`）

| 文件 | V1 在用？ | V2 还用？ | 处置 |
|---|---|---|---|
| `FlywheelOrchestratorRunController` + 2 record DTO + `findAllWithFilters` + `listRuns` | ✅（独立 viewing layer 读 V124 schema） | ✅ 降级作"run history audit"视图 | **保留** |
| `/flywheel-runs` page + 5 hook + 4 子组件 + .css + 路由 + Layout nav | ✅ | ✅ 降级作 audit 视图，主入口让位给 V2 `/workflows` page | **保留** |
| 14 vitest case + 10 ControllerIT + 4 RepositoryFilterIT | - | ✅ | **保留** |

### 总结

- **Sprint 2 ship 整套 framework 全是 dead code**（0 production 调用，0 t_agent 用 Tool）— **无风险 rollback**
- Sprint 1 V124 + V125 schema + entity/service/repository **保留**
- Sprint 4 dashboard viewing layer **保留**（数据流跟 framework 独立，是直接 read schema）

V2 起步从一个"干净的数据层 + 干净的 viewing layer"出发，**不背 Sprint 2 包袱**。

---

## 3. V2 真方向（Claude Code Workflow 形式）

参考研究文档 `research-docs/research/claude code 源码/08 Workflow 工具与编排指南.md`。

### DSL 原语清单（必备）

| Claude Code Workflow 原语 | SkillForge V2 是否要 | 备注 |
|---|---|---|
| `meta = { name, description, phases }` | ✅ workflow 元信息 | YAML frontmatter |
| `phase(title)` | ✅ 阶段分组 | dashboard 节点分组色 |
| `agent(prompt, opts)` | ✅ **核心原语** | 调 worker agent，复用 V1 ChatService + ChildSession |
| `parallel(thunks)` | ✅ fanout barrier | 复用 V1 CompletableFuture |
| `pipeline(items, ...stages)` | ✅ per-item 独立流水线 | 比 parallel 更高级 |
| `log(message)` | ✅ 进度推 WS | dashboard 实时日志 |
| `humanApprove(payload)` | ✅ **新原语**（Claude Code 没有）| workflow 暂停 + 持久化 pending state + 等用户 approve → resume |
| `workflow(name, args)` | 可选 | sub-workflow 嵌套 |

### 存储

- **Phase 1 MVP**：YAML 文件存仓库 `skillforge-server/src/main/resources/workflows/*.workflow.yaml`
- **Phase 3+**：迁移到 DB（`t_flywheel_workflow` 表 + version 字段 + dashboard 编辑器）
- **不嵌 GraalVM JS**（工程量 / 安全 / 资源都太重）

### Runtime 选型（关键决策点）

| 方案 | 形态 | 工程量 | 表达力 |
|---|---|---|---|
| **(a) YAML + Java parser + Java runtime**（推荐）| YAML 声明式 DAG，Java 解释执行 | 中 | 中（够覆盖 4 节点 + fanout + human approve）|
| (b) Java DSL（builder pattern）| `WorkflowBuilder.phase("load").agent(...).parallel(...).build()` | 中 | 高（编译期检查）|
| (c) 嵌 GraalVM JS | 真 1:1 复刻 Claude Code Workflow | 高 | 最高 |

**推荐 (a) YAML**：dashboard 可直接渲 YAML 树成 DAG viz + 后期改 DB 存自然过渡 + 用户写 YAML 比写 Java builder 直观。

### 示例 OPT-REPORT workflow (YAML 形态草稿)

```yaml
name: opt-report
description: Generate optimization report
phases:
  - { title: Load }
  - { title: Annotate }
  - { title: Aggregate }
  - { title: Attribute }
  - { title: Approve }

steps:
  - id: load
    phase: Load
    agent: orchestrator       # ← t_agent.name
    prompt: "Load sessions windowDays={{ input.windowDays }}"
    output_schema: BATCHES_SCHEMA
    out: batches

  - id: annotate
    phase: Annotate
    parallel:
      for_each: "{{ batches.items }}"   # ← fanout
      as: batch
      agent: session-batch-annotator
      prompt: "annotate batch {{ batch.id }}"
      output_schema: ANNOTATION_SCHEMA
    out: annotations

  - id: aggregate
    phase: Aggregate
    agent: aggregator
    prompt: "aggregate: {{ annotations | json }}"
    output_schema: SUMMARY_SCHEMA
    out: summary

  - id: attribute
    phase: Attribute
    agent: attributor
    prompt: "attribute: {{ summary | json }}"
    output_schema: ATTRIBUTION_SCHEMA
    out: attribution

  - id: approve
    phase: Approve
    human_approve:
      payload: "{{ attribution }}"
      ui_template: "opt-report-attribution"   # dashboard 渲染哪个 React 组件
    out: approval
```

---

## 4. 阶段路线 Phase 0-4

### Phase 0：清理 V1 dead code（**第一步**）

- 删 Sprint 2 ship 的整套 framework（见 §2 表 9 文件 + 2 Tool + Bean 注册 + 5 IT 测试）
- 删 Sprint 2.1 hot-fix 路径（`[FRAMEWORK STEP_RUN_ID=...]` header）
- 保留：V124+V125 schema、FlywheelRunService（保留 step CRUD 方法作 V2 API）、Sprint 4 dashboard
- mvn 复测 + 真活验证 OPT-REPORT-V1 0 regression
- commit
- 工程量：~半天 1 sprint

### Phase 1：MVP — 1 个 demo workflow 跑通

- 起新需求包 `2026-05-28-OPT-LOOP-FRAMEWORK-V2-DSL/`（或直接归档 V1 重启 V2，二选一后定）
- DSL YAML parser（Jackson YAML 已在 classpath）
- `WorkflowRunner` 核心类：解释 YAML → 拓扑序遍历 → 调 `agent()` → 复用 ChatService 起 child session → CompletableFuture barrier
- `agent()` / `parallel()` 实现（`pipeline()` 推 Phase 2）
- 不上 `humanApprove()`，不上 viz，不上 DB 存储
- 跑通 1 条 OPT-REPORT YAML demo workflow，对比 V1 prompt-driven 输出一致
- 工程量：~1 Sprint

### Phase 2：完整原语 + viz

- `pipeline()` / `log()` / `humanApprove()` 实现
- DAG viz 组件（react-flow）
- `/workflows` 新 page：列 workflow + run history + DAG live status
- workflow + run 关联：`t_flywheel_run_step` 加 `node_id` 列指向 DSL 哪个节点
- 工程量：~2 Sprint

### Phase 3：DB 存储 + editor

- `t_flywheel_workflow` 表（id / name / yaml_content / version / created_at）
- dashboard YAML 编辑器（monaco）
- workflow validate + dry-run
- 工程量：~1 Sprint

### Phase 4：切换 OPT-REPORT-V1 到 DSL

- 写 OPT-REPORT 对应的 production workflow YAML
- 灰度切换 OPT-REPORT 用 V2 DSL 替代 `report-generator` agent prompt-driven
- 通过 → 删 `report-generator` agent 的 `SubAgent` Tool 依赖
- 真"workflow as code"上线
- 工程量：~1 Sprint

---

## 5. 已 ratify 决策（V2 brainstorm 2026-05-28）

- D-V2-1：DSL 形态参考 Claude Code Workflow 原语（agent / parallel / pipeline / phase / humanApprove）
- D-V2-2：存储**先 YAML 文件 / 后 DB**（Phase 1 用 YAML，Phase 3 加 DB）
- D-V2-3：Runtime 选 (a) YAML + Java parser（不嵌 GraalVM JS）
- D-V2-4：human approve 是**新原语**（Claude Code 没有，SkillForge 加）
- D-V2-5：approve 之后 A/B + analyzer 链路 user 也不确定 DSL 能不能覆盖，**留 V2.1+ brainstorm**
- D-V2-6：session → workflow 反查入口**留 Phase 2+ 设计**（user 也没想好）
- D-V2-7：Sprint 4 dashboard `/flywheel-runs` page **保留作 run history audit 视图**，V2 主入口让位给 `/workflows` 新 page

---

## 6. 待 brainstorm 决策点（V2 brainstorm 待补）

- D-V2-Q1：YAML 模板引擎选型（`{{ input.x }}` / `{{ batch.id }}` 表达式怎么解析？用 Spring SpEL / Mustache / Pebble / 自己写？）
- D-V2-Q2：节点 `output_schema` 引用形式 — JSON Schema inline / 引另一个文件 / Java 类 `@Schema` 注解？
- D-V2-Q3：fanout `for_each` 节点状态聚合 — 失败 1 个是否整个 workflow 失败？还是 partial success？
- D-V2-Q4：human approve 节点暂停的 workflow 怎么 resume — DB poll / WS push / WorkflowResumeController + user click？
- D-V2-Q5：节点 retry 策略 — Phase 1 不做，Phase 2 决策
- D-V2-Q6：workflow 文件 lifecycle（编辑、validate、deploy、rollback、灰度）— Phase 3 决策
- D-V2-Q7：跟 Sprint 4 dashboard `/flywheel-runs` 关系 — V2 新 page `/workflows` 怎么 link 到现有 run history？

---

## 7. 🆕 新方向（user 2026-05-28 提"我要再增加一些新的东西"，待 user 补充）

> _placeholder — 等 user 给出具体新方向，整合进 V2 路线。_
>
> 可能的方向（猜测，等 user 确认）：
> - approve 之后的 A/B + analyzer 链路具体形态？
> - workflow editor 在 dashboard 里的交互形态？
> - workflow template marketplace / sharing？
> - sub-workflow 嵌套 / workflow composition？

---

## 8. Sprint 4 ship 当下处置

- `/flywheel-runs` page 保留（**降级**作 run history audit 视图）
- V2 上线后，user 反查的主路径是 `/workflows`
- `/flywheel-runs` 可以加 Banner "查看 workflow 视图 →" 跳 `/workflows/<workflow-id>?run=<runId>` —— 留 V2 Phase 2 做

---

## 9. 整包归属（待定）

- 选项 A：**直接在当前 active 包加 V2 章节**（这份 V2-DSL-RESET.md），V1 内容保留作历史 reference，prd.md / tech-design.md 之后改写为 V2
- 选项 B：当前 V1 包归档 → 起新独立 `2026-05-28-OPT-LOOP-FRAMEWORK-V2-DSL/` 需求包
- **倾向 B**（命名清晰，V1 与 V2 mental model 真不一样，混在一起后人难分辨）— 等 user 补"新东西"后一起拍板

---

## 10. Iron Law check

- Phase 0 rollback：**不能动 V124 / V125 schema**，否则 OPT-REPORT-V1 13 历史行受损（mvn test + 真活 curl 验证 0 regression）
- V2 Phase 1-3：**不能动 OPT-REPORT-V1 的 SubAgent / report-generator / session-batch-annotator agent**（user 在用）；只在 Phase 4 灰度切换时改
- 不动 `t_session_message` / persistence-shape-invariant / identity-column 守住
