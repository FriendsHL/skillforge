# 技术设计草案 — Agent Team 共享任务图

## 1. 推荐架构

```text
Coordinator / Worker Agent
        |
TaskCreate/Get/Update/List
        |
TaskGraphService
  - ownership/CAS
  - dependency DAG
  - lease/recovery
        |
Session Task + Dependency + Team lease/attempt/event persistence
        |
Team event broadcaster / recovery worker
```

## 2. 持久化原则

升 Active 时先审计现有 Team/Recovery 表和 Session Event 能否复用。只有无法表达以下查询和 CAS 时才新增表：

- Root Task/Team 下按状态和 owner 查询。
- `expectedRevision` 条件更新。
- 入边/出边依赖查询与环检测。
- lease/heartbeat/attempt 恢复。
- 不可变状态事件审计。

优先扩展现有 `t_session_task` / `t_session_task_dependency`；租约、attempt 和不可变事件可使用独立附属表。

## 3. 并发不变量

- TaskUpdate 必须携带 expectedRevision，冲突返回结构化 `TASK_REVISION_CONFLICT`。
- claim 必须是单事务 CAS，不允许读后写窗口。
- dependency 增删与环检测处于同一事务边界或使用可证明一致的串行化策略。
- 单个 Team attempt 的 completed 是终态；Lead 明确重开 Task 时创建新 attempt/event，不覆盖旧 attempt 事实。
- Agent kill 恢复复用 TASK-RESUME-ON-RESTART 的 root/subagent identity，不凭消息文本推断 owner。

## 4. Tool Contract

尽量对齐 Claude Code 2.1.220 的熟悉字段：

```text
TaskCreate(subject, description, activeForm?)
TaskGet(taskId)
TaskUpdate(taskId, expectedRevision, subject?, description?, status?, owner?, addBlocks?, addBlockedBy?)
TaskList(status?, owner?, availableOnly?)
```

SkillForge 扩展字段放在明确可选位置，不把 `id` 和 `taskId` 同时宣传为 canonical 名称。

## 5. 与 Solo Task 的分层

- Solo 与 Team 使用同一四 Tool Schema、Task ID、Reminder reason 和持久化对象。
- Main Agent 无 Team context 时不加载 claim/lease/heartbeat/availableOnly 等调度手册。
- Team context 才渐进加载 Team Task Guidelines；必要的扩展字段保持可选。
- Compact/重启后均从同一 Task 权威存储恢复，Team 额外恢复 lease/attempt 状态。
