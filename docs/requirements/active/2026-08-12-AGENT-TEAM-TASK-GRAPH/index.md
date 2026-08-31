# AGENT-TEAM-TASK-GRAPH — Agent Team 共享任务图

> 状态：Active / Full（Phase 1 implemented；automated + real Team dogfood verified）
> 创建：2026-08-05
> 激活：2026-08-12
> 触发：Session Task 与完整 Team 自动协调职责边界澄清

## 摘要

为 SkillForge 的 Team/Coordinator 在现有持久化 Session Task 之上增加跨 Agent 领取、租约、心跳、自动派发、
失败回收和不可变事件审计。它不新增第二套 Tool Schema 或任务表，而是把已有 owner/依赖字段升级为真正的
多 Agent 调度语义。

## 核心边界

```text
Main Agent / Solo Session
  TaskCreate / TaskGet / TaskUpdate / TaskList
  -> Session 内持久化计划，增量更新

Agent Team / Coordinator
  同一 Task 协议 + Team context
  -> claim / lease / heartbeat / dispatch / recovery / audit events
```

TaskCreate 只创建工作项，不负责启动 Agent。Agent/Team 调度器负责派发，TaskUpdate 负责 owner、状态和依赖。

## 激活依据

以下真实条件已经满足，因此不再作为远期 Task Graph 设想留在 backlog：

1. `TaskCreate/TaskGet/TaskUpdate/TaskList` 已经成为 Solo Session 的权威任务协议。
2. `TeamCreate/TeamSend/TeamList`、持久化 SubAgent run 和 mailbox 已存在，但成员仍看不到同一任务图。
3. Worker 中断后，`in_progress` Task 缺少 lease/attempt 事实，无法可靠释放或恢复。
4. 用户明确要求先补齐 Agent Team Task Graph，再进入 Harness Benchmark。

## Phase 1 交付切片

1. Team 成员自动映射到 leader Session 的共享任务图；Solo 行为保持兼容。
2. Team `TaskUpdate` 强制 `expectedRevision`，通过 leader Session 行锁串行化图写入。
3. 新增不可变 attempt 与 event 附属表，不复制 `t_session_task` 主数据。
4. 用已有四个 Task Tool 表达 claim、complete 与 release，不增加第五个模型工具。
5. Runtime 自动续租；Worker loop 退出、服务重启或 lease 到期时回收未完成领取。
6. 依赖完成后写入 `TASK_UNBLOCKED`，并复用 Team mailbox 唤醒仍存活的成员。
7. Team 上下文自动授权 Task Tools，并只注入精简的 Team Task 使用规则。

## 验证证据

### 2026-08-12 自动化与迁移

- 聚焦回归：Task Graph、Tool、Controller、Reminder、mailbox、迁移共 45 项，0 失败。
- 并发集成：真实 PostgreSQL 下两个 Worker 使用相同 revision 领取同一 Task，恰好一个成功且只产生一条 ACTIVE attempt。
- 全量回归：`mvn -pl skillforge-server -am test`，3583 项执行，0 failures / 0 errors，179 项条件跳过。
- 运行态：开发后端完成 V191 → V192 Flyway，`t_session_task_attempt`、`t_session_task_event` 与关键索引已落库，8080 正常监听。

### 2026-08-31 真实 Team dogfood

- Dashboard 会话 `a8bb2f96-7dc1-43ae-941a-96541746ecc1` 中，两个并行 Worker 对同一 revision 领取同一 Task，恰好一个成功；两个 Worker 绑定同一 collab run。
- 未完成 Worker 退出后，Task 自动回到 `pending`，attempt 记录为 `RELEASED / WORKER_EXITED_WITHOUT_COMPLETION`，不是错误的 `STATE_MISMATCH`。
- 前置 Task 完成后，后继 Task 从 `blocked=true` 变为 `blocked=false`，持久化 `TASK_UNBLOCKED`；等待 Worker 收到 `[TeamTaskEvent]`，执行 `TaskList(availableOnly=true)` 并通过 `TeamSend` 回报主 Agent。
- 显式 `TeamKill` 验证：Worker `f0b2ab48-655f-4aa0-a1e1-22fa9ebb727c` 领取 T4 后被取消，Task 自动恢复为 `pending` 且 owner 清空；attempt/event 分别记录 `RELEASED`、`TASK_RELEASED` 与 `WORKER_CANCELLED`。
- Dogfood 同时暴露并修复三项运行态问题：并行 `TeamCreate` 重复建 run、JPA managed entity 导致 owner 恢复误判、事务提交后 mailbox 写入无新事务且消息 ID 超出 `VARCHAR(36)`。
- 新增真实 PostgreSQL 并发/事务集成测试，并将解锁消息 ID 改为确定性的 36 位 UUID；聚焦回归 20/20 通过。
- 最终 Core + Tools + Observability + Server 全量回归：`mvn -pl skillforge-server -am test`，3598 项执行，0 failures / 0 errors，179 项条件跳过。

## 明确不在 Phase 1

- TaskCreate 自动选择或自动启动 Agent。
- 优先级队列、抢占、跨 Team/跨 Root Task 调度。
- Dashboard/iOS 的租约管理入口。
- 新增 TaskClaim/TaskHeartbeat/TaskRelease Tool。

## 文档

1. [MRD](mrd.md)
2. [PRD](prd.md)
3. [技术设计草案](tech-design.md)
