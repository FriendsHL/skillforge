# PRD — Agent Team 共享任务图

## 1. 功能要求

### FR-1 Task API

- TaskCreate：创建一项 `pending` 任务，服务端生成 `taskId`。
- TaskGet：沿用基础 Session Task 响应；Team context 额外返回 active attempt/lease 摘要。
- TaskList：按 Team/Root Task 返回任务；`availableOnly=true` 只返回 `pending + unblocked + no active lease`。
- TaskUpdate：沿用 `taskId` 增量更新。Solo 的 `expectedRevision` 可选，Team 必填。
- Team 成员调用四个 Task Tool 时，服务端自动解析到 collab run 的 leader Session，不允许模型提交或伪造图 ID。

### FR-2 状态与依赖

模型可见状态保持简洁：

```text
pending | in_progress | completed | deleted
```

`blocked` 由 `blockedBy` 中仍存在未完成任务推导，不与 status 重复保存。依赖必须无环；完成前置任务后，后继
任务自动进入可领取集合。Worker 的失败、超时和取消记录在 attempt/lease，不把基础 Task 协议扩成第二套状态。

### FR-3 Owner 与领取

- owner 是 Agent runtime identity，不是普通用户名。
- Team owner 由服务端写入真实 Worker Session ID，不接受模型传入任意 owner。
- Team Worker 将 `pending` 更新为 `in_progress` 表示 claim；同一事务创建 active attempt/lease。
- 当前 owner 将 `in_progress` 更新为 `pending` 表示 release；更新为 `completed` 表示完成。
- 所有 Team 图写入先锁定 leader Session，再校验 `expectedRevision`；图、attempt、lease 和 event 同事务提交。
- lease 默认 2 分钟，Runtime 每 30 秒根据 Worker Session 运行态续租；模型无需调用 heartbeat 工具。
- Worker loop 结束、异常退出或 lease 到期后，Runtime 释放未完成领取并记录原因；Team Lead 可显式释放，但不能静默覆盖有效 lease。

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

事件采用追加写，可用于恢复和审计；字段保持紧凑，只记录 task/attempt、actor、前后状态、reason code 和时间。
`TASK_UNBLOCKED` 先持久化，再通过现有 Team mailbox 投递提示；内存通知不是权威事实。

### FR-5 Agent 工作流

1. Team Lead 创建任务和依赖。
2. Worker 调用 TaskList 查找 `pending + unblocked + no active owner`。
3. Worker 使用 `TaskUpdate(taskId, expectedRevision, status=in_progress)` 领取。
4. Worker 调用 TaskGet 获取完整要求。
5. 完成后 TaskUpdate 为 completed；Runtime 解锁后继任务并通知 Team。

TaskCreate 不启动 Agent。TeamCreate 仍是显式选择/启动 Worker 的入口；Worker 启动后自行领取任务，避免把外部 Agent 启动和数据库 claim 塞进同一事务。

### FR-6 能力披露与兼容

- Solo Session 继续使用现有四个 Task Tool 和现有 REST/iOS/Dashboard DTO。
- Collab run 中的 leader 与 Worker 自动获得四个 Task Tool，不依赖 Agent 静态工具配置。
- Team context 才注入 claim、revision、release 与可领取任务规则。
- Compact 后 Tool Schema 从 Registry 恢复；共享图、attempt 和事件从数据库恢复。
- Phase 1 不向用户端开放租约写 API；attempt/event 先通过 Tool 结果和后端诊断可见。

## 2. 验收

- 两个 Agent 并发领取同一任务时恰好一个成功。
- 循环依赖创建失败且返回明确路径。
- 前置任务完成后，后继任务无需模型轮询即可收到可执行事件。
- Worker 被 kill 后任务按策略恢复或释放，不永久卡在 in_progress。
- Compact 后 Task Tool Schema 从 Registry 恢复，任务图从权威存储读取。
- Solo Session 不暴露 Team Task Guidelines，但继续使用相同四个 Task Tool。
- TaskCreate 不会隐式生成 Agent；TeamCreate 不会绕开 Task revision/lease 规则。
- REST、Dashboard 和 iOS 现有 Task 读取不因附属表上线而破坏。

## 3. 非目标

- Phase 1 不做自动 agent selector、优先级/抢占调度和跨 collab run 任务。
- Phase 1 不新增 TaskClaim、TaskHeartbeat、TaskRelease Tool。
- Phase 1 不要求 Dashboard/iOS 管理 attempt 或 lease。
- Phase 1 不以消息文本推断 owner、完成或恢复状态。
