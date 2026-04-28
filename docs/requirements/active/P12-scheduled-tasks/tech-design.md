# P12 技术方案

---
id: P12
status: design-draft
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-04-29
---

## TL;DR

使用 Spring `ThreadPoolTaskScheduler` 实现单机动态 user schedules，并持久化到 PostgreSQL。system jobs 和高级可靠性推迟到 V2。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| 使用 `ThreadPoolTaskScheduler` | 当前单机部署下简单、足够。 | Quartz 等集群调度方案推迟。 |
| 只做 user tasks | 降低范围，不混入现有 system jobs。 | SystemJobRegistry 进入 V2。 |
| 只支持 `skip-if-running` | 首版最可预测。 | queue / parallel 进入 V2。 |

## 架构

- `ScheduledTaskService` 负责 CRUD 和持久化。
- `UserTaskScheduler` 负责注册、注销和触发任务。
- `ScheduledTaskRun` 记录执行结果。
- Dashboard `/schedules` 管理任务和执行历史。

## 后端改动

- 新增 scheduled task / run entity 和 repository。
- 新增 scheduler service 和 startup registration。
- 新增 REST API。
- 集成 `ChatService.chatAsync`。

## 前端改动

- 新增 schedules 页面。
- 新增任务列表、编辑 drawer、cron preview、enable toggle、manual trigger、run history timeline。

## 数据模型 / Migration

- `t_scheduled_task`
- `t_scheduled_task_run`

`t_scheduled_task` 字段：

- `id`
- `name`
- `cron_expr`
- `one_shot_at`
- `timezone`
- `agent_id`
- `prompt_template`
- `channel_target`
- `enabled`
- `concurrency_policy`
- `next_fire_at`
- `last_fire_at`
- `status`

`t_scheduled_task_run` 字段：

- `task_id`
- `triggered_at`
- `finished_at`
- `status`
- `error_message`
- `triggered_session_id`

## 调度语义

- 应用启动时从 DB 全量注册 enabled task。
- CRUD 修改后同步 schedule / unschedule。
- cron 使用任务自己的 timezone。
- one-shot 执行成功后不再重复触发。
- shutdown 时优雅等待正在执行的 user task。
- 首版只做 user 型任务；system job 仍保留现有 `@Scheduled`。

## 错误处理 / 安全

- 校验 cron / timezone。
- 执行失败记录脱敏后的错误信息。
- 实现前必须明确 identity / ownership 模型。

## 实施计划

- [ ] 完成前置决策。
- [ ] Full Pipeline planning。
- [ ] 实现后端 schema / service / scheduler。
- [ ] 实现 dashboard。
- [ ] 验证 startup registration 和 manual trigger。

## 测试计划

- [ ] cron next-fire 计算。
- [ ] skip-if-running 行为。
- [ ] CRUD registration update 行为。
- [ ] run history 持久化。
- [ ] dashboard 浏览器 workflow 检查。

## 风险

- 时区 / DST bug。
- 长时间运行的 Agent session 与新触发重叠。
- 身份和 cost accounting 边界不清。

## 评审记录

前置决策完成后才能批准技术方案。
