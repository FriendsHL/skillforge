# P12 MRD

---
id: P12
status: mrd
source: user
created: 2026-04-28
updated: 2026-04-28
---

## 用户诉求

用户希望 SkillForge Agent 能按计划自动运行 prompt，而不是每次手动触发。

## 背景

定时任务会放大 token 消耗、持久化数据量和身份 / 权限复杂度。因此实现前必须先完成几个前置工程决策。

## 期望结果

用户可以创建、启停、手动触发并查看 user scheduled task 的执行历史。

## 约束

- 首版只支持 user 型任务。
- 现有 `@Scheduled` system jobs 不动。
- 首版只支持 `skip-if-running` 并发策略。
- 实现前需要明确 Cost 可见性、PG 备份和多用户边界。

## 未决问题

- [ ] 技术方案批准前，必须解决 Sprint 4 前置决策。
