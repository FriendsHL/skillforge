# TASK-RESUME-ON-RESTART — 技术设计

## 方案比较

### A. 启动时扫描 `runtime_status=running` 并重发 Continue

改动最小，但无法区分旧 owner 与活跃 owner，不能稳定关联原 turn，不能控制重复副作用，也会继续与现有 startup handlers 冲突。仅适合原型，不采用。

### B. 在 `t_session` 增加 recovery 字段

根会话容易实现，但 SubAgent、Workflow、ACP 会复制状态机；Session 同时承担聊天、执行、lease 和重试账本，后续难以审计。只适合作为过渡兼容，不作为 source of truth。

### C. 统一 durable execution claim + 类型适配器（推荐）

建立独立执行恢复账本，根 turn、SubAgent、Workflow 和 ACP 各自实现策略适配器；Session runtime status 作为用户投影。统一处理 admission、lease、attempt、tombstone、审计和管理 API。

## 数据模型

### `t_execution_claim`

| 字段 | 含义 |
| --- | --- |
| `id` UUID | execution claim ID |
| `kind` | `ROOT_TURN / SUBAGENT / WORKFLOW / ACP / CRON_RUN` |
| `resource_id` | sessionId、runId 等 owner resource |
| `session_id` / `user_id` | 权限和 UI 关联 |
| `source_turn_seq` | 原始 user/source message 的稳定 seq；根恢复不得另造 Query |
| `dispatch_id` | 一个中断周期内稳定的幂等 dispatch ID |
| `state` | `ADMITTED / RUNNING / WAITING / RECOVERY_PENDING / RECOVERING / INTERRUPTED / COMPLETED / FAILED / CANCELLED / TOMBSTONED` |
| `owner_instance_id` / `owner_epoch` | 当前运行 owner；epoch 每次成功 claim +1 |
| `lease_expires_at` / `heartbeat_at` | 孤儿判定；均使用 `Instant` |
| `checkpoint_seq` / `checkpoint_kind` | 最近安全边界 |
| `replay_safety` | `SAFE / IDEMPOTENT / UNKNOWN_EFFECT / NEVER` |
| `recovery_attempts` / `max_attempts` | 持久化预算，默认 3 |
| `next_attempt_at` / `interrupted_at` | backoff 与 age gate |
| `root_trace_id` | 恢复后延续 trace root |
| `definition_hash` | Agent/Workflow 关键定义快照 hash |
| `payload_json` | versioned debug/reconstruction shadow；关键判断使用 typed columns |
| `version` | JPA optimistic lock/CAS |
| audit timestamps | created/updated/completed |

唯一约束：一个 resource 同时最多一个 non-terminal claim；`dispatch_id` 唯一。

### `t_tool_execution_receipt`

| 字段 | 含义 |
| --- | --- |
| `(claim_id, tool_use_id)` | 业务唯一键 |
| `tool_name` / `input_hash` | 身份与审计，不存明文 secret |
| `policy` | `READ_ONLY / IDEMPOTENT / RECONCILABLE / EXTERNAL_EFFECT / INTERACTIVE` |
| `idempotency_key` | 工具支持时向下游透传 |
| `state` | `PREPARED / EXECUTING / SUCCEEDED / FAILED / OUTCOME_UNKNOWN` |
| `result_ref` / `result_hash` | 已持久化结果引用与完整性校验 |
| timestamps | prepared/started/finished |

工具调用顺序：写 PREPARED → 提交为 EXECUTING → 执行 → 持久化 tool_result 与 SUCCEEDED。无法让数据库和任意外部 API 构成一个事务，因此 crash 落在 EXECUTING 时必须依赖 idempotency/reconcile；否则 OUTCOME_UNKNOWN 并 fail closed。

### `t_execution_recovery_event`

append-only 审计：`detected / claimed / resumed / reconciled / deferred / interrupted / tombstoned / manually_resolved`，记录 claim、owner、reason code、attempt、checkpoint，不记录敏感 prompt/tool output。

### `t_runtime_instance`

每次进程启动一个 `instance_id + boot_id`，周期更新 heartbeat；graceful shutdown 记录 stopped。claim 的 lease 才是执行 ownership 的 source of truth，instance row 用于诊断和 crash-loop 识别。

## 状态机

```text
ADMITTED -> RUNNING -> WAITING -> RUNNING
                    -> COMPLETED | FAILED | CANCELLED

RUNNING + owner lost -> RECOVERY_PENDING
RECOVERY_PENDING -> RECOVERING -> RUNNING | WAITING | COMPLETED
                               -> INTERRUPTED | TOMBSTONED
INTERRUPTED --manual decision--> RECOVERY_PENDING | COMPLETED | CANCELLED
```

所有迁移由 Service 公共事务方法完成，使用 `WHERE id=? AND version=? AND state IN (...)` 或 `@Version` CAS。客户端看到的 `SessionEntity.runtimeStatus` 是投影，不参与 ownership 判定。

## 原子 admission

根 turn 的关键事务必须包含：

1. 校验 Session 归属和没有冲突的 active claim。
2. 构造一次 `Message`，追加 user row，并取得 `source_turn_seq`。
3. 创建 ADMITTED claim、稳定 dispatch ID、root trace 和 initial checkpoint。
4. 更新 Session runtime projection 为 running。
5. commit 后才返回 started，并在 after-commit 提交 executor。

executor rejection 不删除 user message：若确认尚未进入 runtime，可将 claim 标 RECOVERY_PENDING 且不计 uncertain attempt；如果 dispatch 已接受但结果不确定，保留 attempt charge。

恢复路径加载原有持久化 message，不追加 `[Resume from restart]` user message。Recovery directive 作为 claim 中的 versioned internal payload，注入 engine 的防御性 context copy。这样既让模型知道发生过重启，又不破坏 ChatService/AgentLoopEngine 的 byte-identical persistence shape。

## checkpoint 边界

只在可证明的边界 checkpoint，不保存每个 token：

- user/source turn admitted
- 完整 Assistant message 已持久化
- tool intent PREPARED
- tool result 与 receipt 已持久化
- control card 已持久化并进入 WAITING
- terminal reply/message 已持久化
- parent delivery 已写 durable queue/receipt

流式 delta 仍可丢失；恢复从最近完整消息重新生成后续。append/rewrite 必须继续遵守 message byte shape、identity 列和 tool pairing 不变量。

## transcript tail 安全分类

| Tail | 决策 |
| --- | --- |
| 最后是已持久化 user turn，无 tool intent | SAFE_RESUME |
| 最后是完整 Assistant text，无 terminal delivery | RECONCILE_OR_COMPLETE |
| tool PREPARED，尚未 EXECUTING | SAFE_EXECUTE |
| tool EXECUTING，READ_ONLY/IDEMPOTENT | 使用同 idempotency key resume/reconcile |
| tool EXECUTING，EXTERNAL_EFFECT | OUTCOME_UNKNOWN，停止并让用户决策 |
| orphan tool_use 且无 receipt | 补 error tool_result 保配对，但不自动宣称原工具失败；转 Interrupted |
| pending control row | 恢复 WAITING registry/card |
| transcript/identity/definition 不兼容 | Interrupted，禁止盲目 replay |

“补 orphan tool_result”是 transcript 修复，不代表可以继续执行；当前 `PendingConfirmationStartupRecovery` 把两者合并为 error 的做法应拆开。

## lease 与多实例

- active owner 每 10 秒续租，默认 lease 45 秒；参数可配置。
- planned shutdown 先停止 admission，再将未完成 claim 标 RECOVERY_PENDING，不等待 lease 自然过期。
- crash 后 coordinator 只处理 `lease_expires_at < now - skewAllowance` 的 claim。
- claim 时递增 owner epoch，并写入本实例；执行线程每次 checkpoint/terminal write 都必须携带 epoch，旧 owner 的迟到写入被拒绝。
- 恢复扫描通过 `FOR UPDATE SKIP LOCKED` 或等价 CAS 分批 claim，避免两个实例重复恢复。

## Recovery Coordinator

唯一入口分四步：

1. Discover：显式 RECOVERY_PENDING + lease 过期 RUNNING/ADMITTED。
2. Validate：owner、age、attempt、tail、control、receipt、definition hash、origin。
3. Decide：调用 kind adapter 返回 ResumeDecision。
4. Act：先持久化 attempt/epoch/decision，再 dispatch；结果写 event、runtime projection 和广播。

适配器：

- `RootTurnRecoveryAdapter`
- `SubAgentRecoveryAdapter`
- `WorkflowRecoveryAdapter`
- `AcpRecoveryAdapter`
- `CronRunRecoveryAdapter`

`PendingConfirmationStartupRecovery` 和 `SubAgentStartupRecovery` 先改为 coordinator adapter，验证稳定后删除独立扫描。启动期间由 admission gate 防止用户请求和 recovery 抢同一 Session；达到启动超时后可开放健康端点，但普通 admission 保持明确的 503/recovering，不能静默竞争。

## 各类型恢复细节

### 根 agent turn

- 校验 source turn、activeRootTraceId、tail 和 receipt。
- 安全时复用同 dispatch ID/trace root，注入 internal recovery directive。
- 完整终态已落 message 但 runtime projection 未完成时，只 reconcile 状态，不再运行 LLM。
- 恢复时若触发 compact，必须走现有 CompactionService 锁和 range/boundary 规则。

### waiting_user

- 从 CONTROL row 重建 PendingAsk/PendingConfirmation registry。
- 保持 WAITING claim 和原 control identity。
- 用户回答通过现有 reserve-before-consume 路径；CAS 保证一次回答只消费一次。

### SubAgent

- `t_subagent_run` 保留业务投影，execution claim 负责 owner/lease/retry。
- child 先恢复；child terminal 后通过 durable parent delivery key `(runId, parentSessionId)` 幂等入队，确保 parent 最终只看到一次结果。
- 超过 2 小时默认不复活，mark interrupted/cancelled 并通知 parent。

### Workflow

- 复用现有 JournalCache/step records，按 sourceHash 拒绝变化后的定义。
- 已完成副作用 step 必须 cache-hit，不能重新执行。
- running step 无 durable result 时按 tool receipt safety 分类。
- paused human gate 保持 paused，不当作 orphan running。

### ACP

- 连接/进程是 client-owned，不纳入 server 自动 resume。
- server 重启后把 run 标 INTERRUPTED，并保留原 prompt、worktree、session 和 trace metadata。
- UI 提供“在新 ACP runtime 中重新开始”，新 runId/claim，显式标记 restartedFrom；除非未来 ACP 协议提供 durable attach token，否则不使用“继续原任务”文案。

## graceful restart

新增 lifecycle orchestrator：

1. close admission；对新请求返回 typed `SERVER_DRAINING`。
2. 广播 draining 和预计时间。
3. 等待 active claims，默认 5 分钟，可配置。
4. 对剩余任务写 RECOVERY_PENDING/checkpoint snapshot。
5. 请求 executor/ACP/workflow 停止；flush delivery queue 和审计。
6. 写 instance stopped/restart sentinel，退出。

agent 自己请求重启时，另写 one-shot continuation sentinel，重启健康确认后回到原 Session 报告结果；该机制与普通 crash recovery 使用不同 kind，避免重复 continuation。

## API 与可观察性

- `GET /api/executions?state=&kind=&sessionId=`
- `GET /api/executions/{id}`
- `POST /api/executions/{id}/retry`
- `POST /api/executions/{id}/abort`
- `POST /api/executions/{id}/resolve-uncertain`，decision=`already_completed / retry / abandon`

普通用户只能操作自己的 execution；危险副作用的 retry 需要显式确认，管理接口记录 actor 和 reason。

指标：recovery total/outcome、age、attempts、wedged、lease steal、unknown effects、drain duration。日志使用 claimId/sessionId/dispatchId/reasonCode，不输出 prompt、secret 或完整 tool input。

## 配置默认值

```yaml
skillforge:
  recovery:
    enabled: true
    auto-resume-enabled: false   # Phase 0 shadow；灰度后打开
    lease-duration: 45s
    heartbeat-interval: 10s
    startup-grace: 15s
    max-attempts: 3
    max-age: 2h
    backoff: [5s, 30s, 2m]
    drain-timeout: 5m
```

## 关键不变量

1. 一条 logical user turn 只有一个 source row 和一个 active execution claim。
2. 同一 claim 同时最多一个有效 owner epoch。
3. recovery attempt 在 dispatch 前 charged；不确定结果不退款。
4. tool_use/tool_result 严格配对；repair 与 replay decision 分离。
5. 同一 logical message 的持久化与 engine 内存 JSON shape byte-identical。
6. rewrite 保留 trace/message identity columns。
7. side effect outcome unknown 时禁止默认自动重放。
8. terminal claim 不可回到 running；人工 retry 创建新 recovery cycle/dispatch identity，并保留 lineage。
