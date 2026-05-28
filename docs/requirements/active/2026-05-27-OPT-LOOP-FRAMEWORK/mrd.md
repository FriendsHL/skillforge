# MRD — OPT-LOOP-FRAMEWORK

> 用户原始诉求 + 现状摸底（V97 OPT-REPORT 全貌）+ 痛点拆解 + 旧 DSL 方向作废原因
> 2026-05-27

---

## 1. 用户原始描述（2026-05-27 对话原文要点）

### 1.1 痛点 3 条

> 现在我们的整体数据飞轮（包括后续改名字叫 opt loop）虽然已经算是健全了... 但是我感觉有点太固化了。如果有编排的话，可能我有个可视化的或者 config 配置，然后让他执行就好了，是一套完整的编排工作。
>
> **我为什么做这个事情**：
> 1. 现在得到的结果我没有办法有一个固定编排去追踪，到底执行到哪了？
> 2. 编排太固定了
> 3. 尝试 agent 的 workflow 编排，用来探索评测方面是不是有 agent workflows 能更好一些。

### 1.2 用户中途修正（critical pivot）

最初基于 Explore agent #1 摸底（V95 6 SystemAgent + cron 模型），我和 reviewer 把需求设计成"YAML DSL wrap Java Service"方向，用户 push back：

> 我怎么感觉 还是不太对呢

进一步澄清：

> 我理解的是 当前几个 system agent 是通过另一个 agent 编排的。然后 整体的数据清洗+评测+归因 是通过这种方式传起来的。可以参考下 agent 页面里面的 generation report 这个功能。

**这是关键澄清**——用户**记得**飞轮已经有 orchestrator agent 模式（V97 OPT-REPORT-V1 的 report-generator），但我没摸到。重新 Explore #2 摸 V97 OPT-REPORT 全貌后发现：飞轮**已经有 3 个 orchestrator**（不只是 OPT-REPORT）。

---

## 2. 现状摸底 #2（V97 OPT-REPORT-V1 全貌 + 3 orchestrator agent）

详见 Explore agent #2 输出（落在本文档），本节摘要关键事实。

### 2.1 飞轮 SystemAgent orchestrator vs worker 分类

| Agent | 角色 | Prompt 调 SubAgent？ | Migration |
|---|---|---|---|
| **attribution-dispatcher** | **Orchestrator** | ✓ V95:187-193 prompt 明确 "对决定 dispatch 的 candidate 逐个调 SubAgent(action='dispatch', agentName='attribution-curator', task=...)" | V95 |
| **memory-curator** | **Orchestrator** | ✓ V95 STEP 2 "对每个 userId 调 SubAgent 派发一个 sub-session 执行单 user 整理" | V95 |
| **report-generator** | **Orchestrator** | ✓ V97:139-151 "对每批 sessions 调 SubAgent(action='dispatch', agentName='session-batch-annotator', task=...)" | V97 |
| attribution-curator | Worker | ✗ | V95 |
| session-annotator | Worker | ✗ | V95 |
| session-batch-annotator | Worker | ✗ | V97 |
| metrics-collector | Worker | ✗ | V95 |
| user-simulator | Worker | ✗ | V95 |

**关键**：每个 orchestrator agent 的 SubAgent dispatch 逻辑**各自在 prompt 里手写**（V95 attribution-dispatcher STEP 3 / V95 memory-curator STEP 2 / V97 report-generator STEP 3）——三段代码做同一件事，没抽象。

### 2.2 OPT-REPORT-V1 完整调用链（参考已 ship 模式）

```
User Dashboard "Generate Report" 按钮
  ↓
POST /api/flywheel/agents/{agentId}/generate-report
  ↓
OptReportService.startReport()
  ├─ INSERT t_opt_report (status='pending', id=UUID) ← Run 实体！
  ├─ lookup SystemAgent 'report-generator'
  ├─ SessionService.createSession() → t_session row
  ├─ ChatService.chatAsync()  [异步 fork report-generator session]
  ├─ UPDATE t_opt_report (status='running', generator_session_id=...)
  └─ HTTP 202 Accepted 返 {reportId}
       ↓
[Async report-generator session]
  ├─ STEP 1: LoadSessionBatch (拉时间窗 session)
  ├─ STEP 2: parse & shard → K batches
  ├─ STEP 3: FOR EACH batch: SubAgent(action='dispatch', agentName='session-batch-annotator', task=...)
  │          [K 个 SubAgent 并行]
  │          ├─ SubAgent #1: GetTrace × N → AnnotateSession × N → RecordBatchAnnotations [INSERT t_opt_report_batch]
  │          ├─ SubAgent #2: 同上
  │          └─ ...
  ├─ STEP 4: 等 K barriers
  ├─ STEP 5: LoadSessionBatch 重新拉（含新标注）
  ├─ STEP 6: LLM 归因分析
  └─ STEP 7: WriteOptReport
       ↓
  UPDATE t_opt_report (status='completed', content_md, summary_json)
       ↓
  WS broadcastAll {type: 'opt_report_completed', ...}
       ↓
  FE Layout.tsx toast 通知 + 停止 polling
```

**关键观察**：这个链路里有 5 个组件，**3 个已经在 SkillForge 里成熟运行**：

1. **Run 实体**：`OptReportEntity` (`t_opt_report`)
2. **nested step_run 实体**：`OptReportBatchEntity` (`t_opt_report_batch`)
3. **WS push 基建**：`UserWebSocketHandler.broadcastAll`
4. **Human-in-loop gate**：`OptReportToEventBridge.convertIssueToEvent`
5. **REST endpoints**：`OptReportController` GET list / GET detail / POST run / POST convert-to-event

### 2.3 OptReportEntity 字段 vs 假想的 t_flywheel_loop_run

| 假想字段 | OptReportEntity 实际对应 |
|---|---|
| `loop_id` (UUID) | `id` (UUID) |
| `status (pending/running/completed/failed)` | `status` (pending/running/completed/error) |
| `started_at` | `updatedAt` (status→running 时) |
| `ended_at` | `updatedAt` (status→completed\|error 时) |
| `trigger_source` (cron / user_manual / api / event) | **缺失**（目前所有 OptReport 都是 user_manual） |
| `input_json` ({agentId, windowDays, ...}) | **缺失**（agentId + window_start + window_end 分散在列） |
| `output_json` | `summary_json` (JSONB) + `content_md` (TEXT) |
| `generator_session_id` | `generatorSessionId` 直接对应 |
| `nested step_run table` | `t_opt_report_batch` 1-to-N 完整覆盖 |

**结论**：OptReportEntity 已经在事实上扮演 "FlywheelLoopRun" 角色 — **缺的只是 2 列 + 1 个名字**。

### 2.4 飞轮其他 loop 缺什么

attribution-dispatcher + memory-curator 都已经是 orchestrator，但**它们没有对应的 Run 实体**：

| Loop | Run 实体 | Dashboard page | on-demand 触发 |
|---|---|---|---|
| OPT-REPORT (report-generator) | ✓ OptReportEntity | ✓ Reports tab | ✓ `POST /api/flywheel/agents/{id}/generate-report` |
| Attribution (attribution-dispatcher → curator) | ✗ 只有 OptimizationEvent 原子事件 | ✗ 只有 ActivityFeed 看 event | ✓ V104 FLYWHEEL-PER-AGENT-RUN-NOW |
| Memory (memory-curator) | ✗ | ✗ | ✗ 没看到对应 endpoint |

→ Attribution / Memory 也用 orchestrator 模式但**没像 OPT-REPORT 做完整 Run + Dashboard**。这是用户痛点 1（看不到进度）的真实根因。

---

## 3. 痛点拆解（基于真实现状）

| 用户说的 | 翻译成架构问题 | 根因（基于 V97 OPT-REPORT 摸底） |
|---|---|---|
| ① 没法追踪执行到哪 | **Observability 不一致** | OPT-REPORT 有 Run 实体 + Dashboard page；Attribution / Memory 没有。3 个 orchestrator 中只有 1 个的进度可见 |
| ② 编排太固定 | **Framework 缺失** | 3 个 orchestrator 的 SubAgent dispatch 逻辑各自在 prompt 里手写——重复代码 + 加新 orchestrator 要复制粘贴 |
| ③ 想试 agent workflow 编排（fork variant） | **Variant fork 机制缺失** | 没有"复制一个 run 改 input_json 重跑对比"的 API 或 UI（V4 留） |

**核心洞察**：OPT-REPORT-V1 已经把"orchestrator + Run + Dashboard + WS push" 这套**走通**了，但只用在了 report-generator 这一个 orchestrator。**真正的需求是把这套模式抽出来给 attribution / memory 复用**——不是搞一套全新的 YAML DSL 编排引擎。

---

## 4. 旧需求包 FLYWHEEL-ORCHESTRATION-DSL 作废原因（教训记录）

### 4.1 作废链路

- 2026-05-27 上午：用户原话提"opt loop 编排太固定 / 看不到进度"
- 我请 Explore agent #1 摸底 → 报告显示飞轮是"6 独立 SystemAgent + Spring cron"模型，**漏掉 V97 OPT-REPORT 系统**
- 基于不完整现状设计需求包 `FLYWHEEL-ORCHESTRATION-DSL`：方向 = YAML DSL wrap Java Service
- 跑 architect + java-design-reviewer 第 1 轮 review → NEEDS_FIX (4 blocker + 8 warning)
- 准备 fix 时用户 push back "我怎么感觉 还是不太对呢" + 关键澄清"几个 system agent 是通过另一个 agent 编排的"
- 重新 Explore agent #2 → 发现 V97 OPT-REPORT-V1 + 3 orchestrator 模式
- 整个 DSL 方向是基于错的现状理解 → git checkout 丢掉 4 md 文件

### 4.2 教训

1. **Explore agent 报告可能不全**——本次 Explore #1 只看 V95 milestone，漏掉 V97 OPT-REPORT-V1 的新增 SystemAgent + Entity。下次 brainstorm 前**必须 grep 最新 N 个 migration**（不只是 stable 期 milestone）
2. **用户的"模糊感觉不对"比 reviewer 的"4 blocker 8 warning"更值得停下来**——reviewer 在错的前提下挑出来的问题不解决根本方向问题
3. **看到现状有 OPT-REPORT 这种成熟模式时，应该问"能不能扩展现有模式"而不是"建新的"**——更简方案 push back 原则
4. **多轮 brainstorm 后必须重新校对现状**——不要让单次 Explore 报告锁死方向

### 4.3 跟本需求包关系

- 旧需求包文件已 git checkout 丢掉（README + todo + 4 md + 目录全部）
- 但**部分思考结晶保留**：Run 实体的字段设计（trigger_source / input_json）是有用的，本需求 D1 沿用；feature flag 灰度机制思路 R1 也有借鉴
- 旧 reviewer report (`/tmp/review-java-design-flywheel-dsl.md`) 已保留作历史参考——里面的 PG UNIQUE 约束 + AOP 行为契约 + 表达式求值这些技术细节**虽然方向错，但工程教训通用**，本需求 tech-design 可能会用到部分

---

## 5. 跟 Claude Code Workflow 工具的关系

> 用户原直觉："我感觉这个工作流的方式其实适合我们做 opt loop"，"希望这个能代替飞轮 V1-V6 和 opt loop"

### 关键 push back（2026-05-27 早些时候 + 多次重申）

Claude Code `Workflow` 工具是**跑在 Claude Code CLI 进程内的 JS 沙箱引擎**，不能装到 skillforge-server JVM 跑生产 daemon。

### 真实可行方向（本需求采纳）

**借鉴 Claude Code Workflow 的设计哲学**（schema-driven / parallel fanout / phase / structured output / declarative orchestration / Run 实体 / Live status push），但**这些哲学 SkillForge OPT-REPORT-V1 已经实现了大部分**：

| Workflow 设计哲学 | SkillForge OPT-REPORT 现状 |
|---|---|
| `parallel(thunks)` barrier 并发 | report-generator STEP 3 同 turn 多 SubAgent dispatch + STEP 4 等齐 |
| Schema-driven structured output | `summary_json` 严格 schema (V102) |
| 显式 Run 实体 | OptReportEntity ✓ |
| Phase 进度显示 | OptReports.tsx polling 5s + WS push |
| `agent()` 调用复用 system prompt 角色 | report-generator + session-batch-annotator system prompt |
| Resume from runId | OptReportEntity status 状态机持久化 |

→ **真正缺的**：把这套从 OPT-REPORT 一家扩展到 attribution / memory（本需求 V1 范围聚焦 memory）。

### 研究材料保留

[research-docs/research/claude code 源码/08 Workflow 工具与编排指南.md](../../../../../research-docs/research/claude%20code%20%E6%BA%90%E7%A0%81/08%20Workflow%20%E5%B7%A5%E5%85%B7%E4%B8%8E%E7%BC%96%E6%8E%92%E6%8C%87%E5%8D%97.md) — 这份研究是有价值的（理解 Workflow 工具机制 / 7 编排模式），即使本需求不直接用 Workflow 工具。

---

## 6. 不解决什么（V1 非目标 / V2+ 范围）

- **不重新设计 SystemAgent prompt 自由文本**——3 个 orchestrator 的 prompt 大部分内容保留，**只改 SubAgent dispatch 那一段**改用 framework 工具
- **不替代 OptimizationEvent**——OptimizationEvent 业务原子事件 + stage machine 完全保留；FlywheelRunEntity 是"运行实例"，OptimizationEvent 是"业务事件"，两者通过 step_run.optimization_event_id 关联
- **不引入新编排语言（YAML DSL 等）**——OrchestratorAgentExecutor 是 Java framework class，不引入 DSL 解析
- **不一次性接管所有 3 个 orchestrator**——V1 只接管 memory-curator 验证 framework 可行性。report-generator + attribution-dispatcher V2+ 独立包
- **不做 Variant fork**——留 V4
- **不引入 GUI 编辑**——dashboard read-only，编辑 SystemAgent prompt 还是改 SQL UPDATE
- **不动 attribution / metrics 入口** —— V1 只动 memory（避免一次太多 orchestrator 改造）

---

## 7. 未澄清问题（详见 [`prd.md` Q1-Q5](prd.md)）

简列：

- Q1 rename migration 实现方式（ALTER vs 新建+迁移+drop）
- Q2 OrchestratorAgentExecutor 实现模式（template / interface / SPI）
- Q3 `DispatchOrchestrationStep` 工具替代 SubAgentTool 全部用法吗
- Q4 All Flywheel Runs page 跟 OPT-REPORT Reports tab page 关系
- Q5 trigger_source / input_json schema 细节

---

## 8. 优先级 + 风险信号

- **优先级**：用户 2026-05-27 推动 + 当前 active 队列有 DREAMING-MEMORY-EXTENSION (prd-draft, P1)，本需求 P2，**Sprint 3 前置依赖 DREAMING V1 ship**
- **风险信号**：触红灯（OPT-REPORT 子系统 + entity rename + 3 orchestrator prompt 改造 + 跨模块 + brief >800 字 + 5 sprint），**Full pipeline 强制 + 多 phase 灰度**
- **跟 DREAMING-MEMORY-EXTENSION 的协作**：DREAMING V1 改 memory-curator prompt 行为（V95 升级到 memory + sessions[]）；本需求 Sprint 3 改 memory-curator prompt SubAgent dispatch 段用 framework。**两者改同一个 prompt → 必须 DREAMING V1 先 ship + 稳定 1 周后才能启动 Sprint 3**
