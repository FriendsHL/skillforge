# OBS-2 PRD — Trace 数据模型统一

## 1. 目标

把 trace / span 数据模型从"双轨混合"收敛到单一来源：

- `t_llm_trace` 升级为**所有 trace 的实体表**（不只是 LLM 维度），含完整聚合字段（duration / tool_call_count / status / error）
- `t_llm_span` 升级为**所有 span 的存储表**（含 LLM / tool / event），加 `kind` 列区分；LLM 特有字段保留为 nullable
- 关闭 `t_trace_span` 写入路径，旧表进入 read-only 归档
- `t_session_message` 加 `trace_id` 列（数据模型完整性：让 message 跟产生它的 trace 关联，受益于未来按 trace 看 messages timeline / replay / 调试，不依赖本次 stats bar 显示决策）

## 2. 范围（M0 → M3）

### M0：Schema 扩展（不破坏既有写入）

| 表 | 变更 |
|---|---|
| `t_llm_trace` | 加 `total_duration_ms BIGINT`、`tool_call_count INT`、`event_count INT`、`status VARCHAR(16)`（ok/error/cancelled/running）、`error TEXT`、`agent_name VARCHAR(256)` |
| `t_llm_span` | 加 `kind VARCHAR(16) NOT NULL DEFAULT 'llm'`（'llm' / 'tool' / 'event'）、`event_type VARCHAR(32)`（ask_user / install_confirm / compact / agent_confirm）、`name VARCHAR(256)`（tool name / event name；LLM 走 model 字段已有）；`provider` / `model` / `input_blob_ref` / `output_blob_ref` / `raw_sse_blob_ref` / `usage_json` / `cache_read_tokens` 已为 nullable，无需改 |
| `t_session_message` | 加 `trace_id VARCHAR(36) NULL` + 索引 `(session_id, trace_id)` |

### M1：写入路径改造

- `ChatEventBroadcaster.messageAppended(sessionId, traceId, message)`：加显式 `traceId` 参数，11 处调用点全改
- `AgentLoopEngine`：
  - 创建 rootSpan 时同时 upsert `t_llm_trace`（写入聚合字段）
  - tool span 写入：原来 `traceCollector.record(toolSpan)` → 新增写入 `LlmTraceStore.write()` 路径（`kind='tool'`）
  - event span 写入：同 tool 路径（`kind='event'` + `event_type=ask_user/install_confirm/compact/agent_confirm`）
  - LLM span：保持现有 OBS-1 observer 路径不变
  - **暂不关闭 `t_trace_span` 写入** — 双写过渡期，观察新表稳定后再关
- `TraceLlmCallObserver` / `LlmTraceStore.write()`：扩展支持 tool/event kind 写入；trace upsert 在 root span 创建时执行

### M2：历史数据迁移（M1 上线后双写期内一次性 SQL）

- `t_trace_span` 中的 `TOOL_CALL` + 4 类 event → `t_llm_span`（`source='legacy'`、`kind='tool' / 'event'`、字段映射见 tech-design.md）
- `t_trace_span` 中的 `AGENT_LOOP` → `t_llm_trace` 聚合字段更新（`status` / `error` / `total_duration_ms` / `tool_call_count`）
- 迁移走 Repeatable migration（参照现有 `R__migrate_legacy_llm_call.sql` 模式），placeholder 控制开关
- ON CONFLICT 防重，可重跑

### M3：API + 前端切换 + 关闭旧轨

- 后端：
  - `/api/traces` 改走 `t_llm_trace`（不再依赖 `span_type='AGENT_LOOP'`），消除 N+1
  - `/api/observability/sessions/{id}/spans` 加可选 `traceId` 过滤参数；kind 扩展支持 `event`
  - `/api/traces/{traceId}/spans` 改走 `t_llm_span` BFS（按 trace_id 直接拉所有 kind）
  - DTO：新增 `EventSpanSummaryDto`；`SpanSummaryDto` union 加 event 类型
- 前端：
  - `SessionDetail.tsx`：`sessionSpansQuery` 改为按 `selectedTraceId` 拉 → 移除 (1) 临时 limit=1000；`SpanSummary` union 加 `EventSpanSummary`
  - `SessionWaterfallPanel.tsx`：渲染 event row（kind tag `k-event`）；filter chips 加 `event` chip
  - `SessionStatsBar.tsx`：messages 字段按 `m.trace_id === selectedTraceId` 过滤；过滤后 0 条 fallback 到 session 级（兼容 trace_id NULL 的老数据）
  - `EventSpanDetailView.tsx`：新增（简化版字段：name + eventType + duration + input/output preview + error）
- **关闭 `t_trace_span` 双写**：在 M2/M3 验证 ≥ 1 周后，AgentLoopEngine 移除 `traceCollector.record(...)` 调用，仅写新表
- **`t_trace_span` 进入 read-only**：保留只读至少 4 周，再视情况 drop（独立 cleanup migration）

## 3. 验收点

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | M0 schema migration 跑通，既有 IT 全过 | `mvn test` |
| 2 | M1 后新 LLM/tool/event 调用同时写入 `t_trace_span` 和 `t_llm_span`，per-trace LLM 调用数两表一致 | SQL 核对（参考 Phase A 诊断 query） |
| 3 | M1 后 `t_session_message` 新写入行 `trace_id` 全部填充非 NULL | `SELECT count(*) FROM t_session_message WHERE created_at > 'M1上线时间' AND trace_id IS NULL` 必须 = 0 |
| 4 | M2 迁移完成后，`t_llm_span where kind='tool'` 计数 ≈ `t_trace_span where span_type='TOOL_CALL'` | SQL 核对 |
| 5 | M3 API `/api/traces` 在 30 trace 的 session 上 RT < 100ms（消除 N+1） | curl + EXPLAIN ANALYZE |
| 6 | M3 前端 session 详情页：切 trace 触发新查询、瀑布流显示该 trace 完整 LLM+tool+event span | 浏览器目检 + Playwright snapshot |
| 7 | M3 前端 stats bar 不再显示 Messages 字段；其他 4 个字段（Spans / Tokens / Duration / Errors）跟选中 trace 切换 | 浏览器目检 |
| 8 | 关闭旧轨后 ≥ 1 周内 `t_trace_span` 没新增 INSERT | `SELECT max(start_time) FROM t_trace_span` 不再变化 |
| 9 | 4 类 event span 在浏览器瀑布流可见、可点击查看详情 | 找含 ASK_USER / COMPACT 的 session 目检 |
| 10 | 既有 IT 文件（`SessionSpansAuthIT` / `SessionSpansMergedIT` 等）扩展覆盖 kind=event + traceId 过滤 | 新增 IT 跑通 |

## 4. 待拍板设计点

| # | 决策 | 推荐 | 备选 |
|---|---|---|---|
| Q1 | 双写过渡期长度 | ✅ **2-3 天双写 + 一致性脚本核对**（写入），M3 一次性切读，关旧写在 M3 完成 1-2 周后 | 1 周（保守）/ big-bang（高风险） |
| Q2 | t_trace_span 旧表保留期 | ✅ **关写后只读 4 周，再 drop** | 永久保留 / 立即 drop |
| Q3 | `kind` 列是否包含 `'agent_loop'` | ✅ **不包含**：trace 实体已经是 `t_llm_trace` 自身一行，不需要额外 span 行；M2 迁移时 AGENT_LOOP 不复制到 `t_llm_span` | 包含 agent_loop（保持兼容旧的 BFS 查询） |
| Q4 | `name` 列怎么用 | ✅ **新增 `name` 列通用表示 span 名**（tool name / event name / 留空给 LLM 走 model 字段） | 复用 `model` 列装 tool name |
| Q5 | tool span 大 input/output 是否抽 blob | ✅ **本次不做**：tool span 走 `input_summary` / `output_summary` 直接存；后续独立项 OBS-3 做 tool blob（接 `t_tool_result_archive`） | 本次扩展 BlobStore 给 tool 用 |
| Q6 | `t_session_message.trace_id` backfill 策略 | ✅ **不 backfill**：历史 NULL；新数据由 ChatService 透传 | 时间窗近似 backfill / tool_use_id 反查 backfill |
| Q7 | 前端 stats bar messages 字段处理 | ✅ **C1 直接移除 Messages 字段**：per-trace 模式语义模糊，spans/tokens/duration/errors 已足够代表 trace 维度 | 混合 fallback（trace 有→per-trace；为 0→session 级） |
| Q8 | M2 历史数据迁移时机 | ✅ **走独立 Repeatable migration**（仿 R__migrate_legacy_llm_call.sql），placeholder 控制 | 直接 V41 versioned migration |
| Q9 | broadcaster 接口扩展方式 | ✅ **A.1**：`messageAppended(sessionId, traceId, message)` 加显式参数 | A.2：在 Message 类加 traceId 字段 / A.3：ThreadLocal |
| Q9b | traceId 来源 + 孤儿处理（隐性风险 §9.1） | ✅ **方案 A**：ChatService 提前生成 traceId 透传 engine；孤儿走 **A.3 fallback**（前端 trace_id 查不到 trace 时归 session 级） | stub 预插（A.2）/ 写入后回填（A.1） |
| Q10 | 失败回滚策略 | ✅ **保留 t_trace_span 写入路径直到 M3 完成 1-2 周后**（M4 才关）：任何 stage 异常切回读旧表，新表保留只读不丢数据 | big-bang 一次切完，回滚需 revert migration |

## 5. 范围之外（Out of Scope）

- **OpenTelemetry / 第三方观测平台对接**：不做，长期自建路径
- **trace search / 旧 trace 归档 / blob TTL**：留给 Phase 4
- **MSG-1 / SESSION-state 等其他需求包**：独立线
- **t_tool_result_archive 表整合**：本次不动（已存在但 0 行，保持现状）
- **t_llm_span rename**：明确不做（用户决策 G 选项放弃）

## 6. 风险

| 风险 | 缓解 |
|---|---|
| 触碰 `AgentLoopEngine`（核心文件清单），改写入路径 | Full 档对抗 review；Plan 先把不变量列清楚（rootSpan 唯一性、tool span 写入时机、SubAgent 派发不打断 trace） |
| 跨模块大改（observability + core + server + dashboard） | 拆 BE / FE 并行 dev；M0/M1/M2/M3 各阶段独立 commit + 独立验证 |
| 历史数据迁移正确性 | 仿 OBS-1 ETL：dry-run + ON CONFLICT 防重；M2 迁移先 small batch（10 session）验证再全量 |
| 双写期间一致性漂移 | 写一致性核对脚本（`scripts/observability/check_dual_write.sql`），CI 周期跑 |
| broadcaster 接口改 11 处调用点 | code-reviewer 全数 grep `messageAppended\(` 校验无遗漏 |
| t_session_message 加索引可能影响写入性能 | 索引设计 `(session_id, trace_id)` 复用既有 `session_id` 前缀；EXPLAIN 验证无 hot path 退化 |

## 7. 工期估算

| 里程碑 | 范围 | 工期 |
|---|---|---|
| M0 | schema migration（V41-V43） | 0.5 天 |
| M1 | 写入路径改造 + IT | 3-5 天 |
| M2 | 历史数据迁移（写 ETL + 验证） | 1-2 天 |
| M3 | API + 前端 + 关闭旧轨 | 3-5 天 |
| 灰度观察 | 双写稳定 → 关旧写 → drop 老表 | 2-4 周 |

**总计：~2-3 周开发 + 2-4 周观察期**

## 8. 依赖

- 前置 Phase A 已完成（双轨数据已对齐 33 trace + 176 span）
- 依赖现有 OBS-1 基础设施：`LlmTraceStore.write()` / `TraceLlmCallObserver` / blob 存储 / Flyway placeholder ETL 模式
- 不依赖外部库 / 新依赖
