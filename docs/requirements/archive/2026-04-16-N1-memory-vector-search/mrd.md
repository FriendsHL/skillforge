# N1 MRD

---
id: N1
status: done
source: historical-backfill
created: 2026-04-16
updated: 2026-04-29
---

## 用户诉求

历史补录：Memory 需要从简单关键词检索升级为语义检索，帮助 Agent 更准确召回用户记忆。

## 背景

纯文本检索难以覆盖语义相近但措辞不同的记忆。SkillForge 已迁移到 PostgreSQL，可以利用 pgvector + FTS 组合。

## 期望结果

写入 Memory 时异步生成 embedding，检索时结合全文和向量分数，提供 MemorySearch / MemoryDetail 工具。

## 约束

- pgvector 不可用时要 graceful fallback。
- embedding provider 失败不能拖垮主流程。
- 需要本地 / CI 可测。

## 未决问题

- 无。需求已交付。
