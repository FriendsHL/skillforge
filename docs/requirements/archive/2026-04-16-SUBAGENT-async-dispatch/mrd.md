# SubAgent MRD

---
id: SUBAGENT
status: done
source: historical-backfill
created: 2026-04-08
updated: 2026-04-29
---

## 用户诉求

历史补录：父 Agent 需要能异步派发子 Agent，并在 UI 中看到子任务运行状态和结果。

## 背景

多 Agent 协作不能只依赖同步调用，否则长任务会阻塞主流程，也不利于恢复和可观测。

## 期望结果

SubAgent run 有持久化记录，父 session 能收到结果，前端能展示 SubAgentRunsPanel 和 child sessions。

## 约束

- 重启不能完全丢失 running 状态。
- 需要 sweeper 清理僵尸 run。
- 父子 session 关系要可追踪。

## 未决问题

- 无。需求已交付。
