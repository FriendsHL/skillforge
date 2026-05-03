# OBS-3 PRD — Unified Trace Tree Across Sessions

## 1. 目标

让父 trace waterfall 内**嵌套显示** child trace 的 LLM/tool/event spans，跨 session 边界一次性呈现完整执行树。child trace 仍可通过 `SubagentJumpLink` 独立打开（保留），数据模型不动（child trace 仍独立 `t_llm_trace` 行）。

## 2. 范围

### 后端

- 新 endpoint `GET /api/traces/{traceId}/with_descendants?max_depth=3&max_descendants=20`
  - DFS via `t_session.parent_session_id` 找 descendant child sessions
  - 聚合 parent + descendant spans 为单一时间序列，每条 span 标 `depth`（0 = parent，1 = direct child，2 = grandchild）+ `parent_trace_id`（指向上一层 trace_id）+ `parent_span_id`（指向上一层触发 span，通常是 TeamCreate / SubAgent tool span）
  - 响应含 `descendants` 元数据列表（trace_id + session_id + status + agent_name + total_duration_ms），让前端不需要单独 GET 每个 child trace
- WS 事件扩展：child trace `finalize`（status `running` → `ok` / `error` / `cancelled` 转换）push 到所有订阅了 parent trace 的 client（OBS-1 既有 WS infra 基础上加 `trace_finalized` 事件）

### 前端

3 处 waterfall 渲染都改造（用户明确要求）：

| 文件 | 角色 | 改动 |
|---|---|---|
| `components/sessions/detail/SessionWaterfallPanel.tsx` | full session detail waterfall | 嵌套 child sub-tree（缩进 + 折叠 + child status badge） |
| `pages/Traces.tsx` | Traces 列表页 full waterfall（line 274 `Waterfall` function） | 同上 |
| `components/chat/RightRail.tsx` | chat 页 mini-waterfall（line 171-172） | 嵌套 child sub-tree（mini 版本：缩进 8px / 单行 / child status dot） |

通用前端改动：
- API client：`getTraceWithDescendants(traceId, maxDepth, maxDescendants)` 新函数
- types：`UnifiedSpan = SpanSummary + { depth, parentTraceId, parentSpanId }`，`UnifiedTrace = LlmTraceSummary + { descendants: ChildTraceMeta[] }`
- 折叠状态管理：每个 TeamCreate / SubAgent tool span 一个 `expanded: boolean` state，默认 `false`（折叠）
- lazy load：第一层 child sub-tree 同步加载（含在 with_descendants 响应里），> 20 descendants 走 lazy `GET /api/traces/{childTraceId}/with_descendants?max_depth=2`
- WS subscriber：trace_finalized 事件触发当前 unified view 的 child status badge 实时更新
- `SubagentJumpLink` 保留（OBS-3 是 enhancement 不是 replacement）

## 3. 验收点

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | `GET /api/traces/{traceId}/with_descendants` 返回 parent + descendant spans 含 depth 标记 | curl + JSON inspect |
| 2 | 已知现场 session `6f18ecca`（3 child）trace `f20763ca`：with_descendants 返回 4 traces (parent + 3 child) + 全部 spans 含 depth=0 (parent) / depth=1 (3 child) | 整合测试 |
| 3 | `SessionWaterfallPanel` 渲染 TeamCreate row 旁有 ▶ 按钮，点击展开 child sub-tree（缩进 16px 每层） | 浏览器目检 |
| 4 | `pages/Traces.tsx` 同样支持 unified rendering | 浏览器目检 |
| 5 | `RightRail.tsx` mini-waterfall 也支持嵌套（mini 版本） | 浏览器目检 |
| 6 | child status badge（running/ok/error/cancelled）实时更新（chat 跑完后无需 refresh） | 浏览器目检 + WS log |
| 7 | `SubagentJumpLink` 仍可点击跳到 child session | 浏览器目检 |
| 8 | DFS 深度上限 3 / descendants 上限 20 生效 | 整合测试（mock 4 层嵌套 / 25 child） |
| 9 | lazy load 触发：> 20 child 时点 child 上展开按钮触发 `with_descendants` 第二次 fetch | 整合测试 + network panel |
| 10 | 单测：DFS 算法（环检测 / 最大深度 cutoff / 排序按 started_at） | mvn test |

## 4. 锁定决策

| # | 决策 | 值 |
|---|---|---|
| Q1 | DFS 深度上限 | **3 层**（main → sub → sub-sub） |
| Q2 | descendants 数量上限 / lazy load 阈值 | **20 同步加载，> 20 lazy load** |
| Q3 | 默认折叠 vs 展开 | **默认折叠**（▶ 按钮展开） |
| Q4 | 后端 endpoint 命名 | **`GET /api/traces/{traceId}/with_descendants`** + `max_depth` / `max_descendants` query params |
| Q5 | 前端 child sub-tree 样式 | **缩进 16px 每层 + 左侧 vertical guide line + 颜色暗化（parent agent name 小标签）**；mini-waterfall 缩进 8px 单行版本 |
| Q6 | child running 状态实时更新 | **WS push trace_finalized 事件**（OBS-1 既有 WS infra 扩展） |
| Q7 | SubagentJumpLink 保留 | **保留** |
| Q8（用户补） | 改造范围 | **3 处 waterfall**（SessionWaterfallPanel + Traces.tsx + RightRail mini） |

## 5. 范围之外（OOS）

- **不动 schema**（child trace 独立性 OBS-2 §9.2 不变量保留）
- **不改 OBS-2 写入路径**（M4 已关 t_trace_span，OBS-3 仅扩展读 layer）
- **不强制 child trace 跟随 parent trace_id**（拒绝选项 C，会破坏跨 session trace 边界）
- 不动 dashboard 其他页面（agents / skills / chat 主体）
- 不做 cross-trace search / trace tree 全局视图
- 不做 trace export / replay（留给未来需求）

## 6. 风险

| 风险 | 缓解 |
|---|---|
| DFS 深度无限递归（环 = parent_session_id 形成 cycle） | DFS 加 visited set，遇 cycle 立即停 + log warning；max_depth=3 也是双保险 |
| 大 trace tree 性能（25+ child）lazy load 实现复杂 | 前端用 react-query 缓存第二次 fetch；加载 state UX（spinner 在折叠按钮位置） |
| WS push trace_finalized 事件破坏既有 WS 协议 | OBS-1 WS handler 加新 event type，向后兼容（client 不识别 type 静默忽略） |
| mini-waterfall 视觉太挤（chat 右栏空间小） | 单行渲染 + 缩进 8px + child 数量 ≤ 5 时同步显示，> 5 时显示 "+N more" |
| 前端 3 处 waterfall 共享 NestedRenderer 抽象 | 提取 `<NestedWaterfallRenderer>` 组件 props 含 `mode: 'full' \| 'mini'` 控制密度 |

## 7. 工期估算

| 阶段 | 范围 | 工期 |
|---|---|---|
| 后端 endpoint + DFS service + WS event | new endpoint + DFS algorithm + 单测 + IT | 1.5 天 |
| 前端 unified renderer 抽象 | `<NestedWaterfallRenderer>` 共享组件 | 1 天 |
| 前端 3 处 waterfall 改造 | SessionWaterfallPanel / Traces.tsx / RightRail mini | 1 天 |
| WS subscriber + child status badge | 整合 OBS-1 既有 WS infra | 0.5 天 |
| 测试 + 浏览器目检 | IT + e2e | 0.5 天 |

**总计 ~4-5 天**（Mid 档）。

## 8. 依赖

- OBS-2 M0-M4 已交付（前置）
- OBS-1 WS infra（trace_finalized 事件扩展，不破坏既有协议）
- M3 前端 per-trace fetch 模式（OBS-3 复用 selectedTraceId 切换逻辑）
- M5/M6 不阻塞 OBS-3（cleanup 跟 OBS-3 解耦）
