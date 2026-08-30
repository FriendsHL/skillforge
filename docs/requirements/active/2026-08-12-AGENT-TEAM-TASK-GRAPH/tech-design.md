# 技术设计草案 — Agent Team 共享任务图

## 1. 首期架构

```text
Coordinator / Worker Agent
        |
TaskCreate/Get/Update/List
        |
TaskGraph Tool Facade
  - actor -> leader graph scope
  - expectedRevision
  - ownership/lease
  - dependency DAG
        |
SessionTaskService + TeamTaskLeaseService + Event Recorder
        |
t_session_task + dependency + attempt + event
        |
Team mailbox / lifecycle listener / lease sweeper
```

Team Task 的权威图仍是 leader Session 下的 `t_session_task`。不复制第二份 Team Task 主表；附属表只保存一次领取的运行事实和不可变审计。

## 2. 图作用域与事务边界

1. Tool Facade 先以当前 `SkillContext.sessionId` 加载 actor Session。
2. actor 没有 `collabRunId` 时，图作用域就是当前 Session，走现有 Solo 语义。
3. actor 有 `collabRunId` 时，服务端加载 `CollabRun.leaderSessionId`，验证 actor、leader 和 user 归属一致。
4. 所有 Team 图写入以 leader Session 的 pessimistic write lock 为串行化边界。
5. 获取锁后校验 Task `@Version == expectedRevision`，再在同一事务更新 Task、dependency、attempt/lease 和 event。

这比每个字段各做一次 native CAS 更容易证明依赖环检测与领取的一致性，也复用当前 `SessionTaskService` 已有的 Session 行锁模式。

## 3. V192 持久化

### 3.1 `t_session_task_attempt`

每次成功 claim 创建一行，历史 attempt 不覆盖：

- `id`、`task_id`、`graph_session_id`、`collab_run_id`
- `attempt_no`
- `worker_session_id`、`worker_agent_id`
- `lease_token`
- `status`: `ACTIVE | COMPLETED | FAILED | RELEASED | EXPIRED`
- `leased_at`、`heartbeat_at`、`expires_at`、`finished_at`
- `reason_code`

约束：

- `(task_id, attempt_no)` 唯一。
- `status = ACTIVE` 时同一 Task 最多一行。
- 为 active `expires_at` 和 `(worker_session_id, status)` 建索引。

### 3.2 `t_session_task_event`

追加写生命周期事实：

- `id`、`task_id`、`graph_session_id`、`collab_run_id`、可选 `attempt_id`
- `event_type`
- 可选 `actor_session_id`、`actor_agent_id`
- 可选 `from_status`、`to_status`、`reason_code`
- `created_at`

Task/Session 被硬删除时允许级联清理；普通状态变化只新增事件，不改历史事件。

## 4. claim、续租与回收

### 4.1 claim

`TaskUpdate(status=in_progress)` 在 Team context 中执行：

1. 锁 leader Session，校验 revision、任务为 pending、未 blocked、无 active attempt。
2. 将 owner 写成 actor Session ID、状态写成 in_progress。
3. 创建 ACTIVE attempt，初始 lease 为 2 分钟。
4. 追加 `TASK_CLAIMED`，单事务提交。

两个 Worker 并发读取相同 revision 时，leader Session 锁保证第二个 Worker 在拿锁后看到 revision 冲突或 active attempt。

### 4.2 续租

- 每 30 秒扫描 ACTIVE attempt。
- Worker Session 仍在运行时延长 `heartbeat_at/expires_at`。
- TaskGet/TaskList 等读请求不续租，避免“看一眼”掩盖失联 Worker。
- 不向模型暴露 heartbeat Tool。

### 4.3 完成与 release

- 当前 owner `TaskUpdate(status=completed)`：关闭 attempt 为 COMPLETED，写 `TASK_COMPLETED`。
- 当前 owner或 Team Lead `TaskUpdate(status=pending)`：关闭 attempt 为 RELEASED，清 owner，写 `TASK_RELEASED`。
- 非 owner Worker 不能完成或释放有效领取。

### 4.4 异常恢复

- `SessionLoopFinishedEvent` 到达时，若 Worker 仍持有未完成 Task，立即 release 并记录 reason。
- 服务重启后 sweeper 从 attempt/Session 权威状态继续判断，不依赖 JVM 内存。
- lease 到期且 Worker 不再运行时标为 EXPIRED，Task 回到 pending。
- `waiting_user` 不立即回收；保留到 lease 到期，避免正常等待用户输入被当成崩溃。

## 5. 依赖解锁与唤醒

完成前置 Task 后，在同一图事务中比较后继 Task 的 blocked 状态：

1. 由 blocked 变为 unblocked 时追加 `TASK_UNBLOCKED`。
2. 事务提交后复用 `SubAgentRegistry.enqueueForSession` 投递 `[TeamTaskEvent]`。
3. 只向同 collab run 中仍可恢复/运行的成员发送；mailbox 负责持久化与 `maybeResumeSession`。
4. Worker 再调用 `TaskList(availableOnly=true)` 获取权威可领取集合，事件本身不承载整张图。

## 6. Tool Contract

尽量对齐 Claude Code 2.1.220 的熟悉字段：

```text
TaskCreate(subject, description, activeForm?)
TaskGet(taskId)
TaskUpdate(taskId, expectedRevision?, subject?, description?, status?, owner?, addBlocks?, addBlockedBy?)
TaskList(status?, owner?, availableOnly?)
```

SkillForge 扩展字段放在明确可选位置，不把 `id` 和 `taskId` 同时宣传为 canonical 名称。Team context 中 `expectedRevision` 必填，`owner` 由服务端控制；Solo 继续兼容已有调用。

## 7. 能力与 Prompt 分层

- Solo 与 Team 使用同一四 Tool Schema、Task ID、Reminder reason 和持久化对象。
- Collab run 中的 leader/Worker 自动获得 TaskCreate/Get/Update/List；普通 Session 仍按 Agent 配置授权。
- Main Agent 无 Team context 时不加载 claim/lease/availableOnly 等调度手册。
- Team context 只加载精简协议：先 List、按 revision claim、owner 完成、失败 release；不注入内部表结构。
- Compact/重启后均从同一 Task 权威存储恢复，Team 额外恢复 lease/attempt 状态。

## 8. 验证与回滚

### 自动化

- migration：约束、索引、级联与真实 PostgreSQL Flyway。
- service：scope 映射、revision 冲突、并发唯一 claim、owner 权限、lease 续租/过期、依赖解锁事件。
- tool：Solo 兼容、Team 自动授权、`availableOnly`、结构化错误。
- integration：Worker loop 退出回收，重启后 sweeper 恢复，mailbox 唤醒。
- regression：Core + Server 全量测试，已有 REST/Dashboard/iOS DTO 不变。

### 运行态

1. 创建 Team 与两个 Worker。
2. leader 创建含依赖 Task。
3. 两个 Worker 同 revision 并发 claim，恰好一个成功。
4. kill owner，确认 Task 自动回到 pending 且历史 attempt/event 可查。
5. 完成前置 Task，确认后继 Task 事件和 mailbox 唤醒。

### 回滚

- 应用层可停止写 attempt/event 并恢复 Solo Task 授权逻辑；基础 Task 表不变。
- V192 附属表保持向后兼容，不在紧急回滚中删除，以保留审计事实。
