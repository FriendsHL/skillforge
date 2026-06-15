# AUTOEVOLVING-MASTER — autoEvolving 总需求包

> **创建**：2026-05-28（mental model 对齐）
> **最近更新**：2026-05-29（V1 DSL 选型 ratify Rhino + L1 sandbox + 工程量 ~4.5 周）
> **状态**：master overview ratified；**V1 已交付**（见 [archive/2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD](../../archive/2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/)）；V2-V5 待启动
> **思想根**：[Karpathy autoresearch](https://github.com/karpathy/autoresearch)（630 行 Python + MIT 53.5k stars）—— 7 抽象 + 5 哲学，详见 [`research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md`](../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md)
> **目标**：让 SkillForge 的 skill / prompt / tools / rules / agent / 框架本身按 Karpathy 哲学**自动持续进化**，整合现有 14-stage flywheel + memory + autoResearch + A/B + canary + Iron Law 等能力到一个**可观测的整体面板**

---

## 1. 三个核心进化点（user 2026-05-28 明确）

| # | user 原话 | 解读 | 当前能力 |
|---|---|---|---|
| **痛点 1** | "memory 的梳理整合" | DREAMING V1 已 ship（memory-curator + transcript dreaming + t_memory proposal gate） | ✅ V0 已有 |
| **痛点 2** | "通过用户 session 信息 对当前使用 agent 的各种建议（创建 skill / skill 优化 / tools 优化 / prompt 优化 / 增加 rules）" | OPT-REPORT-V1 + 14-stage flywheel 已 ship，缺整合面板 + outer loop | ⚠️ V0 部分有 |
| **痛点 3** | "使用 autoResearch 思想 对各个功能和能力做打磨（skill / prompt / tools / rules / 当前框架）" | 外部信号源 [AUTORESEARCH-OPTIMIZATION V1](../2026-05-28-AUTORESEARCH-OPTIMIZATION/) PRD 已草，待 V2 接入 | ❌ 缺 |

---

## 2. autoEvolving 父伞结构

```
                        autoEvolving 父伞
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
   ① 进化对象              ② 信号源               ③ 编排方式
        │                     │                     │
   ┌────┴────┐         ┌──────┼──────┐        ┌────┴────┐
   skill     prompt    production    autoResearch    Agent-driven (现有)
   tools     rules     session       finding         DSL workflow (V1 新)
   agent     框架本身                A/B 结果        定时任务 Quartz (现有)
                                    rejected buffer
        │                     │                     │
        └─────────────────────┼─────────────────────┘
                              ▼
                       ④ 执行/调度
                       14-stage flywheel state machine
                       canary + AUTO_ROLLBACK
                       Iron Law 人审 gate
                       outer epoch loop (V3 + K-4)
                              │
                              ▼
                       ⑤ 可观测
                       t_llm_trace + span 3-kind
                       tengu_* events
                       autoEvolving dashboard /autoevolving ⭐ V1
                       workflow DAG viz panel (复用 FlywheelObservability)
```

---

## 3. 已 ship 零件清单（V1 直接复用）

| 类别 | 零件 | 状态 | autoEvolving 角色 |
|---|---|---|---|
| **数据层** | t_llm_trace + span 3-kind + origin partial index | ✅ | 信号源 #1（production session） |
| **数据层** | t_memory + proposal | ✅ DREAMING V1 | 信号源 #1.5（memory） |
| **数据层** | t_flywheel_run + t_flywheel_run_step (V124 + V125) | ✅ | run/step 抽象（V1 DSL workflow 持久化复用）|
| **执行** | 14-stage flywheel state machine | ✅ | 主执行链路 |
| **执行** | 4 surface 独立 A/B（skill / prompt / behaviorRule / sandbox） | ✅ | 进化对象隔离 |
| **执行** | canary + AUTO_ROLLBACK | ✅ | 执行保险 |
| **执行** | EVOLUTION_FORK + 8 SkillSource | ✅ | identity 保留 |
| **执行** | Iron Law 人审 gate | ✅ | 业内独家 |
| **Sub-agent 调度** | SubAgentRegistry + CollabRunService + ChatService.chatAsync + SubAgentRunEntity + SubAgentStartupRecovery | ✅ | ⭐ V1 DSL `agent()` 原语直接套 |
| **进化对象** | memory consolidation（autoDream + memory-curator） | ✅ DREAMING V1 | 痛点 1 |
| **进化对象** | OPT-REPORT-V1（session → suggestion） | ✅ | 痛点 2 部分 |
| **DAG viz 已实现** | reactflow + dagre 已用在 `FlywheelObservability` page（`Insights > Optimization Loop` tab） | ✅ | ⭐ V1 workflow DAG viz panel 换数据源即可 |
| **调度** | ScheduledTaskExecutor + ScheduledTaskService + UserTaskScheduler + cron 框架 | ✅ | V1 workflow 启动入口 |
| **沙箱** | CodeSandboxTool（process-level）+ eval/sandbox/* + SandboxSurface | ✅ | V1 DSL L1 capability sandbox 参考 |
| **Hook framework** | 22 个 hook 类：LifecycleHookDispatcher / LifecycleHookCompositionService / SystemHookRegistry / MethodHandlerRunner / ScriptHandlerRunner | ✅ | V1 `humanApprove()` 原语借用 |
| **WS broadcast** | UserWebSocketHandler + 多个 WsBroadcaster 模式 | ✅ | V1 dashboard 节点状态实时推 |
| **LLM provider** | ClaudeProvider + OpenAiProvider + LlmStreamHandler + cache | ✅ | V1 schema 强制 + structured output |
| **观测** | t_llm_trace dashboard + LlmTraceStore + Span viz | ✅ | V1 dashboard 异常诊断面板 |

---

## 4. 还缺的零件（V1-V5 落地）

| 缺 | 子需求包 | 优先级 |
|---|---|---|
| **autoEvolving dashboard `/autoevolving`** | [AUTOEVOLVING-V1-DSL-DASHBOARD](../2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/) | V1 ⭐ |
| **DSL workflow engine (Rhino + L1 sandbox)** | [AUTOEVOLVING-V1-DSL-DASHBOARD](../2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/) | V1 ⭐ |
| 外部信号源（autoResearch arm） | [AUTORESEARCH-OPTIMIZATION V1](../2026-05-28-AUTORESEARCH-OPTIMIZATION/) PRD 已草 | V2 |
| K-1 拆 `optimizer_program.md`（SkillDraftService 1430 行硬编码 → meta-mutable markdown） | 新子包待开 | V2 |
| K-2 `complexity_delta` 维度（A/B score `pass - α × complexity_delta`） | 新子包待开 | V2 |
| K-3 `v_experiment_ledger` view（5 列 append-only：commit / metric / complexity / status / description） | 新子包待开 | V2 |
| DSL Phase 2 原语（humanApprove 完整版 + pipeline + workflow 嵌套 + budget）| V1 子包 V2 扩展 | V2 |
| K-4 outer epoch loop（现 14-stage 跑到 promoted 即止，缺自动续轮 + 早停 + falsification） | 新子包待开 | V3 |
| 3 信号源融合（production + autoResearch + rejected buffer → candidate） | V3 设计 | V3 |
| 框架自身进化（CompactionService / Engine / hook framework / `.claude/rules/*` → 提 PR 路径，user M6 ratify 不需 sandbox） | 新子包待开 | V5 |
| SkillsBench 公开打榜 | AUTORESEARCH V1 PRD Phase 4 | V4 |

---

## 5. V1 范围（[详见 V1 子包](../2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/)）

### V1 双核心

#### (a) DSL workflow engine — Rhino + L1 sandbox

**形态**：JavaScript 子集脚本，**严格参考 [Claude Code Workflow](../../../../research-docs/research/claude%20code%20源码/08%20Workflow%20工具与编排指南.md)**（5 原语 + 4 全局 + meta literal + schema 强制 + sandbox 限制 + agent 间无通信）。

**6 原语**（V1 实现）：`agent()` / `parallel()` / `pipeline()` / `phase()` / `log()` + **SkillForge 独家 `humanApprove()`**。

**实现栈**：Rhino（Mozilla JS engine 纯 Java ~5MB，**不是** GraalVM）+ L1 capability sandbox（ClassShutter 禁所有 Java 类 + instruction count cap + 单 workflow timeout 30min + agent call budget 1000）。

**workflow 动态加载**：`.workflow.js` 文件，改完 re-load 立刻生效（不重启服务），支持 agent 自己写 workflow。

#### (b) autoEvolving dashboard `/autoevolving`

- **KPI 卡**：当周 promoted / rolled_back / running + autoResearch finding pending + memory proposal pending
- **3 信号源面板**（并列展示，即使 V1 还没融合）：
  - ① production session（OPT-REPORT 最近 4 周 + 14-stage 各阶段 candidate 数）
  - ② autoResearch finding（V2 接入，V1 显示 placeholder「AUTORESEARCH V1 to ship」）
  - ③ memory proposal（接 DREAMING V1 t_memory_proposal）
- **workflow DAG viz panel**（复用 FlywheelObservability + reactflow + dagre）：显示当前在跑 workflow 节点状态 + 历史 run
- **异常诊断面板**：t_llm_trace + tengu_* events 聚合最近 24h anomaly
- **手动触发按钮**（M5 ratify）：列 workflow 库 + 点击触发；后期可自动 cron

### V1 不做

- ❌ outer loop（V3 K-4）
- ❌ K-1~K-4（V2-V3）
- ❌ DSL Phase 2 原语（humanApprove 简化版 + workflow 嵌套 + budget 留 V2）
- ❌ 3 信号源融合（V3）
- ❌ 框架自进化（V5）
- ❌ 改 14-stage state machine（V3 才动）
- ❌ AUTORESEARCH 数据接入（V2，V1 留 placeholder）

### V1 估时 ~4.5 周

| Sprint | 工作 | 周 |
|---|---|---|
| **Sprint 1** | Rhino 集成 + L1 capability sandbox（ClassShutter + instruction cap + budget）+ 6 原语 host binding + hello-world workflow + 安全审计 | 1.5 |
| **Sprint 2** | humanApprove + Schema 强制 + journal/resume + ConsolidationLock | 1 |
| **Sprint 3** | OPT-REPORT 改造为 demo workflow（保留 agent-driven fallback）+ workflow DAG viz panel（复用 FlywheelObservability + reactflow + dagre）+ 真活验证 | 1 |
| **Sprint 4** | dashboard `/autoevolving` page + 3 信号源面板 + 异常诊断 + KPI 卡 + dogfood | 1 |
| **小计** | | **~4.5 周** |

---

## 6. 完整路线图 V0 → V5

| 版本 | 工作 | 子需求包 | 估时 |
|---|---|---|---|
| **V0** ✅ ship | Sprint 1 V124/V125 schema + OPT-REPORT-V1 + DREAMING V1 + 14-stage + canary + Iron Law + memory-curator + autoDream | 历史交付 archive | — |
| **V1** ⭐ 待启动 | (a) **DSL Phase 1**（Rhino + L1 sandbox + 6 原语 + humanApprove + journal/resume）+ (b) **autoEvolving dashboard**（/autoevolving + 3 信号源面板 + workflow DAG viz + 异常诊断 + 手动触发）+ OPT-REPORT 改造为 DSL workflow（保留 agent-driven fallback） | [AUTOEVOLVING-V1-DSL-DASHBOARD](../2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/) | **~4.5 周** |
| **V2** | (a) [AUTORESEARCH V1](../2026-05-28-AUTORESEARCH-OPTIMIZATION/)（外部信号源接 V1 dashboard placeholder）+ (b) K-1 拆 `optimizer_program.md` + (c) DSL Phase 2 完整原语（humanApprove 完整版 + workflow 嵌套 + budget 自适应）+ (d) K-2/K-3 可选 | 子包独立 | **6-8 周** |
| **V3** | **K-4 outer loop** + 3 信号源融合（production + autoResearch + rejected buffer → candidate）+ falsification + predicted_impact + 早停 | 新子包 | **4-6 周** |
| **V4** | **SkillsBench 公开打榜**（86 tasks × 11 domains）+ Pareto frontier (Sentient EvoSkill) + 对外对比报告 | 子包 | **2-3 周** |
| **V5** | **框架自进化**（CompactionService / Engine / hook framework / `.claude/rules/*` 等代码层，user M6 ratify 不需 sandbox 走 PR 路径）| 新子包 | **4-6 周（不急）** |
| **V99** | Bilevel Autoresearch（`optimizer_program.md` 自我演化） | 实验性 | 待定 |
| **Total V1-V5** | | | **~5-6 个月** |

---

## 7. Karpathy 哲学映射

详见 [karpathy-autoresearch-thinking wiki](../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md)。

| 5 哲学 | autoEvolving 应用 | V1 落地? |
|---|---|---|
| ① 人编程 meta-prompt，不写代码 | optimizer_program.md 拆出 | V2（K-1） |
| ② Simplicity criterion（复杂度负价值）| A/B score 加 complexity_delta | V2（K-2） |
| ③ Single mutable file（每次只改 1 surface）| 4 surface 独立 A/B | ✅ V0 |
| ④ Fixed budget, not fixed result | 同时段 traffic split A/B | ✅ V0 |
| ⑤ LOOP FOREVER，人按停 | scheduled auto-trigger，dashboard 不让 user 每轮点跑（V1 含手动触发，V3 后自动） | V3（K-4） |

| 7 抽象 | autoEvolving 应用 | 现状 |
|---|---|---|
| ① Immutable harness | `t_eval_dataset` + production session origin partial index | ⚠️ 部分（缺 eval 物理隔离） |
| ② Single mutable artifact | 每次只改 1 个 surface | ✅ 已有 |
| ③ Meta-mutable instruction | `optimizer_program.md` 拆出 | ❌ V2 (K-1) |
| ④ Fixed budget | A/B traffic split / canary 时间窗 | ✅ 已有 |
| ⑤ Single metric | pass_rate / cost / latency 多指标 | ⚠️ V2+ |
| ⑥ Append-only log | t_llm_trace / t_skill_proposal append-only，但缺 5 列简单视图 | ❌ V2 (K-3) |
| ⑦ Status enum + git | 14-stage 复杂 state machine（反方向）+ git 不自动 | ⚠️ V3 (K-4) |

---

## 8. 子需求依赖图

```
V0 (已 ship)
  ├─ Sprint 1 V124/V125 schema (复用作 V1 持久化)
  ├─ DREAMING V1 memory-curator (痛点 1)
  ├─ OPT-REPORT-V1 (痛点 2 部分)
  ├─ 14-stage flywheel + canary + Iron Law
  ├─ SubAgentRegistry + CollabRunService + ChatService (V1 agent() 原语套用)
  ├─ FlywheelObservability + reactflow + dagre (V1 DAG viz 套用)
  └─ ScheduledTaskExecutor (V1 workflow 启动入口)
            ↓
V1 ⭐ AUTOEVOLVING-V1-DSL-DASHBOARD (本包重点，~4.5 周)
  ├─ DSL engine (Rhino + L1 sandbox)
  ├─ 6 原语 host binding
  ├─ humanApprove 独家原语
  ├─ journal + resume
  ├─ autoEvolving dashboard /autoevolving
  └─ OPT-REPORT 改造为 demo workflow
            ↓
V2 (并行 4 子包)
  ├─ (a) AUTORESEARCH V1 (外部信号源接 V1 dashboard placeholder)
  ├─ (b) K-1 optimizer_program.md (拆 SkillDraftService 1430 行)
  ├─ (c) DSL Phase 2 (humanApprove 完整 + workflow 嵌套 + budget 自适应)
  └─ (d) K-2/K-3 (complexity_delta + ledger view) 可选
            ↓
V3 (K-4 outer loop + 3 信号源融合 + falsification)
            ↓
V4 (SkillsBench 打榜 + Pareto)
            ↓
V5 (框架自进化，PR 路径，不急)
```

---

## 9. M1-M7 ratify 决策（user 2026-05-29 拍）

| # | 决策点 | user 拍板 |
|---|---|---|
| **M1** | 需求包梳理 | 归档 OPT-LOOP-FRAMEWORK active → archive；AUTORESEARCH-OPTIMIZATION 改为 V2 子需求；新建 AUTOEVOLVING-V1-DSL-DASHBOARD；保留 AUTOEVOLVING-MASTER 作总包 |
| **M2** | V1 dashboard page 命名 | ✅ `/autoevolving` |
| **M3** | V1 是否必须等 AUTORESEARCH ship | ✅ 否，留 placeholder「AUTORESEARCH V1 to ship」 |
| **M4** | V2 DSL 跟 (b) AUTORESEARCH 顺序 / DSL 是否提前到 V1 | ✅ **DSL 提前到 V1**（user 说"前提条件" + DSL 跟 agent-driven 差距偏大在可观测性 + K-4 outer loop V3 适应）|
| **M5** | V1 dashboard 是否含「手动触发 candidate / workflow」按钮 | ✅ 含（"目前先有手动触发的形式，后续可以随时变成 auto"）|
| **M6** | 框架自进化 V5 是否真做 | ✅ 要做但不急；user 已 ratify 不需 sandbox（走 PR 路径）|
| **M7** | DSL workflow 在 V1 dashboard 的角色 | ✅ V1 必须（含 workflow DAG viz panel 复用 FlywheelObservability） |

---

## 10. DSL 选型决策（user 2026-05-29 ratify）

详见 [V1 子包 tech-design](../2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/tech-design.md)。摘要：

**形态**：严格参考 [Claude Code Workflow](../../../../research-docs/research/claude%20code%20源码/08%20Workflow%20工具与编排指南.md)（JS 子集 + meta literal + 5 原语 + 4 全局 + schema 强制 + sandbox + agent 间无通信）。

**实现**：**Rhino** （Mozilla JS engine，纯 Java，~5MB，开源 20 年生产稳定）+ host bindings 暴露 6 原语，**不是** GraalVM（overkill）/ Java DSL builder（不能动态加载）/ YAML declarative（失去 dynamic 表达力）。

**沙箱 L1**：capability-based —— Rhino ClassShutter 禁所有 Java 类 + instruction count cap + 单 workflow timeout 30min + agent call budget 1000。**不需要 process-level sandbox**，因为 host 只暴露 6 原语，workflow JS 走不出去。

**动态加载**：`.workflow.js` 文件改完 re-load 立刻生效，支持 agent 自己写 workflow。

**工程量**：~4.5 周（之前估 ~7-8 周，实际重估复用 SkillForge 现有零件 + Rhino 取代 GraalVM 后 −3 周）。

---

## 11. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| V1 dashboard 只是"现有能力拼接"，user 觉得没新价值 | 高 | dashboard 必须能"发现具体问题"（user 第 4 点 E），异常诊断面板是关键，不能只 KPI 卡 |
| Rhino 集成意外（沙箱漏洞 / 性能 / Spring 冲突） | 中 | Sprint 1 头 3 天做 spike 跑通最小 hello-world workflow + 安全审计 |
| K-1 拆 `optimizer_program.md` 牵动 SkillDraftService 1430 行 | 中 | V2 切分小步：先抽 1 个 surface 策略到 markdown，验证可行再扩 |
| autoEvolving dashboard 跟现有 `/insights` / Memory / Reports tab 数据源重叠困惑 | 中 | dashboard 改 layout：autoEvolving 作"总览"页，原 page 作 deep-dive |
| 框架自进化 V5 失控（agent 自动改 AgentLoopEngine 引入 bug） | 高 | user 已确认走 PR 路径（不 hot-reload），PR review 是天然 gate；Iron Law + 单测覆盖红线 |
| V1 dashboard 的 autoResearch panel 在 AUTORESEARCH V1 未 ship 时显空 | 低 | placeholder + "AUTORESEARCH V1 to ship in V2" 提示 |
| workflow 由 agent 写出错 / hallucinate 危险代码 | 中 | L1 capability sandbox（agent 只能调 6 原语，**没有 fs / fetch / process / require / 无任何 Java class 访问**），即使写错最多搞砸自己 |

---

## 12. Iron Law check

- V1 dashboard 是 **read-only viewing layer**，不改任何执行链路
- DSL workflow 提供新的编排能力，但**初期仅用于 OPT-REPORT demo 改造**，不动现有 14-stage state machine
- 不动 4 surface A/B 流程
- 不动 Iron Law 人审 gate（DSL `humanApprove()` 原语是 SkillForge 独家加，跟现有 t_memory_proposal approve 模式同源）
- 不动 OPT-REPORT / DREAMING / canary / AUTO_ROLLBACK
- 不动 V124/V125 schema（V1 直接复用 t_flywheel_run / t_flywheel_run_step）

---

## 13. 文档清单（本 master 包）

- `index.md`（本文）— master overview
- 后续待 V2-V5 启动时补：
  - `roadmap.md`（V2-V5 详细 sprint 划分 + 依赖图）
  - `signals-fusion.md`（V3 3 信号源融合设计）
  - `surface-self-evolution.md`（V5 框架自进化设计）

子需求包各自独立目录：
- [`../2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/`](../2026-05-29-AUTOEVOLVING-V1-DSL-DASHBOARD/) — V1
- [`../2026-05-28-AUTORESEARCH-OPTIMIZATION/`](../2026-05-28-AUTORESEARCH-OPTIMIZATION/) — V2 (a)
- 未来子包待开

历史 reference:
- [`../../archive/2026-05-29-OPT-LOOP-FRAMEWORK/`](../../archive/2026-05-29-OPT-LOOP-FRAMEWORK/) — autoEvolving 前身（Sprint 1 ship + Sprint 2/4 rolled back）+ delivery-receipt.md
