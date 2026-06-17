# AUTOEVOLVING V1 — DSL workflow engine + autoEvolving dashboard

> **创建**：2026-05-29
> **状态**：done — **Sprint 1-4 全部已交付**（2026-05-29，commit Sprint1 `9000bd5` / Sprint3 `b675ee7` / Sprint4 `85ff279` + LLM fail-fast `9049ef8`）= dashboard `/autoevolving` 端到端可测。已交付，见 [delivery-index](../../../delivery-index.md)。
> **父需求包**：[AUTOEVOLVING-MASTER](../../active/2026-05-28-AUTOEVOLVING-MASTER/) (V1 子需求)
> **Anthropic 对照**：[anthropic-comparison-review-2026-05-29.md](anthropic-comparison-review-2026-05-29.md) —— V1 DSL ~90% 对齐 Anthropic Dynamic Workflows，0 P0，humanApprove 是合法 divergence
> **估时**：~4.5 周（4 sprint）；Sprint 1 ✅（engine+sandbox+6 原语+offload）/ Sprint 2 ✅（humanApprove journal-replay + schema + REST Controller + V127）/ Sprint 3 ✅（OPT-REPORT DSL demo workflow + WorkflowSkillRegistryFactory + V128 seed agents + DAG viz panel + Insights Workflows tab）/ **Sprint 4 待续**（dashboard /autoevolving + 3 信号源面板 + 手动触发 + humanApprove review card 复用 ask_user 卡片 = user 端到端测试 milestone）

## 30 秒摘要

V1 双核心交付：

1. **DSL workflow engine** — 严格参考 [Claude Code Workflow](../../../../research-docs/research/claude%20code%20源码/08%20Workflow%20工具与编排指南.md)（JS 子集 + 6 原语 + schema 强制 + sandbox）。实现栈：**Rhino**（Mozilla JS engine 纯 Java ~5MB）+ L1 capability sandbox。支持 `.workflow.js` 文件**动态热加载**（不重启服务），agent 可以自己写 workflow。
2. **autoEvolving dashboard `/autoevolving`** — KPI 卡 + 3 信号源面板（production / autoResearch placeholder / memory）+ workflow DAG viz panel（复用现有 FlywheelObservability + reactflow + dagre）+ 异常诊断面板 + 手动触发 workflow 按钮。

## 文档清单

- [`index.md`](index.md)（本文）— V1 入口 + 摘要
- [`prd.md`](prd.md) — 目标 / 非目标 / FR / AC / 决策记录
- [`tech-design.md`](tech-design.md) — Rhino 集成 + 6 原语实现 + 沙箱 L1 + dashboard tech
- [`dsl-syntax.md`](dsl-syntax.md) — DSL 语法参考（你写 workflow 时查这个）

## V1 范围

详见 [`prd.md`](prd.md)。摘要：

- 双核心 = DSL engine + dashboard
- OPT-REPORT 改造为 demo workflow（保留 agent-driven fallback 防 regression）
- 复用：FlywheelRunService / SubAgentRegistry / ChatService / FlywheelObservability / reactflow+dagre / ScheduledTaskExecutor / Hook framework
- **不做**：outer loop（V3 K-4）/ K-1~K-4（V2-V3）/ DSL Phase 2 humanApprove 完整版 / workflow 嵌套 / 3 信号源融合（V3）/ 框架自进化（V5）/ AUTORESEARCH 数据接入（V2，V1 留 placeholder）

## 估时拆分

| Sprint | 工作 | 周 |
|---|---|---|
| **Sprint 1** | Rhino 集成 + L1 capability sandbox（ClassShutter + instruction cap + budget）+ 6 原语 host binding + hello-world workflow + 安全审计 | 1.5 |
| **Sprint 2** | humanApprove（简化版）+ Schema 强制 + journal/resume + ConsolidationLock Java 实现 | 1 |
| **Sprint 3** | OPT-REPORT 改造为 demo workflow（保留 agent-driven fallback）+ workflow DAG viz panel（复用 FlywheelObservability）+ 真活验证 | 1 |
| **Sprint 4** | dashboard `/autoevolving` page + 3 信号源面板 + 异常诊断面板 + KPI 卡 + 手动触发 button + dogfood | 1 |
| **小计** | | **~4.5 周** |

## D 决策（user 2026-05-29 ratify）

| # | 决策 | 选 |
|---|---|---|
| **D1** | DSL 形态 | JS 子集脚本，严格参考 Claude Code Workflow 形态 + 原语 + 哲学 |
| **D2** | DSL 实现栈 | **Rhino**（Mozilla JS engine 纯 Java ~5MB），不是 GraalVM（overkill）/ Java DSL builder（不能动态加载）/ YAML（失去 dynamic 表达力） |
| **D3** | 沙箱保护层级 | **L1 capability-based** = Rhino ClassShutter 禁所有 Java 类 + instruction count cap + 单 workflow timeout 30min + agent call budget 1000；不需 process-level sandbox（CodeSandboxTool）|
| **D4** | dashboard 入口 | `/autoevolving` 新 page（M2 ratify） |
| **D5** | workflow 触发方式 | V1 含手动触发按钮（M5 ratify），cron 自动触发推 V2+ |
| **D6** | OPT-REPORT 改造方式 | 提供 DSL workflow 实现 + **保留 agent-driven fallback**（生产 OPT-REPORT-V1 0 regression）|
| **D7** | autoResearch 数据接入 | V1 不接入（M3 ratify），dashboard 留 placeholder「AUTORESEARCH V1 to ship」 |

## Q 决策（user 2026-05-29 拍）

| # | Q | 决策 |
|---|---|---|
| **Q1** | DSL workflow 文件存哪 + 谁能写 | ✅ **仓库文件 + agent 写走 review 队列**：`.workflow.js` 存 `skillforge-server/src/main/resources/workflows/`，hot-reload。agent 写的新 workflow 先进 review 队列（status=pending），admin dashboard approve 后才注册可跑（Iron Law gate）。V3+ 加 DB 存储 + 编辑器 |
| **Q2** | workflow run 失败 retry 策略 | ✅ 默认提案：单 workflow 总 timeout 30min；agent() 调用失败重试 1 次（网络错）；schema 验证失败重试 3 次（同 Claude Code Workflow） |
| **Q3** | crash 时是否 resume | ✅ **异常情况（crash/OOM/server 重启 in agent()）不 resume，挂了重跑**（旧 run 标 failed，user 手动重新触发 = 新 run 从头）。**但 human-in-the-loop 暂停需要 resume**（见下） |
| **Q4** | humanApprove 形态 + resume | ✅ **暂停 + 推 WS + dashboard 默认 review 卡 + approve/reject → resume**。**用 journal-replay 持久化**（扛 server 重启 + 人等多久都行）。不做 uiTemplate / multi-reviewer（V2 完整版） |
| **Q5** | workflow 触发权限 | ✅ **admin only 触发 + agent 写走人审**。V2 加细粒度 RBAC |

### Q3 × Q4 交互决策（关键实现选型）

两种 resume 语义**分开处理**：

| 场景 | 机制 | server 重启时 |
|---|---|---|
| **正常 agent() 完成** | result 持久化 `t_flywheel_run_step.step_output_json`（V1 就做）| — |
| **humanApprove 暂停** | 抛 `WorkflowPausedException` 退出线程 + 持久化 paused state（**不 park 线程**）。人点 approve（即使隔天 / 中间 server 重启过）→ 重跑 workflow + **journal cache 跳过已完成 agent()** → 跑到 humanApprove 拿 decision 继续 | ✅ 能 resume（状态在 DB） |
| **crash / OOM（非 humanApprove）** | 不自动 resume。旧 run 标 failed，user 手动重新触发 = 新 run 从头跑（不读 journal）| 挂了重跑 |

**关键**：journal cache V1 就要做（**只用于 humanApprove resume**），不是 V2。它不用于 crash recovery —— crash 后 user 重新触发是新 run 从头。

**好处 vs park-thread**：human-in-the-loop 等待不占线程（状态在 DB）+ 扛 server 重启/部署 + 人隔多久来点都行。

## 接下来

1. ✅ Q1-Q5 已拍
2. 起 Plan pipeline（Full pipeline 触红灯：跨 server / dashboard / 多模块 + Rhino sandbox 设计 + humanApprove park 线程模型）
3. Sprint 1 启动
