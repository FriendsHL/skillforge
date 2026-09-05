# 2026-09-06 实际重启恢复核查

Session：`37c7407d-3313-43e8-bdb4-fa421fb4dfcb`。首次核查仅只读；原 Session、Task、Trace 状态未人工修改，修复后验证另见文末。

结论：根 Session 的跨重启恢复执行及最终回复已验证；任务状态收尾与停止旧执行者存在缺口，不能记为完整恢复验收通过。

## 时间线（北京时间，UTC+8）

| 时间 | 事实 |
| --- | --- |
| 00:29:03 | 旧后端 PID 14333 接受请求并启动 Main Assistant / Agent 3 |
| 00:29:45 | 创建 Task `4b732a22-7e7a-45b3-956e-2260d951a390` |
| 00:29:51 | Task 更新为 `in_progress` |
| 00:30:56 | 旧 JVM 进入关闭钩子，但执行线程尚未退出 |
| 00:31:02.520 | 持久化 `SERVER_RESTART` / `recovering` / attempts=1 |
| 00:31:02.608 | 新后端 PID 16498 为同一 Session 启动恢复循环 |
| 00:31:02.663–00:31:06.103 | 旧 JVM 仍完成三个 Bash，并进入下一轮 LLM 请求；与新循环重叠 |
| 00:31:26 | 旧 JVM 关闭 executor/MCP/JPA/连接池，期间有 executor 超时警告 |
| 00:31:15–00:33:23 | 新循环持续执行工具并保存结果，推进到第 7 轮 |
| 00:35:19 | 新循环正常返回最终早报，Trace 为 `ok`，Session 变为 `idle` |

这次 Session 始于 00:29，所以跨越的是后一次重启，不是约 00:25 的前一次重启。

## 状态与完整性

- 最终 API 与数据库一致：`runtime_status=idle`，`runtime_step=null`，`recovery_state=none`，`recovery_attempts=0`，无 runtime_error。
- `completed_at=2026-09-05T16:35:19.745373Z`；最终回复已持久化，共 42 条消息。
- 恢复前后 Trace 保持同一 root trace `71c97646-4b38-41af-a668-afc6f65354bc`；新 trace `1a0f5733-7879-4127-9f12-519f3017eca5` 正常结束。
- 在最后一轮工具结果已保存、最终文本尚未落库时核对：41 条消息、seq 0–40 连续不重复；34 个 tool_use / 34 个 tool_result，调用 ID 无重复、无孤立结果、无未闭合调用。随后增加最终 assistant 文本。
- 只有一个非 tool_result 用户消息，未新增伪造的 Continue 用户消息。
- Task API 与数据库仍返回该任务 `in_progress`，更新时间仍为 00:29:51。尽管已交付最终回复，任务账本尚未完成收尾。后续定位：恢复入口没有刷新任务快照，文本终结分支没有核对；模型未调用 TaskUpdate，Session 清理不负责修改 Task。
- 旧 trace 仍是 `running`（ended_at 为旧循环最后事件时间），新 trace 是 `ok`；旧执行观测记录未收尾。

## 验收边界与问题

1. 自动识别未完成 Session、恢复上下文、继续执行、保存最终回复、清理 Session 恢复状态：本次实际验证通过。
2. 任务账本和旧 trace 的终态一致性：未通过。
3. 无新旧执行者重叠/副作用至多一次：未通过验证。日志已证明执行窗口重叠，但不能只凭工具 ID 无重复断言没有重复副作用。
4. 本次重启脚本只等待服务端口释放，没有等待旧 JVM 完全退出，启动新 JVM 过早。此重启操作顺序应修正；不能把端口释放当作所有 Agent 停止。
5. `skillforge.session-history.enabled=false`，该 Session 无 active_loop/owner/lease，且 `t_session_tool_attempt` 为零行；此次走旧版启动恢复路径，不能用本次结果认证新的 durable generation/fence/intent/result 协议。
6. 旧进程退出还报告数据库断连及 `PostgresBackupService$1` 的 NoClassDefFoundError。运行中的 jar 在构建期间被替换是需排查的关联风险；本次不声称这些退出异常导致最终任务失败。

## 可复核证据

- 旧进程日志：`~/.skillforge/logs/server-model-settings-20260906.log`（Session 首次启动、shutdown 期间继续执行）。
- 新进程日志：`~/.skillforge/logs/server-model-settings-final-20260906.log`（恢复 dispatch、工具进展、00:35:19 完成）。
- 只读 SQL：`t_session`、`t_session_message`、`t_session_task`、`t_llm_trace`、`t_session_tool_attempt`。
- 只读 HTTP：`GET /api/chat/sessions/{id}?userId=1` 与 `GET /api/chat/sessions/{id}/tasks?userId=1`。
- 代码边界：`PendingConfirmationStartupRecovery` 设置恢复标记，`ChatService.resumeInterruptedTurnAsync` 从持久化尾部恢复，正常终结时 `clearRecoveryState` 清状态。
- 真实 Chromium 只读检查：最终回复可见，`Agent is running` 横幅已消失。

## 任务收尾修复后验证（2026-09-06 00:56，北京时间）

### 修复与根因

恢复前 Task 仅在显式 TaskUpdate 时更新。旧恢复入口缺少当前数据库任务快照；模型输出最终文本后，引擎直接结束，Session idle 不会同步完成 Task。

现为每次引擎启动注入经过 Session 所有权校验的最新 open Task 数据；legacy 文本交付后，若还有任务且预算足够，至多补充一次核对机会，让模型通过正常 TaskUpdate 更新已验证完成的相关任务。等待、部分完成和无关任务不自动完成。保留原业务回复作为 finalResponse/afterLoop，避免通知或调度结果被记账短回复替代。任务文本有长度限制和低信任边界，不改写原用户消息。

### 最新验证结果

| 验证项 | 结果与证据 |
| --- | --- |
| 主线程后端构建及回归 | PASS：233 tests，0 failures/errors/skipped，BUILD SUCCESS；包括 16 个新增核对测试，以及 AgentLoopEngine、任务服务/工具、启动恢复周边测试 |
| 恢复后先交付、再补任务收尾 | PASS：独立 Session `9bd862c2-6bc0-4151-9fa5-a50a397f963e`，00:56:41 进入恢复循环，先交付 17+25=42，再于 00:56:44 调用 TaskUpdate |
| 相关任务 completed | PASS：Task `8dd17808-4027-4923-9bac-ae354913246c` 由 in_progress/version=0 更新为 completed/version=1，API 与 SQL 一致 |
| 等待任务保留 | PASS：Task `538a849c-175a-4973-b087-bb9640c19985` 仍 pending/version=0，没有误完成 |
| Session/Trace 收尾 | PASS：idle、runtimeStep=null、runtimeError=null、recoveryState=none、attempts=0；Trace `528fd884-6182-4fb0-8747-c84498573990` 为 ok |
| 消息完整性 | PASS：seq 0–4 连续；一个真实原 user；一个 TaskUpdate tool_use 对应一个 tool_result，无伪造 Continue；业务原回复保存一次，末尾另有短确认 |
| 重启操作顺序 | PASS：确认旧 JVM 完全退出才启动下一进程；最终 JVM 20587 使用独立不可变 jar，构建未替换运行中的 jar；前端 HTTP 200 |

本次为准备持久化边界数据后的真实启动恢复回归，调用真实模型及 Task 工具；不是 SIGKILL 中途打断工具的测试。首次准备漏设 active_root_trace_id，在进入引擎前因 getActiveRootTraceId 的 null/Optional 行为报 SessionNotFound，未计入通过；补齐 fixture 前置字段并重启后得到上表结果。这个无 root trace 的恢复入口问题未在本次修复。

原 Session 及 Task、旧 Trace 均未人工改账。原始验收仍保留历史失败事实；新增结论为：**legacy 路径的已交付任务状态收尾回归通过**。它不等于所有断点续存场景通过，也不认证 durable 协议或旧 Trace 自动清理；模型仍可能在预算不足或证据不足时保持任务未完成，系统不会强制批量完成。

验证资产：

- 主线程 Maven 日志：`/tmp/skillforge-task-validation/final-maven.log`。
- API 快照：`/tmp/skillforge-task-validation/live-evidence.json`。
- fixture 及核验脚本：`/tmp/skillforge-task-validation/{fixture.json,prepare.py,verify.py}`；prepare.py 为首次准备版本，缺失 root trace 的补齐过程以上述记录为准。
- 新后端日志：`~/.skillforge/logs/server-task-reconciliation-20260906.log`。
- 部署 jar：`~/.skillforge/runtime/skillforge-server-task-reconciliation-20260906-005349.jar`。
- 重启前数据库备份：`~/.skillforge/backups/pre-task-reconciliation-20260906.dump`。
- Full 独立审查通过；新增用例有 RED/GREEN，主线程另行构建与真实 API/SQL 验证。

百炼 Token Plan 与 system Agent 模型编辑已单独提交并推送：`ca3c9cb4d184e09b1cf1811810a3a4064fb46123`。本节任务收尾修复按用户授权单独提交，与已有 SESSION-HISTORY-RECOVERY 工作区改动保持区分。

## 测试数据清理

用户要求测试结束清理临时 Agent。已通过 API 删除测试 Session `9bd862c2-6bc0-4151-9fa5-a50a397f963e` 和 Agent 27；SQL 核对该 Agent、Session、关联 Task 均为 0 行。上文为清理前采样证据，日志与 API 快照保留。后续优先在既有 Agent 下新建测试 Session，结束后清理。

## 独立提交验证

为避免提交未验收的 History 协议，在 `ca3c9cb4` 基线上隔离整理本次修复，使用已有 MEMORY 低信任数据分类，不依赖新增 HISTORY 枚举或 durable writer。隔离提交运行 178 个相关测试，0 failures/errors，BUILD SUCCESS，其中新增核对测试 15 个。工作区额外的 durable-only 测试与 guard 保留在 History 未提交改动中。上文 233 个测试和真实恢复为此前包含 History 工作区代码但 master-off 的部署验证，二者证据范围不同。
