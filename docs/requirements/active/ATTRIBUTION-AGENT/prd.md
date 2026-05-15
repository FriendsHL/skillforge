# ATTRIBUTION-AGENT PRD

---
id: ATTRIBUTION-AGENT
status: ratified
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-15
updated: 2026-05-15
ratified: 2026-05-15
---

## 摘要

V3 飞轮第③⑤⑥步：attribution-curator agent 自动归因 V1 pattern → 产出 proposal → 人工 approve → 自动接现有 candidate generator + A/B + V2 canary。`t_optimization_event` 因果链表全程追溯。

## 已 Ratify 决策（6 条，2026-05-15 全部按 plan.md §V3 推荐锁定）

1. **半自动 vs 全自动**：**半自动**（proposal 产出 → 人工 approve → 起 candidate）。继承 V1 ratify #5
2. **同 pattern 触发 attribution 冷却**：**24h cooldown**（防重复 spend token；手动 override 可绕过）
3. **跨 surface proposal**：**单 surface only**（一次 propose 改 skill OR prompt OR behavior_rule，不同时多 surface）
4. **A/B 通过后自动 canary**：**否**（继承 V2 ratify publish 按钮决策，等人按）
5. **attribution-curator model**：runtime config（不锁开发期决策，seed `claude-sonnet-4-6`）
6. **proposal 默认拒绝 behavior_rule / other / unclear surface**：V3 仅自动接 skill / prompt（behavior_rule 留 V4，other/unclear 不可执行）

## 用户流程

1. 用户用 SkillForge 跑业务 → V1 hourly 标注 + 聚类 → `/insights/patterns` 出 pattern
2. V3 ScheduledTask `attribution-dispatcher-hourly` 触发：
   - 扫 `t_session_pattern` 找未 attribute 过的 pattern（24h cooldown）+ `suspect_surface IN (skill, prompt)` + member ≥ 3
   - 对每个 candidate pattern 派 `attribution-curator` agent run（cap 5 / hour 防 token spike）
3. agent run 5-tool orchestrate：
   - **`PatternRead(patternId)`** → 返 pattern metadata + member session ids
   - **`SessionAnnotationRead(sessionId)`** → 拿 V1 outcome / suspect_surface / signal labels
   - **`GetTrace(action=list_traces|get_trace, sessionId|traceId)`**（复用 V1 V76）→ trace + span tree
   - agent LLM 推理决定 surface + change_type + description + expected_impact + confidence + risk
   - **`ProposeOptimization(patternId, surface, change_type, description, expected_impact, confidence, risk)`** → 写 `t_optimization_event` (stage=proposal_pending) + 推 dashboard WS notify
4. dashboard `/insights/optimization-events` 展示 Pending Approvals queue
5. 用户点 approve：
   - 后端 service 读 event proposal payload
   - surface=skill → 调 `SkillDraftService.createDraftFromAttribution(...)` 生成 candidate skill draft
   - surface=prompt → 调 `PromptImproverService.startImprovement(agentId, abRunId=null, ...)` 生成 candidate prompt version
   - 写 event stage=candidate_ready (intermediate `candidate_generating` is set during the actual SkillDraft / PromptVersion creation; `candidate_failed` if creation throws)
6. candidate 生成完触发现有 SkillAbEvalService 或 AbEvalPipeline A/B：
   - 写 event stage=ab_running
   - A/B 完成 → stage=ab_passed / ab_failed
7. ab_passed → dashboard publish 按钮可点（接 V2 CanaryRolloutController）
8. 用户起 canary 或一刀切 publish → 接 V2 canary 路径 → 写 event stage=canary_started / promoted / rolled_back
9. 全链路 timeline 在 dashboard 可看（pattern → proposal → candidate → ab → canary → final stage）

## 功能需求

### 1. 数据模型

**新表 `t_optimization_event`**：

- `id` BIGINT PK
- `pattern_id` BIGINT FK → t_session_pattern
- `agent_id` BIGINT（哪个 agent 的优化）
- `surface_type` VARCHAR(32) — 'skill' / 'prompt' / 'behavior_rule'（V4+） / 'other' / 'unclear'
- `change_type` VARCHAR(64) — 例 'rewrite_skill_md' / 'tune_prompt' / 'add_constraint'
- `description` TEXT — agent 给的改法描述
- `expected_impact` TEXT — agent 期望指标变化（结构化 JSON 或自然语言）
- `confidence` DECIMAL(3,2) 0..1
- `risk` VARCHAR(16) — 'low' / 'medium' / 'high'
- `stage` VARCHAR(32) — `dispatch_initiated` / `proposal_pending` / `proposal_approved` / `proposal_rejected` / `candidate_generating` / `candidate_ready` / `candidate_failed` / `ab_running` / `ab_passed` / `ab_failed` / `canary_started` / `promoted` / `rolled_back` / `verified` (legacy alias `candidate_created` retained as constant for backward compat)
- `candidate_skill_id` BIGINT NULL — surface=skill 时填
- `candidate_prompt_version_id` BIGINT NULL — surface=prompt 时填
- `ab_run_id` BIGINT NULL — 关联 SkillAbRunEntity 或 PromptAbRunEntity（用 polymorphic 或 separate columns）
- `canary_id` BIGINT NULL — surface=skill 时关联 V2 t_canary_rollout
- `attribution_session_id` VARCHAR(36) — attribution-curator agent run 的 session id（可追溯）
- `created_at` / `updated_at` TIMESTAMPTZ
- `cooldown_expires_at` TIMESTAMPTZ — pattern_id 24h cooldown 计算用

**Index**：`(pattern_id, stage)` / `(agent_id, stage)` / `(stage, created_at)` / `(cooldown_expires_at)`

### 2. 后端组件

- `OptimizationEventEntity` + `OptimizationEventRepository`
- `OptimizationEventService` —— stage 转换 + 校验 + 写 event
- `AttributionDispatcherService` —— hourly cron，扫 pattern + 派 attribution-curator
- `AttributionApprovalService` —— 用户 approve 触发 candidate generation + A/B
- `attribution-curator` system agent + 4 新 tool（PatternRead / SessionAnnotationRead / ProposeOptimization / WriteOptimizationEvent，**GetTrace 复用 V1 V76**）
- `AttributionEventController` REST + WS notify
- `SkillDraftService.createDraftFromAttribution(eventId, ...)` 新入口（不改现行 `extractFromRecentSessions`）
- `PromptImproverService` 看现行 `startImprovement` 是否能直接被 attribution 调，必要时加 attribution-aware overload

### 3. Migration

- V80 schema `t_optimization_event` + 4 索引
- V81 seed `attribution-curator` agent + ScheduledTask（owner_id=1，新约定）

### 4. Agent 设计

`attribution-curator` system prompt orchestrate 4 step：

```
STEP 1 — Read pattern context (deterministic):
  PatternRead(patternId) → metadata + member session IDs (cap 5)

STEP 2 — Drill member sessions (deterministic):
  For each member sessionId:
    SessionAnnotationRead(sessionId) → V1 outcome + suspect_surface + signals
    GetTrace(action='list_traces', sessionId) → trace summaries
    GetTrace(action='get_trace', traceId=<picked>) → span tree (cap 30)
  
STEP 3 — Reason + decide (LLM):
  Based on pattern + sample member sessions, decide:
    - surface (skill | prompt) — V3 不接 behavior_rule / other / unclear
    - change_type (rewrite_skill_md | tune_prompt | add_constraint | ...)
    - description (1-3 sentences, cite specific session evidence)
    - expected_impact (e.g., "outcome 失败率从 60% 降到 20%")
    - confidence (0..1, < 0.5 → 不 propose)
    - risk (low/medium/high)

STEP 4 — Persist proposal (deterministic):
  ProposeOptimization(patternId, surface, change_type, description, expected_impact, confidence, risk)
  → 写 t_optimization_event stage=proposal_pending + dashboard WS notify

CONSTRAINTS:
- Only propose for surface=skill or surface=prompt (V3 scope)
- Confidence < 0.5 → 不 propose（写 event stage=proposal_rejected 自动 + reason='low_confidence'）
- 1 pattern 一次 invocation 只产 1 proposal
- 不 propose 修复方案 implementation 细节（让 candidate generator agent 做，attribution agent 只决策 surface + 大方向）
```

### 5. Dashboard

- 新路由 `/insights/optimization-events` 或 `/insights/proposals`
- Pending Approvals 列表（status='proposal_pending'）：
  - 卡片显示：pattern 摘要 + surface chip + description + confidence + risk
  - Approve / Reject 按钮
  - 点开看完整 attribution-curator session（复用 V1 EvalAnalysisSession 路径）
- Optimization Event Timeline 视图（per pattern 全 stage 转换）
- 整合到现有 SkillEvolutionPanel / SkillAbPanel / V2 CanaryPanel（context-aware 显示当前 stage）

## 非目标

- 不做 attribution-curator 全自动 promote（半自动 per ratify #1）
- 不做 cross-surface proposal（per ratify #3）
- 不做 behavior_rule / hook / mcp / tool surface generator（V4 / V6）
- 不做 user simulator 生产验证（V5）
- 不动 SessionEntity / ChatService / SessionService / CompactionService / AgentLoopEngine（核心红灯）
- 不破坏现有 SkillDraftService.extractFromRecentSessions 路径（只加 attribution 入口）
- 不破坏现有 PromptImproverService.startImprovement 路径

## 验收标准

- [ ] `t_optimization_event` 表 + Entity + Repository + IT
- [ ] `attribution-curator` system agent 跑通：dispatcher 触发 → agent 输出可读 proposal → 写 event
- [ ] 4 个新 tool（PatternRead / SessionAnnotationRead / ProposeOptimization / WriteOptimizationEvent）单测 + 注册
- [ ] 24h cooldown 工作（同 pattern 短时间内不重派）
- [ ] proposal approve 后自动触发 candidate generator（skill OR prompt 分支）+ 写 event stage=candidate_ready (经 candidate_generating intermediate)
- [ ] candidate 生成完 → 触发 A/B（写 ab_running → ab_passed/ab_failed）
- [ ] ab_passed → 触发 V2 publish 按钮 enable（前端 stage 显示）
- [ ] canary 路径接通：ab_passed → 用户 publish/canary → 写 event stage=canary_started → V2 metrics → promoted/rolled_back
- [ ] dashboard `/insights/optimization-events` 展示 + Approve/Reject + Timeline 视图
- [ ] `mvn -pl skillforge-server -am test` 全绿
- [ ] `cd skillforge-dashboard && npm run build` EXIT 0
- [ ] 不动 SessionEntity / ChatService / SessionService / CompactionService / AgentLoopEngine 任何字段
- [ ] Iron Law（persistence-shape + identity-column）0 触

## 后续 Backlog（不在本包）

- V4 MULTI-SURFACE-FLYWHEEL: ProposeOptimization tool 加 behavior_rule 分支
- V5 EVAL-DYNAMIC-USER-SIM: 加 user_sim_verified stage 进 event 链
- attribution-curator 自适应（用历史 proposal 成功率反向调 prompt）
- cross-surface proposal（同 pattern 同时建议改 skill + prompt）
- pattern attribution 优先级排序（按 member_count / severity 排）
