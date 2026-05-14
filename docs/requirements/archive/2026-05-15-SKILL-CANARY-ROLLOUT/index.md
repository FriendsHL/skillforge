# SKILL-CANARY-ROLLOUT Skill 灰度（架构保留）+ 生产指标回流

---
id: SKILL-CANARY-ROLLOUT
mode: full
status: ratified
priority: P1
risk: Full
created: 2026-05-14
updated: 2026-05-14
ratified: 2026-05-14
---

## 摘要

数据飞轮 V2：飞轮第⑦⑧⑨步对 skill 这一条 surface 落地。**默认一刀切**（rolloutPercentage=100 等于现行行为），灰度作 opt-in 模式保留为多用户阶段用。Skill 自动 promote 后通过生产指标回流验证 "真的更好"。

跑完后看用户实际 dogfood 经验决定 V3 attribution agent 的 prompt 工程方向。

## 整体方案归属

本包是 [`docs/plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md`](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) 6 个版本里的 V2。前置 V1 PROD-LABEL-CLUSTER 已交付（[archive](../../archive/2026-05-14-PROD-LABEL-CLUSTER/index.md)）。

## 范围裁剪

V2 Full 推进（触碰 AgentLoopEngine 核心 + 加 schema）：

- 仅 skill surface（prompt / behavior rule 留 V4 MULTI-SURFACE-FLYWHEEL）
- 默认一刀切（rolloutPercentage=100），灰度 opt-in（用户主动起 canary 才进流程）
- 不接 attribution agent（V3）
- 不做 user simulator 多轮验证（V5）

## 阅读顺序

1. [MRD](mrd.md) — 业务痛点 + V2 目标
2. [PRD](prd.md) — 6 ratify 决策 + 功能范围 + 验收 + 非目标
3. [技术方案](tech-design.md) — 复用 vs 新建 / 现有证据 / 实施 phase

## 当前状态

**已 ratify（2026-05-14）**。开发期默认值（plan.md §V2 推荐已全部锁定）：

- canary 默认起步：**10%**（多用户阶段；个人 dogfood 用 100% 一刀切）
- canary 组绑定：写 **t_session_annotation** (annotationType="canary_group")，复用 V1 表
- Auto-rollback 阈值：`candidate_fail_rate / control_fail_rate > 1.5 且样本 > 50`
- 同 agent canary 互斥：**DB unique constraint** on (agent_id, surface_type, stage='canary')
- CanaryAllocator 注入点：**AgentLoopEngine spawn skill 之前**（核心文件红灯）
- ProdMetricsCollector 频率：**hourly**（跟 V1 同款 P12 ScheduledTask）

下一步：**Phase 1.0 证伪**（验 AgentLoopEngine skill 加载入口位置 + SkillEntity 2 列扩展可行性 + V1 t_session_annotation 复用 canary_group 可行性）。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 整体方案 §V2 | [../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) |
| V1 前置（已交付）| [archive/2026-05-14-PROD-LABEL-CLUSTER](../../archive/2026-05-14-PROD-LABEL-CLUSTER/index.md) |
| 交付 | — |
