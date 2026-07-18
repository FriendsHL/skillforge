# TASK-RESUME-ON-RESTART — 实施、灰度与验收计划

## Full pipeline

该需求触碰 AgentLoopEngine、ChatService、SessionService、SessionMessageRepository、Flyway、tool pairing、lease 和 Workflow，必须逐 Phase 使用 Full pipeline。每个阶段都需要计划审查、Java/数据库/安全专项审查和主会话真重启验证。

## Phase 0 — Durable ledger 与 shadow reconciliation

交付：

- migrations、ExecutionClaim/Receipt/Event/RuntimeInstance 实体与 repository。
- admission transaction 只写 claim，不改变现有恢复行为。
- heartbeat/lease、coordinator shadow scan、决策日志和指标。
- 对比 shadow decision 与现有 startup handler 的结果，不自动 dispatch。

Gate：运行至少一个 dogfood 周期；不存在 active claim 泄漏、错误 owner 判断或明显 query 性能问题。

## Phase 1 — 根 turn 安全恢复

交付：

- RootTurn adapter、tail validator、stable dispatch、attempt/tombstone。
- waiting_user registry 重建。
- 仅对 SAFE/READ_ONLY 自动恢复；UNKNOWN_EFFECT 进入 Interrupted。
- dashboard/iOS 先消费新增 runtime states 和 reason code，提供手动 retry/abort。

Gate：kill matrix 全通过；默认 feature flag 先按测试用户/Agent allowlist 灰度。

## Phase 2 — 副作用回执与 graceful drain

交付：

- Tool replay policy 元数据与 receipt state machine。
- 首批核心工具分类；未分类工具一律 EXTERNAL_EFFECT/UNKNOWN。
- 支持 idempotency key/reconcile 的工具适配。
- admission close、drain、remaining marker、restart sentinel。
- durable outbound reply 与已有 delivery queue 的关联对账。

Gate：至少一个 read-only、一个 idempotent write、一个不可确认 external effect 的 crash E2E；证明后两者分别“不重复”和“fail closed”。

## Phase 3 — SubAgent 统一

交付：

- SubAgent adapter、parent delivery idempotency key、age gate。
- 将 `SubAgentStartupRecovery` 与 `PendingConfirmationStartupRecovery` 的扫描责任迁移到 coordinator。
- 双写/对照期结束后删除旧 owner 冲突。

Gate：child 在 admission、LLM、tool、terminal-before-parent-delivery 四个点 kill，parent 最终只收到一次结果。

## Phase 4 — Workflow crash recovery

交付：

- Workflow adapter、sourceHash/step frontier 校验、startup scan。
- journal replay 与 execution lease 结合；paused gate 独立于 orphan running。
- side-effecting step 必须由 receipt/cache short-circuit。

Gate：多 gate workflow、definition changed、step effect unknown、双实例并发恢复全部通过。

## Phase 5 — ACP interruption/restart UX

交付：

- ACP adapter 将失联 runtime 收口为 Interrupted。
- 保留 prompt/worktree/lineage，提供显式 restart-from-prompt。
- 若未来 ACP 支持 durable attach token，再单独设计真正 reconnect。

Gate：文案和 API 不把新进程重跑描述成原进程 resume；权限与 worktree ownership 验证通过。

## 测试矩阵

每个关键点使用可控 fault injector，在 durable write 前后分别 `SIGKILL`：

| 故障点 | 期望 |
| --- | --- |
| user message 前 | 请求未 admitted，不恢复 |
| user message + claim commit 后、executor 前 | 恢复一次 |
| LLM request 前/stream 中 | 从安全边界恢复，Assistant suffix 不重复 |
| tool PREPARED 后 | 可安全开始 |
| tool EXECUTING 中 | 按 policy reconcile/retry/interrupted |
| tool result commit 后、runtime update 前 | 只对账，不重跑 tool |
| waiting control commit 后 | 恢复同一等待卡片 |
| terminal message 后、claim close 前 | 只关闭状态，不再 LLM |
| child terminal 后、parent delivery 前 | parent 投递一次 |
| workflow step result 后、frontier 前 | cache-hit，不重跑 step |

必测维度：Postgres/H2 migration、单实例/双实例、planned drain/SIGTERM/SIGKILL、连续 crash、时钟偏差、旧版本 claim、Agent/Workflow 定义变化、compact 前后、WebSocket 断连重连。

## 验证命令与证据

- 单元/集成：`mvn -pl skillforge-core,skillforge-server -am test`，必须 BUILD SUCCESS。
- DB：Testcontainers Postgres 验证 unique/CAS/`SKIP LOCKED`、迁移与回滚兼容。
- E2E：脚本启动 server → 发起任务 → fault injector kill → 重启 → API/SQL/消息历史核对。
- 副作用：测试 HTTP server 按 idempotency key 计数，证明 write 次数；unknown window 证明没有第二次请求。
- 多实例：两个 server 竞争同一过期 claim，最终一个 owner epoch、一次 dispatch。
- UI/API：状态、reason code、retry/abort/resolve 权限与 WS envelope roundtrip。

不能用“server 能重新启动”代替恢复验收，也不能只检查 runtimeStatus；必须同时核对 claim、receipt、message rows、tool pairing 和外部 effect counter。

## 发布策略

1. migration 向后兼容，旧代码忽略新表。
2. `auto-resume-enabled=false` 上线 shadow。
3. 仅开发 Agent 开启 SAFE root resume。
4. 5% 用户/Agent allowlist，观察 7 天。
5. 逐步 25% → 100%；UNKNOWN_EFFECT 始终 fail closed。
6. SubAgent/Workflow 分别单独 feature flag，不随 root 自动开启。

停止放量条件：duplicate user/message、duplicate external effect、两个 owner、tool pairing 破坏、恢复导致 crash loop、p95 startup/admission 显著回退。

## 回滚

- 关闭各 kind 的 auto-resume flag；coordinator 仍做 reconciliation 并将 orphan 标 Interrupted。
- 不删除 claim/receipt/event 数据；旧 Session runtime projection 保持可读。
- migration 第一阶段只增表/索引，不删除现有字段和 handler；旧 handler 仅在 shadow/迁移期由 mutually-exclusive flag 控制，禁止同时写同一 task。
- tombstone 和 uncertain-effect 记录不可因回滚被清除，避免旧版本重放。

## 开工前批准项

本方案已经给出推荐默认值，没有阻塞实现的未决项。用户批准后，首先只启动 Phase 0；Phase 1 自动恢复功能仍需以 Phase 0 shadow 数据和 plan review 作为 hard gate。
