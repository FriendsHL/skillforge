# Memory v2 MRD

---
id: MEMORY-v2
status: done
source: historical-backfill
created: 2026-04-27
updated: 2026-04-28
---

## 用户诉求

历史补录：记忆系统需要从简单存储升级为可控的写入、召回、去重和淘汰闭环，减少低质量记忆污染上下文。

## 背景

Memory v1 已有提取和检索能力，但缺少 ACTIVE/STALE/ARCHIVED 状态、容量淘汰、增量抽取、召回排重和 UI 批量管理。

## 期望结果

记忆能够按任务上下文召回、自动去重、按容量淘汰，并通过 Dashboard 管理状态。

## 约束

- 保留既有 memory snapshot / rollback 基线。
- 验证集中在 `MemoryConsolidatorTest` 和 `MemoryServiceTest`。

## 未决问题

- 无。需求主链路已交付。
