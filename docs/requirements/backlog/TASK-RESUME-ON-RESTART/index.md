# TASK-RESUME-ON-RESTART — 任务断点续存(重启后在跑的任务不丢)

---
id: TASK-RESUME-ON-RESTART
mode: full
status: backlog（2026-06-24 立项；现状已调研，设计未定稿，未启动）；**2026-06-25 用户拍 Phase 1（根 agent-loop 会话）优先做**
priority: P2
risk: High（触碰核心文件 AgentLoopEngine / ChatService / SessionService → 红灯 Full + HARD-GATE 设计）
created: 2026-06-24
source: 用户 2026-06-24 —— "断点续存这个能力你看看。比如现在在跑一个任务，重启之后这个任务可能就断点了，列为一个需求。"
---

## 用户请求

> 现在如果有一个任务正在跑（agent loop / 子 agent / 外部 cc/codex / workflow），server 一重启，这个任务就断了。希望有"断点续存"能力：重启后在跑的任务能恢复/续上，而不是静默丢失或卡死。

## 现状（开工前必读，避免重造）

> 来源：2026-06-24 Explore 全量调研，file:line 为当时证据，实现前需复核。

SkillForge 的任务执行**全部是内存态**（线程池跑 agent loop / CompletableFuture 跑 cc / ExecutorService 跑 workflow），**循环过程中不落 checkpoint**，只在到达边界（tool 完成 / ask / loop 结束）时把 message 追加进 DB。重启 = 内存执行全丢。各任务类型重启后行为**不一致**：

| 任务类型 | 进度持久化 | 重启后行为 | 证据 |
|---|---|---|---|
| **根 agent-loop 会话** | 无 mid-loop checkpoint，仅 loop 结束 append message | **被标 ERROR**（通用恢复把所有 running → error，补 orphan tool_result） | `ChatService.chatAsync:458`（提交 `chatLoopExecutor`）/ `:435` setRuntimeStatus("running") / `PendingConfirmationStartupRecovery:101-148`（running/waiting_user → error） |
| **子 agent 子会话** | 同上 | **已能 RESUME**（重发 `[Resume from restart] Continue your previous work.`） | `SubAgentStartupRecovery:66-127`（扫 `t_sub_agent_run` RUNNING → 重提 chatAsync） |
| **ACP cc/codex run** | cc 进程是内存态 CompletableFuture，不持久化 | **被标 ERROR**（cc 进程丢，子会话被通用恢复标 error），无法重连 | `AcpAgentRunner`（subAgentExecutor + 短命 cc 进程，无 ACP 专属 startup recovery） |
| **用户定时任务** | 任务定义在 `t_scheduled_task`，但 in-flight 执行不 checkpoint | **丢一次**，重启后按 cron 下次重新触发（可接受） | `UserTaskScheduler.onApplicationReady:108-118`（从 DB 重注册 schedule） |
| **Workflow / flywheel run** | run/step 状态落 `t_flywheel_run` + `t_flywheel_run_step`；journal 有 replay 能力 | **卡死**：无 startup recovery，留在 `status=running` 直到人工干预 | `FlywheelRunEntity:49-60`（pending/running/completed/error/paused）/ `WorkflowRunnerService`（无 startup 恢复）/ journal replay 仅用于手动 pause/resume gate |

### 已有可复用积木

- **消息历史加载**：`SessionService.getFullHistory` + engine 从持久化 messages 继续（ask/confirmation 恢复路径已用）。
- **子 agent 恢复范式**：`SubAgentStartupRecovery` 已示范"查 runtimeStatus → 重提 chatAsync 续跑"的触发模式 —— 根会话可推广同款。
- **Workflow journal + replay**：`WorkflowEvaluator` + `JournalCache` 支持重放 JS 到 resume point（现仅手动 gate 用，未接 crash 恢复）。
- **trace 续连**：`SessionEntity.activeRootTraceId` 在恢复时保 trace 链路（子 agent 恢复已用）。

### ⚠️ 现状里发现的矛盾（设计必须先解决）

`PendingConfirmationStartupRecovery`（phase `MIN_VALUE+100`，**web 起来前**先跑）会把所有 `running`/`waiting_user` 会话**标 error**；而 `SubAgentStartupRecovery`（`ApplicationRunner` 默认 order ~0，**后跑**）才尝试 resume。两个 handler 操作不同模型（前者扫 `t_session.runtime_status`，后者扫 `t_sub_agent_run`）。**疑似**前者先把子会话 runtimeStatus 翻成 error，导致后者的 "running→resume" 分支很少命中（实际走 error→replay-hook）。实现前**必须实测确认这两个 startup handler 的真实交互顺序与效果**，否则推广 resume 会和现有 error-标记互相打架。

## 问题 / Gap

1. **根会话**在跑时重启 → 静默变 error，用户的任务凭空消失，无恢复。
2. **Workflow run** 重启 → 永久卡 `running`，需人工。
3. **ACP cc/codex** 重启 → cc 进程不可重连，只能从头或弃。
4. 三个 startup recovery 行为不统一（子 agent resume / 根会话 error / workflow 卡死），缺统一的"重启对账 + 恢复策略"层。

## 目标

重启后，**在跑的任务有确定的、统一的恢复语义**：能续则续（resume），不能续则干净收尾 + 明确告知（不静默 error、不永久卡死）。**不追求 mid-tool 精确续点**（内存态决定了做不到无副作用精确续），追求**到最近持久化边界的会话级恢复**（复用子 agent 已验证的"重发 Continue"范式）。

## 范围分期（建议；设计阶段 ratify）

- **Phase 1 — 根 agent-loop 会话恢复**（最高价值、最可行）✅ **用户 2026-06-25 拍优先做**：推广 `SubAgentStartupRecovery` 范式到根会话；统一/协调 `PendingConfirmationStartupRecovery`，把"无脑标 error"改成"可恢复的标记为待恢复 → 重提 chatAsync 续跑；orphan tool_use 仍需补 tool_result 保不变量"。
- **Phase 2 — Workflow run 恢复**：加 startup recovery，利用 journal+replay 从最近 step 续；或至少把卡死的 running 干净标 error/可重跑。
- **Phase 3 — ACP cc/codex**（最难、外部进程）：cc 进程不可重连 → 现实策略是"标记中断 + 可选从原始 prompt 在新 worktree 重起"，而非真续点。低优先。
- **范围外**：用户定时任务（已可接受：下次 cron 重跑）。

## 设计决策点（HARD-GATE，设计文档必答）

1. **无 mid-loop checkpoint → resume 会从最近持久化边界重放当前轮**，可能重跑一个有副作用的 tool（幂等性）。子 agent 现用"重发 Continue 让 LLM 重新决策"规避精确续点 —— 是否所有类型都接受这个粒度？
2. **崩溃 vs 合法 error vs waiting_user 的区分**：只 resume "崩溃中断的 running"；合法 error 不 resume；waiting_user 保持等待不自动续。靠什么字段判定（需不需要新增"心跳/lease"或"in-flight 实例标记"列）？
3. **resume 风暴 / 死循环防护**：一个任务恢复后又把 server 搞崩 → 反复 resume。需 resume 次数上限 + 退避。
4. **两个 startup handler 的统一**（见上方矛盾）：合并成单一"重启对账"入口，定义清晰的处置矩阵（resume / 补 orphan / 标 error / 标 cancelled）。
5. **多实例安全**：若将来多实例，重启对账不能把"另一个活实例正在跑"的会话误判为孤儿并 resume（需 lease/owner-instance 概念）。

## 验收点（初稿，设计阶段细化）

- AC1：根会话在跑时 kill+重启 → 自动恢复继续（不再静默 error）；orphan tool_use↔tool_result 不变量保持。
- AC2：合法 error 会话重启后**不**被错误 resume；waiting_user 会话保持等待。
- AC3：workflow run 重启后不再永久卡 running（续跑或干净收尾二选一，明确）。
- AC4：resume 有次数上限，崩溃循环不会无限恢复。
- AC5：恢复路径保持 trace 续连（activeRootTraceId）。
- AC6（范围外确认）：定时任务行为不变（下次 cron 重跑）。

## 风险 / footgun

- **核心文件红灯**：触碰 `AgentLoopEngine` / `ChatService` / `SessionService` → 必走 **Full pipeline + HARD-GATE 设计**（见 [`pipeline.md`](../../../../.claude/rules/pipeline.md) 红灯 + [`think-before-coding.md`](../../../../.claude/rules/think-before-coding.md) HARD-GATE）。
- **持久化字节一致不变量**：resume 重建内存 messages 必须跟持久化字节一致，否则对账 dup-append（见 [`persistence-shape-invariant.md`](../../../../.claude/rules/persistence-shape-invariant.md)）。
- **rewrite identity 列**：若 resume 走 rewrite 路径，注意 trace_id 等 identity 列 preserve（见 [`identity-column-on-rewrite.md`](../../../../.claude/rules/identity-column-on-rewrite.md)）。
- **compact 不变量**：恢复时若触发压缩，tool_use↔tool_result 配对 / boundary 不能破（compact-reviewer 8 条不变量）。
- **幂等**：副作用 tool（Bash、外发渠道、PR）重放风险 —— 设计需明确"恢复时哪些不重放"。

## pipeline

**Full**（红灯：核心文件 + 多不变量协议 + 跨子系统）。**Plan 阶段强制启用**（多种合理实现方向 + 迁移范围不明 + 设计决策点多）。reviewer：java-reviewer + java-design-reviewer（新增"重启对账"抽象层）+ compact-reviewer（若恢复触发压缩）+ database-reviewer（若新增 lease/owner 列或迁移）。Phase Final 必做**真重启 E2E**（kill 中途任务 → 重启 → 验恢复）。

## 关联

- 现有积木：`SubAgentStartupRecovery` / `PendingConfirmationStartupRecovery` / `SessionService.getFullHistory` / Workflow `JournalCache` + `WorkflowEvaluator` / `SessionEntity.activeRootTraceId`
- 相关需求：ACP cc/codex 恢复与 [ACP-EXTERNAL-AGENT](../../active/2026-06-19-ACP-EXTERNAL-AGENT/index.md) 交叠（cc 进程不可重连的现实约束）
- 压缩交互：[COMPACT-IDEMPOTENCY-BOUNDARY-FIX](../../active/2026-06-19-COMPACT-IDEMPOTENCY-BOUNDARY-FIX/index.md)（恢复若触发压缩）
