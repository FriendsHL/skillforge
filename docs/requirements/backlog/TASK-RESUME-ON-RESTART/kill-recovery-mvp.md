# TASK-RESUME-ON-RESTART — Kill Recovery MVP

## 范围

只处理单实例 SkillForge server 被 `SIGKILL`、进程崩溃或机器掉电后，新进程启动时遗留的 `running` task。

不处理 graceful restart drain、多实例抢占、运行中迁移、跨设备恢复和任意外部进程原地续接。

## 可行性结论

可以恢复，但“恢复”定义为从最近持久化安全边界重新进入执行，不是恢复 Java 线程、LLM stream 或 Tool 调用栈。

| Task 类型 | MVP 处置 |
| --- | --- |
| 根 agent-loop | transcript tail 安全时自动继续；不新增重复 user Query |
| SubAgent | 修复现有 startup handler 顺序冲突，再复用 child Session 历史继续 |
| Workflow | 有 journal/frontier 时 replay；没有安全 frontier 时标 Interrupted，不保持 running |
| waiting_user | 保持等待并重建 control registry，不自动执行 |
| ACP/cc/codex 外部进程 | 不能真恢复；标 Interrupted，允许用户显式从原 prompt 新起 |
| cron | 定义重新注册；被 kill 的单次 execution 默认不补跑 |

## 最小实现

### 1. Startup admission gate

Recovery 完成前，Chat/Task 新 admission 返回明确 `SERVER_RECOVERING`，健康检查仍可使用，避免用户请求和 startup scanner 同时接管同一 Session。

### 2. 单一 startup coordinator

以一个 `KilledTaskStartupRecovery` 替代以下两个组件对同一 Session 的竞争决策：

- `PendingConfirmationStartupRecovery`
- `SubAgentStartupRecovery`

启动时扫描：

- production Session `runtime_status=running/waiting_user`
- `t_subagent_run.status=RUNNING`
- `t_flywheel_run.status=running`

在单实例前提下，新 JVM 启动时不存在旧内存 owner，因此这些 running row 都是 orphan candidate。合法 `error/completed/cancelled/idle` 不处理。

### 3. 轻量恢复字段

不引入完整 execution ledger。为可恢复的 task owner 增加：

- `recovery_attempts`，默认 0
- `recovery_state`：`none / recovering / interrupted / wedged`
- `recovery_reason`
- `recovery_started_at`

恢复前先以事务将 `recovery_attempts + 1` 并标 recovering，再提交 executor。最多 3 次；达到上限标 wedged，后续启动不再自动恢复。成功到达新的持久化 checkpoint 或正常终态后清零连续失败计数。

### 4. Tail 决策

| 持久化 tail | 动作 |
| --- | --- |
| user message，尚无 Tool | 自动继续 |
| 完整 Assistant terminal message | 只修正 runtime 状态，不再调用模型 |
| 完整 tool_result | 从该结果后继续 |
| orphan tool_use | 当前正常持久化顺序下不应出现；作为异常数据标 Interrupted，不自动改写或重放 |
| pending Ask/Confirmation control row | 重建 registry，保持 waiting_user |
| transcript 无法解析/定义不兼容 | 标 Interrupted，显示原因 |

### 5. 根 turn 继续方式

- 加载 `SessionService.getContextMessages`。
- 保留 `activeRootTraceId`。
- 使用内部 recovery directive 告知 Agent 上一轮被服务重启中断。
- directive 不作为新的 user Query 持久化，避免重复消息和 persistence-shape 漂移。
- continuation 只允许一个 startup owner 提交一次。

### 6. Orphan Tool 防御策略

- MVP 不增加 `Tool Execution Receipt` 表，也不补造或删除消息。
- 当前 Agent Loop 在完整 loop 返回后统一持久化 Assistant/tool_result，因此进程在工具执行期间被 kill 时，数据库通常只保留上一条完整 user 或 tool_result 边界。
- 启动恢复若意外发现未配对 `tool_use`，说明来自历史版本或特殊持久化路径；Session 标 Interrupted，等待人工处置。
- 正常恢复从最后一条完整持久化边界重新调用模型，接受工具可能 at-least-once 执行及重复副作用风险。

## MVP 不变量

1. startup 前已经返回 started 的 user Query 只能存在一条。
2. tool_use/tool_result 必须配对。
3. orphan Tool 作为异常数据 fail closed，不允许把未配对消息直接发送给模型。
4. waiting_user 不得被改成普通 error，也不得自动回答。
5. 同一 task 单次启动最多提交一个 recovery executor job。
6. 连续三次恢复失败后不再形成启动死循环。

## 验收

1. 根 turn 在 LLM stream 中 kill，重启后继续并产生一个最终回答。
2. user row 已提交但 executor 尚未运行时 kill，重启后只执行一次。
3. Tool 完成并持久化 result 后 kill，重启不重复 Tool。
4. Tool 运行中 kill 且无持久化 result，重启从上一条完整边界重新唤醒 task；最终 transcript 保持配对。
5. waiting_user kill 后仍显示原卡片，回答一次只继续一次。
6. SubAgent 恢复后 parent 只收到一次结果。
7. Workflow 有安全 frontier 时继续；无 frontier 时不永久卡 running。
8. 连续三次 kill 后 task 变 wedged，第四次启动不再自动运行。

## 与完整方案的关系

MVP 适合当前单实例部署，开发量明显低于 durable execution ledger。它不要求额外的 Tool Receipt 存储，但接受“外部工具可能已成功、恢复后再次执行”的 at-least-once 风险。未来需要避免重复副作用、多实例恢复或外部结果对账时，再引入完整方案中的 `t_tool_execution_receipt`；MVP 的 recovery state、reason 和 attempt 数据可作为迁移输入。
