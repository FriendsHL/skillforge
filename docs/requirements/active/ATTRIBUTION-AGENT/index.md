# ATTRIBUTION-AGENT 飞轮归因 + Optimization Event 因果链

---
id: ATTRIBUTION-AGENT
mode: full
status: ratified
priority: P1
risk: Full
created: 2026-05-15
updated: 2026-05-15
ratified: 2026-05-15
---

## 摘要

数据飞轮 V3：飞轮第③⑤⑥步打通自动化通路。从 V1 失败 pattern → `attribution-curator` agent 决策"该改哪个 surface + 怎么改" → 写 proposal → 人工 approve（半自动）→ 自动起 candidate（接现有 SkillDraftService / PromptImproverService）→ 自动起 V2 canary → 每个 stage 转换写 `t_optimization_event` 因果链表 → dashboard timeline 还原全链路。

## 整体方案归属

本包是 [`docs/plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md`](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) 6 版本里的 V3。前置 V1 + V2 已交付：

- V1 PROD-LABEL-CLUSTER（[archive 2026-05-14](../../archive/2026-05-14-PROD-LABEL-CLUSTER/index.md)）—— pattern 表 + session-annotator agent
- V2 SKILL-CANARY-ROLLOUT（[archive 2026-05-15](../../archive/2026-05-15-SKILL-CANARY-ROLLOUT/index.md)）—— canary infrastructure + metrics 回流

## 范围裁剪

V3 Full 推进：

- **半自动**（proposal 等人审 approve；per V1 ratify #5）
- 仅自动接 skill / prompt 两个 surface 的 candidate generator（V1 ratify #1 决策）
- behavior_rule surface 留 stub（V4 才有 generator）
- 一个 pattern 一次只能起一个 active optimization event（防 candidate quota 浪费）
- 单 surface proposal（V3 不做 cross-surface 同时建议改 skill+prompt）

## 阅读顺序

1. [MRD](mrd.md) — 业务痛点 + V3 目标
2. [PRD](prd.md) — 6 ratify 决策 + 功能范围 + 验收 + 非目标
3. [技术方案](tech-design.md) — 复用 vs 新建 + agent prompt 骨架 + 实施 phase

## 当前状态

**已 ratify（2026-05-15）**。开发期默认值：

- 半自动 attribution（proposal → 人工 approve → 自动起 candidate → A/B → 人工 publish → canary，per V1 ratify #4 + #5）
- 单 surface proposal（一次 propose 改 skill OR prompt，不同时）
- pattern attribution 冷却 **24h**（防重复 spend token）
- attribution-curator model 用 `claude-sonnet-4-6` seed（runtime t_agent.llm_model 用户可改）
- ScheduledTask cron 半点错开：`0 15 * * * *`（V75 session-annotator 整点 / V79 metrics-collector 30 分 / V3 attribution 15 分）

下一步：**Phase 1.0 证伪**（验 `EvalAnalysisSessionEntity` 扩 enum 可行性 + 现有 `AnalyzeEvalTaskTool` 模式复用度 + 现有 `SkillDraftService` / `PromptImproverService` 接入点 + SubAgentRegistry 派 attribution-curator 可行）。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 整体方案 §V3 | [../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) |
| V1 前置（已交付）| [archive/2026-05-14-PROD-LABEL-CLUSTER](../../archive/2026-05-14-PROD-LABEL-CLUSTER/index.md) |
| V2 前置（已交付）| [archive/2026-05-15-SKILL-CANARY-ROLLOUT](../../archive/2026-05-15-SKILL-CANARY-ROLLOUT/index.md) |
| 交付 | — |
