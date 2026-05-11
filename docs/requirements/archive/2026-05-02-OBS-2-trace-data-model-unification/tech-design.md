# OBS-2 技术方案

## 1. 目标 schema

### 1.1 `t_llm_trace`（升级为通用 trace 实体）

```sql
ALTER TABLE t_llm_trace
    ADD COLUMN total_duration_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tool_call_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN event_count       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN status            VARCHAR(16) NOT NULL DEFAULT 'running',
    ADD COLUMN error             TEXT,
    ADD COLUMN agent_name        VARCHAR(256);

-- status enum: running | ok | error | cancelled
```

聚合字段在 trace 结束时由 `AgentLoopEngine` 写回。`agent_name` 复用 OBS-1 已有的 `root_name` 语义（保留兼容），新加是为字段自描述。

### 1.2 `t_llm_span`（升级为通用 span 表）

```sql
ALTER TABLE t_llm_span
    ADD COLUMN kind        VARCHAR(16) NOT NULL DEFAULT 'llm',
    ADD COLUMN event_type  VARCHAR(32),
    ADD COLUMN name        VARCHAR(256);

-- kind enum: llm | tool | event
-- event_type (only when kind='event'): ask_user | install_confirm | compact | agent_confirm
-- name: tool name (kind='tool') / event name (kind='event') / NULL for LLM (用 model 字段)

CREATE INDEX idx_llm_span_session_kind ON t_llm_span (session_id, kind, started_at);
CREATE INDEX idx_llm_span_trace_kind   ON t_llm_span (trace_id, kind, started_at);
```

LLM 特有字段（`provider` / `model` / `input_blob_ref` / `output_blob_ref` / `raw_sse_blob_ref` / `usage_json` / `cache_read_tokens` / `finish_reason` / `reasoning_content` / `request_id`）已为 nullable，不需要改。

`tool_use_id` 列已有，对 tool/event kind 仍可用（event 留 NULL）。

### 1.3 `t_session_message`

```sql
ALTER TABLE t_session_message
    ADD COLUMN trace_id VARCHAR(36) NULL;

CREATE INDEX idx_session_message_trace ON t_session_message (session_id, trace_id);
```

历史数据 trace_id = NULL；新写入由 `ChatEventBroadcaster` 接口扩展后传入。

### 1.4 Migration 编号（已落地）

| 编号 | 模块 | 内容 |
|---|---|---|
| V41 | server | `t_session_message.trace_id` |
| V42 | observability | `t_llm_trace` 聚合字段 |
| V43 | observability | `t_llm_span` kind / event_type / name + 索引 |
| R__migrate_legacy_trace_span | observability | M2 历史数据迁移（repeatable，placeholder 控制） |

> server 和 observability 共享全局编号空间（参见 commit 223e5a8）。observability 从 V37 跳到 V42，跳过 server 已占用的 V38–V41，避免 hash 冲突。

## 2. 写入路径改造

### 2.1 `ChatEventBroadcaster` 接口扩展

```java
// 现状
void messageAppended(String sessionId, Message message);

// OBS-2
void messageAppended(String sessionId, String traceId, Message message);
```

`traceId` 可为 null（系统消息 / pre-loop 阶段）。11 处调用点 grep `messageAppended\(` 全改：

- `ChatService.java`: 5 处（user message / notify / tool result × 3 / cancel 路径）
- `AgentLoopEngine.java`: 2 处（assistantMsg / toolResult）
- 测试 / mock: 余下

调用点 traceId 来源：
- `AgentLoopEngine` 内部：`rootSpan != null ? rootSpan.getId() : null`
- `ChatService` 内部：从 `loopCtx` 或 session 当前 active trace（需要新增 `SessionService.getActiveTraceId(sessionId)` 接口或者在 ChatService 内缓存）

### 2.2 `AgentLoopEngine` 改造点

| 改动 | 位置 | 内容 |
|---|---|---|
| rootSpan 创建时同步 upsert t_llm_trace | line 413 后 | 新增 `traceStore.upsertTraceStub(traceId, sessionId, agentId, userId, agentName, startTime)` |
| LLM 调用：保持 OBS-1 路径 | 现状 | 不动 |
| Tool span 写入新表 | line 1186 后 | 新增 `traceStore.writeToolSpan(...)`，保持原 `traceCollector.record(toolSpan)` 不变（双写期） |
| 4 类 event span 写入新表 | line 1032 / 1074 / 1111 / 1127 | 同 tool 模式 |
| trace 结束时聚合写回 t_llm_trace | engine 退出循环各 break 点 | `traceStore.finalizeTrace(traceId, status, error, totalDuration, toolCallCount, eventCount)` |

### 2.3 `LlmTraceStore` 接口扩展

```java
public interface LlmTraceStore {
    // 现状（OBS-1）
    void write(LlmTraceWriteRequest request);          // LLM call upsert + span insert
    Optional<TraceWithSpans> readByTraceId(String traceId);
    List<LlmSpan> listSpansBySession(String sessionId, Instant since, int limit);
    Optional<LlmSpan> readSpan(String spanId);

    // OBS-2 新增
    void upsertTraceStub(TraceStubRequest request);    // rootSpan 创建时；只设 started_at + 元数据
    void writeToolSpan(ToolSpanWriteRequest request);  // tool span 写入
    void writeEventSpan(EventSpanWriteRequest request);// event span 写入
    void finalizeTrace(TraceFinalizeRequest request);  // trace 结束聚合写回

    // OBS-2 新增查询
    List<LlmSpan> listSpansByTrace(String traceId, Set<String> kinds, int limit);  // 路径 3/5
}
```

实现：`PgLlmTraceStore` 加对应方法，事务保护，失败不阻塞主路径（参考 OBS-1 设计 §4.5）。

### 2.4 双写过渡期

M1 上线后：
- 旧写：`AgentLoopEngine.traceCollector.record(...)` → `t_trace_span`（保留）
- 新写：tool/event 同时写 `t_llm_span`；LLM 已经在 `t_llm_span`（OBS-1 既有）；trace 实体在 `t_llm_trace`

观察 ≥ 1 周，双写一致性脚本（见 §6）通过后，M3 关闭旧写。

## 3. 历史数据迁移（M2）

### 3.1 SQL 模板（仿 R__migrate_legacy_llm_call.sql）

```sql
-- 1) AGENT_LOOP → t_llm_trace 聚合字段更新（trace 实体已存在，只补字段）
UPDATE t_llm_trace lt
SET status = CASE WHEN ts.success THEN 'ok' ELSE 'error' END,
    error = ts.error,
    total_duration_ms = ts.duration_ms,
    agent_name = ts.name,
    tool_call_count = (
        SELECT count(*) FROM t_trace_span c
        WHERE c.parent_span_id = ts.id AND c.span_type = 'TOOL_CALL'
    ),
    event_count = (
        SELECT count(*) FROM t_trace_span c
        WHERE c.parent_span_id = ts.id
        AND c.span_type IN ('ASK_USER','INSTALL_CONFIRM','COMPACT','AGENT_CONFIRM')
    )
FROM t_trace_span ts
WHERE ts.span_type = 'AGENT_LOOP' AND ts.id = lt.trace_id;

-- 2) TOOL_CALL → t_llm_span (kind='tool')
INSERT INTO t_llm_span (
    span_id, trace_id, parent_span_id, session_id, agent_id,
    kind, name, tool_use_id,
    input_summary, output_summary, blob_status,
    iteration_index, latency_ms, started_at, ended_at,
    error, source, created_at
)
SELECT
    ts.id, ts.parent_span_id, ts.parent_span_id, ts.session_id, s.agent_id,
    'tool', ts.name, ts.tool_use_id,
    ts.input, ts.output, 'legacy',
    ts.iteration_index, ts.duration_ms, ts.start_time, ts.end_time,
    ts.error, 'legacy', ts.start_time
FROM t_trace_span ts
LEFT JOIN t_session s ON s.id = ts.session_id
WHERE ts.span_type = 'TOOL_CALL'
  AND NOT EXISTS (SELECT 1 FROM t_llm_span ls WHERE ls.span_id = ts.id)
ON CONFLICT (trace_id, span_id) DO NOTHING;

-- 3) Event spans → t_llm_span (kind='event')
INSERT INTO t_llm_span (
    span_id, trace_id, parent_span_id, session_id, agent_id,
    kind, event_type, name,
    input_summary, output_summary, blob_status,
    iteration_index, latency_ms, started_at, ended_at,
    error, source, created_at
)
SELECT
    ts.id, ts.parent_span_id, ts.parent_span_id, ts.session_id, s.agent_id,
    'event',
    LOWER(ts.span_type),  -- ASK_USER → ask_user, INSTALL_CONFIRM → install_confirm, etc.
    ts.name,
    ts.input, ts.output, 'legacy',
    ts.iteration_index, ts.duration_ms, ts.start_time, ts.end_time,
    ts.error, 'legacy', ts.start_time
FROM t_trace_span ts
LEFT JOIN t_session s ON s.id = ts.session_id
WHERE ts.span_type IN ('ASK_USER','INSTALL_CONFIRM','COMPACT','AGENT_CONFIRM')
  AND NOT EXISTS (SELECT 1 FROM t_llm_span ls WHERE ls.span_id = ts.id)
ON CONFLICT (trace_id, span_id) DO NOTHING;
```

### 3.2 防重 + 幂等

- `ON CONFLICT (trace_id, span_id) DO NOTHING` 防同一 span 重复插入
- `NOT EXISTS` 检查避免覆盖已有 live 行（M1 双写期内可能 tool span 已经在新表）
- 走 Flyway repeatable migration（placeholder `etl_mode_obs2_migrate` 控制开关），可重跑

### 3.3 字段映射注意点

| 来源（t_trace_span） | 目标（t_llm_span） | 备注 |
|---|---|---|
| `id` | `span_id` | 直接复用 ID（与 OBS-1 ETL 同模式） |
| `parent_span_id` | `parent_span_id`, `trace_id` | trace_id 用 parent_span_id（即 AGENT_LOOP.id） |
| `name` | `name` | 通用 span 名 |
| `tool_use_id` | `tool_use_id` | 仅 tool kind |
| `input` / `output` | `input_summary` / `output_summary` | t_trace_span 已经是简版字段；不抽 blob |
| `iteration_index` | `iteration_index` | 直接 |
| `duration_ms` | `latency_ms` | 字段名映射 |
| `start_time` / `end_time` | `started_at` / `ended_at` | 字段名映射 |
| `success` (true/false) | `error_type` 是否 NULL 体现 | `error` 字段同步 |

## 4. API 改造（M3）

### 4.1 后端 endpoint

| Endpoint | 现状 | OBS-2 | 备注 |
|---|---|---|---|
| `GET /api/traces?sessionId=X` | `t_trace_span where span_type='AGENT_LOOP'` + N+1 | `t_llm_trace where session_id=X`，聚合字段直接读 | 性能大幅提升 |
| `GET /api/traces/{traceId}/spans` | `t_trace_span` BFS | `t_llm_span where trace_id=?` 按 started_at ASC | 取消 BFS（kind 列已区分） |
| `GET /api/observability/sessions/{id}/spans` | merge 两表 + limit 200 | 仅 `t_llm_span where session_id=?` 加可选 `traceId` 过滤 | 移除 service 层 merge |
| `GET /api/observability/spans/{spanId}` | `t_llm_span` only | 同左（兼容 kind） | 详情视图按 kind 路由 |
| `GET /api/observability/tool-spans/{spanId}` | `t_trace_span` | `t_llm_span where kind='tool'` | 同 endpoint，内部走新表 |
| 新增：`GET /api/observability/event-spans/{spanId}` | 不存在 | `t_llm_span where kind='event'` | event 详情视图 |

### 4.2 前端类型

```typescript
// types/observability.ts
export interface EventSpanSummary {
  kind: 'event';
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  startedAt: string;
  endedAt: string | null;
  latencyMs: number;
  eventType: 'ask_user' | 'install_confirm' | 'compact' | 'agent_confirm';
  name: string;
  success: boolean;
  error: string | null;
  inputPreview: string | null;
  outputPreview: string | null;
}

export type SpanSummary = LlmSpanSummary | ToolSpanSummary | EventSpanSummary;

// api/index.ts
export interface GetSessionSpansParams {
  traceId?: string;   // OBS-2 新增
  since?: string;
  limit?: number;
  kinds?: Array<'llm' | 'tool' | 'event'>;
}
```

### 4.3 前端组件改动

| 文件 | 改动 |
|---|---|
| `SessionDetail.tsx` | `sessionSpansQuery` 改成依赖 `selectedTraceId`；移除 (1) `limit=1000`；queryKey 加 `selectedTraceId` |
| `SessionWaterfallPanel.tsx` | 渲染 event row（`k-event` tag）；filter chips 加 `event` chip |
| `SessionStatsBar.tsx` | **移除 Messages 字段**（Q7 决策 C1）；保留 Spans / Tokens / Duration / Errors 4 项跟选中 trace 走（数据源已切到 per-trace） |
| `SpanDetailTabs.tsx` | 加 event kind 路由 |
| `EventSpanDetailView.tsx` | 新建：name + eventType + duration + input/output preview + error |
| `traces.css` | `.tr-kind-tag.k-event` + 颜色（建议琥珀色，跟 LLM 紫 / tool 绿区分） |

## 5. 不变量 / 关键约束

1. **rootSpan 唯一性**：每个 trace 在 t_llm_trace 只有一行，UUID 由 AgentLoopEngine 生成；upsert 通过 PRIMARY KEY (trace_id) 保证
2. **span_id 全局唯一**：跨 LLM / tool / event span 共享 t_llm_span 主键，要求 UUID 不冲突。AgentLoopEngine 用 UUID v4，足够安全；t_trace_span ETL 复用旧 ID 也安全（旧 ID 也是 UUID v4，跟新 UUID 不冲突概率 ~1e-37）
3. **tool_use_id 配对**：tool span 的 tool_use_id 必须能在 message 序列里找到对应 tool_use 块；本次不改这个语义
4. **写入失败不阻塞主路径**：所有 LlmTraceStore 写入异步执行（继承 OBS-1 设计），失败 log 不抛出
5. **trace_id 从一而终**：`AgentLoopEngine` 中的 `rootSpan.getId()` 全程不变，所有 span 都用它作 traceId；ChatEventBroadcaster 接口拿到的 traceId 也来自同一来源
6. **kind 不可变**：一旦写入 kind 不能改（避免读取一致性问题）

## 6. 双写一致性核对脚本

`scripts/observability/check_dual_write.sql`：

```sql
-- 1. trace 一致性：每个 t_trace_span AGENT_LOOP 都应有 t_llm_trace
SELECT 'missing_trace' AS check, count(*)
FROM t_trace_span ts
LEFT JOIN t_llm_trace lt ON lt.trace_id = ts.id
WHERE ts.span_type = 'AGENT_LOOP' AND lt.trace_id IS NULL
  AND ts.start_time > 'M1上线时间';  -- 只看新数据

-- 2. tool span 一致性：每个 t_trace_span TOOL_CALL 都应有 t_llm_span (kind='tool')
SELECT 'missing_tool' AS check, count(*)
FROM t_trace_span ts
LEFT JOIN t_llm_span ls ON ls.span_id = ts.id AND ls.kind = 'tool'
WHERE ts.span_type = 'TOOL_CALL' AND ls.span_id IS NULL
  AND ts.start_time > 'M1上线时间';

-- 3. LLM call 数一致（per-trace）
WITH cmp AS (
  SELECT
    ts.id AS trace_id,
    (SELECT count(*) FROM t_trace_span c
       WHERE c.parent_span_id=ts.id AND c.span_type='LLM_CALL') AS legacy,
    (SELECT count(*) FROM t_llm_span ls
       WHERE ls.trace_id=ts.id AND ls.kind='llm') AS new
  FROM t_trace_span ts
  WHERE ts.span_type='AGENT_LOOP' AND ts.start_time > 'M1上线时间'
)
SELECT 'mismatched_llm_count' AS check, count(*)
FROM cmp WHERE legacy != new;
```

CI 周期跑（每天 1 次或 PR 触发），输出 ≠ 0 时告警。

## 7. 回滚预案

| Stage | 出问题怎么办 |
|---|---|
| M0 schema migration | revert migration（drop 列 + drop 索引）；nullable 列 drop 不影响既有行 |
| M1 写入路径 | 还原 `ChatEventBroadcaster` 接口 + AgentLoopEngine `traceStore.write*` 调用；旧轨 t_trace_span 仍在写，无数据丢失 |
| M2 历史数据迁移 | 写删除 SQL：`DELETE FROM t_llm_span WHERE source='legacy' AND kind IN ('tool','event')`；trace 聚合字段 reset 到默认值 |
| M3 API 切流 | 前端 / 后端各自 revert commit；旧表 t_trace_span 仍读得到，无 user-facing 中断 |
| 关闭旧写后异常 | 已经过 ≥ 1 周观察 + 一致性脚本校验；理论上不会出。最坏情况：恢复 `traceCollector.record(...)` 调用 |

## 8. 测试计划

### 单元测试

- `LlmTraceStore` 新方法（`upsertTraceStub` / `writeToolSpan` / `writeEventSpan` / `finalizeTrace` / `listSpansByTrace`）单测
- `ChatEventBroadcaster` 11 处调用点 mock 校验 traceId 正确传入
- DTO mapping 单测

### 集成测试（IT）

- 现有 IT 全部通过：`SessionSpansAuthIT` / `SessionSpansMergedIT` / `LlmSpanControllerSemaphoreLeakTest` / `LlmTraceStoreFailDoesNotBlockChatIT`
- 新增 IT：
  - `traceId` 过滤参数生效
  - `kind=event` 序列化 / 反序列化
  - 双写期 tool 在两表都有
  - M2 ETL 重跑幂等
  - `t_session_message.trace_id` 写入正确
  - `/api/traces` 性能 < 100ms（30 trace session）

### Phase Final 浏览器目检

- session `2fb749a5-…b465`（30+ trace）：
  - 切 trace 触发新查询
  - 瀑布流完整显示该 trace 所有 span（含 4 类 event）
  - stats bar messages 跟选中 trace 切换
- 找含 ASK_USER / COMPACT 的 session 验证 event span 详情视图

## 9. 已识别的隐性风险（self-review 提前暴露）

1. **`SessionService` / `ChatService` 的 traceId 来源** ✅ **决策：方案 A + A.3 孤儿 fallback**：
   - ChatService 在调用 `agentLoop.run(loopCtx, traceId)` 之前用 UUID 生成 traceId，写入 `loopCtx.traceId` 字段
   - AgentLoopEngine `line 413` 的 `rootSpan = new TraceSpan("AGENT_LOOP", ...)` 改成接收外部 traceId（不再独立生成）
   - 所有 `messageAppended(sessionId, traceId, message)` 调用点（ChatService 5 处 + Engine 2 处）共用同一个 traceId
   - **孤儿处理（A.3）**：如果 ChatService 已写 user message（trace_id=X）但 engine 未启动（agent disabled / 实例化失败），`t_llm_trace` 表里没有 X 行；前端拉 trace 时识别 NULL/不存在并归 session 级 fallback（兼容老数据 trace_id NULL 的同一逻辑分支，无额外代码）
   - 副作用：`AgentLoopEngine.run()` 签名加 `String traceId` 参数；`LoopContext` 加 `traceId` 字段；约 10-15 处调用点 + 测试 mock 更新
2. **SubAgent 派发**：父 agent 的某个 LLM tool_use 触发 SubAgent 创建 child session，child session 跑自己的 trace。child session 的 messages.trace_id 走 child 自己的 trace_id，跟父分开 — 这是预期，无需特殊处理
3. **`compactSpan` 的特殊语义**：context compaction 不在主 agent loop 内 trace，是后台编排（CompactionService）。compact 触发的 span 应该归到哪个 trace？现状 `t_trace_span COMPACT (count=1)` 是有 parent_span_id 指向 AGENT_LOOP 的，所以归当前 trace。本次保持现状
4. **CollabRun 多 agent**：父 session 有 collab_run_id，多个 agent 并行各跑自己的 trace；本次不做特殊处理，保持每个 agent 独立 trace_id

## 10. 实施顺序（已锁定）

```
M0  schema migration (V41-V43)            [1 PR, BE-only]    读: OBS-1 现状
  ↓
M1  写入路径改造（双写：旧表 + 新表）       [2 PR: BE / IT]    读: OBS-1 现状
    + ChatService 提前生成 traceId 透传
  ↓
M1.5 观察 2-3 天 + 双写一致性脚本          [no code]          读: OBS-1 现状
  ↓
M2  历史数据迁移 (R__migrate_legacy_trace_span) [1 PR]      读: OBS-1 现状
  ↓
M3  API + 前端切读 (一次性)                [2 PR: BE / FE]    读: 切到 t_llm_span
    + per-trace fetch + event kind UI
  ↓
M3.5 观察 1-2 周（生产实际使用新读路径）   [no code]          读: 新表
  ↓
M4  关闭 t_trace_span 写入                 [1 PR, BE-only]    读: 新表 / 写: 单轨
  ↓
M5  观察 4 周                              [no code]
  ↓
M6  drop t_trace_span (cleanup migration)  [独立 PR，可推迟]   写: 单轨
```

**关键约束**：
- 双写期 M1-M3 读路径不动，避免一次引入两个变量；M3 才切读 → 写问题/读问题精确归因
- M3 切读时新表已经过 M1 双写（实时新数据）+ M2 历史数据迁移（含 4-29 前 legacy）共同覆盖，数据完整
- M4 关旧写后**写**变成单轨；M5 期间**读**的容错保险已撤；M6 drop 旧表是物理 cleanup（M5 没事就走）

每个 PR 独立可发布、独立可回滚。

## 11. 参考

- [OBS-1 PRD](../../archive/2026-04-29-OBS-1-session-trace/prd.md)
- [OBS-1 tech-design.md](../../archive/2026-04-29-OBS-1-session-trace/tech-design.md)
- 现有 `R__migrate_legacy_llm_call.sql`（M2 ETL 模板）
- `pipeline.md` 第三节（Full 档流程）
