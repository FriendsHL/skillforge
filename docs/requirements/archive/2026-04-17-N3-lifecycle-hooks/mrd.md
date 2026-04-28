# N3 MRD

---
id: N3
status: done
source: historical-backfill
created: 2026-04-17
updated: 2026-04-28
---

## 用户诉求

历史补录：Agent 需要在 session start/end、用户提交、工具调用等生命周期事件上挂载可配置行为。

## 背景

SkillForge 需要从固定流程走向可编排流程，让用户能用 skill/script/method handler 扩展 Agent 行为。

## 期望结果

支持 lifecycle hook 配置、dispatcher、超时、失败策略、trace 记录和前端编辑器。

## 约束

- Hook 不能破坏主 loop 不变量。
- ABORT / CONTINUE 等失败策略需要语义清晰。
- 配置需要可序列化、可编辑、可校验。

## 未决问题

- 无。基础体系已交付。
