# OBS-4 技术方案 — root_trace_id 跨 agent 串联

## 1. 目标 schema

### 1.1 `t_llm_trace` 加 `root_trace_id`

**M0 V45**（仅加 nullable + 回填 + 索引；不 SET NOT NULL）：

```sql
-- skillforge-observability/V45__add_root_trace_id.sql

ALTER TABLE t_llm_trace
    ADD COLUMN IF NOT EXISTS root_trace_id VARCHAR(36);

-- 历史数据回填（每个 trace 自己当 root）
UPDATE t_llm_trace SET root_trace_id = trace_id WHERE root_trace_id IS NULL;

-- 查询索引：按 root_trace_id 拿整树
CREATE INDEX IF NOT EXISTS idx_llm_trace_root ON t_llm_trace (root_trace_id, started_at);
```

**为什么 M0 不 SET NOT NULL**：`PgLlmTraceStore.upsertTrace` 当前 INSERT SQL 不传 `root_trace_id` 列。M0 一次性 SET NOT NULL 会导致 M1 写入路径改造前任何新 trace INSERT NOT NULL violation。两步部署：M0 nullable 让系统继续跑老路径；M1 同 commit 加 V46 改写入路径并 SET NOT NULL，原子切换。

**M1 V46**（M1 commit 内部署）：

```sql
-- skillforge-observability/V46__set_root_trace_id_not_null.sql
ALTER TABLE t_llm_trace ALTER COLUMN root_trace_id SET NOT NULL;
```

`root_trace_id` 的值域：
- 主 agent 第一个 trace（user message 起点）：`root_trace_id = self.trace_id`
- 主 agent 后续 trace（同 user message 内）：继承 `session.active_root_trace_id`
- subagent 任意 trace（含 child of child）：继承父 session 当前 active_root（在 spawn 时复制）

### 1.2 `t_session` 加 `active_root_trace_id`

```sql
-- skillforge-server/V44__add_active_root_trace_id_to_session.sql

ALTER TABLE t_session
    ADD COLUMN active_root_trace_id VARCHAR(36);
```

不需要索引（仅作为单 session 的状态字段读写，没按它查）。默认 NULL，新会话才填。

### 1.3 Migration 编号约定

OBS-2 已确立 server 和 observability 共享全局编号空间（commit `223e5a8`）。当前最新：

- server: `V41__add_trace_id_to_session_message.sql`
- observability: `V43__llm_span_kind_event_type_name.sql`

OBS-4 用：
- `skillforge-server/V44__add_active_root_trace_id_to_session.sql`（M0）
- `skillforge-observability/V45__add_root_trace_id.sql`（M0 — 加 nullable + 回填 + 索引）
- `skillforge-observability/V46__set_root_trace_id_not_null.sql`（M1 commit 内 — SET NOT NULL）

## 2. 写入路径改造

### 2.1 ChatService（user message 边界 + active root 决策）

**位置**：`ChatService.java` line 207-263 区域（OBS-2 M1 §D.1 已经在这里集中处理 traceId 生成）。

**改动**：

```java
// 现状（OBS-2）
String traceId = UUID.randomUUID().toString();
sessionService.appendNormalMessages(sessionId, List.of(userMsg), traceId);
// ...
preCtx.setTraceId(traceId);

// OBS-4 改造
String traceId = UUID.randomUUID().toString();

// §C.1 root_trace_id 决策：检查 session 的 active_root_trace_id
String existingActiveRoot = sessionService.getActiveRootTraceId(sessionId);
String rootTraceId;
if (existingActiveRoot == null) {
    // 新 root：每次 user message 都重置 active_root（边界规则 a），
    // 这里 existingActiveRoot 必为 null（user message 处理前已清空，见 §C.2）
    rootTraceId = traceId;
    sessionService.setActiveRootTraceId(sessionId, rootTraceId);
} else {
    // 继承（理论上 user message 边界不该走到这；保留作为 defensive：
    // 如果上次 user message 处理异常没清 active_root，下次 user message
    // 进来仍按"新 root"走，覆盖式 set；不依赖此分支）
    rootTraceId = existingActiveRoot;
}

sessionService.appendNormalMessages(sessionId, List.of(userMsg), traceId);
// ...
preCtx.setTraceId(traceId);
preCtx.setRootTraceId(rootTraceId);   // §A.1 透传
```

**user message 边界处理**：在 `chatAsync` 入口（line 130 附近，user message 入队前）：

```java
// §C.2 OBS-4 边界规则 a：每个 user message 一个新 root
sessionService.clearActiveRootTraceId(sessionId);   // 清空，等下面新 trace 创建时重新填
```

**注**：清空 + 设新值是两个独立 writes；放在同一事务内保证原子性。

### 2.2 AgentLoopEngine（trace 创建时写 root_trace_id）

**位置**：`AgentLoopEngine.java` line 442-470（rootSpan 创建 + traceLifecycleSink.upsertTraceStub 区域）。

**改动**：

```java
// 现状（OBS-2 M1）
if (traceLifecycleSink != null && loopCtx.getTraceId() != null) {
    try {
        traceLifecycleSink.upsertTraceStub(
            new TraceStubRequest(loopCtx.getTraceId(), sessionId, ...));
    } catch (Exception e) { /* log + drop */ }
}

// OBS-4：透传 rootTraceId 到 stub
if (traceLifecycleSink != null && loopCtx.getTraceId() != null) {
    try {
        traceLifecycleSink.upsertTraceStub(
            new TraceStubRequest(
                loopCtx.getTraceId(),
                loopCtx.getRootTraceId(),  // §A.2 OBS-4 新字段
                sessionId, ...
            ));
    } catch (Exception e) { /* log + drop */ }
}
```

**`LoopContext` 加字段**：

```java
public class LoopContext {
    private String traceId;
    private String rootTraceId;  // §A.2 OBS-4
    // getter/setter
}
```

**`TraceStubRequest` 加字段**：构造参数加 rootTraceId（DTO 是 record，加构造参数）。

### 2.3 TraceLifecycleSink / PgLlmTraceStore

**`TraceLifecycleSink.upsertTraceStub`**：实现端写入 `t_llm_trace.root_trace_id`。

**SQL 改造**：

```sql
-- 现状
INSERT INTO t_llm_trace (trace_id, session_id, agent_id, ...)
VALUES (?, ?, ?, ...)
ON CONFLICT (trace_id) DO UPDATE SET ...

-- OBS-4
INSERT INTO t_llm_trace (trace_id, root_trace_id, session_id, agent_id, ...)
VALUES (?, ?, ?, ?, ...)
ON CONFLICT (trace_id) DO UPDATE SET
    -- root_trace_id 不更新（首次写入即定型，不可变）
    ...
```

`root_trace_id` 设计为 immutable：trace 创建时定型，后续 finalize 更新只动 status / error / duration / count，不改 root。

### 2.4 TeamCreateTool / spawnMember（subagent 派发继承 root）

**位置**：`TeamCreateTool.java` line 135 → `collabRunService.spawnMember(...)` 创建 child SessionEntity。

**改动**：spawnMember 内部创建 SessionEntity 时，复制父 session 的 `active_root_trace_id`：

```java
// CollabRunService.spawnMember (路径自查)
SessionEntity child = new SessionEntity();
// ... 现有字段 ...
child.setParentSessionId(parentSession.getId());
child.setActiveRootTraceId(parentSession.getActiveRootTraceId());  // §A.3 OBS-4 继承
sessionRepository.save(child);
```

**SubAgentRunSweeper / 异步派发路径**：所有"创建 child SessionEntity 且会触发 agent loop 跑 trace"的位置都要复制 active_root（grep `setParentSessionId` + `new SessionEntity()` 全数核对）。

**child session 完成后**：父 session 的 `active_root_trace_id` 不清（父 session 仍可能起新 trace 收 child 结果做汇总，需要继承同一个 root）。下次父 session 收到新 user message 时由 §2.1 §C.2 统一清空。

### 2.5 SessionService 新接口

```java
public interface SessionService {
    // 现有 ...

    // OBS-4 新增
    String getActiveRootTraceId(String sessionId);

    @Transactional
    void setActiveRootTraceId(String sessionId, String rootTraceId);

    @Transactional
    void clearActiveRootTraceId(String sessionId);
}
```

实现走 `SessionRepository` JPA 方法（按 ID 加载 entity，set 字段，save）。失败抛 `SessionNotFoundException`，调用方 `ChatService` 把整个 user message 处理回滚。

### 2.6 写入路径不变量（review 时核对）

| # | 不变量 | 验证 |
|---|---|---|
| INV-1 | `t_llm_trace.root_trace_id` 全部非 NULL（M0 migration 后） | SQL: `count(*) FROM t_llm_trace WHERE root_trace_id IS NULL = 0` |
| INV-2 | `root_trace_id` 一旦 INSERT 后不再改 | upsert SQL 的 ON CONFLICT 不动 root_trace_id；review 时 grep 确认无 UPDATE root_trace_id 路径 |
| INV-3 | 同一个 user message 内主 agent 所有 trace 共享同一 root_trace_id | M1 IT：跑一个含 TeamCreate 的 user message，断言所有 trace 的 root_trace_id 相同 |
| INV-4 | subagent trace 的 root_trace_id == 父 session 当前 active_root | M1 IT：spawn child，断言 child trace.root_trace_id == parent.active_root_trace_id |
| INV-5 | 下一个 user message 必开新 root | M1 IT：连续两条 user message，断言两次 trace 的 root_trace_id 不同 |
| INV-6 | child session 完成后父 session 的 active_root 不清 | M1 IT：spawn child，等 child 完成，断言 parent.active_root_trace_id 仍是原值 |

## 3. 历史数据迁移（M0 一次性 SQL）

```sql
-- 已含在 V45 migration 内
UPDATE t_llm_trace SET root_trace_id = trace_id WHERE root_trace_id IS NULL;
ALTER TABLE t_llm_trace ALTER COLUMN root_trace_id SET NOT NULL;
```

老 trace 自己当 root → unified view 退化为单 trace 行为。**不做时间窗启发式 backfill**（决策 Q2 已拒）。

## 4. Read API

### 4.1 新 endpoint `GET /api/traces/{rootTraceId}/tree`

```java
@GetMapping("/api/traces/{rootTraceId}/tree")
public TraceTreeDto getTraceTree(@PathVariable String rootTraceId) { ... }
```

**响应 DTO**：

```java
record TraceTreeDto(
    String rootTraceId,
    List<TraceNodeDto> traces        // 按 started_at 排序
) {}

record TraceNodeDto(
    String traceId,
    String sessionId,
    Long agentId,
    String agentName,
    int depth,                       // 父 trace depth=0；child trace depth=1；child of child=2
    String parentSessionId,          // null = 顶层 session
    String status,                   // running | ok | error | cancelled
    Instant startedAt,
    Instant endedAt,
    long totalDurationMs,
    int llmCallCount,
    int toolCallCount,
    int eventCount,
    List<SpanDto> spans              // 该 trace 的所有 span，按 started_at 排序
) {}

record SpanDto(
    String spanId,
    String parentSpanId,
    String kind,                     // llm | tool | event
    String name,                     // tool name / event name / null for llm
    String model,                    // llm 才有
    Instant startedAt,
    Instant endedAt,
    long latencyMs,
    String status,                   // ok | error
    // input/output preview / blob refs
    ...
) {}
```

### 4.2 实现 — `TraceTreeService`

```java
public TraceTreeDto getTree(String rootTraceId) {
    // §B.1 一条 SQL 拿全部 traces
    List<LlmTraceEntity> traces = traceRepository.findByRootTraceId(rootTraceId);

    // §B.2 一条 SQL 拿全部 spans（trace_id IN (...)）
    Set<String> traceIds = traces.stream().map(LlmTraceEntity::getTraceId).collect(toSet());
    List<LlmSpanEntity> spans = spanRepository.findAllByTraceIdIn(traceIds);

    // §B.3 用 t_session.parent_session_id 在内存里 DFS 计算 depth
    Map<String, Integer> depthBySession = computeDepth(traces);

    // §B.4 组装 DTO
    return assemble(traces, spans, depthBySession);
}
```

**性能**：6f18ecca 实测规模 6 session / 138 span，2 条 SQL + 内存组装 <50ms。500 行以下场景都不需要分页。

### 4.3 `/api/traces?sessionId=X` 响应加 `rootTraceId`

让前端拿到当前 session 的 trace 列表时知道每个 trace 属于哪个 root，进入详情时直接调 `/api/traces/{rootId}/tree`。

```java
record TraceListItemDto(
    String traceId,
    String rootTraceId,    // OBS-4 新增
    // ... 现有字段
) {}
```

## 5. FE 渲染（M3 — 二级折叠 inline group）

### 5.1 数据加载

`SessionDetail.tsx` 进入页面 / 切 trace 时：

```typescript
// 现状（OBS-2）
const spansQuery = useSpansByTrace(selectedTraceId);

// OBS-4
const tracesListQuery = useTracesBySession(sessionId);
const currentTrace = tracesListQuery.data?.find(t => t.traceId === selectedTraceId);
const rootTraceId = currentTrace?.rootTraceId;
const treeQuery = useTraceTree(rootTraceId);  // 新 hook，调 /api/traces/{rootId}/tree
```

### 5.2 SessionWaterfallPanel 升级

**核心数据结构**：

```typescript
type WaterfallRow =
  | { kind: 'span'; depth: 0; span: SpanDto }              // 父主线 span
  | { kind: 'fold-team'; teamSpan: SpanDto; children: TraceNodeDto[]; expanded: boolean }
  | { kind: 'fold-child-summary'; child: TraceNodeDto; expanded: boolean }
  | { kind: 'span'; depth: 1; span: SpanDto; parentChildId: string };  // child 内部 span（缩进）
```

**渲染流程**：

1. 取 root trace（depth=0 的 trace）→ 按时序铺它的 spans（`kind='span', depth=0`）
2. 遇到 `kind='tool'` 且 `name IN ('TeamCreate', 'TeamSend', 'TeamKill')` 的 row → 转成 `kind='fold-team'`，挂上对应 child traces
3. 用户点击 fold-team caret → `expanded=true` → 在该行下面插入 `kind='fold-child-summary'` 行（每个 child 一行）
4. 用户点击 child summary caret → `expanded=true` → 在该行下面插入 `kind='span', depth=1` 行（child 内部 spans）

**折叠状态**：

```typescript
const [foldState, setFoldState] = useState<Record<string, boolean>>({});
// key = teamSpan.spanId 或 child.traceId
const toggle = (key: string) => setFoldState(prev => ({ ...prev, [key]: !prev[key] }));
```

不持久化（页面刷新即重置）。**默认全收起** — 上次 OBS-3 v1 fail 经验：默认任何展开都会淹没主线。

### 5.3 视觉规范

- 父主线 span：原 `SpanRow` 不变
- fold-team row：左边加 ▶ caret + 文字 `TeamCreate (派 N child / 共 X spans / 总 Y ms)` + 跟普通 tool span 同色
- fold-child-summary row：缩进 1 级（24px），背景色稍深，左侧 ▶ caret + `agent_name` + status badge + duration bar + `内部 X spans`
- depth=1 span row：缩进 2 级（48px），背景色再深一点；保持瀑布流时间轴对齐父主线时间
- 跨 session 不切换 timeline 时间轴：所有 row 用同一时间轴（绝对时间），方便看"父等了多久 child 才完成"

### 5.4 老 session 行为

- API 返回的 tree 只有 1 个 trace（depth=0，无 child）
- 渲染只走父主线分支，没有 fold-team row（因为没有 child）
- **跟 OBS-2 当前体验视觉零变化** — 验收点 9 强制

### 5.5 涉及文件

| 文件 | 改动 |
|---|---|
| `SessionDetail.tsx` | 数据加载替换：`useSpansByTrace` → `useTraceTree(rootTraceId)` |
| `SessionWaterfallPanel.tsx` | 渲染逻辑加 `WaterfallRow` 联合类型 + 二级折叠 |
| `hooks/useTraceTree.ts` | 新 hook |
| `hooks/useTracesBySession.ts` | 现有 hook 加 rootTraceId 字段映射 |
| `types/api.ts` | 加 `TraceTreeDto` / `TraceNodeDto` / 修改 `TraceListItemDto` |
| `components/WaterfallFoldTeamRow.tsx` | 新组件（折叠组标题行） |
| `components/WaterfallChildSummaryRow.tsx` | 新组件（child summary 行） |

## 6. 不变量与风险

### 6.1 关键不变量（review 时强制检查）

- **trace_id 仍是单 agent loop 的 ID**（不被 root_trace_id 取代；§9.2 OBS-1 child session 独立性继续保持）
- **root_trace_id 一次写定终身不变**（INV-2）
- **session.active_root_trace_id 边界由 user message 触发清空 + 由首个 trace 创建触发设置**，不在其他位置改写
- **subagent spawn 时复制父的 active_root，复制后两者独立**（child 自己的 active_root 也跟着变化是误用 — 但实际 child session 是 child 视角的"主 agent"，它有自己的 user message 边界吗？— 没有，child session 是 spawn 即跑，没有显式 user message → child 第一个 trace 进 ChatService.chatAsync 时同样走"existingActiveRoot != null → 继承"分支，不会自己起新 root）

### 6.2 边界 case

| 场景 | 行为 |
|---|---|
| 主 session 第一个 trace（user message 起点） | active_root=null → 自己当 root，回填 active_root |
| 主 session 后续 trace（同 user message 内，收 child 结果汇总） | active_root 已有 → 继承 |
| 主 session 收到第二条 user message | clearActiveRootTraceId → 下个 trace 又当新 root |
| TeamCreate spawn child session | child.active_root = parent.active_root |
| Child session 内部第一个 trace | child.active_root 已被 spawn 时设好 → 继承（不自己当 root）|
| Child session 派 child of child | child.active_root 已设 → 复制给 grandchild |
| Child session 完成 | parent.active_root 不变（父 session 可能起新 trace 汇总） |
| Engine 异常 / DB 写入 active_root 失败 | 整个 user message 处理事务回滚；下次重试 active_root 仍 null，新 trace 重新当 root |
| 老 session 第一次升级后被打开 | 走当前 trace_id 反查 root_trace_id（老 trace root=自己），调 `/api/traces/{rootId}/tree`，返回单 trace，FE 渲染等同当前 |

### 6.3 风险

| 风险 | 缓解 |
|---|---|
| `active_root_trace_id` 写失败导致 root 链断 | `@Transactional` + 失败回滚整个 user message；fallback：trace 自己当 root（退化）不会数据丢失 |
| spawn child 漏复制 active_root | 新 IT 覆盖：`SubAgentSpawnIT` 断言 child trace.root_trace_id == parent.active_root_trace_id；review 时 grep `setParentSessionId` 全数核对是否同时 set active_root |
| 多 user message 并发到同一 session | 不存在 — session 状态机本身单线程消费 user message（idle/running/waiting 互斥） |
| FE 折叠状态机 bug（点击展开/收起异常） | 单测覆盖 `toggle` 函数；目检 6f18ecca + 一个无 child 的简单 session |
| API tree 数据量超预期（root 下 100+ child） | 当前实测最大 138 span / 6 session；超过 500 row 加分页（OBS-5 后续做） |

## 7. 回滚预案

### 7.1 M0 回滚（schema）

`V45` / `V44` migration 回滚：

```sql
-- V45 reverse
DROP INDEX idx_llm_trace_root;
ALTER TABLE t_llm_trace DROP COLUMN root_trace_id;

-- V44 reverse
ALTER TABLE t_session DROP COLUMN active_root_trace_id;
```

历史数据无损（root_trace_id = trace_id 是冗余信息，删了不影响 OBS-2 体验）。

### 7.2 M1 回滚（写入路径）

- 单 commit revert：写入路径恢复 OBS-2 状态
- 已写入的 root_trace_id 数据保留（不删，下次重新启用时直接消费）

### 7.3 M3 回滚（FE）

- FE 单 commit revert：`SessionDetail.tsx` 回到按 selectedTraceId 查 spans
- BE API 仍正常 serve（M2 endpoint 保留），无下游影响

## 8. 测试

### 8.1 IT 覆盖

- **新建** `SubAgentSpawnRootTraceIT`：spawn child，断言 child.active_root_trace_id == parent.active_root_trace_id；child 内部 trace.root_trace_id 等于父 active_root
- **新建** `UserMessageBoundaryRootTraceIT`：连续两条 user message，断言两次 trace.root_trace_id 不同
- **新建** `TraceTreeServiceIT`：6f18ecca 风格 fixture（1 parent + 5 child + 多 trace），断言 `getTree(rootId)` 返回完整 traces + spans
- **扩展** `ChatServiceIT`：M1 后断言 user message 处理后 session.active_root_trace_id == 新 trace.trace_id
- **扩展** `AgentLoopEngineIT`：traceLifecycleSink.upsertTraceStub 调用参数包含 root_trace_id

### 8.2 e2e

- 跑一次 6f18ecca 风格调研：发起 → TeamCreate 派 5 child → 等汇总
  - SQL 验证：5 个 child trace 的 root_trace_id 全等于父 trace.trace_id
  - 浏览器目检：SessionDetail 瀑布流默认折叠态显示 parent 主线 + 1 个 fold-team row（"派 5 child"）；展开看到 5 个 child summary；再展开看到 child 内部 spans
- 跑老 session：进入瀑布流，视觉跟 OBS-2 一致

## 9. 命名约束

| 层 | 命名 | 备注 |
|---|---|---|
| DB 字段 | `root_trace_id` / `active_root_trace_id` | 简洁，描述准 |
| Java 字段 | `rootTraceId` / `activeRootTraceId` | camelCase |
| API DTO | `rootTraceId` / `tree` | endpoint 用 `tree` 不用 `investigation` |
| FE 类型 | `TraceTreeDto` / `TraceNodeDto` | 同 BE |
| **UI 文案** | **不出现** "investigation" / "调研流程" / "root trace" / 任何新名词 | 决策 Q4：不引入新概念给用户 |

UI 上仍叫 "trace" / "瀑布流" / "TeamCreate" / "subagent"，跟当前一致。

## 10. 工作量分解

| 阶段 | 文件 | 行数估算 |
|---|---|---|
| M0 schema | V44, V45 | ~30 |
| M1 写入路径 | ChatService, SessionEntity, SessionService impl, AgentLoopEngine, LoopContext, TraceStubRequest, PgLlmTraceStore SQL, TeamCreateTool/CollabRunService spawn | ~250 |
| M1 IT | 4 个 IT 文件 | ~400 |
| M2 Read API | TracesController, TraceTreeService, DTOs (3 个), Repository methods | ~200 |
| M3 FE | SessionDetail, SessionWaterfallPanel, 2 新组件, 1 新 hook, types | ~600 |
| 文档（本需求包） | 4 文件 | 已完成 |

**总计 ~1500 行新增 + ~50 行修改**，跨 7 文件核心改动。
