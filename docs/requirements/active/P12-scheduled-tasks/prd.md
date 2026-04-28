# P12 PRD

---
id: P12
status: prd-ready
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-28
updated: 2026-04-29
---

## 摘要

实现一个收窄版 scheduled task MVP：用户定义 schedule，定时触发 Agent session。

## 目标

- 创建和管理 scheduled tasks。
- 持久化 task metadata 和 execution history。
- 应用启动和 CRUD 更新后动态注册 schedule。
- 在 dashboard 提供任务管理页面。

## 非目标

- MVP 不做 SystemJobRegistry。
- MVP 不做 queue / parallel concurrency policy。
- MVP 不做告警推送或 admin 权限拆分。

## 功能需求

- `t_scheduled_task` CRUD。
- 使用 `ThreadPoolTaskScheduler` + `CronTrigger` 动态注册。
- 通过 `ChatService.chatAsync` 触发执行。
- `t_scheduled_task_run` 记录执行历史。
- Dashboard `/schedules` 页面支持列表、编辑器、手动触发、启停、执行历史。

## 从旧 ToDo 合并的首版范围

| 子任务 | 范围 |
| --- | --- |
| P12-1 Schedule 实体 + CRUD | `t_scheduled_task`，字段包括 id、name、cronExpr、oneShotAt、timezone、agentId、promptTemplate、channelTarget、enabled、concurrencyPolicy、nextFireAt、lastFireAt、status；Flyway migration；`ScheduledTaskService` CRUD；REST API；只存 user 型任务。 |
| P12-2 动态调度器内核 | `UserTaskScheduler` 基于 `ThreadPoolTaskScheduler` + `CronTrigger`；应用启动从 DB 全量 register；CRUD 后同步 schedule / unschedule；触发时调用 `ChatService.chatAsync` 起 session；首版只支持 `concurrencyPolicy=skip-if-running`；shutdown 优雅等待。 |
| P12-4 执行历史 + 可观测 | `t_scheduled_task_run`，字段包括 taskId、triggeredAt、finishedAt、status、errorMessage、triggeredSessionId；每次调度写一行。 |
| P12-5 前端 `/schedules` 页面 | 只做 user 型 tab；任务列表、cron 表达式编辑器、下 5 次触发时间预览、新建/编辑 drawer、Agent 选择、prompt 模板、channel target、启停 toggle、手动 trigger、执行历史时间线。 |
| P12-3 SystemJobRegistry | V2。首版保留现有 `@Scheduled` 注解不动，UI 只做 user 型 tab。 |
| P12-6 高级可靠性 + 权限 | V2。首版只做超时 kill + 失败日志；告警推送和 admin 权限分离后续实现。 |

## 验收标准

- [ ] enabled task 在应用启动后会注册。
- [ ] CRUD 更新会同步 schedule / unschedule。
- [ ] `skip-if-running` 能阻止重叠执行。
- [ ] 每次运行记录 success、failure、skipped、timeout 或 triggered session。
- [ ] Dashboard 支持创建、编辑、启停、手动触发。
- [ ] 应用重启后能从 DB 恢复已启用任务。

## 验证预期

- 后端 scheduler tests。
- Repository / service tests。
- Dashboard build 和浏览器检查。
- 数据库 migration 校验。
