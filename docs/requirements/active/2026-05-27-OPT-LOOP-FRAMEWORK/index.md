# OPT-LOOP-FRAMEWORK — 飞轮编排框架抽取 + Run 实体泛化（基于 OPT-REPORT-V1 现成模式扩展）

> 创建：2026-05-27
> 状态：**prd-draft**（PRD 已草拟，等用户 ratify D1-D5 + 回答 Q1-Q5 后开 Plan pipeline）
> 模式：Full pipeline（触红灯：核心 OPT-REPORT 子系统 + OptReportEntity rename + 抽 framework 重构 + 跨模块 + brief >800 字）
> 触发：2026-05-27 用户提"飞轮编排太固定 / 看不到进度 / 想 fork variant"，多轮 brainstorm 后发现**飞轮已有 3 orchestrator agent**（attribution-dispatcher / memory-curator / report-generator），但**编排逻辑各自在 prompt 里手写 SubAgent dispatch 段 + Run 实体只 OPT-REPORT 一家有**——重复代码 + 观测性不一致

## 阅读顺序

1. 当前 `index.md`（本文）— 摘要 + 5 决策 + V1 sprint 划分
2. [`mrd.md`](mrd.md) — 用户原话 + Explore 两次摸底结果 + 旧 DSL 方向作废原因 + 痛点拆解
3. [`prd.md`](prd.md) — 目标 / 非目标 / 工作流 / FR / AC / 5 D 决策 + 5 Q 澄清
4. [`tech-design.md`](tech-design.md) — Run 实体 schema 改造 + OrchestratorAgentExecutor 设计 + Migration 顺序（PRD ratify 后深化）

## 调研来源

- **用户原话**（2026-05-27 对话）— 飞轮 V1-V6 已交付但用户感觉"太固化 / 看不到进度 / 想 fork variant 实验"，**额外补**："当前几个 system agent 是通过另一个 agent 编排的。然后 整体的数据清洗+评测+归因 是通过这种方式传起来的。可以参考下 agent 页面里面的 generation report 这个功能"
- **Explore agent 摸底 #1**（V95 6 SystemAgent + cron 模型）— **不完整**，漏掉 V97 OPT-REPORT 系统
- **Explore agent 摸底 #2**（V97 OPT-REPORT-V1 全貌 + 飞轮 orchestrator 模式现状）— 关键发现：① 飞轮已有 3 orchestrator (attribution-dispatcher V95 / memory-curator V95 / report-generator V97)；② OptReportEntity 已经是 Run 实体 95% 完成（缺 trigger_source + input_json 2 列）；③ OptReportBatchEntity 已经是 nested step_run；④ `convertIssueToEvent` 已经是 human-in-loop gate
- **旧需求包 [FLYWHEEL-ORCHESTRATION-DSL](../../../requirements/) 已作废**（基于错的现状理解，git checkout 丢掉）— YAML DSL 方向假设飞轮是"6 独立 cron 散写编排"，实际是"3 orchestrator agent 用 SubAgent fanout"，DSL 重新发明 SkillForge 已有机制
- **Claude Code Workflow 工具研究** — [research-docs/research/claude code 源码/08 Workflow 工具与编排指南.md](../../../../../research-docs/research/claude%20code%20%E6%BA%90%E7%A0%81/08%20Workflow%20%E5%B7%A5%E5%85%B7%E4%B8%8E%E7%BC%96%E6%8E%92%E6%8C%87%E5%8D%97.md)（设计哲学：schema-driven / parallel fanout / 显式 Run / Live status 推送——这些 SkillForge 飞轮已经具备，但不统一）

## 5 个 ratify 决策（开 Plan 时再次确认 + 细化）

| # | 决策 | 选 |
|---|---|---|
| **D1** | Run 实体演进策略 | **OptReportEntity 改名 FlywheelRunEntity + 补 `trigger_source` (cron/user_manual/api/event) + `input_json` (JSONB)**。OPT-REPORT-V1 现有 9 字段保留，rename 改名向下兼容（视图 `t_opt_report` 留 1 个版本兜底）。不双表共存 |
| **D2** | step_run 实体演进 | **OptReportBatchEntity 改名 FlywheelRunStepEntity**。OPT-REPORT-V1 现有 batch 概念（1 batch = 1 SubAgent session）泛化为通用 step；保留 batch_id 字段（语义为该 step 的 batch grouping，可空） |
| **D3** | OrchestratorAgentExecutor framework 抽取 | 抽 Java framework class（不动 prompt 自由文本）。framework 提供 `dispatchSubAgents(workers, dispatchInput) → Future<List<StepResult>>` API + 自动写 FlywheelRunStepEntity。3 个 orchestrator agent 的 prompt 末尾从"手写 SubAgent dispatch"改为"调 framework 工具"（用法跟 SubAgent 工具一样，但内部多写 step_run 行 + 状态追踪） |
| **D4** | V1 范围 | **Sprint 1**: OptReportEntity rename + schema migration + 兼容视图; **Sprint 2**: 抽 OrchestratorAgentExecutor framework; **Sprint 3**: memory-curator 复用 framework + 写 FlywheelRunEntity（替代手写 SubAgent dispatch 段）; **Sprint 4**: dashboard 加 "All Flywheel Runs" page; **Sprint 5**: 测试 + 灰度 + dogfood 1 周 |
| **D5** | dashboard 页面策略 | 新加 **All Flywheel Runs page** 列所有 FlywheelRunEntity（不分 loop_kind），按 status / agentId / loop_kind filter。**保留** OPT-REPORT 现有 Reports tab page（不动），FlywheelFlowchart 现有 Insights tab 不动。两个面板**共享底层数据源**（都查 FlywheelRunEntity），但视角不同 |

> **D1 关键**：rename `OptReportEntity → FlywheelRunEntity` 是 risk 操作，会影响 OPT-REPORT-V1 现有 dashboard / WS push / REST endpoint。Sprint 1 先做 migration + 视图 + entity rename（不动业务逻辑），跑稳定后再继续 Sprint 2

## 核心交付（V1，待开工时细化）

参见 [`prd.md`](prd.md) 验收点 + [`tech-design.md`](tech-design.md) 实现拆分。简略：

- **Sprint 1 — Run 实体泛化**（M / 触 OPT-REPORT 子系统 + OptReportEntity rename）
  - F1 新 Flyway migration：`t_opt_report → t_flywheel_run` rename + 补 `trigger_source VARCHAR(32)` + `input_json JSONB`
  - F2 `t_opt_report_batch → t_flywheel_run_step` rename + 字段映射（`session_ids_json → step_input_json` / `annotations_written_count → step_output_summary_json` 含 count）
  - F3 Entity 类 rename + Repository / Service 引用更新（OptReport** → FlywheelRun**）
  - F4 兼容视图 `t_opt_report` view → `t_flywheel_run WHERE loop_kind='opt_report'` 兜底 1 个 release 防外部依赖

- **Sprint 2 — OrchestratorAgentExecutor 抽取**（L / 新模块 + 跨现有 3 orchestrator agent）
  - O1 新 `flywheel/orchestrator/OrchestratorAgentExecutor.java`：API `dispatchSubAgents(parentRunId, workerSpecs)` 返 `Future<List<StepResult>>`，自动落 FlywheelRunStepEntity
  - O2 新 `flywheel/orchestrator/StepResult.java` record / `OrchestratorWorkerSpec.java` record
  - O3 新 Tool `DispatchOrchestrationStep`：让 orchestrator agent prompt 改用此工具替代手写 SubAgent dispatch 段（语义跟 SubAgent 工具基本一致，但自动落 step_run 行 + 关联 parentRunId）
  - O4 现有 SubAgentTool 保留（不破坏）— `DispatchOrchestrationStep` 是 SubAgentTool 的 Run-aware 增强版

- **Sprint 3 — memory-curator 接入 framework**（M / 改 V95 prompt + 现有 cron 入口）
  - M1 V95 memory-curator prompt 改造：STEP 2 SubAgent dispatch 段改用 `DispatchOrchestrationStep` 工具
  - M2 MemoryConsolidationScheduler 入口：每次 cron fire 创建一行 FlywheelRunEntity（loop_kind='memory_curation' / trigger_source='cron'）+ 把 runId 传给 memory-curator session
  - M3 IT：跑一次 cron 后验 FlywheelRunEntity 含 1 行 root + N 行 step（per user）
  - M4 跟 `DREAMING-MEMORY-EXTENSION` 协调：DREAMING V1 先 ship（前置约束），本 Sprint 3 不冲突

- **Sprint 4 — dashboard All Flywheel Runs page**（M / FE 改造）
  - V1 BE：新 endpoint `GET /api/flywheel/runs` 列 FlywheelRunEntity（filter by status / loop_kind / agentId / 时间窗）+ `GET /api/flywheel/runs/{id}` 详情含 step_run 列表
  - V2 FE：新 page `/flywheel-runs` 列所有 run + filter + 点行进 detail Drawer 展示 step_run timeline
  - V3 FE：WS event `flywheel_run_status_changed` 推送（复用 OPT-REPORT 现有 broadcaster 基建）

- **Sprint 5 — 测试 + 灰度**（M / 综合）
  - T1 IT 覆盖 8 case：rename migration 不破坏 OPT-REPORT-V1 / OrchestratorAgentExecutor minimal use case / memory-curator 真活跑 / dashboard 渲染 / WS push / convertIssueToEvent 不破坏 / 兼容视图查询 / framework 错误处理
  - T2 Dogfood：memory-curator 切 framework 跑 1 周对比新旧产出（diff < 5%）
  - T3 真活验证：dashboard All Flywheel Runs page 跑 1 次 memory-curation + 1 次 generate-report，看 latency / 状态切换 / step timeline

## V2+ 范围（V1 ship 1-2 周后判断启动节奏）

- **V2**: attribution-dispatcher / report-generator 接入 OrchestratorAgentExecutor framework（独立需求包 `OPT-LOOP-FRAMEWORK-V2-ATTRIBUTION-REPORT`）
- **V3**: dashboard panel 完善（OPT-REPORT page 和 All Flywheel Runs page 数据源统一 / FlywheelFlowchart 数据源切到 FlywheelRunEntity）
- **V4**: Variant fork — 新 endpoint `POST /api/flywheel/runs/{id}/fork` 复制 input_json + 改某字段重跑，对比执行结果（用户痛点 3）
- **V5**: behavior_rule / Skill AB 也接入 framework（统一 OptimizationEvent 链路）
- **V6**: cron 体系统一（`t_scheduled_task` + Spring `@Scheduled` 都跑 framework）

## 验收点（V1，待细化，详见 prd.md AC-1~AC-8）

- **AC-1**: F1+F2 — OptReport** → FlywheelRun** migration + entity rename，OPT-REPORT-V1 现有 dogfood 跑通（mvn -pl skillforge-server -am test BUILD SUCCESS + 真活生成 1 个 report + dashboard 显示无 regression）
- **AC-2**: F4 — 兼容视图 `t_opt_report` 仍可查询，外部依赖（如有）零破坏
- **AC-3**: O1+O3 — OrchestratorAgentExecutor `dispatchSubAgents()` minimal IT case 跑通 + 自动落 FlywheelRunStepEntity
- **AC-4**: M1-M3 — memory-curator 用 framework 跑后，`t_flywheel_run` +1 行 root + N 行 step（per user）+ `t_session_annotation` 写入跟旧路径一致
- **AC-5**: V1+V2 — All Flywheel Runs page 能看到 OPT-REPORT + Memory 两种 loop_kind 的 run，filter 工作正常
- **AC-6**: V3 — WS event `flywheel_run_status_changed` 推送 + FE 实时更新 < 2s
- **AC-7**: T2 — memory-curator dogfood 1 周对比新旧产出 `t_memory_proposal` 行数 diff < 5%
- **AC-8**: T1 IT 覆盖 8 case（详见 prd.md AC-8 矩阵）

## 5 个 Q 澄清（开 Plan 时回答，详见 prd.md）

| # | Q | 影响 |
|---|---|---|
| **Q1** | rename 路径细节：直接 `ALTER TABLE t_opt_report RENAME TO t_flywheel_run`（保 PK）vs 新建 `t_flywheel_run` + 数据迁移 + drop old | migration 复杂度 / 回滚难度 |
| **Q2** | OrchestratorAgentExecutor 实现模式：template method vs interface + impl vs SPI 注册 (`@FlywheelOrchestrator` 注解) | 工程量 + 扩展性 |
| **Q3** | `DispatchOrchestrationStep` 工具是否替代 SubAgentTool 全部用法？还是只在 orchestrator agent prompt 用？ | tool 数量 / 复杂度 |
| **Q4** | All Flywheel Runs page 跟 OPT-REPORT Reports tab page 关系：合并 / 独立 / 联动 deep-link | UI 设计范围 |
| **Q5** | `trigger_source` + `input_json` schema 字段：trigger_source enum 是 4 值 (cron/user_manual/api/event) 还是 5+ 值？input_json 是不是要规定通用字段（agentId / windowDays / ...） | schema 设计 |

## 下一步

1. **用户 ratify D1-D5 决策** + **回答 Q1-Q5 澄清**
2. 开 Plan pipeline（Full 档对抗 review，因为触红灯：OPT-REPORT 子系统 + entity rename + 3 orchestrator prompt 改造 + 跨模块）
3. Plan 通过后 Sprint 1 (F1-F4) 启动，必须先 ship 稳定再继续 Sprint 2

## 关联

- **现状摸底报告**（Explore agent #2 输出，2026-05-27）落在 mrd.md 第 2 节 — OPT-REPORT-V1 全貌 + 飞轮 orchestrator 模式对位
- **Claude Code Workflow 工具研究** — [research-docs/research/claude code 源码/08 Workflow 工具与编排指南.md](../../../../../research-docs/research/claude%20code%20%E6%BA%90%E7%A0%81/08%20Workflow%20%E5%B7%A5%E5%85%B7%E4%B8%8E%E7%BC%96%E6%8E%92%E6%8C%87%E5%8D%97.md)
- **跟 [`active/2026-05-26-DREAMING-MEMORY-EXTENSION/`](../2026-05-26-DREAMING-MEMORY-EXTENSION/index.md)**：共享 memory-curator surface。**前置约束**：DREAMING V1 必须先 ship（改 memory-curator prompt 行为），本需求 Sprint 3 才能启动（改 memory-curator prompt 用 framework）。两者不能同时改
- **跟 [`docs/plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md`](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md)**：飞轮 V1-V6 产生**业务能力**（label / cluster / attribute / propose / AB / promote），本需求**抽编排公共代码 + 统一 Run 观测层**让业务能力可见、可改、可 fork
- **跟历史 OPT-REPORT-V1**：直接基于 OPT-REPORT 现有 Entity + WriteOptReportTool + onReportCompleted WS 推送模式扩展，V97-V107 7 个 migration 的 prompt 演进经验积累成 framework
- **旧作废需求包 FLYWHEEL-ORCHESTRATION-DSL**：已 git checkout 丢掉，但本需求 mrd.md 第 4 节保留"作废原因"作为教训记录（YAML DSL 方向是基于错的现状理解，下次类似 brainstorm 要先精准摸代码现状再下方案）
