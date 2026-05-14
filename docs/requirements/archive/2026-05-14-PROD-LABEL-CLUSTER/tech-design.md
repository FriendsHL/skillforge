# PROD-LABEL-CLUSTER 技术方案

---
id: PROD-LABEL-CLUSTER
status: ratified
mode: mid
created: 2026-05-14
updated: 2026-05-14
---

## 0. 现状证据（开工前必跑 Phase 1.0 证伪）

### 0.1 复用目标已存在 — 文件路径

| 复用项 | 文件路径 | 用法 |
|---|---|---|
| Signal-based 6 reason 检测 | `skillforge-server/src/main/java/com/skillforge/server/service/TraceScenarioImportService.java:136-151` | V1 Stage A 直接调，输出复制到 t_session_annotation |
| Trace 查询 | `skillforge-server/src/main/java/com/skillforge/server/repository/LlmTraceRepository.java` | 取 sessionId → trace 集 |
| Span 查询 | `skillforge-server/src/main/java/com/skillforge/server/repository/LlmSpanRepository.java` | 取 traceId → span 集（tool span 的 error/errorType） |
| Memory-curator agent 模板 | `skillforge-server/src/main/java/com/skillforge/server/memory/llmsynth/MemoryCuratorBootstrap.java:43`<br>`+ classpath:memory-curator-system-prompt.md`<br>`+ V69 Flyway seed t_agent` | V1 session-annotator agent 完全照抄此模板 |
| Memory-curator 工具模板 | `skillforge-server/src/main/java/com/skillforge/server/tool/memorysynth/{ClusterMemoriesTool,CreateMemoryProposalTool,ListActiveUsersTool,ListMemoryCandidatesTool}.java` | V1 session-annotator 的 SessionAnnotationWrite / SessionFetch 复制此 4 工具组织方式 |
| **P12 ScheduledTask 框架 + V69 memory-curator dogfood 模式** | `t_scheduled_task` 表 + V69 seed row（concurrency_policy='skip-if-running'，hourly cron 表达式 + agent_id 指向 session-annotator）| V1 走 **1 cron + 1 agent + 多 tool orchestrate pipeline** 模式（跟 V69 同款），不用 Spring @Scheduled。优势：用户可在 dashboard `/schedules` 关 / 调频 / skip-if-running 内建 |
| SubAgent dispatch | `skillforge-server/src/main/java/com/skillforge/server/subagent/SubAgentRegistry.java`<br>`+ tool/SubAgentTool.java` | session-annotator 派发走它，**不开新 dispatch 路径** |
| Traces dashboard 详情页 | `skillforge-dashboard/src/components/.../Traces.tsx`（含 query param 深链接，OBS-2 已实现） | Pattern member 跳转目标，零改动 |
| EvalAnnotationEntity 模型形态 | `skillforge-server/src/main/java/com/skillforge/server/entity/EvalAnnotationEntity.java` | 人工修标 V3 复用，本包不动 |

### 0.2 不动的核心文件清单（grep diff 验证）

| 文件 | 不动理由 |
|---|---|
| `skillforge-server/.../entity/SessionEntity.java` | 触碰即 identity-column-on-rewrite + persistence-shape-invariant 双红灯 |
| `skillforge-server/.../entity/SessionMessageEntity.java` | 同上 |
| `skillforge-server/.../service/ChatService.java` | 核心文件 |
| `skillforge-server/.../service/SessionService.java` | 核心文件 |
| `skillforge-server/.../service/CompactionService.java` | 核心文件 |
| `skillforge-core/.../engine/AgentLoopEngine.java` | 核心文件 |
| `skillforge-server/.../service/TraceScenarioImportService.java` | 复用 line 134-152 reason 检测逻辑（**需先抽 package-private `detectReasons(LlmTraceEntity, List<LlmSpanEntity>, ...)` helper，零行为漂移；现有 `TraceScenarioImportServiceTest:102` 保留绿色**）。其它公共方法签名与行为不动 |

**Phase 1.0 证伪步骤**（dev 开工第一步必跑）：
1. 跑一遍 memory-curator 当前 dispatch 链路，确认 SubAgentRegistry 接 system agent 不需要 hack
2. 单元测试调用 `TraceScenarioImportService` 现有 reason 检测，确认输出格式可被直接 map 到 t_session_annotation
3. 写**红测试**：建一条假 session + 假 trace 带 tool error，跑 V1 signal stage，断言 t_session_annotation 出现 `tool_failure` 标签 → 此时实现还没写应该红
4. 然后才进 Phase 1.1 实现

---

## 1. 总体架构（V69 memory-curator dogfood 同款 pattern）

```
        ┌─────────────────────────────────────────┐
        │  P12 ScheduledTask (hourly cron)         │
        │  seeded in V75 migration                 │
        │  concurrency_policy='skip-if-running'    │
        │  target_agent = session-annotator        │
        └────────────────┬────────────────────────┘
                         │ 触发
                         ▼
        ┌─────────────────────────────────────────┐
        │  session-annotator agent run             │
        │  (system_prompt orchestrates pipeline)   │
        │                                          │
        │  agent loop 内按需调用 3 个 tool：       │
        │                                          │
        │  ① DetectSignalAnnotations(window=1h)   │
        │     → 调 TraceScenarioImportService     │
        │       package-private detectReasons     │
        │     → 写 source=signal 标注              │
        │     → 返回需 LLM 标注的 session 列表     │
        │                                          │
        │  ② AnnotateSession(sessionId)            │
        │     → 拿 trace + message tail            │
        │     → LLM 推理 outcome + suspect_surface │
        │     → 写 source=llm 标注                 │
        │     → 一次 tool call 处理一条 session    │
        │                                          │
        │  ③ RecomputeClusters(window=7d)         │
        │     → bucket on (outcome ×              │
        │        suspect_surface × top_failing_    │
        │        tool × agent_id)                  │
        │     → ≥3 member upsert t_session_       │
        │        pattern + member rows             │
        └────────────────┬────────────────────────┘
                         │
                         ▼
        ┌─────────────────────────────────────────┐
        │  Dashboard /insights/patterns           │
        │  GET /api/insights/patterns             │
        │  GET /api/insights/patterns/{id}/        │
        │      members                            │
        └─────────────────────────────────────────┘
```

**关键设计点**：
- **不是 3 个独立 cron**，而是 1 个 ScheduledTask 触发 1 次 agent run，agent 内部 orchestrate 3 个 tool（V69 memory-curator 同款）
- **signal 检测 + clustering 是纯 Java 逻辑**，但通过 Tool wrap 让 agent 决定调用顺序（一致的 V69 dogfood 模式 + 未来 attribution agent V3 可扩展同样调这些 tool）
- **agent 不做 LLM 推理也能跑** —— `DetectSignalAnnotations` 和 `RecomputeClusters` 是 deterministic tool；只有 `AnnotateSession` 让 agent 用 LLM 判断 outcome / suspect_surface
- **没有 advisory lock** —— ScheduledTask `concurrency_policy='skip-if-running'` 内建防重入

## 2. 数据库 Schema

### 2.1 Flyway migration `V74__create_session_annotation_and_pattern.sql`

> **版本号 ratify (2026-05-14)**：Phase 1.0 BE-Dev 验证 V72/V73 已被 multimodal 系列占用，本包改用 **V74 (schema) + V75 (seed)**。

```sql
CREATE TABLE t_session_annotation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,
    annotation_type      VARCHAR(32) NOT NULL,
    annotation_value     VARCHAR(64) NOT NULL,
    source          VARCHAR(16) NOT NULL,  -- signal / llm / human
    confidence      DECIMAL(3,2) NOT NULL DEFAULT 1.00,
    reasoning       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_session_annotation UNIQUE (session_id, annotation_type, annotation_value, source)
);
CREATE INDEX idx_session_annotation_session ON t_session_annotation(session_id);
CREATE INDEX idx_session_annotation_type_value ON t_session_annotation(annotation_type, annotation_value);
CREATE INDEX idx_session_annotation_created ON t_session_annotation(created_at);

CREATE TABLE t_session_pattern (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signature           VARCHAR(256) NOT NULL UNIQUE,
    outcome             VARCHAR(32) NOT NULL,
    suspect_surface     VARCHAR(32) NOT NULL,
    top_failing_tool    VARCHAR(128),
    agent_id            BIGINT,
    member_count        INT NOT NULL DEFAULT 0,
    suggested_surface   VARCHAR(32),  -- V3 attribution 写，V1 = suspect_surface
    first_seen_at       TIMESTAMPTZ NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_session_pattern_outcome ON t_session_pattern(outcome);
CREATE INDEX idx_session_pattern_agent ON t_session_pattern(agent_id);
CREATE INDEX idx_session_pattern_last_seen ON t_session_pattern(last_seen_at);

CREATE TABLE t_pattern_session_member (
    pattern_id  BIGINT NOT NULL REFERENCES t_session_pattern(id) ON DELETE CASCADE,
    session_id  VARCHAR(36) NOT NULL,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (pattern_id, session_id)
);
CREATE INDEX idx_pattern_member_session ON t_pattern_session_member(session_id);
```

**注意**：
- `session_id` 是 VARCHAR(36) 跟 t_session.id 类型对齐
- 没加 FK 到 t_session 避免迁移期复杂性；通过应用层校验
- `signature` UNIQUE 保证聚类重跑幂等
- **时间戳走 `TIMESTAMPTZ`**（aligned with V70/V73 multimodal 系列；Instant roundtrip 现役惯例）—— Phase 1.1 BE-Dev 校对发现 tech-design 初稿写 `TIMESTAMP` 与现行惯例不一致，已修正

### 2.2 Seed migration `V75__seed_session_annotator_agent.sql`

参考 V69（memory-curator）模式 seed **system agent + ScheduledTask 双 INSERT**，agent 用新约定 `owner_id = 1` + `is_public = TRUE`（V69 memory-curator 留 `owner_id = NULL` 不动）。

```sql
-- ① system agent
INSERT INTO t_agent (
    name,
    description,
    model_id,
    system_prompt,
    skill_ids,
    tool_ids,
    config,
    lifecycle_hooks,   -- ← Phase 1.0 BE-Dev push back: 显式 NULL，V1 不需要 hook
    owner_id,          -- ← 新约定：=1（admin user），非 V69 的 NULL
    is_public,         -- ← 新约定：TRUE（系统共用）
    status,
    execution_mode,
    created_at,
    updated_at
)
SELECT
    'session-annotator',
    'System agent: hourly orchestration of production session annotation + clustering. '
        || 'Calls DetectSignalAnnotations / AnnotateSession / RecomputeClusters tools in sequence. '
        || 'Drives PROD-LABEL-CLUSTER (V1) data flywheel step ①②.',
    'claude-sonnet-4-6',  -- default; 用户在 dashboard 可改
    'SEE_FILE:session-annotator-system-prompt.md',  -- 由 SessionAnnotatorBootstrap 启动加载
    '[]',
    '["DetectSignalAnnotations","AnnotateSession","RecomputeClusters"]',
    '{"temperature": 0.2, "maxTokens": 4096}',
    NULL,              -- ← lifecycle_hooks: 不需要（V69 用于 SESSION_END broadcast，本包不用）
    1,                 -- ← owner_id = 1（新约定）
    TRUE,              -- ← is_public = TRUE
    'active',
    'auto',
    NOW(),
    NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_agent WHERE name = 'session-annotator');

-- ② ScheduledTask 触发 cron (V69 dogfood 同款 pattern — 字段已对照 V69 实际 schema)
INSERT INTO t_scheduled_task (
    name,
    creator_user_id,
    agent_id,             -- ← FK 到 t_agent.id（不是 agent_name 字符串）
    cron_expr,            -- ← V59 schema 实际字段名（不是 cron_expression）
    timezone,             -- NOT NULL
    prompt_template,      -- NOT NULL
    session_mode,         -- NOT NULL，V69 取值 'new'
    enabled,
    concurrency_policy,
    status,               -- NOT NULL，V69 取值 'idle'
    created_at,
    updated_at
)
SELECT
    'session-annotator-hourly',
    0,                                                                    -- SYSTEM marker
    (SELECT id FROM t_agent WHERE name = 'session-annotator'),            -- FK 子查询拿 agent.id
    '0 0 * * * *',                                                        -- 6-field Spring cron: hourly at top
    'Asia/Shanghai',
    'Hourly orchestration: run DetectSignalAnnotations → AnnotateSession × N → RecomputeClusters',
    'new',
    TRUE,                                                                 -- V1 默认 enabled (dogfood)；V69 是 FALSE
    'skip-if-running',
    'idle',
    NOW(),
    NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_scheduled_task WHERE name = 'session-annotator-hourly');
```

**字段校对结果（Phase 1.1 BE-Dev 落地后回写）**：
- `cron_expr` ≠ `cron_expression`（V59 schema 实际是 cron_expr）
- `agent_id`（BIGINT FK，用子查询填）≠ `target_agent_name`（字符串）
- 4 个 NOT NULL 字段必填：`timezone` / `prompt_template` / `session_mode` / `status`
- V69 实际 SQL：`skillforge-server/src/main/resources/db/migration/V69__memory_curator_dogfood.sql:85-110`

**约定决策（2026-05-14 ratify）**：
- **新 system agent 默认 `owner_id = 1` + `is_public = TRUE`**（"admin 拥有 / 系统共用"模式）
- **V69 memory-curator 留 `owner_id = NULL` 不动**（已发布的迁移不补 fix migration）
- **dispatch 模式**：P12 ScheduledTask（V69 dogfood 同款），不走 Spring @Scheduled
- **V1 默认 enabled=TRUE**（不像 V69 默认 false 等用户启用 —— 我们是 dogfood 阶段，直接跑）
- V75 注释 acknowledge "owner_id=1 跟 V69 是有意的新约定" + "enabled=TRUE 跟 V69 不一样因为是 dogfood 默认开"

**Model 配置说明**：`session-annotator.model_id` 是 `t_agent` 标准字段，**用户在 dashboard 的 Agents 页面像配置任何其他 agent 一样可改**。V75 seed 时填一个合理默认值（`claude-sonnet-4-6`，跟 memory-curator 同档；对成本敏感的用户可后续切到 Haiku 类）—— 但**这不是 V1 开发期决策，是 runtime 用户配置**。

prompt 文件 `classpath:session-annotator-system-prompt.md` 由 `SessionAnnotatorBootstrap` 启动时加载（复用 MemoryCuratorBootstrap 模板，避免长 prompt 转义到 SQL string 里）。

**✅ Phase 1.1 校对完成（2026-05-14）**：BE-Dev 读 V69 line 85-110 实际 SQL 后回写 §2.2 的 12 个字段名，与 V69 字段顺序完全一致；4 个原 placeholder 漏掉的 NOT NULL 字段（timezone / prompt_template / session_mode / status）已补全。V75 文件 `skillforge-server/src/main/resources/db/migration/V75__seed_session_annotator_agent.sql` 与本节同步。

## 3. 后端组件清单

### 3.1 复用（不新写）

| 类 | 位置 | 复用程度 |
|---|---|---|
| `TraceScenarioImportService` 内部 reason 检测 | 现有 service line 134-152 内联在 `suggestImportCandidates` 循环里 | **Phase 1.2 已抽 `public static detectReasons(LlmTraceEntity, List<LlmSpanEntity>, int totalTokens, int totalToolCalls, int totalLlmCalls, int minTokens) -> List<String>`** helper（**注**：原稿写 package-private，BE-Dev 落地时改 public static 因为 `SessionAnnotationSignalService` 在 `com.skillforge.server.sessionannotation` 跨包；行为零漂移 + `TraceScenarioImportServiceTest` 9/9 绿）；V1 `DetectSignalAnnotationsTool` 通过 service 间接调它 |
| `LlmTraceRepository` / `LlmSpanRepository` | 现有 | 直接调（取 sessionId → trace 集 + traceId → span 集） |
| `SubAgentRegistry` + `SubAgentTool` | 现有 | session-annotator 派发走它，**零改动**（Phase 1.0 BE-Dev 已确认） |
| `MemoryCuratorBootstrap` 模板 | 现有 | 复制成 `SessionAnnotatorBootstrap` —— 改 `AGENT_NAME` + `PROMPT_RESOURCE_PATH` 两常量 |
| **P12 ScheduledTask + V69 dogfood 模式** | 现有 `t_scheduled_task` 表 + `V69__memory_curator_dogfood.sql` 第二段 INSERT 模板 | V75 抄一份 INSERT 写 session-annotator-hourly row，**不写 Spring @Scheduled，不需 advisory lock**（`concurrency_policy='skip-if-running'` 内建） |
| memory-curator tool 4 件 | `skillforge-server/src/main/java/com/skillforge/server/tool/memorysynth/` 4 个 Tool 类 | V1 3 个 Tool 类**组织方式** copy（class 结构 + JSON 输入输出 + 注入 service），不 copy 业务逻辑 |

### 3.2 新建

| 组件 | 类型 | 备注 |
|---|---|---|
| `SessionAnnotationEntity` / `SessionAnnotationRepository` | JPA | 标准 |
| `SessionPatternEntity` / `SessionPatternRepository` | JPA | 标准 |
| `PatternSessionMemberEntity` / `PatternSessionMemberRepository` | JPA | 关联表 |
| `SessionAnnotationService` | Service | upsert + 幂等 + source 区分（signal / llm / human） |
| `SessionAnnotationSignalService` | Service | 调 `TraceScenarioImportService.detectReasons` + 写 signal 标注。**Phase 1.2 红测试转绿的目标** |
| `SessionPatternClusterService` | Service | bucket 聚类 + upsert `t_session_pattern` |
| `SessionAnnotatorBootstrap` | @Component | 启动时同步 system agent prompt（复用 MemoryCuratorBootstrap 模板） |
| `classpath:session-annotator-system-prompt.md` | 资源文件 | agent 系统 prompt（orchestrate 3 tool 调用顺序） |
| `DetectSignalAnnotationsTool` | Tool | agent 工具：调 `SessionAnnotationSignalService.detectAndPersist(window)` 返回需 LLM 标注的 session IDs |
| `AnnotateSessionTool` | Tool | agent 工具：拿 sessionId → fetch trace summary → 让 agent 内 LLM 判断 outcome/suspect_surface → 写 source=llm 标注 |
| `RecomputeClustersTool` | Tool | agent 工具：调 `SessionPatternClusterService.recompute(window=7d)` |
| `InsightsController` | REST | `GET /api/insights/patterns` + `GET /api/insights/patterns/{id}/members` |
| `SurfaceType` enum | Core | skill / prompt / behavior_rule / tool / hook / mcp / other / unclear |
| `OptimizableSurface<V>` 空接口 | Core | V4 之前不实现，留扩展位 |

**注意**：
- `OptimizableSurface<V>` 在 V1 只是空接口骨架（loadActive / createCandidate / promote / rollback 方法声明），不要任何实现类
- 3 个 Tool 都是**薄包装**：实际业务逻辑都在对应 Service 里，Tool 只做参数解析 + service 调用 + JSON 序列化。这样 Service 单测能锁住主逻辑，Tool 测只验 wiring
- 没有"3 个 @Scheduled cron job"类 —— ScheduledTask 表 + agent 内部循环承担 orchestration 角色

## 4. session-annotator Agent 设计

### 4.1 System prompt 骨架（写到 `classpath:session-annotator-system-prompt.md`）

```
You are session-annotator, a SkillForge system agent that orchestrates
hourly annotation + clustering of production sessions.

Every time you are invoked (via ScheduledTask), run this pipeline:

STEP 1 — Signal detection (deterministic):
  Call DetectSignalAnnotations(window="1h").
  Returns: { signal_count, sessions_needing_llm: [sessionId, ...] }
  Writes source=signal annotations from trace/span derived reasons.
  No LLM judgment required from you for this step.

STEP 2 — LLM annotation (your job):
  For each sessionId in sessions_needing_llm (cap at 10):
    Call AnnotateSession(sessionId).
    AnnotateSession returns the trace summary + recent message tail; you
    decide outcome + suspect_surface + confidence + reasoning, and the
    tool writes source=llm annotation in one round-trip.
  If sessions_needing_llm is empty, skip to step 3.

STEP 3 — Clustering (deterministic):
  Call RecomputeClusters(window="7d").
  Returns: { patterns_upserted, members_added }.

DECISION HEURISTICS (only used inside AnnotateSession LLM step):
- outcome:
    success: agent completed user's request without retry/error
    partial_success: completed with degraded output or extra clarification
    failure: agent failed to deliver / aborted / runtime_error
    cancelled: user cancelled or session timed out without completion
- suspect_surface:
    skill: session failed because a skill returned wrong/incomplete output
    prompt: agent misunderstood user intent or produced rambling output
    behavior_rule: agent violated established behavior rule
    other: cause clearly outside the 3 above (LLM timeout, network)
    unclear: not enough signal to decide
- confidence: 0..1; under 0.5 won't enter clustering (still persists for audit)

CONSTRAINTS:
- Do NOT propose fixes — that's V3 attribution-curator agent's job
- Do NOT call any tool not in your toolbox
- Do NOT skip step 1 or 3 — they must run every invocation
- If a tool returns an error, log it and proceed; never abort the pipeline
```

### 4.2 Dispatch shape

ScheduledTask 触发（V69 dogfood 同款 path）：

```
P12 ScheduledRunner → SubAgentDispatch.dispatch(
    targetAgentName: "session-annotator",
    payload: { "trigger": "hourly", "invocation_id": <generated> },
    timeoutMs: 600_000
)
```

session-annotator agent loop **不接收 session_ids**（不像之前设计的 LlmAnnotationJob 直接派 batch）。改为 agent 自己调 `DetectSignalAnnotations` 拿一批。**好处**：未来想从 dashboard "手动 trigger 一次"也是同一条 dispatch path；attribution agent (V3) 可以选择性调 V1 这些 tool 而不需要绕过 cron。

### 4.3 Tool 接口（粗略，Phase 1.3 落地时细化）

- **`DetectSignalAnnotationsTool`** —— input `{ "window_hours": int }`（默认 1）→ output `{ "signal_count": int, "sessions_needing_llm": [{"sessionId": str, "agentName": str, "signalReasons": [str]}] }`
  - service 内调 `TraceScenarioImportService.detectReasons` + 写 t_session_annotation (source=signal) + 返回 cap 10 条需 LLM 标注的 session
- **`AnnotateSessionTool`** —— input `{ "sessionId": str, "outcome": str, "suspect_surface": str, "confidence": float, "reasoning": str }` → output `{ "ok": true, "annotationId": long }`
  - **agent 决策 outcome/surface/confidence/reasoning** —— Tool 不做判断只持久化（agent 是 brain，tool 是 hand）
  - service 写 t_session_annotation (source=llm) 幂等（同 sessionId + outcome 已存在则 update）
- **`RecomputeClustersTool`** —— input `{ "window_days": int }`（默认 7）→ output `{ "patterns_upserted": int, "members_added": int }`
  - service 内跑 bucket 聚类 + upsert t_session_pattern + t_pattern_session_member

**幂等保证**：3 个 tool 各自的 service 都用 UNIQUE 约束 + ON CONFLICT upsert（Postgres），同一 invocation_id 重跑结果一致。

## 5. 聚类策略

### 5.1 Cluster key

```
signature = outcome + "|" + suspect_surface + "|" + top_failing_tool + "|" + agent_id
```

其中 `top_failing_tool` 通过查 session 关联 trace 的 tool span 取 error 出现频次最高的 tool name。无 tool failure 则填 `null`，signature 段写空字符串。

### 5.2 准入门槛

- bucket 至少 3 个 session member 才入 `t_session_pattern`
- 只聚 outcome 非 `success` 的 session（成功 session 不进 pattern，省存储）
- < 0.5 confidence 的 llm label 不参与聚类

### 5.3 重跑幂等

- `RecomputeClustersTool` → `SessionPatternClusterService.recompute(window=7d)` 每次跑：(1) 扫过去 7 天有新标注的 session（2）按 signature 重算 bucket（3）upsert `t_session_pattern`（4）增量插入新 member（5）更新 `member_count` / `last_seen_at`
- 不删除老 pattern，即使 member 数量降回 0 也保留（V3 attribution 可能引用）

## 6. Dashboard

### 6.1 REST endpoints（新建 `InsightsController`）

- `GET /api/insights/patterns?outcome=&surface=&agent=&limit=50` → 返回 pattern list
- `GET /api/insights/patterns/{id}/members?limit=100` → 返回 member sessions

### 6.2 前端组件

- 新 page `skillforge-dashboard/src/pages/Insights.tsx`
- 新组件 `PatternList.tsx` + `PatternDetailDrawer.tsx`
- 路由加 `/insights/patterns`
- 跳 trace 用现有 `/traces?sessionId=...` 深链接（OBS-2 已实现）

## 7. 实施计划

- [x] **Phase 1.0：证伪 + 红测试** ✅ 2026-05-14
  - ✅ memory-curator dispatch 链路确认：SubAgentRegistry 零改动可挂新 system agent
  - ✅ TraceScenarioImportService reason 检测内联在 `suggestImportCandidates`，需先抽 `detectReasons` helper
  - ✅ 红测试已落地 `skillforge-server/src/test/java/com/skillforge/server/sessionlabel/SignalAnnotationJobRedTest.java`（@Disabled placeholder）
  - **3 个 push back 已修**：Flyway V72/V73 占用 → 改 V74/V75；dispatch 模式 → ScheduledTask + 1 agent + 3 tool；V75 INSERT 补 `lifecycle_hooks` 列
- [x] **Phase 1.1：DB schema + Entity + Repository + IT** ✅ 2026-05-14
  - ✅ V74 migration（3 张新表 + 3 INDEX + UNIQUE + FK CASCADE，TIMESTAMPTZ）
  - ✅ V75 migration（t_agent + t_scheduled_task 双 INSERT，字段已对照 V69 line 85-110 实际 schema）
  - ✅ 3 Entity (`SessionAnnotationEntity` / `SessionPatternEntity` / `PatternSessionMemberEntity` + `PatternSessionMemberId` IdClass) + 3 Repository
  - ✅ `SessionAnnotatorBootstrap` 启动加载 prompt（照抄 MemoryCuratorBootstrap，仅改 2 个常量）
  - ✅ `session-annotator-system-prompt.md`（resources/ 根目录，verbatim copy §4.1）
  - ✅ `SessionAnnotationPersistenceIT` 4 测试（save + UNIQUE 约束 + findBySignature + CASCADE delete）
  - ⚠️ **本机 Docker 未安装 → 4 个 IT skip**（与项目其它 14+ AbstractPostgresIT 子类同款行为，CI 真跑）。代码层 regression 验证：`mvn -pl skillforge-server -am test` **1402 / 0 / 0 / 72 → BUILD SUCCESS in 27.7s**，无 modified existing files
- [x] **Phase 1.2：Signal Service + DetectSignalAnnotationsTool** ✅ 2026-05-14
  - ✅ 重构 `TraceScenarioImportService.detectReasons` 抽 **`public static`**（跨包必需，原稿"package-private"修正）—— `TraceScenarioImportServiceTest` 9/9 绿 + 加 6 个 detectReasons 直测单测
  - ✅ `SessionAnnotationSignalService.detectAndPersist(Duration window)` + 4 单测（写标注 / 幂等 / skip eval / empty）—— UNIQUE 约束 + try-catch DataIntegrityViolationException 幂等
  - ✅ `DetectSignalAnnotationsTool` 薄包装（window_hours clamp [1, 168]）+ 2 单测；在 `SkillForgeConfig` 加 `@Bean` 注册
  - ✅ 删 Phase 1.0 红测试占位 `SignalAnnotationJobRedTest.java`（包名收口到 sessionannotation）
  - **mvn 1416/0/0/71 → BUILD SUCCESS in 28.3s**（+14 test，-1 skipped）；核心文件 6 个 `git diff HEAD` 零行
- [x] **Phase 1.3：AnnotateSessionTool + GetTrace 复用** ✅ 2026-05-14
  - ✅ V76 migration：`UPDATE t_agent.tool_ids` 加 `GetTrace` + `AnnotateSession`（dep order：DetectSignal → GetTrace → AnnotateSession → RecomputeClusters）；同时 reset `system_prompt` 回 `SEE_FILE:*` placeholder 让 `SessionAnnotatorBootstrap` 下次启动重新加载更新过的 prompt md（Bootstrap 现行逻辑识别 sentinel 是否仍存在 → 是则重读，避免改 Bootstrap）
  - ✅ `session-annotator-system-prompt.md` STEP 2 拆为 2.1 `GetTrace(list_traces + get_trace)` 取 trace 上下文 + 2.2 `AnnotateSession(outcome, suspect_surface, confidence, reasoning, top_failing_tool)` 写 llm 标注；STEP 1 / STEP 3 / heuristics / constraints 不动
  - ✅ **复用现有 `GetTraceTool`** 而非新建 `SessionFetchTool`（user-confirmed Option D）—— 减重复实现 + agent 已熟悉双 action 接口；V1 单租户 dogfood (session.userId = agent.owner_id = 1) `assertSessionAccessible` 不触发；V2+ 多租户需 system-agent context bypass（backlog，不在本包）
  - ✅ `SessionAnnotationLlmService.annotateSession(sessionId, outcome, suspect_surface, confidence, reasoning, top_failing_tool)` 写 2-3 行（outcome + suspect_surface + 可选 top_failing_tool）以 `source='llm'`；enum + range + blank 校验集中 `SessionAnnotationConstants`；UNIQUE 幂等同 signal-stage（exact-tuple skip，不同 value append）
  - ✅ `AnnotateSessionTool` 薄包装（input/output JSON 解析 + service 调用）+ `SkillForgeConfig` 注册紧跟 `detectSignalAnnotationsTool` Bean
  - ✅ 7 单测 `SessionAnnotationLlmServiceTest` + 5 单测 `AnnotateSessionToolTest`（Mockito 风格，无 Docker 依赖）
  - **mvn 1428/0/0/71 → BUILD SUCCESS**（+12 test 全绿）；核心文件 6 个 `git diff HEAD` 零行
  - 端到端 dispatch manual test 推迟到 Phase Final（与 cron `*/2 * * * * *` 那次合并）
- [x] **Phase 1.4：Clustering + InsightsController** ✅ 2026-05-14
  - ✅ `SessionPatternClusterService.recompute(Duration)` — 4-dim bucket on (outcome × suspect_surface × top_failing_tool × agent_id)，准入 outcome≠success + confidence≥0.5 + member≥3；增量 add member（不删老 pattern）；signature 拼接 null tool/agent 渲染为空串；upsert via `findBySignature` + DB UNIQUE on signature；member 幂等 via composite PK + catch DataIntegrityViolationException
  - ✅ `RecomputeClustersTool` 薄包装（window_days clamp [1, 30], default 7）+ `SkillForgeConfig.recomputeClustersTool` @Bean 紧跟 `annotateSessionTool`
  - ✅ `InsightsController`：`GET /api/insights/patterns` (filters: outcome / surface / agent / limit clamp [1, 200] default 50, sort ORDER BY member_count DESC, last_seen_at DESC) + `GET /api/insights/patterns/{id}/members` (limit clamp [1, 500] default 100, sort added_at DESC, 404 when pattern missing, runtime_error truncate 200, batch agent-name lookup)
  - ✅ Repository 加 3 个查询方法：`SessionAnnotationRepository.findDistinctSessionIdsCreatedSince(Instant)` / `SessionPatternRepository.findWithFilters(outcome, surface, agentId, Pageable)` / `PatternSessionMemberRepository.findByPatternIdOrderByAddedAtDesc(patternId, Pageable)`
  - ✅ 21 单测（7 cluster service + 4 tool + 8 controller + 2 buildSignature edge cases）全绿
  - **mvn 1449/0/0/71 → BUILD SUCCESS in 27.577s**（+21 test）；核心文件 11 个 `git diff HEAD` 零行（SessionEntity / SessionMessageEntity / ChatService / SessionService / CompactionService / AgentLoopEngine / GetTraceTool / Phase 1.2-1.3 落地的 SessionAnnotation{Signal,Llm}Service / AnnotateSessionTool / DetectSignalAnnotationsTool）
- [ ] **Phase 1.5：Dashboard 页**
  - `Insights.tsx` + `PatternList` + `PatternDetailDrawer`
  - 路由 + nav
- [ ] **Phase Final：Verify & Commit**
  - `mvn -pl skillforge-server -am test` 全绿
  - `cd skillforge-dashboard && npm run build` EXIT 0
  - **真跑 ScheduledTask 一次** —— 把 cron 改成 `*/2 * * * * *`（每 2 min）让它在测试期内跑一次，确认 agent run 端到端写标注 + cluster
  - `git diff` 确认核心文件零改动（grep SessionEntity / ChatService / SessionService / CompactionService / AgentLoopEngine）
  - Reviewer: `java-reviewer` + `typescript-reviewer` + `database-reviewer`（新表 + JPA + 2 个 INSERT migration）

## 8. 风险 & 边界

| 风险 | 缓解 |
|---|---|
| `TraceScenarioImportService` 现有方法签名调整影响 SmartImport | 只重构 visibility 抽 helper，不改签名；保留 `TraceScenarioImportServiceTest:102` 绿色锁行为 |
| LLM 标注错误率拖累聚类 | confidence < 0.5 不入聚类；source 字段区分便于 V3 加人工修正入口 |
| ScheduledTask cron 跟 memory-curator 04:30 冲突 | session-annotator hourly 跑 00:00 整点；memory-curator 一天一次 04:30；不冲突 |
| 三个新表磁盘膨胀 | t_session_annotation 加 created_at 索引；7 天滚动清理（V1 不做，V3 加） |
| 派 session-annotator agent 成本失控 | DetectSignalAnnotations 单次 cap 10 session；agent 内 max_loops 限制 12（3 tool × 10 session）；hourly cron 每小时 1 次 |
| Phase 1.3 派 agent 跨进程调试困难 | 走 memory-curator 现有 SubAgentDispatch + 派发日志，不开新通路；SubAgentRun entity 记录每次 invocation |
| V75 `t_scheduled_task` 字段名瞎猜 | Phase 1.1 强制读 V69 实际 SQL 抄字段；reviewer 在 Phase 2 显式 audit |

## 9. 与现有规则的关系

- 遵守 [`docs-reading.md`](../../../../.claude/rules/docs-reading.md)：本包 prd + tech-design 是实现入口
- 遵守 [`think-before-coding.md`](../../../../.claude/rules/think-before-coding.md)：Phase 1.0 证伪先行，scope discipline 不动核心文件
- 遵守 [`verification-before-completion.md`](../../../../.claude/rules/verification-before-completion.md)：Completion Gate 三件套 + spot-check 20 条标签
- 遵守 [`pipeline.md`](../../../../.claude/rules/pipeline.md) Mid 档：1 dev → 2 reviewer 1 轮对抗 → Judge → Phase Final
- 遵守 [`persistence-shape-invariant.md`](../../../../.claude/rules/persistence-shape-invariant.md) + [`identity-column-on-rewrite.md`](../../../../.claude/rules/identity-column-on-rewrite.md)：本包不动 t_session / t_session_message 因此不触这两条 Iron Law，但 reviewer 需 grep diff 确认

## 10. 变更记录

- 2026-05-14：claude 初稿（design-draft）
