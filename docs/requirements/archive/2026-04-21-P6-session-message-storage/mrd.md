# P6 MRD

---
id: P6
status: done
source: historical-backfill
created: 2026-04-21
updated: 2026-04-28
---

## 用户诉求

历史补录：需要让 session 历史具备可追溯、可压缩、可恢复的结构化存储能力，避免整块上下文被 compaction 或重写污染。

## 背景

早期 session 存储难以表达 compact boundary、summary 和完整历史之间的关系，也不利于后续 checkpoint、branch/restore、trace 排查和长 session 治理。

## 期望结果

每条 session message 独立持久化，compaction 追加 boundary 和 summary 行，不删除旧消息；LLM context 从消息行中构建。

## 约束

- 保持消息历史 append-only。
- 兼容现有 session 数据。
- 支持灰度和回滚。

## 未决问题

- 无。需求已交付。
