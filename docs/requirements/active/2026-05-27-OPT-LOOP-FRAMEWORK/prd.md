# PRD — OPT-LOOP-FRAMEWORK

> 产品需求文档：飞轮编排框架抽取 + Run 实体泛化（基于 OPT-REPORT-V1 现成模式扩展）
> 2026-05-27 状态：draft（等用户 ratify D1-D5 + 回答 Q1-Q5）

---

## 1. 产品目标

把 OPT-REPORT-V1 已经走通的 "orchestrator agent + Run 实体 + Dashboard + WS push" **模式抽出来**，扩展到飞轮其他 orchestrator agent（V1 先 memory-curator）。具体做 4 件事：

1. **Run 实体泛化**：`OptReportEntity → FlywheelRunEntity`（rename + 补 `trigger_source` + `input_json` + `loop_kind` 3 列），所有 flywheel loop run 写同一张表
2. **Framework 抽取**：`OrchestratorAgentExecutor` Java framework class，替代 3 个 orchestrator agent prompt 里各自手写的 SubAgent dispatch 段（V1 先让 memory-curator 复用验证可行性）
3. **可见性闭环**：dashboard 新 page `/flywheel-runs` 列所有 FlywheelRunEntity + step_run timeline + WS push live status
4. **不动业务逻辑**：3 个 orchestrator 内部"判断 / 推理"仍由 LLM prompt 自由处理，DSL 不抢；只改"who calls who when"那一段为 framework 调用

---

## 2. 非目标

- 不引入新编排语言（YAML DSL / Kotlin DSL 等）
- 不重写 SystemAgent prompt 自由文本（只改 SubAgent dispatch 段，其余 prompt 内容保留）
- 不替代 OptimizationEvent（业务原子事件保留，跟 FlywheelRunEntity 通过 step_run.optimization_event_id 关联）
- V1 **不一次性接管所有 3 个 orchestrator** — 只接管 memory-curator 验证 framework；report-generator + attribution-dispatcher V2+ 独立包
- 不做 Variant fork（V4）
- 不引入 GUI 编辑（dashboard read-only）
- 不动 attribution / metrics 入口（V2+）
- 不动 OPT-REPORT-V1 的业务逻辑（只改 Entity 名 + 加列；REST API / WS event / Dashboard Reports tab page 保持不变）

---

## 3. 工作流（用户视角）

### 3.1 改 orchestrator agent prompt

`memory-curator` 的 V95 prompt 末尾 STEP 2 "对每个 userId 调 SubAgent..." 一段，**改成调 `DispatchOrchestrationStep` 工具**（语义跟 SubAgent 工具几乎一样，但自动落 step_run 行 + 关联 parentRunId）。其余 prompt 不动。

改 prompt 工作流：
- 改新 V?? migration UPDATE t_agent SET system_prompt = $prompt$ ... $prompt$ WHERE agent_type='memory-curator'
- git push → PR review → merge → 重启 server 加载新 prompt

### 3.2 跑 loop（系统视角）

```
Cron / dashboard 按钮 / event 触发
  ↓
入口 Service (MemoryConsolidationScheduler / OptReportService.startReport / AttributionDispatcherService)
  ├─ INSERT FlywheelRunEntity (status='pending', loop_kind, trigger_source, input_json)
  ├─ spawn orchestrator SystemAgent session (memory-curator / report-generator / attribution-dispatcher)
  ├─ kickoff message 含 runId
  └─ HTTP 202 / WS event run_started
       ↓
[Async orchestrator session]
  ├─ prompt 自由文本：思考 / 判断 / 决策
  └─ STEP X: DispatchOrchestrationStep(workers=[{agentName, task, input}, ...])
       │
       └─ [framework 接管]：
            ├─ FOR EACH worker: 写 FlywheelRunStepEntity (status='pending')
            ├─ FOR EACH worker: 调 SubAgent.dispatch() 异步派发
            ├─ 等齐 barrier (worker tool 完成时回调 framework)
            ├─ UPDATE step_run status (completed / failed)
            └─ 返 List<StepResult> 给 orchestrator prompt 继续
       ↓
  orchestrator 完成 → UPDATE FlywheelRunEntity status='completed'
  WS event flywheel_run_status_changed
```

### 3.3 看进度

新 page `/flywheel-runs`：
- **左侧**：所有 FlywheelRunEntity 列表（filter by loop_kind / agentId / status / 时间窗）
- **中部**：选中 run 的详情（input_json / output_json / generator_session_id 链接到 SessionDetail 页）
- **右侧**：step_run timeline（每个 SubAgent step 的 status + 耗时 + 错误 detail）
- WS event 实时推送 status 切换 < 2s 更新

### 3.4 OPT-REPORT-V1 兼容性

Sprint 1 rename `OptReportEntity → FlywheelRunEntity` 后：
- 创建 SQL 视图 `t_opt_report` 兜底 1 release（视图 = `SELECT ... FROM t_flywheel_run WHERE loop_kind='opt_report'`）
- OPT-REPORT REST API endpoints 保持不变（Service 层代码改字段映射，对外接口稳定）
- Dashboard Reports tab page 保持不变（数据源对接 FlywheelRunEntity，但 UI 不变）
- WS event `opt_report_completed` 继续推送（同时新加 `flywheel_run_status_changed` 通用 event）

---

## 4. 功能需求

### FR-1 Run 实体 schema migration

- 新 Flyway migration V??（编号待定）：
  - `ALTER TABLE t_opt_report RENAME TO t_flywheel_run`
  - `ALTER TABLE t_flywheel_run ADD COLUMN trigger_source VARCHAR(32) NOT NULL DEFAULT 'user_manual'` (CHECK IN 'cron','user_manual','api','event')
  - `ALTER TABLE t_flywheel_run ADD COLUMN input_json JSONB NOT NULL DEFAULT '{}'`
  - `ALTER TABLE t_flywheel_run ADD COLUMN loop_kind VARCHAR(32) NOT NULL DEFAULT 'opt_report'` (CHECK IN 'opt_report','memory_curation','attribution','metrics_collection','custom')
  - Backfill：已有 OPT-REPORT 行 trigger_source='user_manual'，loop_kind='opt_report'，input_json 从 (agent_id, window_start, window_end) 合成
  - `ALTER TABLE t_opt_report_batch RENAME TO t_flywheel_run_step`
  - 加 `step_kind VARCHAR(32) NOT NULL DEFAULT 'subagent_dispatch'`（V1 只有这 1 种 kind）
  - 字段语义映射：`session_ids_json → step_input_json` / `annotations_written_count → step_output_summary` 含 count
  - 创建兼容视图 `CREATE VIEW t_opt_report AS SELECT id, ... FROM t_flywheel_run WHERE loop_kind='opt_report'` + 同样 batch 视图

### FR-2 OrchestratorAgentExecutor framework

新模块 `skillforge-server/src/main/java/com/skillforge/server/flywheel/orchestrator/`：
- `OrchestratorAgentExecutor.java`：核心类
- API 1: `FlywheelRunEntity startRun(loopKind, triggerSource, inputJson, generatorAgentType)` — 入口 Service 调用
- API 2: `CompletableFuture<List<StepResult>> dispatchSubAgents(parentRunId, List<OrchestratorWorkerSpec> workers)` — 框架内部，由 DispatchOrchestrationStep 工具调用
- 自动行为：
  - 写 FlywheelRunStepEntity（per worker 一行）
  - 调 SubAgentTool.dispatch() 异步派发
  - 监听 worker session 完成（复用现有 SubAgent 回调机制）
  - UPDATE step_run.status (completed / failed) + 触发 WS push
  - 等齐 barrier + 返 StepResult list

支持类：
- `OrchestratorWorkerSpec.java` record (agentName, task, inputJson, timeoutSeconds)
- `StepResult.java` record (stepRunId, status, outputJson, errorReason)

### FR-3 DispatchOrchestrationStep Tool

新 Tool 在 `skillforge-tools/src/main/java/com/skillforge/tools/flywheel/`：
- 实现 `com.skillforge.core.skill.Tool` 接口
- Tool name: `DispatchOrchestrationStep`
- Tool description: "Dispatch parallel SubAgents as flywheel run steps. Automatically writes step_run rows + waits for barrier + returns results. Use this in orchestrator system agents' STEP X SubAgent fanout sections."
- 入参 schema：`{parentRunId: UUID, workers: [{agentName, task, input: {...}, timeoutSeconds}]}`
- 出参：`{stepResults: [{stepRunId, status, output, errorReason}], allSucceeded: bool}`
- 内部：调 OrchestratorAgentExecutor.dispatchSubAgents()

`SubAgentTool` 保留不动（已有用法不破坏，但新 orchestrator agent 推荐用 DispatchOrchestrationStep）

### FR-4 memory-curator 接入 framework

- 新 migration UPDATE memory-curator 的 system_prompt：STEP 2 "对每个 userId 调 SubAgent(...)" → "对每个 userId 调 DispatchOrchestrationStep(workers=[{agentName='memory-curator-sub-worker', task=..., input={userId}}])"
- 新 `memory-curator-sub-worker` SystemAgent seed（如果 memory-curator 现在 sub-session 是用自己 agent 跑的，要拆分一个 worker agent；否则复用现有）
- MemoryConsolidationScheduler 入口改造：
  - 调 `OrchestratorAgentExecutor.startRun(loopKind='memory_curation', triggerSource='cron', inputJson={cronExpression, ...}, generatorAgentType='memory-curator')`
  - 返 FlywheelRunEntity → kickoff memory-curator session with runId

### FR-5 dashboard All Flywheel Runs page

- BE endpoint：
  - `GET /api/flywheel/runs?loopKind=&agentId=&status=&limit=&offset=` 列 FlywheelRunEntity（按 createdAt DESC）
  - `GET /api/flywheel/runs/{id}` 详情含 step_run list + input_json + output_json
- FE page `/flywheel-runs`：
  - 左侧 filter + list table
  - 中部 selected run detail
  - 右侧 step_run timeline（按 createdAt 排序，含 status 颜色 + 耗时 + 错误 detail）
  - 点 generator_session_id 跳 SessionDetail 页
  - 点 optimization_event_id 跳 OptimizationEvents 页（如有）

### FR-6 WebSocket push live status

- 后端：`broadcaster.systemEvent("flywheel_run_status_changed", payload)` 系统级广播（不走 user-level）
- payload：`{runId, loopKind, agentId, old_status, new_status, timestamp, error_message?}`
- 前端：useFlywheelRuns hook 通过 WebSocket 接收 + 局部 update state
- 节流：FE 200ms debounce（同步骤变化合并）

### FR-7 OPT-REPORT-V1 backward compat

- `OptReportEntity` Java 类保留，内部委托给 FlywheelRunEntity（或直接 rename + 加 @Deprecated alias）
- `OptReportController` 现有 endpoints 不变（业务逻辑层切到 FlywheelRunEntity 但 DTO 不变）
- `convertIssueToEvent` 保留不变（FlywheelRunStepEntity.optimization_event_id 字段已对位）
- WS event `opt_report_completed` 继续推送 + 新加 `flywheel_run_status_changed` 通用 event 并行

---

## 5. 验收标准（V1）

- **AC-1**: F1+F2 — Migration 跑后 `t_flywheel_run` + `t_flywheel_run_step` 两表完整 + 3 列新字段 backfill 正确；OPT-REPORT-V1 dogfood 跑通（mvn -pl skillforge-server -am test BUILD SUCCESS + 真活生成 1 个 report + dashboard Reports tab 显示无 regression）
- **AC-2**: F4 兼容视图 `t_opt_report` 仍可 SELECT 查询，外部依赖（如有）零破坏；视图保留至少 1 个 release
- **AC-3**: FR-2 + FR-3 — `OrchestratorAgentExecutor.dispatchSubAgents()` minimal IT case 跑通：2 个 worker 并行 dispatch → 等齐 → 返 2 个 StepResult，step_run 表自动写 2 行
- **AC-4**: FR-4 — memory-curator 用 DispatchOrchestrationStep 工具跑一次 cron 后：
  - `t_flywheel_run` +1 行 (loop_kind='memory_curation', status='completed')
  - `t_flywheel_run_step` +N 行 (per user / per worker, step_kind='subagent_dispatch')
  - `t_memory_proposal` 写入跟旧路径数量 diff < 5%
- **AC-5**: FR-5 — `/flywheel-runs` page 能看到 OPT-REPORT + Memory 两种 loop_kind 的 run；filter by loop_kind / status / agentId 工作正常；点 run 进 detail 看到 step_run timeline
- **AC-6**: FR-6 — WS event `flywheel_run_status_changed` 推送 + FE 实时更新 latency < 2s
- **AC-7**: FR-7 — OPT-REPORT-V1 现有 dashboard Reports tab page + REST endpoints + `opt_report_completed` WS event 全部不破坏（regression test 全通过）
- **AC-8**: IT 至少覆盖 8 case（详见 AC-8 矩阵）

### AC-8 — IT 覆盖矩阵

| Case | 验证什么 |
|---|---|
| 1 | rename migration 跑后 OPT-REPORT-V1 existing data 完整可查（含兼容视图）|
| 2 | OPT-REPORT-V1 startReport 整链路跑通（HTTP 202 + spawn report-generator + ... + WriteOptReport + WS push）|
| 3 | OrchestratorAgentExecutor.dispatchSubAgents() 2 worker 并行 + 等齐 + 自动落 step_run |
| 4 | OrchestratorAgentExecutor 错误处理：某 worker 失败 → step_run.status='failed' + 整 run.status='failed' |
| 5 | memory-curator 用 DispatchOrchestrationStep 跑一次 cron + 验 t_flywheel_run + t_flywheel_run_step + t_memory_proposal |
| 6 | dashboard `/flywheel-runs` GET endpoint 返 FlywheelRunEntity list + filter 工作 |
| 7 | WS event `flywheel_run_status_changed` 推送 + payload 字段全 |
| 8 | OPT-REPORT-V1 convertIssueToEvent 经 rename 后仍跑通（FlywheelRunStepEntity.optimization_event_id 字段对位）|

---

## 6. 5 D 决策表

| # | 决策 | 选 |
|---|---|---|
| **D1** | Run 实体演进策略 | OptReportEntity rename FlywheelRunEntity + 补 trigger_source/input_json/loop_kind 3 列 + 兼容视图 |
| **D2** | step_run 实体演进 | OptReportBatchEntity rename FlywheelRunStepEntity，泛化 batch 概念为通用 step |
| **D3** | OrchestratorAgentExecutor framework 抽取 | 抽 Java framework class，3 orchestrator 通过新工具 DispatchOrchestrationStep 复用；prompt 自由文本部分不动 |
| **D4** | V1 范围 | 5 sprint：rename + framework + memory-curator 接入 + dashboard + 测试灰度 |
| **D5** | dashboard 页面策略 | 新加 All Flywheel Runs page，保留 OPT-REPORT Reports tab + FlywheelFlowchart Insights tab；共享底层数据源 |

---

## 7. Q 澄清（开 Plan 时回答）

### Q1: rename 路径细节

**选项**：
- (a) `ALTER TABLE t_opt_report RENAME TO t_flywheel_run`（保 PK，原 row 不动）— 推荐
- (b) 新建 `t_flywheel_run` + INSERT SELECT 数据迁移 + drop t_opt_report

**推荐**：(a)，operations 简单 + 回滚可控（`ALTER TABLE RENAME`）
**影响**：migration 复杂度 + 回滚难度

### Q2: OrchestratorAgentExecutor 实现模式

**选项**：
- (a) **单一 Class + Method API**（推荐）：一个 OrchestratorAgentExecutor 类，提供 startRun + dispatchSubAgents 两个 method；所有 orchestrator agent 共用，按 loopKind 参数区分
- (b) Template method pattern：abstract OrchestratorAgentExecutor + 子类（MemoryCuratorOrchestrator / ReportGeneratorOrchestrator / AttributionDispatcherOrchestrator）— overkill for V1
- (c) SPI 注册（`@FlywheelOrchestrator` 注解）— 需要改现有 orchestrator class 加注解，工程成本高

**推荐**：(a)
**影响**：工程量 + 扩展性

### Q3: `DispatchOrchestrationStep` 工具替代 SubAgentTool 全部用法？

**选项**：
- (a) 不替代：SubAgentTool 保留所有现有用法（非 orchestrator 场景，如 ChatService 用户主动 dispatch SubAgent）；DispatchOrchestrationStep 仅给 orchestrator agent prompt 用 — 推荐
- (b) 替代：所有 SubAgent dispatch 都走 DispatchOrchestrationStep（含非 flywheel 场景）— 改动范围大

**推荐**：(a)，工具职责分离 + 不破坏现有用法
**影响**：tool 数量 / 复杂度

### Q4: All Flywheel Runs page 跟 OPT-REPORT Reports tab page 关系

**选项**：
- (a) 完全独立：两个页面，各看各的视图；OPT-REPORT Reports tab 用 reports-specific UI（含 markdown + convertIssueToEvent 按钮等），All Flywheel Runs 用通用 Run table + step timeline — 推荐 V1
- (b) 合并：废弃 OPT-REPORT Reports tab，All Flywheel Runs 加 loop_kind filter 区分类型 — 长期方向但 V1 破坏面大
- (c) 联动 deep-link：OPT-REPORT Reports tab 加 "View as Flywheel Run" 链接 + All Flywheel Runs 点击 opt_report 类型 run → 跳 Reports tab 看完整 report — 推荐 V2

**推荐**：(a) V1 独立，(c) V2 加联动
**影响**：UI 设计范围

### Q5: trigger_source + input_json + loop_kind schema 细节

**选项**：
- (a) `trigger_source` enum 4 值：`cron / user_manual / api / event`
- (b) `loop_kind` enum 起始 5 值：`opt_report / memory_curation / attribution / metrics_collection / custom`
- (c) `input_json` 不规定通用 schema（每个 loop_kind 自己定义内部字段）

**推荐**：(a) + (b) + (c)。input_json 按 loop_kind 自定义字段更灵活，但加 `@JsonAlias` 兼容 OPT-REPORT 原字段（agentId, windowDays 等）
**影响**：schema 设计

---

## 8. 验证预期

- **IT**：8 case 覆盖见 AC-8 矩阵
- **单测**：OrchestratorAgentExecutor / DispatchOrchestrationStep / FlywheelRunEntity lifecycle / 兼容视图 query / migration rollback
- **Dogfood**：memory-curator 切 framework 跑 1 周，`t_memory_proposal` 行数 diff < 5%
- **真活验证**：
  - dashboard `/flywheel-runs` 看 OPT-REPORT + Memory 两种 run，latency < 2s
  - OPT-REPORT-V1 dashboard Reports tab page regression 0
  - convertIssueToEvent 仍跑通

---

## 9. 影响范围

| 模块 | 改动 |
|---|---|
| skillforge-core | 不动 |
| **skillforge-tools** | 加 `DispatchOrchestrationStep` Tool |
| **skillforge-server** | 大改：新 `flywheel/orchestrator/` 模块（OrchestratorAgentExecutor / WorkerSpec / StepResult）+ `flywheel/run/` 模块（FlywheelRunEntity / Repository / Service / Controller） + `optreport/` 包内部委托 + MemoryConsolidationScheduler 入口改造 |
| **skillforge-dashboard** | 新 page `/flywheel-runs` + 新 useFlywheelRuns hook + WS event handler + API wrapper |
| **db migration** | 1-2 个新 V*.sql（rename + 加列 + 兼容视图 + memory-curator prompt UPDATE） |
| **现有 SystemAgent prompt** | 仅 memory-curator 改 STEP 2（其余 V2+ 改）|
| **现有 Java Service** | MemoryConsolidationScheduler 入口改造写 FlywheelRunEntity + 复用 framework；其他 cron Service 不动 |
| **新依赖** | 无（不引入新外部库） |

---

## 10. 风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| **R1** | rename `t_opt_report → t_flywheel_run` migration 破坏 OPT-REPORT-V1 现有数据 / dashboard / REST | (1) Sprint 1 独立 ship，不带其他改动；(2) 兼容视图 `t_opt_report` 保留 1 release 兜底外部依赖；(3) AC-7 regression test 必须 100% 通过；(4) staging 环境先跑 + dogfood 验证 OPT-REPORT 跑通 |
| **R2** | memory-curator framework 集成跟 DREAMING V1 改造冲突（同时改 prompt） | 前置约束：DREAMING V1 ship + 稳定 1 周后才能启动本需求 Sprint 3；mrd.md / prd.md 显式标注；DREAMING-MEMORY-EXTENSION index.md 加双向链接 |
| **R3** | SubAgentTool 跟 DispatchOrchestrationStep 双工具混乱（用户不知道用哪个） | tech-design 加"工具职责对比"小节：SubAgent 用于 chat 用户主动派发 / `DispatchOrchestrationStep` 用于 orchestrator agent；新 orchestrator agent prompt 必须用后者 |
| **R4** | framework 抽得太早（V1 只 memory-curator 一个 orchestrator 复用，可能 over-abstract） | 接受 risk，V2 接入 attribution / report-generator 时如果 API 不合适，迭代改 framework 不破坏 contract；V1 framework 设计要按"先抽 2-3 个 case 共性"的方法（参考 OPT-REPORT 的 STEP 3 + memory-curator 的 STEP 2 SubAgent dispatch 段抽公共代码）|
| **R5** | dashboard 数据源切换破坏现有 OPT-REPORT Reports tab page | 数据源切到 FlywheelRunEntity，但 DTO / API 不变；Reports tab 跟 All Flywheel Runs page 独立（D5 Q4 选项 a），互不影响 |
| **R6** | OrchestratorAgentExecutor 跟现有 `@Transactional` / `@Async` AOP 行为契约 | tech-design 加"AOP 行为契约"小节（参考旧 FLYWHEEL-ORCHESTRATION-DSL B2 教训）：framework 不持外层事务，SubAgentTool.dispatch 异步派发不在事务内，step_run 写在独立 short tx |
| **R7** | 兼容视图性能（OPT-REPORT 查询走 view → t_flywheel_run filter by loop_kind） | 加 `CREATE INDEX idx_flywheel_run_loop_kind ON t_flywheel_run (loop_kind, created_at DESC)`；查询性能跟 rename 前等价（直接 filter）|
| **R8** | rename 期间 OPT-REPORT 新 report 写入失败（migration 跑到一半 row 锁住）| migration 设计为单事务 + `ALTER TABLE RENAME` 是 PG metadata op 不锁数据（实测毫秒级）；Sprint 1 灰度先 staging |
