# P12-PRE Sprint 4 前置决策

---
id: P12-PRE
mode: lite
status: ready
priority: P0
risk: Solo
created: 2026-04-28
updated: 2026-04-29
---

## 请求

在 P12 定时任务开工前，先明确三个会放大成生产 footgun 的工程决策：Cost 可见性、embedded PG 备份、多用户 / 权限边界。

旧 ToDo 原始判断：P12 会放大 token 消耗和数据量，如果没有这三项决策，上线后会快速踩到账单、数据恢复和身份边界问题。这一步不要求立刻实现完整功能，但必须先确定最小边界。

## 验收

- [ ] 写清 Cost Dashboard 最小范围。
- [ ] 写清 embedded PG 备份策略。
- [ ] 写清多用户 / 权限模型边界。
- [ ] P12 PRD 和技术方案链接到这些决策。

## 实现说明

这一步可以保持 Lite，因为当前工作只是文档化决策；如果后续要实现 Cost Dashboard、备份脚本或权限模型，再分别拆成独立需求。

## 三个前置决策

| 决策 | 原因 | 最小版 |
| --- | --- | --- |
| Cost Dashboard | P12 / P15 / 未来 P14 都会放大 token 消耗；没有 cost 可见性会出现“跑几天账单爆炸”。 | session / agent 维度 token 用量 + LLM 估算费用，复用现有 TraceSpan 数据，单页 UI。 |
| Embedded PG 备份策略 | zonky embedded PG 无内置备份；data dir 损坏会导致 sessions / skills / memories / agent 配置 / scheduled tasks 全部丢失。 | `pg_dump` cron 脚本 + 本地压缩保留 7 天。 |
| 多用户 / 权限模型 design doc | 当前 auto-token 是单用户假设；P11 跨 Agent 调用身份、P12 定时任务触发 session 的身份都会踩到边界。 | 写 design doc，明确 agent / session / memory / scheduled_task 如何隔离；不要求立刻实现。**此外含 SkillForge 全局 auth model upgrade scope**：当前所有 controller (SkillController / AgentController / SessionController / 等) 都接受 `userId` query param 当 ownerId 来源，没有 enforce token user == query userId。多用户决策定型后，统一改成从 token 里 extract userId（AuthInterceptor 注入 request attribute）+ controller 不再接受 userId query param。SKILL-IMPORT-BATCH (2026-05-01) follow-up 触发记录此项。 |

## 验证

- [ ] 确认 P12 需求包中不存在未解决的前置 blocker。
