# PRD — Agent Team 共享任务图

## 1. 功能要求

### FR-1 Task API

- TaskCreate：创建一项 `pending` 任务，服务端生成 `taskId`。
- TaskGet：沿用基础 Session Task 响应，并在 Team context 增加 lease/attempt/审计摘要。
- TaskList：按 Team/Root Task 返回可领取、进行中、阻塞和完成任务。
- TaskUpdate：沿用 `taskId` 增量更新；Team claim/release 使用版本或专用 CAS 字段防止并发覆盖。

### FR-2 状态与依赖

模型可见状态保持简洁：

```text
pending | in_progress | completed | deleted
```

`blocked` 由 `blockedBy` 中仍存在未完成任务推导，不与 status 重复保存。依赖必须无环；完成前置任务后，后继
任务自动进入可领取集合。Worker 的失败、超时和取消记录在 attempt/lease，不把基础 Task 协议扩成第二套状态。

### FR-3 Owner 与领取

- owner 是 Agent runtime identity，不是普通用户名。
- 领取采用 CAS/lease，避免两个 Agent 同时获得同一任务。
- Agent heartbeat 丢失或终止后，Runtime 根据策略释放、恢复或标记 failed。
- Team Lead 可以重新分配，但不能静默覆盖有效 lease。

### FR-4 事件

至少产生：

```text
TASK_CREATED
TASK_CLAIMED
TASK_UPDATED
TASK_BLOCKED
TASK_UNBLOCKED
TASK_COMPLETED
TASK_FAILED
TASK_RELEASED
```

事件可用于 UI、恢复和审计；不得只依赖内存通知。

### FR-5 Agent 工作流

1. Team Lead 创建任务和依赖。
2. Worker 调用 TaskList 查找 `pending + unblocked + no active owner`。
3. Worker 使用 TaskUpdate/CAS 领取。
4. Worker 调用 TaskGet 获取完整要求。
5. 完成后 TaskUpdate 为 completed；Runtime 解锁后继任务并通知 Team。

## 2. 验收

- 两个 Agent 并发领取同一任务时恰好一个成功。
- 循环依赖创建失败且返回明确路径。
- 前置任务完成后，后继任务无需模型轮询即可收到可执行事件。
- Worker 被 kill 后任务按策略恢复或释放，不永久卡在 in_progress。
- Compact 后 Task Tool Schema 从 Registry 恢复，任务图从权威存储读取。
- Solo Session 不暴露 Team Task Guidelines，但继续使用相同四个 Task Tool。
