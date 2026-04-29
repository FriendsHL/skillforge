# OBS-1 技术方案

---
id: OBS-1
status: design-draft
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-04-29
---

## TL;DR

新增独立 LLM 观测链路：`skillforge-observability` module、`LlmTraceStore`、DB 摘要行、文件 blob raw payload，以及可按需加载完整 payload 的 Session Detail UI。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| MVP 使用 PostgreSQL + 文件 blob | 当前是单用户、点查为主，且已有 Flyway 管理。 | ClickHouse 等到规模或跨 trace 分析需求出现再引入。 |
| live payload 永远写 DB 摘要 + blob_ref | DB row 体积可预测，UI 路径一致。 | 按阈值落 blob 会增加分支。 |
| 流式调用保存累加 JSON + raw SSE | 默认看结构化 JSON，必要时排查 chunk 边界 bug。 | 只存累加 JSON 会丢流式原始形态。 |
| 使用 Observer，不用 AOP | stream callback、provider 工厂创建、session 上下文都会让 AOP 变得不透明。 | Spring AOP 因线程和回调限制被排除。 |
| legacy span 用 `source=legacy` | 旧 `t_trace_span` 只有摘要，raw payload 无法还原。 | 到处判断 null blob_ref 更隐晦。 |
| OBS-1-4 / OBS-1-5 推迟 | 先看真实 payload 和用户使用方式，再设计特殊诊断 UI。 | 现在做会偏猜测。 |

## 模块结构

新增独立 maven module `skillforge-observability`，与 core / tools / server 同级。

```text
skillforge/
├── skillforge-core
├── skillforge-tools
├── skillforge-observability
│   ├── api/
│   │   ├── LlmTraceStore
│   │   └── BlobStore
│   ├── impl/
│   │   ├── PgLlmTraceStore
│   │   ├── FileSystemBlobStore
│   │   └── TraceLlmCallObserver
│   ├── entity/
│   │   ├── LlmTraceEntity
│   │   └── LlmSpanEntity
│   ├── etl/
│   │   └── LegacyLlmCallMapper
│   └── migration/
│       ├── Vxx__create_llm_trace_tables.sql
│       └── Vxx__migrate_legacy_llm_call.sql
├── skillforge-server
├── skillforge-dashboard
└── skillforge-cli
```

依赖方向：

- `skillforge-core` 定义 observer 接口，不依赖实现。
- `skillforge-observability` 依赖 core，提供 store、entity、blob 和 observer 实现。
- `skillforge-server` 依赖 core + observability，启动时自动收集 observers 注入 provider。
- 依赖方向是 core -> observability -> server，无循环依赖。

## Observer 模式

```java
public interface LlmCallObserver {
    void beforeCall(LlmCallContext ctx);
    void afterCall(LlmCallContext ctx, LlmResponse response);
    void onError(LlmCallContext ctx, Throwable err);
}
```

Provider 构造时接收 `List<LlmCallObserver>`，每次通知都必须隔离异常：

```java
private void notifyBefore(LlmCallContext ctx) {
    for (var observer : observers) {
        try {
            observer.beforeCall(ctx);
        } catch (Exception e) {
            log.warn("LLM call observer failed", e);
        }
    }
}
```

### 为什么不用 AOP

1. 流式回调让 AOP 的零侵入承诺失效：`chatStream(req, handler)` 返回时 SSE delta 仍在异步接收，AOP 拿不到完整 response，最终仍要 wrap handler。
2. OkHttp 异步回调线程和 `@Async` SubAgent 派发不会自动传播 ThreadLocal；既然要手动传播，不如显式传 `LlmCallContext`。
3. AOP 切面拿不到 sessionId / agentId，仍需要调用侧提前设置上下文。
4. Spring AOP 对工厂方法直接创建的 provider 对象不可靠，改成 AspectJ 会让复杂度上升。
5. 项目已经有 `@Transactional` self-invocation 这类 AOP footgun，不适合再叠一层隐式 trace 行为。

## 存储抽象

所有 trace 写入和查询都走 `LlmTraceStore`，禁止 controller / service 直接写 SQL 或暴露 ResultSet / JSONB / SQL 片段。本期只交付 `PgLlmTraceStore`；未来如需 ClickHouse，新增实现并做停服 ETL，不动业务调用方。

```java
public interface LlmTraceStore {
    void writeTrace(LlmTrace trace);
    void writeSpan(LlmSpan span);
    void writeBlob(String blobRef, byte[] payload);
    Optional<LlmTrace> getTrace(String traceId);
    List<LlmSpan> getSpans(String traceId);
    Optional<byte[]> getBlob(String blobRef);
}
```

## 数据模型 / Migration

新建两张表：

- `t_llm_trace`
- `t_llm_span`

核心字段：

- `trace_id`
- `span_id`
- `parent_span_id`
- `session_id`
- `agent_id`
- `model`
- `provider`
- `input`
- `output`
- `input_blob_ref`
- `output_blob_ref`
- `input_tokens`
- `output_tokens`
- `latency_ms`
- `usage_json`
- `cost_usd`
- `finish_reason`
- `request_id`
- `reasoning_content`
- `error`
- `error_type`
- `source`
- `created_at`

`source` 取值：

- `live`：OBS-1 上线后的新数据，有 raw blob。
- `legacy`：从旧 `t_trace_span` ETL 过来的摘要数据，没有 raw blob。

## A3 ETL 设计

评估结论：

- `t_trace_span` 没有 attributes JSONB，旧 schema 是固定列。
- 旧 LLM_CALL row 的 input / output 只存摘要，不是完整 payload。
- 旧数据拿不到完整 input/output、cache_read_tokens、usage_json、cost、finish_reason、request_id、reasoning_content、error_type。
- 失败 LLM 调用可能没有 LLM_CALL row。
- ETL 可行性中等：trace_id / session_id / model / tokens / latency 等关联字段可迁移，内容字段稀缺。

字段映射：

| `t_llm_span` 字段 | 来源 | 备注 |
| --- | --- | --- |
| `trace_id` | `t_trace_span.id` | 直接复制 |
| `span_id` | `t_trace_span.id` | 老 schema 没分 trace/span，复用 |
| `parent_span_id` | `t_trace_span.parent_span_id` | 直接复制 |
| `session_id` | `t_trace_span.session_id` | 直接复制 |
| `agent_id` | `LEFT JOIN t_session.agent_id` | 老 session 已删则 NULL |
| `model` | `t_trace_span.model_id` | 直接复制 |
| `provider` | `resolve_provider_from_model_id(model_id)` | 前缀解析，兜底 unknown |
| `input` | `t_trace_span.input` | 摘要，非完整 payload |
| `output` | `t_trace_span.output` | 摘要，非完整 payload |
| `input_blob_ref` | NULL | 老 row 无 raw blob |
| `output_blob_ref` | NULL | 老 row 无 raw blob |
| `input_tokens` / `output_tokens` | `t_trace_span.input_tokens` / `output_tokens` | 直接复制 |
| `latency_ms` | `t_trace_span.duration_ms` | 直接复制 |
| `iteration_index` | `t_trace_span.iteration_index` | 直接复制 |
| `created_at` | `t_trace_span.start_time` | 直接复制 |
| `error` | `t_trace_span.error` | 通常为空，失败 row 可能没写 |
| `cache_read_tokens` / `usage_json` / `cost_usd` / `finish_reason` / `error_type` / `request_id` / `reasoning_content` | NULL | 物理拿不到 |
| `source` | `'legacy'` | 新表字段 |

幂等性：

- unique constraint `(trace_id, span_id)`
- `ON CONFLICT DO NOTHING`

旧表清理：

- `t_trace_span` LLM_CALL row 先保留 3-6 个月，作为回滚安全网。
- 稳定后再写后续 migration 批量删。

ETL 风险：

- 数据量未知：Plan 阶段先执行 `SELECT count(*) FROM t_trace_span WHERE span_type='LLM_CALL'`。
- 超过 50 万行时降级为异步 ETL service，避免 Flyway 启动卡死。
- provider 解析漏 case 时允许写 ad-hoc `UPDATE` 修正。
- session 已删时 `agent_id` 为 NULL，UI 显示 agent unknown。
- 老 session 的失败 LLM 调用无法重建，新数据从 OBS-1 上线后开始记录失败 row。

## Blob 存储

payload 目录结构：

```text
data/llm-payloads/{yyyy-MM-dd}/{traceId}/{spanId}-{request|response}.json
```

规则：

- DB 永远只存 32KB 摘要 + `*_blob_ref`。
- raw request / response 写文件 blob。
- 流式调用保存累加后 JSON，同时保存 raw SSE。
- PG backup 不包含 payload 文件；文件丢失只影响 raw 查看，DB 摘要仍可用。

## 主链路保护

硬约束：

1. Observer 调用一律 try-catch，trace 失败只 log，绝不抛给业务。
2. Blob 落盘走异步 executor。
3. DB 写入失败也不能阻断 LLM 调用。
4. Payload 序列化失败写空 JSON + warn。
5. Executor 队列满时 drop + 计数告警，不阻塞业务也不无界堆积。

## 安全

- 剥离 `Authorization`、`x-api-key`、`Bearer *`、`api-key`、`apikey` 等 HTTP headers。
- MVP 不主动扫描 request body secret。
- UI 加载完整 blob 必须走受控 API，不暴露任意文件路径。
- 错误信息不能把内部路径或 secret 直接返回给前端。

## 前端设计

- 改造 Sessions 页进入 Session Detail。
- Session Detail 展示消息时间线和相关 spans。
- LLM span 详情提供“原始请求 / 原始响应”tabs。
- 默认展示 32KB 摘要，用户显式点击后加载完整 blob。
- `source=legacy` 时禁用完整 payload 按钮，并解释“历史 session 无 raw payload”。
- SubAgent 调用 span 展示“跳转到子 session”。
- 大 payload 需要分块或懒渲染，避免 UI 卡顿。

## 存储引擎选择

当前使用 PostgreSQL，不引 ClickHouse。理由：

- SkillForge 当前单用户 / 单机 / 低并发。
- OBS-1 主查询是按 trace_id 拉 span 树、按 span_id 拉单 row payload，属于 PG 擅长的点查。
- ClickHouse 会引入 docker-compose 依赖、备份、监控、启动时间和 Flyway 之外的新运维面。
- payload 走文件系统，未来真要切 ClickHouse 时 ETL 成本可控。

触发重评 ClickHouse 的条件：

- `t_llm_span` 行数超过 5M 且按 trace_id 查询 P95 超过 100ms。
- 出现真实跨 trace 聚合分析需求，例如按 model 统计 7 天 token 用量。
- SkillForge 进入多机 / 多用户 / 高并发部署。

不做双写或运行时切换。切换流程是停服、跑 ETL、改配置、起服。

## 实施计划

- [ ] Full Pipeline plan 阶段确认草拟决策。
- [ ] 统计 legacy `t_trace_span` 行数。
- [ ] 新增 `skillforge-observability` module。
- [ ] 在 core 中新增 `LlmCallObserver` 和上下文类型。
- [ ] 新增 `LlmTraceStore`、`BlobStore`、PG store、filesystem blob store。
- [ ] 新增 schema 和 legacy ETL。
- [ ] 接入 provider observer。
- [ ] 增加 server API。
- [ ] 增加 dashboard Session Detail 和 Payload Viewer。
- [ ] 用真实 session payload 验证。

## 测试计划

- [ ] live span store/query 单测。
- [ ] `source=legacy` ETL 测试。
- [ ] observer 失败隔离测试。
- [ ] blob 脱敏和读取测试。
- [ ] provider stream lifecycle 测试。
- [ ] 前端摘要、完整加载、legacy state 测试。
- [ ] 真实浏览器检查。
- [ ] 跟生产数据量相当的环境跑一次 ETL，验证时间和字段正确性。

## 不在本次范围

- OBS-1-4 / OBS-1-5。
- 跨 session 全文检索。
- 流量重放。
- 自动告警。
- LLM call 录制为 eval scenario。
- `t_trace_span` LLM_CALL row 物理删除。

## 与 P15 关系

P15 GetTraceTool 继续保留裁剪输出给 Agent 用。OBS-1 的完整 payload 是给人看的旁路通道，二者不互相替代。

## 风险

- Flyway ETL 在大表上可能过慢；用 row-count gate 和 async ETL fallback 缓解。
- raw payload 文件增长较快；用 retention 和清理任务缓解。
- stream 生命周期接入不慎可能漏最终数据；评审时必须重点看 stream lifecycle。
- observer 写入如果隔离不严，会影响聊天主链路；必须用失败隔离测试锁住。

## 评审记录

**Plan 阶段**（3 轮对抗循环 PASS）：
- R1 → FAIL（4 真 blocker：B1 Tool span 通路 / B2 chatStream 握手 retry 与 observer 重复触发 / B3 t_llm_trace upsert 幂等 / B4 ProviderName 维度紊乱）
- R2 → FAIL（W-N2 SubAgent 字段位置升 blocker / W-N6 `@Bean ObjectMapper` 抑制 auto-config 升 blocker）
- R3 → PASS（修复 W-N2 改 SubagentSessionId 到 ToolSpan + 改用 `Jackson2ObjectMapperBuilderCustomizer`）

**Code review 阶段**（2 轮，第 3 轮用户决定跳过直接交付）：
- R1 → FAIL（4 真 blocker：BE-B1 Semaphore 4 条 404 早返回不释放 / BE-B2 12 个 plan 测试缺失 / FE-B-USER-1 auto-select spans[0] / FE-B1 SpanDetailPanel 缺 key={span.spanId}）
- R2 → FAIL（BE-W6 user_id 过滤缺失升 blocker，三层兜底全无）
- R3 → 用户验收（修了 W6 + W1-W5 顺手 warning + 跳过 R3 复审）

## 交付补丁（plan 之外）

实施过程发现的 plan 漏判 / 现有代码 bug：

1. **`LlmSpanEntity` JSONB 列缺类型注解**（critical）
   - 现象：`PSQLException: column "attributes_json" is of type jsonb but expression is of type character varying`，每次 LLM call 写库 100% 失败，被 PRD 验收 #6 的"trace 持久化失败不影响主调用"路径**正常吞掉** → 静默丢失
   - Root cause：plan §3.2 schema 用了 JSONB，但 entity 字段 `private String attributesJson;` 没注 `@JdbcTypeCode(SqlTypes.JSON)`。Hibernate 6 默认走 VARCHAR 路径
   - Fix：`LlmSpanEntity.usageJson` + `attributesJson` 都加 `@JdbcTypeCode(SqlTypes.JSON)`
   - 教训：JSONB column + Hibernate 6 是可重复的 footgun，应在 plan §3.5 footgun 清单里加入

2. **`LlmObservabilityConfig` 与 Flyway 循环依赖**
   - 现象：Spring Boot 3.0+ 默认禁循环依赖，启动失败
   - 链路：`Flyway` → `LlmObservabilityConfig` → `LlmCallObserverRegistry` → `TraceLlmCallObserver` → `PgLlmTraceStore` → `LlmTraceRepository` → `EntityManagerFactory` → `Flyway`
   - Fix：把 `FlywayConfigurationCustomizer` bean 拆到独立的 `ObservabilityFlywayConfig`（无构造器依赖），断开循环

3. **R__migrate_legacy_llm_call.sql GUC 路径无设值代码**
   - 现象：mode=flyway 时 SQL 仍走 ELSE 分支不跑 ETL
   - Root cause：plan 设计用 PG GUC `skillforge.etl_legacy_mode`，但没人写 `SET` 该 GUC 的代码
   - Fix：SQL 改用 Flyway 已注入的 `${etl_mode}` placeholder 直接判断

4. **ETL 与 live observer 双写造成重复**
   - 现象：每个新 LLM call 在 `t_llm_span` 里出现两次（source=live + source=legacy）
   - Root cause：plan §3.4 设计的"双写 3-6 月作为 rollback 安全网"叠加 ETL 没区分 pre/post-OBS-1
   - Fix：V36（5s 窗口 dedup，已应用） + V37（1s + iteration_index 收窄，retighten） + R__ NOT EXISTS guard 防未来重入

5. **`Message.getTextContent()` 不识别 `tool_result` 类型**（pre-existing bug）
   - 现象：所有历史 + 新的 TOOL_CALL trace span `output` 列永远是空字符串
   - Root cause：`getTextContent()` 只识别 `block.type=="text"`，但 `ContentBlock.toolResult()` 造的是 `type="tool_result"`，content 在 `block.getContent()` 不在 `block.getText()`
   - Fix：`Message.getTextContent()` 加 `tool_result` 分支 + `extractToolResultText` helper 处理 String/List<ContentBlock>/List<Map> 三种 content 形态

6. **OBS-1 4 个 controller 缺 user_id 过滤**（auth bypass）
   - 现象：任何已认证用户可猜 sessionId 访问他人 LLM/Tool span 数据
   - Root cause：plan §6.3 / §7.3 明文要求 user_id 过滤，但 backend dev 实现时 controller 只取 `@PathVariable sessionId`，service 层无 userId 参数；项目无 Spring Security filter 兜底
   - Fix：4 个 controller 加 `@RequestParam Long userId` + 复用 `ChatController.requireOwnedSession` 模式（提取为 `ObservabilityOwnershipGuard` helper）；service 加 userId 防御性双校验；前端 `api/index.ts` 4 个 OBS-1 函数签名加 userId
   - 测试：`SessionSpansAuthIT` 17 用例覆盖 4 controller × {200/403/400/404}

## Follow-up（不阻塞交付，留给后续 PR）

- BE-W1 完整 `@SpringBootTest+TestRestTemplate HttpIT`（项目无 SpringBootTest infra，留 follow-up）
- 8 个 plan §9.1/§9.2 应修测试（SubagentSessionResolverTest / HeaderSanitizerTest / FileSystemBlobStoreTest / BlobPathTraversalIT / TraceLlmCallObserverTest / ProviderNameAlignmentTest / LlmTraceControllerIT / OpsObserverWiredIT / BlobStreamingIT / BlobReadConcurrencyLimitIT）
- `SpanDetailPanel.crossSpanIsolation.test.tsx` fixture 类型与 LlmSpanSummary interface 不匹配（tsconfig 排除测试）
- `t_llm_span.user_id` 列 + SQL 层 user_id 过滤（如需 service-to-service 调用，纯 session 校验不够）
- OBS-1-4（compact 验证视角）+ OBS-1-5（provider quirk 检测）按 PRD V2 规划
