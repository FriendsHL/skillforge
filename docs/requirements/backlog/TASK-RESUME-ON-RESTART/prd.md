# TASK-RESUME-ON-RESTART — 产品需求

## 背景

SkillForge 的 agent loop、SubAgent、Workflow 和 ACP 具有不同持久化程度。当前 server 重启会让部分任务被标 error、部分任务尝试恢复、部分任务永久停留在 running。用户无法判断任务是在继续、等待、失败还是已经丢失。

## 目标

1. planned restart 尽量通过 drain 避免中断。
2. crash/kill 后，所有已接受任务都得到确定且可观察的最终处置。
3. 能从安全边界继续的任务自动恢复；存在重复副作用风险时停止并要求确认。
4. 用户能看到“恢复中、恢复成功、需要确认、恢复失败、已隔离”等状态和原因。
5. 运维可以查询、重试、放弃或隔离恢复任务，不需要直接修改数据库。

## 非目标

- 不保存 Java stack、线程、LLM stream socket 或外部 cc 进程内存。
- 不承诺 exactly-once 的任意外部世界副作用；对不支持幂等/查询的外部系统只能做到不盲目重复。
- 不在首期实现跨机器进程迁移、工作区文件快照或分布式 workflow 调度器。
- 不自动补跑所有错过的 cron execution。

## 用户体验

### 正常 planned restart

- 系统进入 `draining`，拒绝新任务并显示“服务正在重启，请稍后重试”。
- 已运行任务最多等待配置的 drain budget；完成则不触发恢复。
- 仍未完成的任务在终止前写入 durable recovery marker。

### crash/kill 后

- 客户端重连后看到 `正在从服务重启中恢复`，而不是普通 error。
- 自动恢复成功后，原 Session 继续产生消息，不创建重复 Query 或新 Session。
- 如果停在 waiting_user，原卡片继续等待，用户回答后从原控制点继续。
- 如果上一个工具可能已经产生外部副作用但结果未知，显示：`上一步执行结果无法确认，已暂停以避免重复操作`，提供查看上下文、确认已执行、重新执行、终止四种操作；危险操作不提供一键默认重试。
- 恢复超过预算后标为 `恢复失败，已隔离`，不会在每次启动时再次自动运行。

## 状态语义

用户可见状态：

| 状态 | 含义 | 用户动作 |
| --- | --- | --- |
| Running | 有活 owner 且 lease 正常 | 可取消 |
| Recovering | coordinator 已 claim，正在恢复 | 可查看，不重复提交 |
| Waiting | 等用户输入/确认 | 回答原卡片 |
| Interrupted | 不能自动判断安全性或已过期 | 选择继续/重新执行/终止 |
| Failed | 明确业务/运行失败，不自动恢复 | 按 retry policy 重试 |
| Wedged | 自动恢复预算耗尽，已 tombstone | 检查后人工重开 |
| Completed/Cancelled | 终态 | 无 |

## 任务类型策略

| 类型 | planned restart | crash 后默认策略 |
| --- | --- | --- |
| 根 agent turn | drain | 安全 tail 自动 resume；未知副作用时 interrupted |
| SubAgent | drain | 由统一 coordinator 恢复 child，再由既有 registry 对账 parent delivery |
| Workflow | drain | 按 journal replay 到 frontier；已完成 step short-circuit；definition 变化则拒绝 |
| ACP/cc/codex | 通知 client/尽量 drain | client-owned runtime 标 interrupted；可显式从原 prompt 新起，不称为 resume |
| waiting_user | 保持等待 | 重建 registry，绝不自动回答或重新执行工具 |
| cron definition | 持久化 | scheduler re-arm；in-flight run 按任务声明的 misfire policy，默认不补跑 |

## 功能需求

- FR1：接收任务时，user/source message 与 execution claim 必须在一个事务中落库，成功后才返回 started。
- FR2：每个 running execution 必须有 owner instance、owner epoch、lease expiry 和稳定 dispatch ID。
- FR3：正常终态必须原子关闭 claim；失败也必须记录 failure source/code 和 side-effect evidence。
- FR4：coordinator 只 claim lease 已过期且没有新 owner 的任务，并使用 CAS 防止双恢复。
- FR5：恢复前校验 transcript tail、tool pairing、pending control、source turn、agent/workflow definition 兼容性。
- FR6：恢复尝试必须先持久化扣减预算再 dispatch；明确 admission rejection 才退款。
- FR7：自动恢复默认最多 3 次，使用持久化指数退避；耗尽后 tombstone。
- FR8：tool intent/result 必须有 durable receipt；`executing + unknown result` 按工具 replay policy 决策。
- FR9：所有恢复决策形成审计事件并广播给 Session/Control UI。
- FR10：提供 list/detail/retry/abort/resolve-uncertain 管理 API，带用户/管理员权限边界。
- FR11：graceful shutdown 必须先 close admission，再 drain，再标记 remaining tasks，最后终止 executor。
- FR12：startup recovery 不得阻塞整个 web server 无限等待；admission gate 在 reconciliation 完成或超时降级后才开放。

## 验收标准

1. user message 已返回 started 后立刻 kill -9，重启只产生一个恢复执行，原 Query 不重复落库。
2. LLM streaming 中 kill -9，恢复从最后持久化消息边界继续，最终 Session 无重复 Assistant suffix。
3. read-only tool 执行前/后分别 kill，自动恢复且 tool_use/tool_result 1:1。
4. non-idempotent tool 在结果未知窗口 kill，不自动重放，状态为 Interrupted。
5. waiting_user 重启后仍是同一个 control card，回答一次只启动一个 continuation。
6. 连续三次恢复失败后变 Wedged；下一次重启不再执行。
7. 两个 server instance 同时启动时，同一 claim 只有一个 owner。
8. SubAgent child 恢复完成后 parent 只收到一次最终投递。
9. Workflow 已完成 step 不重跑；source hash 改变时 409/Interrupted，不混用新定义。
10. ACP 断联明确显示“外部运行已中断”，只有用户显式选择后才从原 prompt 新起。
11. planned restart 在 drain budget 内完成的任务不进入 recovery。
12. 恢复决策、age、attempt、outcome 和 side-effect classification 可通过日志、指标、管理 API 查询。
