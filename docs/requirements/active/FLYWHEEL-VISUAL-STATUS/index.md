# FLYWHEEL-VISUAL-STATUS 自动化归因飞轮可视化

---
id: FLYWHEEL-VISUAL-STATUS
mode: mid
status: design-draft
priority: P2
risk: Low
created: 2026-05-16
updated: 2026-05-16
---

## 摘要

V1+V2+V3+V4+V5 5 个版本累计交付了完整 9 步飞轮 (标注 → 聚类 → 归因 → candidate → A/B → Gate → canary → 回流 → 决策)。但 **dashboard 上没有一个集中可视化**让 operator 一眼看到当前飞轮跑到哪步、哪个 candidate 处于什么 stage、哪个 surface 在 in-flight。

operator 现状只能跨 3 个 page 拼图：
- `/insights/patterns` (V1 cluster 输出)
- `/insights/optimization-events` (V3 attribution 状态机)
- `/insights/skill-canary` 或 agents/{id}/skill-evolution (V2 canary)

V5 落地后再加 `/insights/behavior-rules` (V4) + `/insights/dynamic-sim` (V5 trial)。**总计 5 个独立 panel，没全局视图**。

本包做"飞轮状态总览" panel，跨表聚合显示 9 步 + 3 surface (skill / prompt / behavior_rule) 的实时状态 timeline。

## 范围

Mid 档, ~2-3 工作日:

- 新建 `FlywheelStatusPanel` (主 panel)
- 新建 `FlywheelTimeline` (单 surface 9 step timeline 组件)
- 复用现有 BE endpoint (不新建 BE) — 多 useQuery 并行拉数据 + FE 聚合
- 嵌入 `Insights.tsx` 第 5 tab `'flywheel'`
- 不动核心 7+1 BE + 3 FE 文件 (Iron Law)

**不做** (留 follow-up):
- 实时 SSE/polling 自动刷新 (operator 手动刷)
- 历史回放 / 趋势图表
- 跨 agent 聚合 (per-agent 视图)

## 阅读顺序

1. [MRD](mrd.md) — 痛点 + operator 使用场景
2. [PRD](prd.md) — Phase 1 范围 + 验收点 + UI 草图
3. [技术方案](tech-design.md) — 数据源聚合方案 + 9 step → state mapping + FE 组件结构

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 整体方案 | [PROD-OPTIMIZATION-FLYWHEEL](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) |
