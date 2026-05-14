# SKILL-CANARY-ROLLOUT PRD

---
id: SKILL-CANARY-ROLLOUT
status: ratified
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-14
updated: 2026-05-14
ratified: 2026-05-14
---

## 摘要

V2 飞轮第⑦⑧⑨步对 skill 这一条 surface 落地。**默认一刀切**（rolloutPercentage=100）+ 灰度作 opt-in 模式 + 生产指标 hourly 回流验证 + auto-rollback。

## 已 Ratify 决策（6 条，2026-05-14 全部按 plan.md §V2 推荐落定）

1. **canary 默认起步比例（多用户阶段）**：**10%**（个人 dogfood 用 100% 一刀切）
2. **session canary 组绑定持久化**：**写 t_session_annotation** (annotationType="canary_group", value=`<surface>:<version_id>`)，复用 V1 表，无新表
3. **Auto-rollback 阈值**：`candidate_fail_rate / control_fail_rate > 1.5 且 candidate 样本 > 50`（per surface 配置项可改）
4. **同 agent canary 互斥**：**DB unique constraint** on `(agent_id, surface_type)` where `rollout_stage = 'canary'`（PostgreSQL partial unique index）
5. **CanaryAllocator 注入点**：**AgentLoopEngine spawn skill 之前**（核心文件红灯，reviewer 必须显式审 persistence-shape-invariant + identity-column-on-rewrite 两条 Iron Law）
6. **ProdMetricsCollector 频率**：**hourly**（P12 ScheduledTask 同款，复用 V1 V69 dogfood 模式）

## 用户流程

### Opt-in canary 流程

1. 用户在 SkillForge 跑日常工作 → 产生 production session
2. SkillDraftService 抽 draft → SkillAbEvalService A/B 通过 → 进 `awaiting_publish` 状态（**新状态机**，等人 publish）
3. 用户在 skill 详情页选择起 canary（默认 10%）vs 立刻 publish 一刀切
4. 起 canary：
   - `CanaryRolloutService.startCanary(skillId, candidateId, percentage)` 写 `t_canary_rollout` row + reset `t_canary_metric_snapshot` 累计
   - SkillEntity.rolloutStage='canary' / rolloutPercentage=10
   - CanaryAllocator 对后续 session 按 hash 分流（10% canary / 90% control）
   - **每条进入 canary group 的 session 写 t_session_annotation (annotationType="canary_group", value="skill:candidateId")**，session 生命周期锁定不切版本
5. ProdMetricsCollector hourly 跑：
   - 扫过去 1h 完成 session 的 t_session_annotation outcome 标签
   - 按 sessionId 反查 canary_group 标签 → 分组 control / candidate
   - 4 维分数（quality / efficiency / latency / cost）+ outcome 分布 → 写 `t_canary_metric_snapshot`
   - 触发 auto-rollback signal 检查（candidate_fail_rate / control_fail_rate > 1.5 且样本 > 50）
6. Dashboard skill 详情页 canary panel 实时看：rollout gauge / 4 维对比 / outcome 分布 / 累计样本
7. 用户手动升档：percentage 10 → 20 → 50 → 100，或一键 publish（直接 100% + active 切换）
8. Auto-rollback 触发：percentage 立刻置 0 + rolloutStage='rolled_back' + dashboard 告警 + 必须人工 reset 才能再起 canary

### 默认一刀切流程

1. 同上 1-2（A/B 通过）
2. 用户按 publish 按钮 → 调 `SkillAbEvalService.manualPromote(abRunId)` → 现行 enable 100% 路径
3. 行为 = 现行（rolloutPercentage 默认 100）+ ProdMetricsCollector 仍然 hourly 跑（跟历史 baseline 比，没有 control group）
4. 看到指标恶化 → 人工 disable

## 功能需求

### 1. 数据模型

**新表 `t_canary_rollout`**（per surface per canary 实例）：
- `id` BIGINT PK
- `surface_type` VARCHAR(32)（"skill" / future "prompt" / "behavior_rule"）
- `agent_id` BIGINT FK
- `baseline_version_id` BIGINT（旧 active skill id）
- `candidate_version_id` BIGINT（新 candidate skill id）
- `rollout_stage` VARCHAR(32)（disabled / canary / production / rolled_back）
- `rollout_percentage` INT 0..100
- `started_at` TIMESTAMPTZ
- `last_decision_at` TIMESTAMPTZ
- `decision` VARCHAR(32) NULL（promoted / rolled_back / null=ongoing）
- `created_at` / `updated_at` TIMESTAMPTZ
- **partial unique constraint**：`(agent_id, surface_type) WHERE rollout_stage='canary'`（同 agent 同 surface 同时只能 1 个 canary）

**新表 `t_canary_metric_snapshot`**（hourly per canary）：
- `id` BIGINT PK
- `canary_id` BIGINT FK → t_canary_rollout
- `bucket_at` TIMESTAMPTZ（hour boundary）
- `control_sample_size` INT
- `control_success_count` INT / `control_failure_count` INT
- `control_avg_quality` / `control_avg_efficiency` / `control_avg_latency` / `control_avg_cost` DECIMAL
- `candidate_sample_size` / `candidate_success_count` / `candidate_failure_count`
- `candidate_avg_quality` / `efficiency` / `latency` / `cost`
- `fail_rate_ratio` DECIMAL（candidate / control，触发 auto-rollback 用）
- `created_at` TIMESTAMPTZ
- index `(canary_id, bucket_at DESC)`

**SkillEntity 加 2 列**：
- `rollout_stage` VARCHAR(32) DEFAULT 'production'（disabled / canary / production / rolled_back）
- `rollout_percentage` INT DEFAULT 100（**默认 100 = 现行行为**）

### 2. 后端组件

- `CanaryRolloutService`：startCanary / stepUp / publish / rollback / autoRollbackCheck
- `CanaryAllocator`：`Long allocateSkillVersion(sessionId, agentId, baselineSkillId)` 返回应用的 skill version id
- `ProdMetricsCollector`：hourly cron 聚合 outcome 标签 + 写 metric snapshot + 触发 auto-rollback signal
- `t_session_annotation` 新 annotation_type `canary_group` 值 `<surface>:<version_id>`（复用 V1 表，零 schema 变更需要）

### 3. AgentLoopEngine 改动（核心文件红灯）

**唯一需要触碰的核心文件**：在 spawn skill 之前加一层 `CanaryAllocator.allocateSkillVersion(sessionId, agentId, baselineSkillId)` 调用，结果决定挂载 baseline 还是 candidate skill。

**不变量保护**：
- `persistence-shape-invariant.md`：allocator 决策不能影响 ChatService 持久化的 Message 内容
- `identity-column-on-rewrite.md`：SessionService.rewriteMessages 路径完全不动
- reviewer 显式 grep 两条 Iron Law 验证

### 4. Dashboard

- Skill 详情页加 canary panel：
  - Rollout gauge（百分比环形图，颜色按 stage 上色）
  - 4 维分数对比（control vs candidate，bar chart，dimensionStatus not_measured 灰显）
  - Outcome 分布对比（饼图 success / partial / failure / cancelled）
  - 累计样本计数 + auto-rollback 阈值 progress
  - 操作按钮：start canary / step up / publish / rollback / reset
- 配置项 `auto_promote_after_ab`（per agent）：true = A/B 通过自动 enable 100%，false = 等人按 publish（默认）

### 5. 配置

- `application.yml` 新增（per-agent runtime 配置覆盖）：
  - `skillforge.canary.default_percentage`: 10
  - `skillforge.canary.auto_rollback.fail_rate_ratio_threshold`: 1.5
  - `skillforge.canary.auto_rollback.min_sample_size`: 50
  - `skillforge.canary.metrics.frequency`: hourly

## 非目标

- 不接 V3 attribution agent
- 不做 prompt / behavior rule canary（V4）
- 不做 user simulator 多轮验证（V5）
- 不动 SessionEntity / ChatService / SessionService / CompactionService
- 不改 SkillAbEvalService.promoteCandidate 现行签名（向后兼容）
- 不做 multi-tenant 隔离
- 不动 V1 表 schema（仅复用 t_session_annotation 加新 annotation_type 值）

## 验收标准

- [ ] 2 张新表 + Entity / Repository + JPA IT
- [ ] SkillEntity 加 2 列 migration + JPA 字段 + Repository 不破现有
- [ ] CanaryAllocator + CanaryRolloutService + ProdMetricsCollector 单测 + 集成测试
- [ ] AgentLoopEngine 加 allocator 注入 + persistence-shape-invariant + identity-column-on-rewrite reviewer 显式验证（两条 Iron Law 不变）
- [ ] Skill 详情页加 canary panel 组件 + Publish/Rollback 按钮 + 4 维对比图
- [ ] 配置 `auto_promote_after_ab` 默认 false + 起 canary 路径完整
- [ ] **rolloutPercentage 默认 100 = 现行行为 100% backward-compatible**（regression test 现行 promote 全量路径不破）
- [ ] Auto-rollback 触发 + 状态机置 rolled_back + dashboard 告警
- [ ] `mvn -pl skillforge-server -am test` 全绿 + `cd skillforge-dashboard && npm run build` EXIT 0
- [ ] 不动 SessionEntity / ChatService / SessionService / CompactionService 任何字段（grep diff 证明）

## 后续 Backlog（不在本包）

- V3 ATTRIBUTION-AGENT 自动接 candidate → 自动起 canary
- V4 MULTI-SURFACE-FLYWHEEL 扩 CanaryAllocator 支持 prompt / behavior_rule
- V5 EVAL-DYNAMIC-USER-SIM 把 user sim 结果加进 metric snapshot
- 跨 agent / 跨用户 canary 协调（multi-tenant 上线时）
