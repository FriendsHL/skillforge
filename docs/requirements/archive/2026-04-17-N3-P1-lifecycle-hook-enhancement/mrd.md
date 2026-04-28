# N3 P1 MRD

---
id: N3-P1
status: done
source: historical-backfill
created: 2026-04-17
updated: 2026-04-28
---

## 用户诉求

历史补录：Lifecycle hook 需要从基础单 entry 能力升级为可编排、多 entry、支持脚本和 prompt enrichment 的实用能力。

## 背景

N3 P0 建立了 hook 基础设施，但还需要多 entry 链式执行、脚本 handler、安全约束和更完整的前端编辑体验。

## 期望结果

用户能配置多 entry hook，使用 script handler，控制 SKIP_CHAIN/ABORT 语义，并让 hook 输出注入 prompt。

## 约束

- script 执行必须沙箱化。
- async 与 SKIP_CHAIN 等语义冲突必须拒绝保存。
- prompt enrichment 不能破坏 provider 消息结构。

## 未决问题

- 无。需求已交付。
