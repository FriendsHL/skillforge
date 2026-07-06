# TASK-RESUME-ON-RESTART — tech-design（Phase 1：根 agent-loop 会话恢复）

> **状态**：草拟 v2（2026-06-25，经 architect 对抗 review 修订；**方案定为 B**）。HARD-GATE 设计，**待用户最终批准后实现**。
> 范围：仅 Phase 1（根 agent-loop 会话）。Phase 2（workflow）/ Phase 3（ACP）另出。
> v1→v2 变更：原推荐"方案 A（interrupted 新状态）"经 review F2/F6 否决 → 改 **方案 B（不加新状态）**；折入 F1/F3/F4/F5/F7 修正（见 §12）。

## 1. 目标与非目标

**目标**：单实例部署下，server 重启后，**重启前在 `running` 的根 production 会话自动续跑**（re-submit `[Resume from restart] Continue`），不再静默标 error；保持 tool_use↔tool_result 配对不变量。**顺带修复** subagent resume 被 shadow（§3）。

**非目标（Phase 1 明确不做）**：mid-tool 精确续点 / `waiting_user` 恢复（registry 内存态丢失）/ collab 成员恢复 / 多实例安全（无 lease 列）/ workflow & ACP（Phase 2/3）。

## 2. 现状机制（实现前已核 file:line）

- **执行**：`chatAsync` → `chatLoopExecutor`（`EngineConfig:285` `shutdown()` + daemon 线程）跑 `AgentLoopEngine`，全内存，仅边界 append message；提交前 `setRuntimeStatus("running")`（`ChatService:435`）。
- **"是否真在跑"的内存信号 = LoopContext registry**：POST 到已 running 的会话走 `ctx.enqueueUserMessage`（`ChatService:302-326`）。**重启后无任何 ctx → 所有 DB `running` 都是孤儿**（方案 B 的孤儿判据）。
- **resume 原语**：`chatAsync(sessionId, msg, userId, preserveActiveRoot=true)`（`ChatService:268/273`）已存在，`SubAgentStartupRecovery:116` 已用。
- **会话三分**（`SessionEntity`）：根 = `parentSessionId==null && collabRunId==null`；subagent 子 = `parentSessionId` 设（且 id ∈ `t_sub_agent_run.childSessionId`）；collab 成员 = `collabRunId` 设。
- **状态列**：`runtimeStatus`（idle/running/waiting_user/error/**terminated**——注：`terminated` 已是 DB-only 值，持久化但广播 "idle"、前端不引入枚举，`ChatService:944-948`）、`runtimeStep`、`runtimeError`、`activeRootTraceId`、`origin`（production/eval）。**无 instance/lease/resume 计数列。**
- **两个 startup handler**：`PendingConfirmationStartupRecovery`（SmartLifecycle `MIN+100`，web 前）扫 running/waiting_user（跳 eval）→ 补 orphan → **标 error**；`SubAgentStartupRecovery`（ApplicationRunner `@Order(100)`，web 后）扫 `t_sub_agent_run` RUNNING → child running 则 resume，否则 replay finally hook。

## 3. 核心问题：lifecycle 顺序 shadow 了 resume（且**主动报失败**）

Spring 顺序：SmartLifecycle（PendingConfirmation，MIN+100）→ web server（~MAX）→ ApplicationRunner（SubAgentStartupRecovery）。
→ PendingConfirmation 先把**所有** running（含 subagent 子）标 `error`；SubAgentStartupRecovery 再看 child 已是 `error` → 走 `error → replay finally hook`（`SubAgentStartupRecovery:131`）→ **不仅没 resume，还把在跑的子 agent 当失败上报给父**，可能触发父侧错误处理/中止。

> ⚠️ 此为**静态 lifecycle 分析**，未实证（2026-06-24 重启时无 in-flight）。**实现第 1 步 = 受控重启测试 T1 坐实**。

## 4. 选定方案：B（不加新状态，单一 resume 权威）

**核心**：重启后会话**保持 `running`**（不新增 `interrupted` 态 → 避开 review F2 的全量消费者审计）；用"DB running + 无内存 ctx"判孤儿；**单一 resume 权威**按三分分派。

被否方案：**A（interrupted 新状态）** —— review F2：拖入前端封闭 union（force-cast/无 banner/WS/输入门）+ 后端 3 处硬编码 `"running"`（活跃计数门/sweeper/collab 计数）全量审计，F6：其多实例/未来-phase 理由在单实例 Phase 1 站不住。**C（精确 checkpoint）** —— 副作用幂等无法干净解，巨大，拒。

### 4.1 阶段 1（pre-web，改 PendingConfirmationStartupRecovery 行为）

对 `running`/`waiting_user` production 会话（**保留 eval skip**）：
1. 补 orphan tool_use → fabricated tool_result（**文案通用化**，F5："Tool execution aborted due to server restart; result unknown"，isError=true）。
2. 决策矩阵改写（**不再无脑 error**）：

| 会话类型（判据） | pre-web 处置 |
|---|---|
| `running` 根 production（parentSessionId==null && collabRunId==null） | **保持 `running`**（交阶段 2 resume）|
| `running` subagent 子（parentSessionId 设） | **保持 `running`**（交 SubAgentStartupRecovery resume——shadow 修复）|
| `running` collab 成员（collabRunId 设） | **标 error**（Phase 1 不恢复 collab，记 limitation）|
| `waiting_user`（任意） | **标 error** + 清理悬挂 pending control 行（registry 丢失，维持现状）|
| `origin == eval` | **skip**（EvalOrchestrator 处理）|

> 阶段 1 仍用 SmartLifecycle（web 前）→ 保留"用户 POST 撞 mid-repair" race 保护（继承现有初衷）。

### 4.2 阶段 2（post-web，单一 resume 权威）

**单一权威原则（F3）**：一个 session 只能被一个组件 resume。三分严格不重叠：
- **新增 `RootSessionResumeRunner`（ApplicationRunner，低 @Order 尽早跑缩小窗口）**：扫 `runtimeStatus=running && parentSessionId==null && collabRunId==null && origin=production` → resume。
- **`SubAgentStartupRecovery`（保留，shadow 已修）**：继续扫 `t_sub_agent_run` RUNNING → 现在 child 是 `running`（非 error）→ resume 分支真正可达。
- 两者判据互斥（根 resumer 显式排除 parentSessionId/collabRunId 非空）→ **不会双 resume**。

每次 resume：
1. **先 `resumeAttempts++` 并落库，再 resume**（计数必须先持久化，否则"一 resume 即崩"丢计数永循环）。
2. 超 cap（默认 3，config `skillforge.resume.max-attempts`）→ 标 `error`（"resume limit exceeded"），不再 resume。
3. 否则 `chatAsync(id, "[Resume from restart] Continue your previous work.", userId, true)`。

### 4.3 计数重置（F4：测"连续崩"而非"一生重启次数"）

`resumeAttempts` 在会话**成功 resume 后到达干净 idle 边界时重置为 0**（落点 `ChatService:948` idle 收尾处）。→ 计数语义 = "连续 resume 未取得进展次数",才是崩溃循环的真信号；避免长命会话历经多次正常重启被误杀。

## 5. HTTP 启动窗口处理（方案 B 的已知代价）

web 起来 → 阶段 2 resume 触发之间有窗口，用户可能 POST 到一个"running 但无 ctx"的孤儿会话。处理：
- `RootSessionResumeRunner` 用**低 @Order 尽早跑**，缩小窗口。
- 窗口内 POST：现有 `enqueueUserMessage` 依赖内存 ctx；孤儿无 ctx → **实现需补**："POST 到 running-但-无-ctx 的会话" 走"触发 resume（带上这条新消息）"或"排队等 resume 接管"。实现期需明确这条交互（验证 `enqueueUserMessage` 在无 ctx 时的行为，必要时让 resume loop 接管已 enqueue 的消息）。

## 6. 五个决策点（结论）

1. **粒度/幂等**：resume = re-submit `[Resume] Continue`，LLM 从持久化边界重新决策，非 mid-tool。已完成副作用 tool 有 tool_result 不重放；唯一 orphan 标 aborted-error 由 LLM 决定重试。与 subagent 现有接受风险一致，**不新增幂等保证**（§10 具名 limitation）。
2. **崩溃 vs error vs waiting_user**：单实例下"启动时 running = 孤儿"→ resume；`error` 终态不碰；`waiting_user` → error（registry 丢）。
3. **resume cap**：新增 `resume_attempts INT default 0`（t_session counter 列，非 identity，不触发 identity-column rule），先增后 resume + idle 重置（§4.2/§4.3）。
4. **统一 handler**：单一 resume 权威 + 三分互斥（§4.2）根除 shadow & 双 resume。
5. **多实例**：Phase 1 单实例假设；多实例需 `owner_instance_id`+heartbeat —— out of scope（§11）。

## 7. Schema 改动

- 唯一：`V<n>__add_session_resume_attempts.sql` 给 `t_session` 加 `resume_attempts INT NOT NULL DEFAULT 0`。
- **无新 runtimeStatus 值**（方案 B 不引入 interrupted）→ 无前后端消费者审计负担。
- database-reviewer 审迁移；非 t_session_message 列，不涉 identity-column-on-rewrite。

## 8. 不变量与消费者影响

- **tool_use↔tool_result 配对**：沿用 `collectOrphanToolUseIds` + fabricated error tool_result，resume 前必补。
- **持久化-engine 字节一致**（persistence-shape-invariant）：resume 走既有 `chatAsync`→`buildUserMessageWithReminder`→7-arg engine 路径，不新造 message 拼装 → 不破不变量。reviewer 需 eyeball：合成 resume 消息会经 reminder 注入路径（行为正常，非字节问题）。
- **消费者影响最小**：会话保持既有 `running` → 前端 union / banner / WS / 输入门 / 后端活跃计数门 / sweeper / collab 计数**全不需改**（这是方案 B 相对 A 的核心收益）。
- **@Transactional 纪律（F7）**：`RootSessionResumeRunner`（ApplicationRunner）+ 改造的 PendingConfirmation（SmartLifecycle）**不得加 `@Transactional`**（AOP 不代理 lifecycle/runner）→ 所有持久化经 `SessionService` public 方法。
- **eval skip**：阶段 1 & 阶段 2 都必须保留 `ORIGIN_EVAL` skip。

## 9. 测试计划（Phase Final 必跑真重启 E2E）

- **T1（坐实 shadow，实现第一步）**：造 running 根 + running 子会话 → kill -9 → 重启 → 验证**修复前**两者都被标 error（且子被报失败给父）。
- **T2（根会话 resume）**：running 根会话 → kill → 重启 → 自动续跑完成（不再静默 error）。
- **T3（orphan 不变量）**：running 带 1 orphan tool_use → 重启 → fabricated tool_result 补上 + resume 后 LLM 请求不 400。
- **T4（resume cap + 重置）**：mock resume 即抛 → 重启 3 次 → 第 4 次标 error "resume limit exceeded"；另：成功 resume 跑到 idle → resume_attempts 归 0。
- **T5（不误恢复）**：error 不 resume；waiting_user → error + pending 行清理；**eval 会话 skip**；**collab 成员标 error 不 resume**。
- **T6（子 agent resume 复活 + 单一权威）**：running 子会话 → 重启 → SubAgentStartupRecovery resume 分支真正可达 + 父被正确续接；断言**每个 session 恰好一次 chatAsync**（无双 resume）。
- **T7（HTTP 启动窗口）**：web 起来后、resume 前 POST 到孤儿 running 会话 → 不丢消息、不双 loop。

## 10. 已知 limitation / accepted risk（需用户签字）

1. **副作用 tool 双执行**（F5）：orphan tool 可能在崩溃前**已执行副作用**（`Bash rm` / 外发渠道 / PR push）但未落 tool_result；resume 后 LLM 可能重试 → 非幂等动作**双执行**。Phase 1 接受（同 subagent 现状），不做幂等保证。
2. **方案 B "假 running" 残留**（review F6 对 B 的批评）：若阶段 2 resumer 因 bug 跳过某会话 → 它停在 running 永不恢复（cap 不增不触发）。缓解：per-session try/catch + 清晰日志 + 跨重启自愈（下次重启再试）；彻底解法（watchdog / interrupted 态）留 future。
3. **正常重启也会 resume**（F7）：`chatLoopExecutor` `shutdown()`+daemon → 干净重启也留 running 行 → resume 在正常重启触发（非仅崩溃）。这其实**符合用户诉求**（任何重启都续上任务），cap 是唯一护栏。
4. `waiting_user` / collab / 多实例 / workflow / ACP 不在 Phase 1。

## 11. Future
多实例 lease（owner_instance_id+heartbeat）/ `waiting_user` 恢复（重建 pending registry）/ 优雅 shutdown 钩子主动标记 in-flight / watchdog 兜 limitation-2 / workflow（Phase 2 journal replay）/ ACP（Phase 3 新 worktree 重起）。

## 12. 对抗 review 结论（architect，2026-06-25）与处置

| 发现 | 严重度 | 处置 |
|---|---|---|
| F1 shadow 不仅没 resume 还**主动报失败给父** | warning | §3 已补 active failure-propagation |
| F2 `interrupted` 新状态全量消费者审计（前端 union/banner/WS/输入门 + 后端 3 处 running 硬编码）| **blocker** | **改方案 B 规避**（不加新状态）|
| F3 双 resume（根∩子）+ collab 未归类 | warning→blocker | §4.2 单一权威 + 三分互斥；§4.1 collab 矩阵行；T6 断言恰好一次 |
| F4 resume_attempts 永不重置误杀长命会话 | warning | §4.3 idle 边界重置（测"连续崩"）|
| F5 fabricated 文案 install-专用 + 副作用双执行 | warning | §4.1 文案通用化；§10.1 具名 accepted risk |
| F6 YAGNI:interrupted vs B（单实例下 A 理由 moot）| warning | 采纳 → 选 B |
| F7 @Transactional 纪律 / shutdown+daemon 正常重启也 resume / eval skip / 悬挂 pending 行 | mixed | §8 纪律、§10.3 note、§4.1 eval skip、§4.1 waiting_user 清理 |

architect 总体结论："核心诊断(shadow)正确且有据,resume 原语真实存在且复用安全;送回修 F2/F3/F4 + 简化"。本 v2 已逐条处置。

## 13. pipeline

Full（红灯核心文件）。reviewer：java-reviewer + java-design-reviewer（RootSessionResumeRunner 抽象 + PendingConfirmation 行为改）+ compact-reviewer（resume×压缩交互）+ database-reviewer（resume_attempts 迁移）。Phase Final 必跑 T1-T7 真重启 E2E。
