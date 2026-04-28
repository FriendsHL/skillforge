# SubAgent PRD

---
id: SUBAGENT
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-08
updated: 2026-04-29
---

## 摘要

实现 SubAgent 异步派发机制和端到端可观测。

## 目标

- 父 Agent 可派发子 Agent。
- SubAgent run 持久化。
- 支持 pending result 信箱。
- UI 展示 running / finished / child sessions。

## 非目标

- 不在本需求中实现完整多 Agent workflow 编排语言。

## 功能需求

- `t_subagent_run`。
- `t_subagent_pending_result`。
- `/subagent-runs` endpoint。
- startup recovery。
- sweeper。
- SubAgentRunsPanel / ChildSessionsPanel。

## 验收标准

- [x] 派发后 run 可见。
- [x] 子 session 状态可追踪。
- [x] 重启和僵尸 run 有处理机制。

## 验证预期

- API smoke。
- 前端 panel 浏览器检查。
