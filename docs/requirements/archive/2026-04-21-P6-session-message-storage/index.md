# P6 消息行存储

---
id: P6
mode: full
status: done
priority: P0
risk: Full
created: 2026-04-21
updated: 2026-04-28
delivered: 2026-04-21
---

## 摘要

将 session 历史从整块上下文改为消息行存储，建立结构化 compaction boundary 和 append-only 消息语义。

## 阅读顺序

1. [MRD](mrd.md) - 历史补录的用户诉求。
2. [PRD](prd.md) - 历史补录的产品需求和验收。
3. [技术方案](tech-design.md) - 原 `design-p6-session-message-storage.md` 迁入。

## 当前状态

已交付。交付事实见 [delivery-index.md](../../../delivery-index.md)。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| Rollout 手册 | [p6-rollout-playbook.md](../../../operations/p6-rollout-playbook.md) |
| 交付 | [delivery-index.md](../../../delivery-index.md) |
