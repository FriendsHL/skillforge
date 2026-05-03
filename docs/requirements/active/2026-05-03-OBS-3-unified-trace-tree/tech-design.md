# OBS-3 技术方案 — Unified Trace Tree

## 1. 后端 — `GET /api/traces/{traceId}/with_descendants`

### 1.1 endpoint 签名

```
GET /api/traces/{traceId}/with_descendants?max_depth=3&max_descendants=20&user_id={uid}
```

参数：
- `max_depth`（默认 3）— DFS 最深层数
- `max_descendants`（默认 20）— descendant trace 总数上限；超过则只返回前 20 + `truncated=true`
- `user_id`（必填）— 复用 OBS-1 ownership guard 模式

### 1.2 响应 DTO

```java
public record TraceWithDescendantsDto(
    LlmTraceSummaryDto rootTrace,           // depth=0
    List<DescendantTraceDto> descendants,   // 按 DFS 序，每条含 depth + parent_trace_id
    List<UnifiedSpanDto> spans,             // 全部 spans 按 started_at ASC，每条标 depth + parent_trace_id
    boolean truncated                       // descendants > max_descendants 时 true
) {}

public record DescendantTraceDto(
    String traceId,
    String sessionId,
    int depth,                              // 1 / 2 / 3
    String parentTraceId,
    String parentSpanId,                    // 触发 child 的 TeamCreate / SubAgent tool span_id
    String agentName,
    String status,                          // running / ok / error / cancelled
    long totalDurationMs,
    int toolCallCount,
    int eventCount
) {}

public record UnifiedSpanDto(
    SpanSummaryDto span,                    // 复用 OBS-2 SpanSummaryDto sealed union
    int depth,                              // 0 = parent, 1+ = descendant
    String parentTraceId                    // null for parent, non-null for descendant
) {}
```

### 1.3 DFS 算法（service 层）

```java
@Service
public class TraceDescendantsService {
    private final LlmTraceStore traceStore;
    private final SessionRepository sessionRepository;

    public TraceWithDescendantsDto fetch(String traceId, int maxDepth, int maxDescendants, Long userId) {
        // 1. ownership check (复用 OBS-1 ObservabilityOwnershipGuard 模式)
        // 2. fetch root trace
        LlmTrace root = traceStore.readByTraceId(traceId).orElseThrow(...);

        // 3. DFS find descendants
        List<DescendantTraceDto> descendants = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(root.sessionId());
        boolean truncated = dfsDescendants(root, 1, maxDepth, maxDescendants, visited, descendants);

        // 4. fetch all spans (parent + descendants 各 trace 一次 listSpansByTrace)
        List<UnifiedSpanDto> allSpans = new ArrayList<>();
        // parent spans (depth=0)
        traceStore.listSpansByTrace(traceId, null, MAX_SPANS_PER_TRACE)
            .forEach(s -> allSpans.add(new UnifiedSpanDto(toDto(s), 0, null)));
        // descendant spans
        for (DescendantTraceDto d : descendants) {
            traceStore.listSpansByTrace(d.traceId(), null, MAX_SPANS_PER_TRACE)
                .forEach(s -> allSpans.add(new UnifiedSpanDto(toDto(s), d.depth(), d.parentTraceId())));
        }
        // 5. sort all spans by started_at ASC (跨 trace 时间线统一)
        allSpans.sort(Comparator.comparing(...));

        return new TraceWithDescendantsDto(toSummary(root), descendants, allSpans, truncated);
    }

    private boolean dfsDescendants(LlmTrace currentTrace, int depth, int maxDepth, int maxDescendants,
                                   Set<String> visited, List<DescendantTraceDto> out) {
        if (depth > maxDepth) return false;
        if (out.size() >= maxDescendants) return true;  // truncated

        // find child sessions of currentTrace.sessionId
        List<SessionEntity> children = sessionRepository
            .findByParentSessionIdOrderByCreatedAtAsc(currentTrace.sessionId());

        for (SessionEntity child : children) {
            if (visited.contains(child.getId())) continue;  // cycle guard
            visited.add(child.getId());

            // for each child session, find its trace(s) (child session 可能多 trace if multi-turn)
            // OBS-3 简化：只取最近的 trace（first trace of the child session that triggered this dispatch）
            // — 实际是按 parent SessionEntity.parent_span_id 关联到具体 trace，但这字段没有
            // 退化方案：取 child session 第一个 trace（按 started_at ASC）
            List<LlmTrace> childTraces = traceStore.listTracesBySessionAsc(child.getId(), 1);
            if (childTraces.isEmpty()) continue;
            LlmTrace childTrace = childTraces.get(0);

            // resolve parentSpanId — 父 trace 内派发该 child 的 TeamCreate/SubAgent tool span
            // 算法：扫 parent trace 的 tool spans where output_summary contains "childSessionId: <child.id>"
            String parentSpanId = resolveDispatchSpan(currentTrace.traceId(), child.getId());

            out.add(new DescendantTraceDto(
                childTrace.traceId(), child.getId(), depth, currentTrace.traceId(),
                parentSpanId,
                childTrace.rootName() /* or agentName */, childTrace.status(),
                childTrace.totalDurationMs(), childTrace.toolCallCount(), childTrace.eventCount()
            ));

            if (out.size() >= maxDescendants) return true;

            // recurse
            boolean truncated = dfsDescendants(childTrace, depth + 1, maxDepth, maxDescendants, visited, out);
            if (truncated) return true;
        }
        return false;
    }

    /** Scan tool spans in parentTrace whose output contains "childSessionId: <childSessionId>". */
    private String resolveDispatchSpan(String parentTraceId, String childSessionId) {
        return traceStore.listSpansByTrace(parentTraceId, Set.of("tool"), 200).stream()
            .filter(s -> s.outputSummary() != null
                && s.outputSummary().contains("childSessionId: " + childSessionId))
            .map(LlmSpan::spanId)
            .findFirst().orElse(null);
    }
}
```

### 1.4 不变量

1. **环检测**：DFS 用 visited set，理论上 `parent_session_id` 是 DAG 不应该有环，但加 visited 是双保险
2. **max_depth**：硬上限 3 防止意外的深度递归
3. **max_descendants**：软上限 20，超过 truncate 不报错
4. **child trace 选择**：每个 child session 只取最早的 trace（first turn）；多 turn child 场景用户得用 SubagentJumpLink 看完整
5. **resolveDispatchSpan 失败兜底**：如果 child session output 里没找到 `childSessionId:` 字符串（极少 case），`parentSpanId` 返回 null，前端把这个 child sub-tree 渲染在父 trace 末尾

### 1.5 LlmTraceStore 接口扩展

新加方法：

```java
List<LlmTrace> listTracesBySessionAsc(String sessionId, int limit);
```

实现：`SELECT * FROM t_llm_trace WHERE session_id = ? ORDER BY started_at ASC LIMIT ?`

## 2. WebSocket 推送 — `trace_finalized` 事件

### 2.1 触发点

`AgentLoopEngine.finalizeTraceSafe(...)` 完成后，broadcaster 加 push：

```java
// 改 ChatEventBroadcaster 加新方法
void traceFinalized(String sessionId, String traceId, String status, String error,
                    long totalDurationMs, int toolCallCount, int eventCount);
```

### 2.2 WS payload

```json
{
  "type": "trace_finalized",
  "sessionId": "...",
  "traceId": "...",
  "status": "ok",
  "error": null,
  "totalDurationMs": 38542,
  "toolCallCount": 5,
  "eventCount": 0
}
```

### 2.3 前端订阅

`SessionDetail` / `Traces` / `RightRail` 加 WS handler 监听 `trace_finalized`：
- 在 `unified` 视图中找 `traceId` 对应的 child trace badge → 状态从 `running` 切到响应里的 status
- 触发 react-query invalidate 重新 fetch（备选：直接 mutate cache 避免 refetch overhead）

### 2.4 向后兼容

OBS-1 既有 WS handler 用 `event.type` 路由消息。`trace_finalized` 是新 type，不识别的 client 静默忽略，无破坏性。

## 3. 前端 — 共享 `<NestedWaterfallRenderer>` 组件

### 3.1 抽象

```typescript
interface NestedWaterfallProps {
  spans: UnifiedSpan[];                  // 含 depth + parentTraceId
  descendants: DescendantTraceMeta[];    // 含 status / agentName
  totalMs: number;                       // 时间轴总时长（all parent + descendant durations）
  selectedSpanId: string | null;
  onSelectSpan: (span: SpanSummary) => void;
  mode: 'full' | 'mini';                 // full: SessionWaterfallPanel/Traces.tsx; mini: RightRail
  expandedSubtrees: Set<string>;         // 展开的 child trace_ids
  onToggleSubtree: (childTraceId: string) => void;
}
```

### 3.2 渲染规则

| 模式 | 缩进 | 行高 | child status badge | 折叠 button |
|---|---|---|---|---|
| `full` | 16px 每 depth | 标准 | 完整文本（"running" / "ok" / "error"）| ▶ / ▼ icon button |
| `mini` | 8px 每 depth | 紧凑（单行） | 状态点（绿/红/灰圆点） | tap to toggle，无 icon button |

公共逻辑：
- 按 `started_at` 排序后渲染
- `depth > 0` 行加左侧 vertical guide line + 暗化颜色（opacity 0.85 - 0.05*depth）
- TeamCreate / SubAgent tool span（identified by `name === 'TeamCreate' || 'SubAgent'`）行旁加折叠 toggle
- 折叠状态下 `expandedSubtrees.has(childTraceId) === false` 隐藏该 child 的所有 spans
- lazy load：当 `truncated === true` 时，"Show more" 按钮触发 `getTraceWithDescendants(childTraceId, max_depth=2)`

### 3.3 改动文件

| 文件 | 改动 | 备注 |
|---|---|---|
| `types/observability.ts` | + `UnifiedSpan` / `DescendantTraceMeta` / `TraceWithDescendants` interfaces | |
| `api/index.ts` | + `getTraceWithDescendants(traceId, maxDepth?, maxDescendants?)` 函数 | |
| `components/observability/NestedWaterfallRenderer.tsx`（新） | 共享渲染组件 | full + mini 共用 |
| `components/sessions/detail/SessionWaterfallPanel.tsx` | 数据源切到 `getTraceWithDescendants`；渲染换 `<NestedWaterfallRenderer mode="full">` | OBS-2 M3 已经 per-trace fetch，这里只换数据源 + 渲染器 |
| `pages/Traces.tsx` | line 274 内嵌 `Waterfall` function 改用 `<NestedWaterfallRenderer mode="full">` | 同上 |
| `components/chat/RightRail.tsx` | line 171-172 mini-waterfall 改用 `<NestedWaterfallRenderer mode="mini">` | mini 版 |
| `components/sessions/detail/SubagentJumpLink.tsx` | 不动 | 保留独立打开 child session 的能力 |
| `pages/SessionDetail.tsx` | 数据源切到 `getTraceWithDescendants` | queryKey 包含 maxDepth/maxDescendants |

### 3.4 折叠状态管理

每个 page-level 组件（SessionDetail / Traces / Chat 容器）持有 `expandedSubtrees: Set<string>` state，传给 `NestedWaterfallRenderer`。

`onToggleSubtree(childTraceId)` 简单 set 加减，不需要全局 store。

切换 selectedTraceId 时清空 expandedSubtrees（视觉上每次进新 trace 都从折叠态开始）。

### 3.5 WS subscriber

3 个页面各自的 WS handler 接收 `trace_finalized` 事件：
- 找到当前 `unifiedTraceQuery.data.descendants` 里 `traceId` 匹配的 child
- 走 react-query `setQueryData` 直接 mutate descendants[i].status / totalDurationMs（不 refetch）
- 自动触发 NestedWaterfallRenderer 重渲染 child status badge

## 4. 测试计划

### 单元测试

- `TraceDescendantsServiceTest`:
  - DFS 找 1 层 / 2 层 / 3 层 descendants
  - max_depth=3 截断深层 child
  - max_descendants=20 truncate
  - cycle detection（mock 一个 child 反向 parent_session_id）
  - resolveDispatchSpan 找不到 childSessionId 时返回 null + 不报错

### 集成测试

- `TraceWithDescendantsControllerIT`:
  - 用 dev DB session `6f18ecca`（已知 3 child）：with_descendants 返回 4 traces + 全部 spans + descendants 含 3 行
  - ownership 不属用户返回 403
  - max_depth/max_descendants query params 生效

### 前端测试

- `NestedWaterfallRenderer` vitest + react-testing-library：
  - render full mode + mini mode 各自快照
  - toggle 折叠：点击 ▶ → expandedSubtrees 含 childTraceId → child rows 显示
  - lazy load：truncated=true 时 "Show more" 按钮触发 fetch
- 浏览器目检（Playwright 或手测）：
  - session `6f18ecca` 进 SessionWaterfallPanel：默认折叠看到 TeamCreate row × 3，逐个展开看 child sub-tree
  - chat 页 RightRail mini-waterfall：紧凑嵌套显示
  - WS event 实时更新：触发新 chat 派 child → child running → 等完成 → 状态自动切 ok（不 refresh 页面）

## 5. 实施顺序

```
1. 后端 endpoint + service DFS + 单测                [1.5 day]
   ↓
2. 前端 NestedWaterfallRenderer 抽象 + types + api    [1 day]
   ↓
3. SessionWaterfallPanel 切换（full mode）            [0.3 day]
   ↓
4. Traces.tsx + RightRail mini 切换                  [0.7 day]
   ↓
5. WS trace_finalized 事件 + 前端 subscriber          [0.5 day]
   ↓
6. 浏览器目检 + IT                                   [0.5 day]
   ↓
7. commit + push
```

总计 **~4-5 天**（Mid 档）。

## 6. 与既有不变量的关系

- **OBS-2 §5 不变量保留**：rootSpan.id == traceId / finalizeTrace SQL 幂等 / kind 不可变 — 都不动
- **child trace 独立性（tech-design §9.2）保留**：child 仍独立 t_llm_trace 行，trace_id 不跨 session 共享
- **M3 切读路径**：unified view 只是叠加；既有 `/api/traces/{id}/spans` / `/api/observability/sessions/{id}/spans` 端点保留，前端选择性使用

## 7. 参考

- [OBS-2 PRD](../2026-05-02-OBS-2-trace-data-model-unification/prd.md) §M3 §9.2
- [OBS-2 tech-design](../2026-05-02-OBS-2-trace-data-model-unification/tech-design.md) §3 SubagentSessionResolver `childSessionId:` regex 模式（OBS-3 复用此模式）
- 现场 session `6f18ecca`（3 个 TeamCreate child）作为开发 + 验证 fixture
