# SKILL-CANARY-ROLLOUT 技术方案

---
id: SKILL-CANARY-ROLLOUT
status: ratified
mode: full
created: 2026-05-14
updated: 2026-05-14
---

## 0. 现状证据（Phase 1.0 证伪必跑）

### 0.1 复用目标已存在 — 文件路径

| 复用项 | 文件路径 | 用法 |
|---|---|---|
| Skill A/B 评测全套 | `skillforge-server/.../improve/SkillAbEvalService.java` (createAndTrigger / runAbTestAsync / promoteCandidate / manualPromote / delta 阈值) | V2 完全不改签名 / 现行 promoteCandidate 路径 100% 不退化 |
| Skill sandbox 注入 | `skillforge-server/.../SandboxSkillRegistryFactory.buildSandboxRegistryWithSkills` | V2 不动 |
| 4 维分数 + dimensionStatus | `skillforge-server/.../eval/EvalScoreFormula.java` (M4_V2) | V2 ProdMetricsCollector 复用作 canary 指标对比轴 |
| V1 `t_session_annotation` 表 | V74 migration | V2 加新 `annotation_type='canary_group'` 值（**无需新表**，复用 V1 schema） |
| V1 `session-annotator` agent + ScheduledTask | V69 dogfood 同款 + V75 seed + V76 加 tool | V2 ProdMetricsCollector cron 走同款 P12 ScheduledTask |
| V1 `OptimizableSurface<V>` 空骨架 | V1 Phase 1.1 落地 | V2 加 `SkillSurface` 实现类（第一个 surface 实现） |
| Skill 详情页 | `skillforge-dashboard/src/components/skills/SkillEvolutionPanel.tsx` / `SkillAbPanel.tsx` | V2 canary panel 嵌入扩展，不重写 |
| V1 EvalAnnotationEntity UI 模式 | annotation queue 卡片 | V2 canary metrics 表格借用风格 |
| SkillEntity（parent_skill_id / artifact_status / version）| `skillforge-server/.../entity/SkillEntity.java` | V2 加 2 列：rollout_stage / rollout_percentage |

### 0.2 不动的核心文件清单（grep diff 验证）

| 文件 | 不动理由 |
|---|---|
| `skillforge-server/.../entity/SessionEntity.java` | identity-column-on-rewrite + persistence-shape-invariant 双红灯 |
| `skillforge-server/.../entity/SessionMessageEntity.java` | 同上 |
| `skillforge-server/.../service/ChatService.java` | 核心文件 |
| `skillforge-server/.../service/SessionService.java` | 核心文件 |
| `skillforge-server/.../service/CompactionService.java` | 核心文件 |
| `skillforge-server/.../service/TraceScenarioImportService.java` | V1 用 |

### 0.3 必须触碰的核心文件（V2 红灯，reviewer 显式审）

**Phase 1.0 BE-Dev 实地校对发现（2026-05-14）**：原计划 "AgentLoopEngine spawn skill 前插 allocator" 是**错的** —— skill 实际不在 AgentLoopEngine 入口 mount，而是在 server 层 `DefaultSessionSkillResolver.resolveFor(agentDef)` 用 **skill name (String)** 解析，LLM 调 `Skill` tool 时按 name lazy load。**真实 hook 点是 `DefaultSessionSkillResolver`（server 层），不是 engine**。

| 文件 | 改动 | 不变量保护 |
|---|---|---|
| `skillforge-server/.../DefaultSessionSkillResolver.java`（or `SessionSkillResolver` 接口实现）| `resolveFor(...)` 调用前先调 `CanaryAllocator.allocate(sessionId, agentId, skillName)` 决定挂 baseline 还是 candidate skill name 对应的 SkillDefinition；预估 5-10 行新增 | **非核心文件**，无 Iron Law 影响 |
| `skillforge-core/.../engine/AgentLoopEngine.java` | 仅扩 `SessionSkillResolver` 接口签名（如需 `sessionId` 参数），**≤ 3 行**（grep 验证：`rewriteMessages` / `persistedContext` / `commonPrefixSize` 全 0 命中 in AgentLoopEngine 周围 spawn skill 区域，Iron Law 不触）| 显式 reviewer 验证 persistence-shape-invariant + identity-column-on-rewrite 不变量 |

**Phase 1.0 证伪步骤**（dev 开工第一步必跑）：
1. **验 AgentLoopEngine skill 加载入口位置可行**：grep AgentLoopEngine 找到 spawn skill 的代码段 + 设计 allocator 插入位置 + 确认改动范围（≤ 30 行）
2. **验 SkillEntity 2 列扩展可行**：现行 SkillEntity 加 2 列不破现有 mapper / @Column / @JsonIgnore 等
3. **验 V1 t_session_annotation 复用 canary_group**：annotation_type VARCHAR(32) 够长（"canary_group" 12 字符）+ annotation_value VARCHAR(64) 够长（"skill:1234" 10 字符）+ 跟现有 signal/llm annotation 共存不冲突
4. **写红测试**：fake skill + fake canary_rollout + sessionId hash 分流 → 期望 CanaryAllocator 返回正确 version_id → 此时实现还没写应该红

---

## 1. 总体架构

```
        ┌──────────────────────────────────────────────────┐
        │  Promote 路径（用户在 dashboard 操作）             │
        │                                                    │
        │  A/B 通过 → skill detail page                      │
        │   ├─ 一刀切按钮（默认）→ SkillAbEvalService        │
        │   │  .promoteCandidate 现行 100% 路径             │
        │   └─ 起 canary 按钮 → CanaryRolloutService         │
        │      .startCanary(skillId, candidateId, pct=10)    │
        │      → 写 t_canary_rollout row                     │
        │      → SkillEntity.rolloutStage='canary' / pct=10  │
        └──────────────────────────────────────────────────┘
                              │
                              ▼
        ┌──────────────────────────────────────────────────┐
        │  Runtime allocator（每 session 分流）              │
        │                                                    │
        │  AgentLoopEngine spawn skill 前：                  │
        │    versionId = CanaryAllocator.allocate(           │
        │      sessionId, agentId, baselineSkillId)          │
        │    ├─ 查 t_canary_rollout 是否 active canary       │
        │    │  └─ 无 → 返 baselineSkillId（一刀切路径）     │
        │    │  └─ 有 → hash(sessionId) % 100 < pct          │
        │    │     ├─ true → 返 candidateId + 写             │
        │    │     │   t_session_annotation                  │
        │    │     │   (annotation_type='canary_group',      │
        │    │     │    annotation_value='skill:<id>')       │
        │    │     └─ false → 返 baselineSkillId             │
        │  → 用 versionId 挂载到 SkillRegistry sandbox        │
        └──────────────────────────────────────────────────┘
                              │
                              ▼
        ┌──────────────────────────────────────────────────┐
        │  Metrics 回流（V1 session-annotator hourly 写完    │
        │   outcome 标签之后，V2 ProdMetricsCollector 接续）  │
        │                                                    │
        │  ProdMetricsCollector hourly cron：                │
        │   1. 找过去 1h active canary 列表                  │
        │   2. 对每个 canary：                                │
        │      a. 扫 t_session_annotation outcome 标签       │
        │         （source=llm，过去 1h）                     │
        │      b. 按 sessionId 反查 canary_group 标签        │
        │         → 分组 control / candidate                  │
        │      c. 4 维分数 + outcome 分布聚合                 │
        │      d. 写 t_canary_metric_snapshot bucket=hour    │
        │      e. 触发 auto-rollback signal 检查              │
        │         （candidate_fail_rate / control_fail_rate   │
        │          > 1.5 且 candidate sample > 50）           │
        │           ├─ 是 → CanaryRolloutService.rollback    │
        │           │       + dashboard 告警                  │
        │           └─ 否 → 继续 canary                       │
        └──────────────────────────────────────────────────┘
                              │
                              ▼
        ┌──────────────────────────────────────────────────┐
        │  Dashboard skill 详情页 canary panel               │
        │   - rollout gauge / 4 维对比 / outcome 分布        │
        │   - 操作按钮：step up / publish / rollback / reset │
        └──────────────────────────────────────────────────┘
```

**关键设计点**：
- **默认一刀切**：rolloutPercentage 默认 100，allocator 直接返 baseline，等价现行行为
- **canary opt-in**：用户主动起 canary 才进分流
- **session 锁版本**：每个进 canary 组的 session 写 t_session_annotation，整个 session 生命周期用同一版本（不中途切，per V1 ratify "session canary 组绑定"）
- **metrics 复用 V1 outcome 标签**：不重复打标，V1 session-annotator agent 写完 outcome → V2 collector 按 canary_group 分组聚合
- **AgentLoopEngine 唯一红灯**：仅在 spawn skill 处 + 30 行内改动；reviewer 显式审 Iron Law

## 2. 数据库 Schema

### 2.1 Flyway migration `V77__create_canary_rollout.sql`

> **版本号 ratify**：Phase 1.0 BE-Dev 验证下一个可用版本号（V74-V76 已被 V1 占用）

```sql
CREATE TABLE t_canary_rollout (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    surface_type            VARCHAR(32) NOT NULL,  -- skill / future: prompt / behavior_rule
    agent_id                BIGINT NOT NULL,
    baseline_skill_name     VARCHAR(64) NOT NULL,  -- skill name (SessionSkillResolver 用 name 不是 id)
    candidate_skill_name    VARCHAR(64) NOT NULL,
    rollout_stage           VARCHAR(32) NOT NULL,  -- disabled / canary / production / rolled_back
    rollout_percentage      INT NOT NULL DEFAULT 0 CHECK (rollout_percentage >= 0 AND rollout_percentage <= 100),
    started_at              TIMESTAMPTZ NOT NULL,
    last_decision_at        TIMESTAMPTZ,
    decision                VARCHAR(32),  -- promoted / rolled_back / null=ongoing
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_canary_rollout_agent_surface ON t_canary_rollout(agent_id, surface_type);
CREATE INDEX idx_canary_rollout_stage ON t_canary_rollout(rollout_stage);
-- 同 agent 同 surface 同时只能 1 个 canary (ratify #4)
CREATE UNIQUE INDEX uq_canary_active ON t_canary_rollout(agent_id, surface_type)
    WHERE rollout_stage = 'canary';

CREATE TABLE t_canary_metric_snapshot (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    canary_id                   BIGINT NOT NULL REFERENCES t_canary_rollout(id) ON DELETE CASCADE,
    bucket_at                   TIMESTAMPTZ NOT NULL,  -- hour boundary
    control_sample_size         INT NOT NULL DEFAULT 0,
    control_success_count       INT NOT NULL DEFAULT 0,
    control_failure_count       INT NOT NULL DEFAULT 0,
    control_avg_quality         DECIMAL(5,2),
    control_avg_efficiency      DECIMAL(5,2),
    control_avg_latency         DECIMAL(10,2),
    control_avg_cost            DECIMAL(10,6),
    candidate_sample_size       INT NOT NULL DEFAULT 0,
    candidate_success_count     INT NOT NULL DEFAULT 0,
    candidate_failure_count     INT NOT NULL DEFAULT 0,
    candidate_avg_quality       DECIMAL(5,2),
    candidate_avg_efficiency    DECIMAL(5,2),
    candidate_avg_latency       DECIMAL(10,2),
    candidate_avg_cost          DECIMAL(10,6),
    fail_rate_ratio             DECIMAL(6,3),  -- candidate / control，触发 auto-rollback 用
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_metric_canary_bucket UNIQUE (canary_id, bucket_at)
);
CREATE INDEX idx_metric_canary_bucket ON t_canary_metric_snapshot(canary_id, bucket_at DESC);
```

**注意**：
- 时间戳 TIMESTAMPTZ（V1 经验，跟 V70+ 一致）
- partial UNIQUE INDEX 用 PostgreSQL `WHERE` 语法（H2 不支持，但 SessionAnnotationPersistenceIT 在 Testcontainers PG 跑）
- decimal 精度：quality/efficiency 0-100 / latency ms / cost USD 跟 EvalScoreFormula 一致

### 2.2 Migration `V78__add_skill_rollout_columns.sql`

```sql
ALTER TABLE t_skill ADD COLUMN rollout_stage VARCHAR(32) NOT NULL DEFAULT 'production';
ALTER TABLE t_skill ADD COLUMN rollout_percentage INT NOT NULL DEFAULT 100
    CHECK (rollout_percentage >= 0 AND rollout_percentage <= 100);
-- 默认 100 = 现行行为，所有现有 skill 自动一刀切模式
```

**重要**：默认 100 = 现行 promote 路径 backward-compatible；现有 SkillAbEvalService.promoteCandidate 无需改动。

### 2.3 Migration `V79__seed_prod_metrics_collector_task.sql`

参考 V75 模式 seed ScheduledTask 触发 ProdMetricsCollector：

```sql
-- ProdMetricsCollector 是 service 不是 agent，但用 P12 ScheduledTask 框架 trigger
-- V1 经验：V69 memory-curator 是 agent + ScheduledTask；V2 改用 method-ref 模式

-- ⚠️ Phase 1.1 必须校对：t_scheduled_task 是否支持 method-ref 不指向 agent_id
-- 如果不支持，要么 (a) 让 ProdMetricsCollector 也包装成 system agent + tool
-- 要么 (b) 直接 Spring @Scheduled（违反"用项目自有 cron 系统"原则，不推荐）
-- 推荐 (a) 包装成 metrics-collector system agent + RecomputeMetricsTool

INSERT INTO t_agent (...)
SELECT 'metrics-collector', ..., owner_id=1, is_public=TRUE, ...
WHERE NOT EXISTS (...);

INSERT INTO t_scheduled_task (...)
SELECT 'metrics-collector-hourly',
    creator_user_id=0,
    agent_id=(SELECT id FROM t_agent WHERE name='metrics-collector'),
    cron_expr='0 30 * * * *',  -- 每小时 30 分跑（错开 session-annotator 整点）
    timezone='Asia/Shanghai',
    prompt_template='Hourly aggregate canary metrics for active rollouts',
    session_mode='new',
    enabled=TRUE,
    concurrency_policy='skip-if-running',
    status='idle',
    created_at=NOW(), updated_at=NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_scheduled_task WHERE name='metrics-collector-hourly');
```

## 3. 后端组件清单

### 3.1 复用（不新写）

| 类 | 复用程度 |
|---|---|
| `SkillAbEvalService` (createAndTrigger / runAbTestAsync / **promoteCandidate** / manualPromote) | promoteCandidate 现行 100% 路径不动；新 publish 按钮也走它 |
| `SandboxSkillRegistryFactory.buildSandboxRegistryWithSkills` | 不动 |
| `EvalScoreFormula` M4_V2 | ProdMetricsCollector 直接调对比 candidate vs control |
| `SkillEntity` (parent_skill_id / artifact_status / version) | 只加 2 列 |
| V1 `t_session_annotation` 表 | 加新 annotation_type='canary_group' 值，无 schema 改动 |
| V1 `SessionAnnotationRepository` | 加 1 查询方法 findCanaryGroupAnnotations |
| V1 `SessionAnnotatorBootstrap` 模板 | metrics-collector agent 复用 bootstrap 模式 |
| V1 session-annotator system prompt | metrics-collector 类似模板（更简单：只 1 tool） |
| V1 P12 ScheduledTask 框架（V69 + V75 模式） | metrics-collector 同款 row seed |

### 3.2 新建

| 组件 | 类型 | 备注 |
|---|---|---|
| `CanaryRolloutEntity` / `CanaryRolloutRepository` | JPA | 标准 |
| `CanaryMetricSnapshotEntity` / `CanaryMetricSnapshotRepository` | JPA | 标准 |
| `CanaryRolloutService` | Service | startCanary / stepUp / publish / rollback / autoRollbackCheck |
| `CanaryAllocator` | Component | `allocateSkillVersion(sessionId, agentId, baselineSkillId): Long` |
| `ProdMetricsCollector` 或 `CanaryMetricsService` | Service | hourly 跑，被 RecomputeMetricsTool 调 |
| `RecomputeMetricsTool` | Tool | metrics-collector agent 的工具 |
| `metrics-collector` system agent | Bootstrap + classpath prompt | V69 模式 |
| `CanaryRolloutController` | REST | `POST /api/canary/rollouts` / `PATCH /{id}/step-up` / `POST /{id}/publish` / `POST /{id}/rollback` / `GET /agent/{agentId}/active-canaries` |
| `MetricSnapshotController` | REST | `GET /api/canary/rollouts/{id}/metrics` |
| `SkillSurface` 实现 V1 `OptimizableSurface<V>` 接口 | Core | 第一个 surface 实现 |
| FE `CanaryPanel.tsx` | React 组件 | 嵌入 SkillEvolutionPanel 或 SkillAbPanel |
| FE API client `api/canary.ts` | TS | 5 endpoint wrapper + types |

### 3.3 改动的现有文件（红灯）

| 文件 | 改动 |
|---|---|
| `AgentLoopEngine.java` | spawn skill 前加 CanaryAllocator 调用（~30 行新增）；reviewer 显式审 Iron Law |
| `SkillEntity.java` | 加 2 字段 (rolloutStage / rolloutPercentage) + getter/setter；@Column 注解；默认 100 |
| `SkillRepository.java` | 加 1 查询 findByAgentIdAndRolloutStage（如需） |
| `SkillForgeConfig.java` | 注册 CanaryAllocator @Bean + RecomputeMetricsTool @Bean |

## 4. metrics-collector Agent 设计

### 4.1 System prompt 骨架（写到 `classpath:metrics-collector-system-prompt.md`）

```
You are metrics-collector, a SkillForge system agent that orchestrates
hourly aggregation of canary rollout metrics.

Every invocation (via ScheduledTask), run:

STEP 1 — Aggregate metrics (deterministic):
  Call RecomputeMetrics(window_hours=1).
  This finds all active canary rollouts, scans
  t_session_annotation outcome+canary_group entries from the last hour,
  computes 4-dim score deltas and outcome distributions per canary,
  writes hourly bucket rows to t_canary_metric_snapshot, and triggers
  auto-rollback signal check.

  Returns: { active_canaries, snapshots_written, auto_rollbacks_triggered }

That's it. Single step. You do no LLM reasoning — RecomputeMetrics is
fully deterministic.

CONSTRAINTS:
- Do NOT call any tool not in your toolbox
- If tool returns error, log and proceed
```

### 4.2 RecomputeMetricsTool 接口

**Input schema**：`{ "window_hours": int (optional, default 1) }`
**Output schema**：`{ "ok": true, "active_canaries": int, "snapshots_written": int, "auto_rollbacks_triggered": int }`

实现：薄包装调 `CanaryMetricsService.recompute(Duration window)`。

## 5. CanaryAllocator 算法

**接口签名（Phase 1.0 BE-Dev 修正 2026-05-14）**：

原稿用 `Long skillId` 路由，但实际 SessionSkillResolver 用 **skill name (String)** 抽象。修正为 `String skillName` 进出 + 可选反查 SkillDefinition：

```java
public class CanaryAllocator {

    /**
     * 决定本次 session 用哪个 skill name（baseline 或 candidate）。
     * 调用方 (SessionSkillResolver) 拿 name → 走原有 SkillRegistry 查 SkillDefinition。
     *
     * @param sessionId 当前 session ID
     * @param agentId 所属 agent
     * @param baselineSkillName 原本要挂的 skill name
     * @return 实际要挂的 skill name（baseline 或 candidate）
     */
    public String allocate(String sessionId, Long agentId, String baselineSkillName) {
        // 1. 查 t_canary_rollout (agent_id=agentId, surface_type='skill', stage='canary')
        //    + baseline_skill_name == baselineSkillName
        Optional<CanaryRolloutEntity> activeCanary = canaryRepository
            .findActiveCanaryForSkill(agentId, baselineSkillName);
        if (activeCanary.isEmpty()) {
            return baselineSkillName;  // 一刀切默认路径（rolloutPercentage=100 也走这条）
        }

        CanaryRolloutEntity canary = activeCanary.get();
        int pct = canary.getRolloutPercentage();
        if (pct == 100) {
            return canary.getCandidateSkillName();  // 100% 已上线
        }
        if (pct == 0) {
            return baselineSkillName;  // rolled_back 或刚 reset
        }

        // 2. session 已分组 → 用已存的（不切版本）
        Optional<String> existingGroup = sessionAnnotationRepository
            .findCanaryGroup(sessionId, "skill");
        if (existingGroup.isPresent()) {
            return parseSkillNameFromGroupValue(existingGroup.get());
        }

        // 3. 新 session → hash 分流
        int bucket = (sessionId.hashCode() & 0x7FFFFFFF) % 100;
        String pickedName = bucket < pct ? canary.getCandidateSkillName() : baselineSkillName;

        // 4. 持久化分组（per V1 ratify "session 绑定" 决策）
        sessionAnnotationRepository.upsertSkipDuplicate(
            sessionId, "canary_group",
            "skill:" + pickedName,  // 改 name 不再用 id
            "system",
            BigDecimal.ONE, null
        );

        return pickedName;
    }
}
```

**Entity 影响**：`t_canary_rollout` 字段从 `baseline_version_id BIGINT` / `candidate_version_id BIGINT` 改为 `baseline_skill_name VARCHAR(64)` / `candidate_skill_name VARCHAR(64)`（与 SessionSkillResolver name-based 抽象对齐）。Phase 1.1 落地 V77 时按此命名。

**幂等保证**：靠 V1 t_session_annotation UNIQUE 约束 + ON CONFLICT DO NOTHING（V1 W2 fix 后路径）。

## 6. 聚类 + auto-rollback 算法

`CanaryMetricsService.recompute(Duration window)`：

1. 找所有 `t_canary_rollout WHERE rollout_stage='canary'`
2. 对每个 canary：
   - 查 t_session_annotation 过去 window 内 (annotationType IN ('outcome','canary_group')) 关联 session
   - join canary_group annotation → 分组 control / candidate
   - join outcome annotation → 计算 success/failure count（**outcome 4 值映射见下方决策**）
   - join eval task item（如有）→ 4 维分数平均
   - 写 t_canary_metric_snapshot bucket=hour boundary（UNIQUE 防重）
3. 触发 auto-rollback 检查：
   - 取最近 N 个 snapshot（or rolling window）
   - 累计 candidate_sample_size >= 50 且 candidate_fail_rate / control_fail_rate > 1.5 → trigger
   - `CanaryRolloutService.rollback(canaryId, reason='auto')` → percentage=0 + stage=rolled_back + 写 t_optimization_event（V3 才有，V2 暂存 dashboard 通知）

### 6.1 outcome 4 值映射（Phase 1.4 review 后 ratify，2026-05-15）

session-annotator agent 给每个 session 打的 `outcome` 标签是 `success / partial_success / failure / cancelled` 4 选 1，metric 聚合时映射：

| outcome 标签值 | 映射到 metric 维度 |
|---|---|
| `success` | `*_success_count`（计成功）|
| `partial_success` | `*_success_count`（**算成功**，per Phase 1.4 BE-Dev brief 决定）|
| `failure` | `*_failure_count`（计失败）|
| `cancelled` | `*_failure_count`（**算失败**，per Phase 1.4 BE-Dev brief 决定）|

**所有 4 类都计入 `*_sample_size`**（不丢任何 session）。

**为何 partial_success → success**：候选 skill 部分完成用户请求 > baseline 完全失败时，candidate 应算改进。

**为何 cancelled → failure**：cancelled 包含 "用户中断"（候选体验差用户主动取消）+ "系统超时"（候选效率退化）；归为失败让 auto-rollback 能捕获这类退化。

**已知风险**：基础设施 infra cancelled（如网络故障、上游 LLM 超时）会污染 fail_rate_ratio。V2 dogfood 阶段接受此简化；V3 attribution agent 接入后由 attribution 区分 infra vs skill-caused cancelled，前者不计入。

### 6.2 empty-window snapshot 行为（Phase 1.4 review 后 ratify，2026-05-15）

`recompute` 对每个 active canary 都写一行 `t_canary_metric_snapshot`（包括窗口内 0 outcome 样本的情况，写 0/0/0/0 行）。

**为什么不按原稿"无数据 → 不写"**：
- Dashboard FE 渲染时间序列图时，需要连续 hour bucket（含 0 sample 段）才不会让用户误以为系统挂了
- 空 row 占用磁盘可忽略（active canary 数 × 24 row/day × 16 column ≈ KB/day）
- ON CONFLICT 仍保幂等

**已知 trade-off**：dashboard 看到 long stretch of 0 sample 时应 surface 警告（"canary 流量太低，结果不可信"）。Phase 1.5 FE 实现此 UX。

## 7. Dashboard

### 7.1 REST endpoints

- `POST /api/canary/rollouts` 起 canary → 返 canaryId
- `PATCH /api/canary/rollouts/{id}/step-up` body `{percentage}` 升档
- `POST /api/canary/rollouts/{id}/publish` 一键 100% + active 切换
- `POST /api/canary/rollouts/{id}/rollback` 手动 rollback
- `GET /api/canary/rollouts/{id}` 详情
- `GET /api/canary/rollouts/{id}/metrics?limit=24` 最近 24 个 snapshot
- `GET /api/canary/rollouts?agentId=&surfaceType=&stage=` 列表

### 7.2 前端组件

- `skillforge-dashboard/src/components/canary/CanaryPanel.tsx`：rollout gauge + 4 维对比图（ECharts）+ outcome 分布 + 操作按钮
- `skillforge-dashboard/src/api/canary.ts`：5 endpoint wrapper + types
- 嵌入 `SkillEvolutionPanel.tsx` 或 `SkillAbPanel.tsx`（A/B 通过后展示 publish / start-canary 2 按钮）

## 8. 实施计划

- [x] **Phase 1.0：证伪 + 红测试** ✅ 2026-05-14
  - ✅ 实地校对发现 spawn skill 不在 AgentLoopEngine 入口而在 `SessionSkillResolver` server 层 + 用 skill **name** 不是 id。改动总量 ≤ 3 行 in engine（仅 interface 签名扩 sessionId param）+ 5-10 行 in resolver
  - ✅ AgentLoopEngine 周围 grep `rewriteMessages` / `persistedContext` / `commonPrefixSize` 全 0 命中 → Iron Law 不触
  - ✅ SkillEntity 21 字段无 Jackson 特殊注解，加 2 列 0 风险
  - ✅ V1 t_session_annotation 字段长度全够，复用 upsertSkipDuplicate
  - ✅ t_scheduled_task.agent_id NOT NULL 确认 → V2 必须包装 metrics-collector system agent + RecomputeMetricsTool（V1 V69 同款，已 ratify）
  - ✅ 红测试 `CanaryAllocatorRedTest.java` @Disabled 2 case 落地，mvn 1451/0/0/73 BUILD SUCCESS
  - **Push back 已修**：§5 allocator 签名从 `Long skillId` 改 `String skillName`（与 SessionSkillResolver 对齐）；t_canary_rollout 字段从 `*_version_id BIGINT` 改 `*_skill_name VARCHAR(64)`
- [ ] **Phase 1.1：DB schema + Entity + Repository + IT**
  - V77 (t_canary_rollout + t_canary_metric_snapshot) + V78 (skill 加 2 列) migration
  - V79 seed metrics-collector agent + ScheduledTask（V69 模式）
  - 2 entity + 2 repository + IT
  - 加 SessionAnnotationRepository.findCanaryGroup query 方法
  - SkillEntity 加 2 字段 + 现行 promote 路径 regression test 不破
- [ ] **Phase 1.2：CanaryAllocator + AgentLoopEngine 集成**
  - `CanaryAllocator.allocateSkillVersion` 实现 + 单测 4-5 case
  - AgentLoopEngine 加 allocator 调用（~30 行）
  - **reviewer 显式验 persistence-shape-invariant + identity-column-on-rewrite**
  - 红测试转绿
- [ ] **Phase 1.3：CanaryRolloutService + Controller**
  - startCanary / stepUp / publish / rollback / autoRollbackCheck 方法
  - `CanaryRolloutController` 5 endpoint
  - 单测 + 集成 IT
- [ ] **Phase 1.4：ProdMetricsCollector + metrics-collector agent + RecomputeMetricsTool**
  - `CanaryMetricsService.recompute(Duration)` 实现
  - `RecomputeMetricsTool` 薄包装
  - metrics-collector system agent + Bootstrap + classpath prompt
  - SkillForgeConfig 注册 tool
- [ ] **Phase 1.5：Dashboard `CanaryPanel.tsx`**
  - `api/canary.ts` 5 endpoint wrapper
  - `CanaryPanel.tsx` rollout gauge + 4 维对比图 + outcome 分布 + 操作按钮
  - 嵌入 SkillAbPanel 或 SkillEvolutionPanel
- [ ] **Phase Final：Verify & Commit**
  - `mvn -pl skillforge-server -am test` 全绿
  - `cd skillforge-dashboard && npm run build` EXIT 0
  - **真启服务器跑一次** ProdMetricsCollector hourly cron 看端到端
  - `git diff` 确认核心文件零改动（除 AgentLoopEngine + SkillEntity 两处必要触碰）
  - Reviewer: java-reviewer + typescript-reviewer + database-reviewer + Judge

## 9. 风险 & 边界

| 风险 | 缓解 |
|---|---|
| AgentLoopEngine 触碰双红灯 | reviewer 必须显式 grep 验证 persistence-shape-invariant + identity-column-on-rewrite；commit message acknowledge；改动限 ≤ 30 行 |
| 默认行为意外破坏 | rolloutPercentage=100 默认 + 现行 promote 路径 regression test 锁住 |
| t_scheduled_task 是否支持 method-ref（不指向 agent_id）| Phase 1.0 必须验证；不支持就走 metrics-collector agent + RecomputeMetricsTool 模式（V1 同款，安全推荐） |
| auto-rollback 触发后无法立即 reset（防 oscillation）| 必须显式 manual reset endpoint，dashboard 显示阻塞状态 |
| 跨 agent / 跨用户 canary 协调 | V2 单 agent 互斥 unique constraint；多 agent / multi-tenant 留 V4+ |
| Allocator hash 算法稳定性 | sessionId hash & 0x7FFFFFFF % 100；hash 在同 sessionId 总稳定（同 session 整生命周期版本不切）|
| metrics-collector cron 跟 V1 session-annotator 整点冲突 | V1 cron `0 0 * * * *`（整点）；V2 用 `0 30 * * * *`（半点错开） |
| V1 W2 PG aborted-tx 经验复用 | 所有写 t_session_annotation / t_canary_metric_snapshot 用 ON CONFLICT DO NOTHING（V1 教训）|

## 10. 与现有规则的关系

- 遵守 [`pipeline.md`](../../../../.claude/rules/pipeline.md) Full 档：可选 Plan 对抗 + 1+ dev + 2 reviewer 对抗循环最多 2 轮 + Judge + Phase Final
- 遵守 [`persistence-shape-invariant.md`](../../../../.claude/rules/persistence-shape-invariant.md) + [`identity-column-on-rewrite.md`](../../../../.claude/rules/identity-column-on-rewrite.md)：触碰 AgentLoopEngine 时 reviewer 显式审
- 遵守 [`think-before-coding.md`](../../../../.claude/rules/think-before-coding.md)：Phase 1.0 证伪先行
- 遵守 [`verification-before-completion.md`](../../../../.claude/rules/verification-before-completion.md)：Completion Gate 三件套
- 遵守 [`java.md`](../../../../.claude/rules/java.md)：@Transactional 用法 + ON CONFLICT DO NOTHING（V1 W2 经验）

## 11. 变更记录

- 2026-05-14：claude 初稿（ratified，6 ratify 决策按 plan.md §V2 推荐全部锁定）
- 2026-05-14：Phase 1.0 BE-Dev 校对发现 spawn skill 实际 hook 点是 `SessionSkillResolver`（server 层 / skill name-based）不是 AgentLoopEngine（engine 层 / skill id-based）—— §0.3 + §1 + §2.1 + §5 全部按 name 抽象修正；t_canary_rollout 字段 `*_version_id BIGINT` → `*_skill_name VARCHAR(64)`；AgentLoopEngine 改动从原计划 ≤30 行降到 ≤3 行（Iron Law 几乎不触）
- 2026-05-15：Phase 1.4 review 后锁 2 决策（reviewer 误读两次的歧义点）—— §6.1 outcome 4 值映射明确（partial_success→success、cancelled→failure，避免下一轮 reviewer 复议）+ §6.2 empty-window snapshot 写 0/0/0 行（UX 需要 continuous hour bucket，磁盘可忽略，FE 应 surface low-sample 警告）；W2 tx 隔离修法走 `@Transactional(REQUIRES_NEW)` on `autoRollbackCheck`
