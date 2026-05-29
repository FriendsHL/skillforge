# AUTOEVOLVING V1 Sprint 4 — Plan (r1)

> V1 收官 Sprint：dashboard `/autoevolving` 总览 page。做完 user 能端到端测试
> (触发 workflow → 看 DAG 实时着色 → humanApprove review card → approve → 完成)。
> Sprint 1/2/3 已 ship（DSL engine + humanApprove journal-replay + REST/WS +
> OPT-REPORT demo workflow + DAG viz）。本 Sprint **重 FE，最小 BE**。

---

## 1. 现状摸底（已 grep 验证）

### 1.1 已 ship 的 workflow REST/WS（数据源 + 触发面）

`WorkflowController` (`/api/workflows`) 已全部就位：
- `GET /api/workflows` → `{items: WorkflowDto[], total}`（name/description/phases）
- `POST /api/workflows/{name}/run` body `{args}` → 202 `{runId, name, status}`，409 if already running
- `GET /api/workflows/runs?status=&limit=&offset=` → `{items, total, limit, offset}`
- `GET /api/workflows/runs/{runId}` → 单对象 `{runId, name, status, summaryJson, errorReason, steps[]}`
- `POST /api/workflows/runs/{runId}/approve` body `{decision:'approved'|'rejected', reason?}` → resume

WS（`WorkflowWsBroadcaster`，走 `broadcastAll`）：
- `workflow_phase` / `workflow_log`（runId + title/message）
- `workflow_human_approve_required`：`{type, runId, stepRunId, stepIndex, status:'paused_for_human_approve', payload}` ← **review card 监听这个弹出**
- `flywheel_run_status_changed`（带 `loopKind`，workflow run 也走这条，用于刷 runs list）

⚠️ **FE `api/workflow.ts` 当前只有 3 个 GET wrapper**（`listWorkflows` / `listWorkflowRuns` / `getWorkflowRun`）。**缺 `runWorkflow` + `approveRun` 两个 POST wrapper —— Sprint 4 必须补**（BE 端点已存在，只差 FE 客户端）。

### 1.2 humanApprove payload 形态（review card 渲染对象）

`HostHumanApprove.call` → `JsConversions.jsToJava(args[0])` → 任意 JS object。
opt-report.workflow.js 在 Approve phase 传的是 **attribution summary JSON**（topIssues 等结构），不是问答。WS payload 字段 = `payload`（已 JS→Java 转换的 Map/List）+ `runId` + `stepRunId` + `stepIndex`。

### 1.3 ask_user card 现状（复用源）

`PendingAskCard.tsx`：props = `pendingAsk {askId, question, context, options[], allowOther}` + `onAnswer(string)`。渲染「问题 + 离散 option 按钮 + 自定义输入框」，**绑定 chat session**（answer 走 chat answerAsk 路径）。CSS 类 `.ask-card / .ask-header / .ask-q / .ask-ctx / .ask-opt*` 定义在 `index.css` (2877+)；旁边 2979 已有「结构类似 .ask-card 但 alarm-red」的注释块（说明该视觉语言已被复用过一次）。

### 1.4 三信号源现有 API

| 信号 | 现有 API | 状态 |
|---|---|---|
| ① production OPT-REPORT | `GET /api/flywheel/agents/{agentId}/reports` → `{items, limit}`（**per-agent，需 agentId**）；single `GET /api/flywheel/reports/{reportId}` | ⚠️ **无跨 agent「最近 N 报告」端点** |
| ① 14-stage candidate | `GET /api/flywheel/runs` → `{items, limit, hideTerminal}`（per-run + stage/surface），无按 stage 聚合 count | ⚠️ 无聚合 count 端点 |
| ② autoResearch | — | placeholder（FR-6.3 占位卡，V2 接入）|
| ③ memory proposal | `listMemoryProposals({userId, status:'proposed', limit})` → **裸 `MemoryProposal[]`** | ✅ 现成 |

### 1.5 异常诊断数据源

`TracesController GET /api/traces?origin=` 返回 trace list（无 time-window / error-only / anomaly 聚合）。`LlmTraceController` / `ToolSpanController` 类似（明细，非聚合）。**无现成「最近 24h anomaly 聚合」端点**。brief 已许可：没有就简化为「最近 failed workflow run + error reason」。

### 1.6 dashboard 结构（整合关系）

- 路由 `App.tsx`：扁平 `<Route path="...">`，需加 `<Route path="autoevolving">`
- nav `Layout.tsx` `primaryNav[]`：末项 `{insights, /insights/patterns, 'Optimization'}`，在它**之后**插 `{autoevolving, /autoevolving, 'Auto-Evolving'}`（FR-6.7）；`paletteItems` 自动从 primaryNav 派生，无需额外改
- `Insights.tsx` 已有 Workflows tab（lazy `WorkflowRunsPanel`，read-only DAG）—— deep-dive 落点
- KPI/信号面板 click 跳：production→`/insights/patterns?tab=reports`、memory→`/memories`、workflow→`/insights/patterns?tab=workflows`

---

## 2. 难题 1 — humanApprove review card 复用方案 ⭐

**结论：仿建（new `WorkflowApproveCard`），复用 ask_user 的 CSS 视觉语言，不复用其组件逻辑。**

理由（对抗约束 A 自查点）：`PendingAskCard` 的契约是「问题 + 离散 options + 单选 answer string」，humanApprove 是「审一坨 attribution summary JSON + 二元 approve/reject + 可选 reason」。payload 形态根本不同，硬塞 `PendingAskCard` 要把 JSON 假装成 question、把 approve/reject 假装成两个 option —— 是 anti-pattern，且 answer 路径完全不同（chat answerAsk vs workflow approve REST）。

**实现：**
- 新组件 `src/components/workflow/WorkflowApproveCard.tsx`：
  - props：`{ runId, stepIndex, payload: unknown, onResolved?: () => void }`
  - 渲染：header（"Workflow needs approval" + workflow name/runId 短码）→ payload 用现有 `JsonViewer`（`<JsonViewer data={payload}/>`）→ 可选 reason `<textarea>` → `Approve` / `Reject` 两个按钮
  - 按钮 → `approveRun(runId, {decision, reason})`（新 wrapper）→ 成功后乐观隐藏卡 + `message.success` + invalidate runs/run query
  - 错误处理：approve 失败（409 已被别人 approve / run 状态变了）→ `message.error` + 重新拉 run detail
- CSS：新建 `workflow.css` 追加 `.wf-approve-card*` 类（**不改 index.css** —— index.css 是 scope-out 全局文件，按 think-before-coding 规则不顺手动；新页样式走页面级 CSS）。视觉上对齐 `.ask-card`（圆角 8px / accent 边 / designed hover），但类名独立避免耦合 chat 样式。

**在 /autoevolving 哪显示**：顶部「pending approvals」区（KPI 卡下方、信号面板上方）。一个 run park 在 gate 就显一张卡。来源双路：
1. 进页时 `listWorkflowRuns({status:'paused'})` 拉当前所有 paused run（每个 run 拉 detail 取最后 pending human_approve step 的 payload）
2. WS `workflow_human_approve_required` 实时 append 新卡（payload 直接在 WS frame 里，无需再查）

⚠️ **时序坑（自查点）**：approve 成功 → run 转 running → 跑到下个 gate 可能再 pause → 再来一条 WS。卡的 key 用 `runId+stepIndex`（stepIndex 单调递增，天然去重，避免同 run 多 gate 串台）。approve 后本地按 `runId+stepIndex` 移除该卡，等新 WS / 新 paused 拉取补。

---

## 3. 难题 2 — /autoevolving 布局 + 面板 + 数据源

**布局**（design.md Linear/Raycast dark precision，bento 而非教科书三栏）：

```
┌─ Header: "Auto-Evolving" + subtitle + [Trigger workflow ▸] 按钮 ───────────┐
├─ KPI strip: 4 卡 (workflow running / promoted-this-week / memory pending /  │
│             autoResearch N/A) —— scale-contrast 大数字 + label + click 跳   │
├─ Pending approvals (仅当有 paused run 时出现): WorkflowApproveCard[]        │
├─ 3 信号源面板 (bento, 并列/响应式折行):                                      │
│   ① Production (OPT-REPORT 最近 N + click→reports tab)                       │
│   ② AutoResearch (placeholder 卡 + link 子需求包)                           │
│   ③ Memory Proposals (pending list + click→/memories)                       │
├─ Workflow DAG viz: 直接嵌 <WorkflowRunsPanel/> (复用, 见难题3)              │
└─ 异常诊断: 最近 failed workflow/flywheel run + error reason (简化版)        │
```

**各区数据源：**

| 区 | 数据源 | 备注 |
|---|---|---|
| KPI workflow running/promoted | 新 `GET /api/autoevolving/overview`（见难题4）| 周窗口 count BE 算 |
| KPI memory pending | `listMemoryProposals({userId,status:'proposed'})`.length（或并进 overview）| 现成 |
| KPI autoResearch | 硬编 `N/A` placeholder | FR-6.2 |
| Pending approvals | `listWorkflowRuns({status:'paused'})` + per-run detail + WS | 见难题1 |
| ① Production | overview.recentReports（跨 agent）| 见难题4 gap |
| ② AutoResearch | 静态占位 | 无 API |
| ③ Memory | `listMemoryProposals` | 现成 |
| DAG viz | `WorkflowRunsPanel`（自带 listWorkflowRuns + getWorkflowRun + WS）| 复用 |
| 异常诊断 | overview.recentAnomalies（failed runs）| 简化 |

**Trigger workflow button（FR-6.6 / AC-10）**：`src/components/workflow/TriggerWorkflowModal.tsx`：
- 点按钮 → `listWorkflows()` 列已注册 workflow（name + phases 预览）
- 选一个 → 表单填 args（V1 简化：JSON textarea 或 opt-report 专用 `{agentId, windowDays}` 字段）→ `runWorkflow(name, {args})`（新 wrapper）
- 成功 202 → `message.success` + 跳/滚到 DAG panel + invalidate runs（实时着色靠 WorkflowRunsPanel 已有 WS + poll）
- 409（already running）→ `message.warning("workflow already running")`

---

## 4. 难题 3 — 整合关系（新 page + 跳转 + Sprint 3 panel 复用）

- **新 page**（FR-6.1，M2 ratify）：`/autoevolving` 独立 page，**不是** Insights 新 tab。`pages/AutoEvolving.tsx` + `App.tsx` 加路由 + `Layout.tsx` nav 加项。
- **DAG viz panel 复用**：**直接嵌 `<WorkflowRunsPanel/>`**（已 lazy-load、自带 runs list + DAG + WS + poll，是自包含可复用组件）。**不重造、不抽简版**。/autoevolving 的 workflow 区 = 完整 WorkflowRunsPanel；Insights Workflows tab 也用同一个组件 —— 两处共用同一组件，零重复逻辑。轻微「两处都能看 workflow」是 acceptable（一个是 autoEvolving 总览语境，一个是 Optimization 深挖语境），符合 PRD「总览 + click 跳 deep-dive」。
- **跳转**：信号面板 row/卡 click → `navigate('/insights/patterns?tab=reports&...')` 等，复用现有 deep-dive page，不重复造详情。

⚠️ 自查点（/autoevolving vs Insights Workflows tab 重复）：不是重复实现，是**同一 `WorkflowRunsPanel` 组件双挂载**。若觉得总览页 DAG 太重，fallback 方案 = 总览页只放「最近 5 run mini list + 状态 chip」+ "View all →" 跳 Insights tab。**r1 推荐直接嵌完整 panel**（复用成本最低，user 端到端体验最完整），如 reviewer 觉得页面过载再降级。

---

## 5. 难题 4 — BE 缺什么 API（最小化）

**结论：新增 1 个只读聚合 endpoint + 1 个 read service，其余全复用。**

新增 `GET /api/autoevolving/overview?userId=&weekDays=7&reportLimit=8`：
```
{
  kpi: {
    workflowRunning: int,           // count FlywheelRun loopKind=workflow status=running
    workflowCompletedThisWeek: int, // 周窗口 completed count
    memoryProposalPending: int,     // count t_memory_proposal status=proposed
    autoResearchPending: null       // V1 placeholder
  },
  recentReports: [ {reportId, agentId, agentName, windowEnd, status, topIssueCount} ],  // 跨 agent
  recentAnomalies: [ {runId, name, status:'error', errorReason, updatedAt} ]            // failed workflow runs
}
```
- 新 `AutoEvolvingController` + `AutoEvolvingOverviewService`（构造器注入 `FlywheelRunRepository` / `OptReportRepository` / `MemoryProposalRepository`，全只读 query，复用现有 repo）。
- 解决三个 gap：①跨 agent 报告 list（FR-6.3）②KPI 周窗口聚合（FR-6.2）③anomaly 简化为 failed runs（FR-6.5）—— 一个端点收口，FE 一次 query（WS invalidate），避免 N 次拼接。
- 返回 `{...}` envelope（footgun #6b：单对象，FE `api.get<OverviewResponse>` 对应单对象，非裸 array）。

**为什么不纯 FE 拼**（自查）：memory pending ✓ 可 FE 拿，但**跨 agent OPT-REPORT 没端点**（per-agent only）→ FE 拼需先列 agents 再 N 次查 reports = N+1，且周窗口 count 现有 list 端点不支持。1 个聚合端点比 FE N+1 更对、更省。

⚠️ `OptReportRepository` 需确认有「跨 agent 最近 N + topIssueCount」query（topIssueCount 从 summary_json 解析或加 count）；若无，service 内查最近 report rows 后解析 summary_json 取 topIssues.size。**be-dev 落地前 grep `OptReportRepository` 现有方法确认**（plan 假设需补一个 `findTopNByOrderByCreatedAtDesc`）。

---

## 6. 模块边界

**FE（主体）：**
- `pages/AutoEvolving.tsx`（新 page，组合各 panel）
- `components/workflow/WorkflowApproveCard.tsx`（新，仿 ask_user）
- `components/workflow/TriggerWorkflowModal.tsx`（新）
- `components/autoevolving/`（KPIStrip / SignalPanels / AnomalyPanel 小组件，可拆文件）
- `api/workflow.ts`：补 `runWorkflow` + `approveRun` wrapper + types
- `api/autoevolving.ts`（新，overview wrapper + types）
- `App.tsx` + `Layout.tsx`（路由 + nav，最小改）
- `components/workflow/workflow.css`（追加 approve-card 样式，**不动 index.css**）

**BE（最小）：**
- `workflow/AutoEvolvingController.java` + `AutoEvolvingOverviewService.java`（新，只读聚合）
- 可能 `OptReportRepository` 加 1 个跨 agent 查询方法
- 复用 `FlywheelRunRepository` / `MemoryProposalRepository`

---

## 7. 实施 order

1. **FE api wrapper 先行**：`workflow.ts` 补 runWorkflow/approveRun + 新 `autoevolving.ts`（types 锁 BE 契约，footgun #6/#6b）
2. **BE overview endpoint**：Controller + Service + repo 方法 + roundtrip IT（FE-BE 契约）
3. **WorkflowApproveCard** + workflow.css 样式
4. **TriggerWorkflowModal**
5. **AutoEvolving page** 组装（KPI + 信号面板 + approvals 区 + 嵌 WorkflowRunsPanel + 异常面板）
6. **路由 + nav**（App.tsx / Layout.tsx）
7. **真活端到端验证**（AC-9/10/11 + humanApprove e2e）

---

## 8. 测试矩阵

| 层 | 测试 | 验证点 |
|---|---|---|
| BE unit | `AutoEvolvingOverviewServiceTest` | KPI count 正确（周窗口 / status 过滤）|
| BE IT | `AutoEvolvingControllerIT` + overview roundtrip | envelope shape + 跨 agent reports + footgun #6b |
| FE unit (vitest) | `WorkflowApproveCard.test` | payload 渲染 / approve→POST / reject→POST / 错误 message |
| FE unit | `TriggerWorkflowModal.test` | 列 workflow / submit→runWorkflow / 409 warning |
| FE unit | `AutoEvolving.test` | KPI 渲染真数据 / 信号面板 click 跳转 / WS approve card append |
| 真活 e2e | curl + agent-browser | (1) `curl /api/autoevolving/overview` raw JSON 对 TS interface (2) `npx agent-browser goto /autoevolving` 断言 4 区渲染 (3) 触发 opt-report workflow→DAG 着色→approve card 弹→approve→completed (AC-9/10) (4) 改 .workflow.js→hot-reload (AC-11) |

**FE-BE 契约强制**（verification-before-completion 表）：overview / approve / run 三处新契约 grep 字段名 + envelope shape + roundtrip IT + 真活 curl 四重验。

---

## 9. 风险

| 风险 | 缓解 |
|---|---|
| ask_user card 硬复用 payload 不匹配 | **仿建**，只复用 CSS 视觉语言（已决策）|
| 跨 agent OPT-REPORT 端点缺 | 新 1 个聚合 endpoint 收口（难题4）|
| 异常诊断无现成聚合数据源 | 简化为 failed workflow/flywheel run（brief 许可）；完整 t_llm_trace/tengu anomaly 标 deferred V2 |
| WS review card 时序（approve→再 pause→再弹）| key=runId+stepIndex 去重 + 单调递增 |
| /autoevolving 与 Insights Workflows tab 观感重复 | 同组件双挂载非重复实现；过载则降级 mini-list（fallback 已备）|
| index.css 全局污染（scope-out 文件）| 新样式走 workflow.css/autoevolving.css，不动 index.css |
| overview 聚合 query 性能 | 只读 + count query + limit；report topIssueCount 解析在 service 内做一次 |

---

## 10. Self-check（对抗约束 A）

读一遍后，最可能被挑的 3 点 + 处置：

1. **「ask_user card 直接复用」假设错** → 已 grep `PendingAskCard` 确认 payload 形态（question/options vs summary JSON）不匹配，改为**仿建 + 复用 CSS**，并给出理由。✅ 修正
2. **「信号源 API 都现成」瞎写** → 已 grep 确认 memory ✅ 现成，但**跨 agent OPT-REPORT 无端点 + KPI 无周聚合 + anomaly 无聚合** → 明确标 gap + 1 个聚合 endpoint 解决，不假装现成。✅ 修正
3. **「异常诊断有现成数据源」错** → 已 grep TracesController 确认只有明细无 anomaly 聚合 → 简化为 failed runs（brief 许可），完整版 defer。✅ 修正
4. （额外）**FE approve/run wrapper 假设已存在** → grep `workflow.ts` 确认**只有 3 个 GET，缺 2 个 POST wrapper** → 明确列入实施 order step 1。✅ 修正

无 blocker。BE 仅 +1 只读聚合端点（+可能 1 个 repo 方法）。
