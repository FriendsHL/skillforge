# AUTOEVOLVE-AGENT-FLYWHEEL — agent 驱动的自动评测/归因/迭代飞轮 + workflow 操作能力

> **创建**：2026-05-31
> **状态**：done — 已交付（2026-06-01/06-02：BUG-1 winner-carry-forward + 进化 loop 反思 + judge per-case rationale 方案 B）。已交付，见 [delivery-index](../../../delivery-index.md)。
> **父需求包**：[AUTOEVOLVING-MASTER](../../active/2026-05-28-AUTOEVOLVING-MASTER/)（痛点 2 + 痛点 3 的 agent-driven 落地）
> **关联**：[AUTOEVOLVING-V1-DSL-DASHBOARD](../2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/)（已 ship workflow engine + opt-report workflow + /autoevolving，本包**复用**）
> **思想来源**：清华 AHE（Agentic Harness Engineering）`predicted_impact` + 错峰世代（见 [`papers/agentic-harness-engineering.md`](../../../../research-docs/research/agent-harness-wiki/papers/agentic-harness-engineering.md)）

## 30 秒摘要

两条主线：

1. **平台能力**：给平台增加"**agent 能编排 + 执行 workflow**"——`RunWorkflow` tool（name 模式跑已存 / inline 模式现写现跑）。
2. **Auto-Evolving 系统**：一套 **agent 驱动**的自动评测→归因→迭代→自净化 loop。顶层 orchestrator agent 驱动：调 report-workflow 产报告 → 调 A/B tool 评测 → 科学 gate 判断保留 → 循环 N 次 → 人末尾定夺采纳。每轮记**迭代账本** + **轨迹图**可观测"是否真提升 agent"。

**核心思路**：**新增全是薄编排层 + tool 包装 + 可观测；评测算力一行不重写**（复用现有 A/B 评测引擎 / opt-report workflow / improver / promote / ab_run 实体）。

## 架构一图

```
顶层 orchestrator agent（驱动者，非 workflow 子 agent → 防递归）
  │
  ├─① RunWorkflow('opt-report')  → 复用现有 opt-report workflow（拉 session→batch 评估→归因→report）
  │
  ├─② loop（maxIter N 次）：
  │     GenerateCandidate(issue)      → 改 surface 候选（包装 improver service）
  │     TriggerAbEval(candidate)      → 触发现有异步 A/B（返 abRunId）
  │     GetAbResult(abRunId)          → 轮询拿分（baseline 有则跳过重跑）
  │     <科学 gate 判断保留>           → kept?
  │     RecordIteration(...)          → 落迭代账本（改了什么 + 分数 delta + kept）
  │
  ├─③ humanApprove（末尾）           → 人看轨迹图，定夺是否采纳
  │
  └─④ PromoteCandidate(adopted)      → 采纳的真 promote（包装现有 promote，走守卫）

可观测：迭代账本 → 轨迹折线图页（分数 vs 迭代，标注每轮改动）
```

## 5 模块（详见 prd.md / tech-design.md）

> ⚠️ **2026-05-31 architect review 修正**（"别重复造轮子"核查命中多处，详见 tech-design.md REUSE TABLE）：

| 模块 | 目标 | 档位 | 优先级 | review 修正 |
|---|---|---|---|---|
| **A** Workflow 操作能力 | `RunWorkflow` tool（name + inline）| ~~Mid-Full~~ | ~~第一波~~ | **已造好（未提交）**：`RunWorkflowTool.java`(13KB) + `parseInline` + `startRun(def)` 都在。**降级为：验证 + 分配给 orchestrator `tool_ids` + 提交**，近零工作 |
| **B** A/B 封装成 Tool | TriggerAbEval / GetAbResult / PromoteCandidate（薄包装现有引擎）| Full | **第一波** | 复用 auto-trigger listener 的 surface 分派 switch；**B tools 也必须排除出 WorkflowSkillRegistryFactory**（同 A，防递归）|
| **C** Auto-Evolving Orchestrator | orchestrator agent + GenerateCandidate tool + loop + **触发入口**| Full | 第二波 | **extend 现有 `attribution-dispatcher` 模式 + FlywheelChainOrchestrator 链**，非从零设计；**补触发入口**（REST `/evolve-run` 或 ScheduledTask，仿 `FlywheelController run-loop`）—— 原计划漏了"谁启动 orchestrator" |
| **D** 迭代账本 + 轨迹可观测 | ~~新表~~ → **复用 FlywheelRun+Step** + 轨迹页 | Mid-Full | 第三波 | **不新建 `t_evolve_iteration` 表**：复用 `FlywheelRunEntity`(loop_kind=`evolve`)+`FlywheelRunStepEntity`(step_kind=`evolve_iteration`,分数进 step_output_json)；轨迹页复用 `SkillEvalHistoryEntity`(score-over-time 已有)+ /autoevolving 壳,只新建图表 + 图表库 |
| **E** AHE 借鉴（predicted_impact + 证伪）| 候选预测字段 + per-task 对比 + 证伪 rollback | Full | 第四波（进阶）| 真新建（grep 确认 predicted_impact 只在文档无代码）；skill per-scenario baseline 缺(`SkillAbEvalService.java:690` 硬编码)是 E 前置 |

**review 结论**：真新工作收敛到 = **4 个 B/C/D 薄包装 tool + 1 个 orchestrator agent prompt + 1 个轨迹图 + 模块 E**。A 已有、D 账本复用、C orchestrator 仿现有 dispatcher。

## 依赖 + 顺序
```
A（RunWorkflow）─┐
                 ├─→ C（orchestrator）─→ D（账本+轨迹页）─→ E（AHE 进阶）
B（A/B tools）───┘
```
用户定：**先收尾 A + B（地基），再 C，再 D，最后 E。**

## 复用 vs 新建
- **复用现有**：opt-report workflow / A/B 评测引擎（AbEvalPipeline / EvalScoreFormula / 三 surface / dataset）/ improver service（SkillDraft/Prompt/BehaviorRule）/ promote service / ab_run 实体 / /autoevolving 页框架 / WorkflowRunnerService
- **全新建**：RunWorkflow tool / A/B+Promote+GenerateCandidate+RecordIteration tool / orchestrator agent / 迭代账本实体 / 轨迹页 /（E）predicted_impact + 证伪逻辑

## 文档清单
- [`index.md`](index.md)（本文）— 入口 + 摘要 + 模块/依赖/复用
- [`prd.md`](prd.md) — 目标 / 非目标 / 流程 / 各模块 FR / AC / 决策记录
- [`tech-design.md`](tech-design.md) — 各模块技术方案 + 关键决策 + architect 预审发现 + 风险 + 测试计划

## 不做（明确非目标）
- 灰度 canary（v1 先不加，人定夺后直接改配置）
- 多 surface 同时改的 per-task 交互归因（模块 E，进阶）
- 事件驱动旧飞轮的 retrofit（**关掉它的 auto-trigger，建全新 agent 驱动编排**，不来回适配）
- 框架自进化（V5）/ AUTORESEARCH 数据接入（V2 另包）

## 下一步
architect review（边界 / 顺序 / 安全 / 复用核查——确认没重复造轮子）→ 用户过目 → 开发（按 A+B → C → D → E）。
