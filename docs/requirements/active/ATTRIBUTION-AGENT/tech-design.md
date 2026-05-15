# ATTRIBUTION-AGENT 技术方案

---
id: ATTRIBUTION-AGENT
status: ratified
mode: full
created: 2026-05-15
updated: 2026-05-15
---

## 0. 现状证据（Phase 1.0 证伪必跑）

### 0.1 复用目标已存在 — 文件路径

| 复用项 | 文件路径 | 用法 |
|---|---|---|
| V1 `t_session_pattern` + `t_pattern_session_member` 表 | V74 migration | attribution dispatcher 扫这两个找 candidate pattern |
| V1 `SessionAnnotationEntity` + `SessionAnnotationRepository` | Phase 1.1 V1 | `SessionAnnotationRead` tool 读 V1 outcome / suspect_surface / signal labels |
| V1 `GetTraceTool`（V76 已注册）| `skillforge-server/src/main/java/com/skillforge/server/tool/GetTraceTool.java` | attribution-curator agent 复用，**不重建** SessionFetch / TraceFetch |
| V1 `session-annotator` Bootstrap + classpath prompt + V69 dogfood pattern | V75 seed + `SessionAnnotatorBootstrap` | V3 `attribution-curator` Bootstrap 完全照搬，改 3 个常量 |
| V1 P12 ScheduledTask + concurrency_policy='skip-if-running' | V69 + V75 + V79 模式 | V3 ScheduledTask seed `attribution-dispatcher-hourly` 半点错开 |
| V1 `SubAgentRegistry` + `SubAgentTool` | 现有 | dispatcher 派 attribution-curator session 走它，**零改动** |
| 现有 `SkillDraftService.extractFromRecentSessions` | `skillforge-server/src/main/java/com/skillforge/server/improve/SkillDraftService.java` | approve flow surface=skill 时调它的 attribution-aware 新入口 |
| 现有 `PromptImproverService.startImprovement` | `skillforge-server/src/main/java/com/skillforge/server/improve/PromptImproverService.java` | approve flow surface=prompt 时调它（attribution-aware overload） |
| 现有 `SkillAbEvalService` / `AbEvalPipeline` | V1 改前 + V2 改后 | candidate 起完自动触发 A/B 走它们 |
| V2 `CanaryRolloutService` | Phase 1.3 落地 | ab_passed → 用户起 canary 经它（写 event canary_started）|
| V2 `t_canary_rollout` + `t_canary_metric_snapshot` | V77 落地 | event link canary_id 字段引用 |
| 现有 `EvalAnalysisSessionEntity` 模型 | V1 落地 | V3 扩 `analysisType` enum 加 `PATTERN_LEVEL`，**不新建表**，attribution-curator session 链到 pattern |
| 现有 `AnalyzeEvalTaskTool` 写 attributionSummary 模式 | `skillforge-server/src/main/java/com/skillforge/server/tool/AnalyzeEvalTaskTool.java` | `ProposeOptimization` tool 直接 copy 形态（attribution_summary → proposal 字段）|
| V1 `OptimizableSurface<V>` 空接口骨架 | V1 Phase 1.1 落地 | V3 不动它（V4 才填实第二个 surface 实现）|
| V1 `SkillEvolutionPanel.tsx` timeline 视图 | V1 dashboard | V3 optimization event timeline 复用 UI 模式 |

### 0.2 不动的核心文件清单（grep diff 验证）

| 文件 | 不动理由 |
|---|---|
| `skillforge-server/.../entity/SessionEntity.java` | Iron Law 双红灯 |
| `skillforge-server/.../entity/SessionMessageEntity.java` | 同上 |
| `skillforge-server/.../service/ChatService.java` | 核心文件 |
| `skillforge-server/.../service/SessionService.java` | 核心文件 |
| `skillforge-server/.../service/CompactionService.java` | 核心文件 |
| `skillforge-core/.../engine/AgentLoopEngine.java` | 核心文件，V2 已扩 1 行 sessionId 透传足够 |
| `skillforge-server/.../tool/GetTraceTool.java` | V1 V76 已接入 session-annotator + V3 attribution-curator 也复用，**不改它的签名** |

### 0.3 V3 触碰的现有文件

| 文件 | 改动 |
|---|---|
| `SkillDraftService.java` | 加 `createDraftFromAttribution(eventId, ...)` 新入口；现行 `extractFromRecentSessions` 不动 |
| `PromptImproverService.java` | 加 `startImprovementFromAttribution(eventId, agentId, ...)` 新入口；现行 `startImprovement` 不动 |
| `EvalAnalysisSessionEntity.java` | analysisType 是 `String length=32`（Phase 1.0 BE-Dev 校对，不是 @Enumerated / @Convert）；加 `PATTERN_LEVEL` 常量 100% 安全（grep 0 switch/case 命中），加 `TYPE_PATTERN_LEVEL` 常量保持惯例 |
| `SkillForgeConfig.java` | 注册 4 新 tool（PatternRead / SessionAnnotationRead / ProposeOptimization / WriteOptimizationEvent）|

### Phase 1.0 证伪步骤

1. 跑现有 `AnalyzeEvalTaskTool` 链路一次（看 attribution_summary 写入路径）
2. grep `EvalAnalysisSessionEntity` 现有 analysisType enum 值，确认加 `PATTERN_LEVEL` 不破现有 case
3. grep `SkillDraftService.extractFromRecentSessions` 看怎么生成 draft（draft entity / repository / approve flow），评估 `createDraftFromAttribution` 入口设计
4. grep `PromptImproverService.startImprovement` 看现行入口，评估 attribution-aware overload
5. 写红测试 `AttributionDispatcherRedTest` @Disabled 占位

---

## 1. 总体架构

```
        ┌──────────────────────────────────────┐
        │  ScheduledTask attribution-dispatcher │
        │  hourly cron `0 15 * * * *`           │
        │  agent_id = attribution-curator       │
        │  concurrency_policy='skip-if-running' │
        └────────────────┬─────────────────────┘
                         │ 触发
                         ▼
        ┌──────────────────────────────────────┐
        │  attribution-curator agent run        │
        │                                       │
        │  STEP 1 PatternRead(patternId)         │
        │  STEP 2 多 SessionAnnotationRead +     │
        │         GetTrace（V1 V76 复用）       │
        │  STEP 3 LLM 推理 → surface + change_  │
        │         type + description + risk     │
        │  STEP 4 ProposeOptimization (写 event │
        │         stage=proposal_pending)        │
        └────────────────┬─────────────────────┘
                         │
                         ▼
        ┌──────────────────────────────────────┐
        │  Dashboard /insights/optimization-     │
        │  events （Pending Approvals + Timeline）│
        └────────────────┬─────────────────────┘
                         │ 用户点 Approve
                         ▼
        ┌──────────────────────────────────────┐
        │  AttributionApprovalService           │
        │  - surface=skill → SkillDraftService  │
        │    .createDraftFromAttribution(...)   │
        │  - surface=prompt → PromptImprover    │
        │    .startImprovementFromAttribution() │
        │  → 写 event stage=candidate_ready     │
        └────────────────┬─────────────────────┘
                         │ candidate 生成完
                         ▼
        ┌──────────────────────────────────────┐
        │  现有 SkillAbEvalService /            │
        │  AbEvalPipeline A/B 评测              │
        │  → 写 event stage=ab_running → passed │
        └────────────────┬─────────────────────┘
                         │ A/B passed
                         ▼
        ┌──────────────────────────────────────┐
        │  Dashboard Publish 按钮 enable        │
        │  → 用户起 V2 CanaryRollout            │
        │  → 写 event stage=canary_started      │
        └────────────────┬─────────────────────┘
                         │ V2 metrics + auto-rollback
                         ▼
        ┌──────────────────────────────────────┐
        │  写 event stage=promoted / rolled_back│
        │  → Dashboard timeline 还原全链路       │
        └──────────────────────────────────────┘
```

## 2. 数据库 Schema

### V80 migration `V80__create_optimization_event.sql`

```sql
CREATE TABLE t_optimization_event (
    id                              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pattern_id                      BIGINT NOT NULL REFERENCES t_session_pattern(id) ON DELETE CASCADE,
    agent_id                        BIGINT NOT NULL,
    surface_type                    VARCHAR(32) NOT NULL,
    change_type                     VARCHAR(64),
    description                     TEXT,
    expected_impact                 TEXT,
    confidence                      DECIMAL(3,2) CHECK (confidence >= 0 AND confidence <= 1),
    risk                            VARCHAR(16),  -- low / medium / high
    stage                           VARCHAR(32) NOT NULL,
    candidate_skill_id              BIGINT,
    candidate_prompt_version_id     BIGINT,
    ab_run_id                       BIGINT,
    canary_id                       BIGINT,
    attribution_session_id          VARCHAR(36),
    cooldown_expires_at             TIMESTAMPTZ,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_optimization_event_pattern ON t_optimization_event(pattern_id, stage);
CREATE INDEX idx_optimization_event_agent ON t_optimization_event(agent_id, stage);
CREATE INDEX idx_optimization_event_stage_time ON t_optimization_event(stage, created_at DESC);
CREATE INDEX idx_optimization_event_cooldown ON t_optimization_event(cooldown_expires_at) WHERE cooldown_expires_at IS NOT NULL;
```

### V81 migration — seed attribution-curator agent + ScheduledTask

参考 V79 模式：

```sql
INSERT INTO t_agent (name, description, model_id, system_prompt, skill_ids, tool_ids,
                     config, lifecycle_hooks, owner_id, is_public, status, execution_mode,
                     created_at, updated_at)
SELECT 'attribution-curator', '...',
    'claude-sonnet-4-6',  -- runtime user 可改
    'SEE_FILE:attribution-curator-system-prompt.md',
    '[]',
    '["PatternRead","SessionAnnotationRead","GetTrace","ProposeOptimization","WriteOptimizationEvent"]',
    '{"temperature": 0.2, "maxTokens": 4096}',
    NULL, 1, TRUE, 'active', 'auto', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_agent WHERE name = 'attribution-curator');

INSERT INTO t_scheduled_task (name, creator_user_id, agent_id, cron_expr, timezone,
                              prompt_template, session_mode, enabled, concurrency_policy,
                              status, created_at, updated_at)
SELECT 'attribution-dispatcher-hourly', 0,
    (SELECT id FROM t_agent WHERE name='attribution-curator'),
    '0 15 * * * *',  -- 15 分错开 V75 整点 + V79 30 分
    'Asia/Shanghai',
    'Attribution dispatcher: scan unprocessed patterns and propose optimizations',
    'new', TRUE, 'skip-if-running', 'idle', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_scheduled_task WHERE name='attribution-dispatcher-hourly');
```

## 3. 后端组件清单

### 3.1 复用（不新写）

| 类 | 复用程度 |
|---|---|
| V1 `SessionPatternRepository` / `PatternSessionMemberRepository` | dispatcher 扫 pattern 直接调 |
| V1 `SessionAnnotationRepository` | SessionAnnotationRead tool 调（findRecentBySessionId） |
| V1 `GetTraceTool` | attribution-curator agent 复用，**零改动** |
| V1 `SessionAnnotatorBootstrap` 模板 | 复制成 `AttributionCuratorBootstrap`，改 3 个常量 |
| V1/V2 P12 ScheduledTask 框架 | V81 seed 同款 |
| 现有 `SkillDraftService` / `PromptImproverService` | 加 attribution-aware 入口（不破现有） |
| 现有 `SkillAbEvalService` / `AbEvalPipeline` | A/B 触发逻辑 |
| V2 `CanaryRolloutService` / `CanaryRolloutController` | publish/canary 路径 |
| 现有 `EvalAnalysisSessionEntity` | analysisType enum 加 PATTERN_LEVEL |

### 3.2 新建

| 组件 | 类型 |
|---|---|
| `OptimizationEventEntity` / `Repository` | JPA |
| `OptimizationEventService` | stage 转换 + 校验 |
| `AttributionDispatcherService` | hourly cron 扫 pattern + 派 attribution-curator |
| `AttributionApprovalService` | 用户 approve 触发 candidate generation + A/B |
| `attribution-curator` Bootstrap + classpath prompt md | 启动加载 |
| 4 新 tool（PatternRead / SessionAnnotationRead / ProposeOptimization / WriteOptimizationEvent）| Tool 接口实现 |
| `AttributionEventController` | REST CRUD + Approve/Reject |
| WS notify `attribution_proposal_pending` | 推送 dashboard |
| FE `api/attribution.ts` + `pages/OptimizationEvents.tsx` + `components/attribution/*.tsx` | dashboard |

## 4. agent prompt 骨架（细节延后 Phase 1.3）

写 `classpath:attribution-curator-system-prompt.md`，按 mrd §V3 STEP 1-4 结构（PRD §功能需求 §4 已详细列）。

## 5. 实施计划

- [ ] **Phase 1.0** 证伪 + 红测试（5 项 grep 见 §0.3）
- [ ] **Phase 1.1** V80 + V81 migration + Entity + Repository + Bootstrap + classpath prompt + JPA IT
- [ ] **Phase 1.2** AttributionDispatcherService + 4 tool（PatternRead / SessionAnnotationRead / ProposeOptimization / WriteOptimizationEvent）+ tool 单测
- [ ] **Phase 1.3** AttributionApprovalService（approve / reject + stage transition validation）+ 接现有 SkillDraftService.createDraftFromAttribution + PromptImproverService.startImprovementFromAttribution（attribution-aware 入口，bypass agent-level cooldown per 2026-05-15 ratify）+ AttributionDispatcherService 加 `dispatch_initiated` sentinel（race window 防御，per 2026-05-15 ratify）+ stage 流转写 event 行
- [ ] **Phase 1.4** AttributionEventController + REST + WS notify
- [ ] **Phase 1.5** FE OptimizationEvents page + Pending Approvals queue + Timeline 视图 + 嵌入 SkillEvolutionPanel context
- [ ] **Phase Final** mvn / npm build / 真启 attribution-dispatcher cron 跑一次 / 归档

## 6. 风险 & 边界

| 风险 | 缓解 |
|---|---|
| V3 触碰 SkillDraftService / PromptImproverService 现行入口 | 加新方法 不破现有签名 |
| attribution-curator 跑空 pattern（V1 dogfood 数据少）| dispatcher 跳过 member_count < 3 + suspect_surface not in (skill, prompt) |
| 24h cooldown 计算精度 | 写 cooldown_expires_at = NOW() + INTERVAL '24h'，cron 查 WHERE cooldown_expires_at < NOW() |
| candidate generation 调失败（外部 LLM API down）| 写 event stage=candidate_failed + retry 路径（手动）|
| ~~EvalAnalysisSessionEntity analysisType enum 扩展破现有 case~~ | Phase 1.0 BE-Dev 已校对：analysisType 是 String length=32 不是 enum，grep 0 switch/case 命中 → 加新值 100% 安全 |
| **PromptImprover 双 cooldown 冲突**（V3 24h pattern-level vs 现行 `checkEligibility` 24h `agent.lastPromotedAt`）| **已 ratify 2026-05-15: BYPASS agent-level cooldown**。attribution 路径走 `startImprovementFromAttribution` 不调 `checkEligibility`，仅依赖 V3 自带 24h pattern-level cooldown + risk gating。理由：attribution 是精准驱动（cluster 出 ≥3 个失败 member + 高 confidence），跟 manual / cron-job-spam 不是同一保护场景。|
| **dispatcher race window**（cron tick + manual trigger 在 LLM 处理窗口双发同一 pattern）| **已 ratify 2026-05-15: 加 sentinel**。dispatcher 在调 `chatService.chatAsync` 前先写一条 `stage='dispatch_initiated'` event row，ACTIVE_STAGES 含该 stage，后续 Filter 4 自动跳过。ProposeOptimizationTool 写 `proposal_pending` 时同时 update 老 sentinel 行的 stage（或 delete + insert，取决于实现）。同时 dispatch_initiated 进 KNOWN_STAGES。|
| Iron Law（不改 6 核心文件 + GetTraceTool） | reviewer 显式 grep 验证 |

## 7. 与现有规则的关系

- 遵守 [`pipeline.md`](../../../../.claude/rules/pipeline.md) Full 档
- 遵守 [`persistence-shape-invariant.md`](../../../../.claude/rules/persistence-shape-invariant.md) + [`identity-column-on-rewrite.md`](../../../../.claude/rules/identity-column-on-rewrite.md)
- 遵守 [`java.md`](../../../../.claude/rules/java.md) + V1 W2 / V2 W2 教训（ON CONFLICT + REQUIRES_NEW）
- 遵守 [`think-before-coding.md`](../../../../.claude/rules/think-before-coding.md) + [`verification-before-completion.md`](../../../../.claude/rules/verification-before-completion.md)

## 8. 变更记录

- 2026-05-15：claude 初稿（ratified，6 ratify 决策按 plan.md §V3 + V1/V2 ratify 锁定）
- 2026-05-15：Phase 1.0 BE-Dev 校对 + 修 2 push back：(1) §0.3 + §6 EvalAnalysisSession enum 措辞改为 String 字段（实际不是 enum，加值 100% 安全）；(2) §6 加 PromptImprover 双 cooldown 冲突风险条目，Phase 1.3 必须 ratify attribution 路径是否 bypass agent-level cooldown（推荐 bypass）；其它 5 项调研全 ✅ 复用计划成立
- 2026-05-15：Phase 1.3 开工前 2 ratify 锁定：(1) PromptImprover 双 cooldown → **BYPASS agent-level cooldown**（仅 V3 24h pattern-level + risk gating）；(2) dispatcher race window → **加 `dispatch_initiated` sentinel**（chatAsync 前写 sentinel event，ACTIVE_STAGES / KNOWN_STAGES 同步加该 stage）。§5 Phase 1.3 措辞更新，§6 两条风险条目从"Phase 1.3 必须 ratify"改为"已 ratify"
