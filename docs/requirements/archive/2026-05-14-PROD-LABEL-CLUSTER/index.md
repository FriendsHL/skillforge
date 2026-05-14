# PROD-LABEL-CLUSTER 生产 Session 标注 + 失败聚类 MVP

---
id: PROD-LABEL-CLUSTER
mode: mid
status: ratified
priority: P1
risk: Mid
created: 2026-05-14
updated: 2026-05-14
ratified: 2026-05-14
---

## 摘要

数据飞轮 V1：给每条 production session 做结构化标注（signal + LLM agent 双通道），把多个失败 session 按简单 bucket 聚类成 pattern，dashboard 展示。本包是飞轮第①② 步的 MVP 实现，**不**接归因 agent / 不接灰度 / 不动核心 service。

跑完 V1 后基于真实数据看 pattern 是否有效再决定 V2（skill 灰度 + 生产指标回流）开工。

## 整体方案归属

本包是 [`docs/plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md`](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) 6 个版本里的 V1。后续版本：

- V2 SKILL-CANARY-ROLLOUT（依赖 V1 outcome 标签作为指标 baseline）
- V3 ATTRIBUTION-AGENT（依赖 V1 pattern 作为输入）
- V4 MULTI-SURFACE-FLYWHEEL（依赖 V2/V3 已验证模式）
- V5 EVAL-DYNAMIC-USER-SIM（已在 backlog）
- V6 TOOL-REGISTRY-VERSIONING（押后，可能不做）

## 范围裁剪

V1 单包 Mid 推进：

- 不接 attribution agent（V3）
- 不接 candidate 生成 / A/B（已有，但 V1 不调）
- 不接灰度 / 生产指标回流（V2）
- 不上 ML 聚类 / embedding（简单 bucket 聚类）
- 不动 SessionEntity / ChatService / SessionService / CompactionService / AgentLoopEngine 任何核心路径
- 不做人工标注修正 UI（推迟到 V3 跟 optimization event 一起）

## 阅读顺序

1. [MRD](mrd.md) — 业务痛点 + V1 目标
2. [PRD](prd.md) — 功能范围、验收、非目标
3. [技术方案](tech-design.md) — **复用 vs 新建**清单 / 现有证据 / 实施 phase

## 当前状态

**已 ratify（2026-05-14）+ Phase 1.0 完成（押 3 个 push back）**。开发期默认值：
- ScheduledTask `session-annotator-hourly`：**hourly**（P12 框架，V69 dogfood 同款模式，非 Spring @Scheduled）
- agent 内部 orchestrate 3 个 tool（`DetectSignalAnnotations` / `AnnotateSession` / `RecomputeClusters`）
- session-annotator 单次 invocation 最多标注：**10 条 session**

下一步：**Phase 1.0 证伪**（spot-check 已有 TraceScenarioImportService 输出 + 跑一遍 memory-curator agent 看 dispatch 模式接通），然后进入 Phase 1.1 实现。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 整体方案 | [../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) |
| 交付 | — |
