# TEAM-COORDINATOR-FOUNDATION PRD

---
id: TEAM-COORDINATOR-FOUNDATION
status: deferred
owner: youren
priority: P0
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-12
updated: 2026-05-12
---

## 摘要

落 SkillForge 多 agent 协作的可见性基础：(1) 把 Main Assistant agent rename 为 Coordinator；(2) `AgentLoopEngine` 在每个 loop iter 开始 / 每个 tool 开始结束自动广播 `collab_member_progress` 事件，事件含 iter / currentTool / tokensUsed；(3) Dashboard 新增 `/collab-runs/:id` 树状看板，订阅事件实时显示 Coordinator + 子 agent 的执行状态。

> 状态说明：本 PRD 曾在 2026-05-12 达到 `prd-ready`，但同日因用户判断当前架构下 ROI 不足而整体转为 `deferred`。保留本文作为后续复活时的需求记录。

## 目标

1. **Coordinator 改名落地**：Main Assistant → Coordinator，agent 名 + system prompt + dashboard 文案统一
2. **进度事件自动注入**：`AgentLoopEngine` 在 collab run 上下文中（即 `LoopContext.collabRunId != null`）自动出 `collab_member_progress` 事件，覆盖 loop iter 切换 + tool 调用生命周期，**不引入新工具**让 LLM 主动上报
3. **协作看板**：Dashboard `/collab-runs/:id` 显示树状结构（Coordinator → 各子 agent），每个子 agent 显示 status / 当前 iter / 当前 tool / 已用 tokens，WebSocket 实时刷新
4. **stuck 告警**：子 agent 超过 30 秒无 progress 事件，看板节点变 warning 色

## 非目标

明确不在本期范围：

- ❌ 失败重试 (`maxRetries` / backoff) — SSE delta 重复风险，Anthropic 自己不做
- ❌ `outputSchema` JSON Schema 结果验证 — 对 agent 任务太脆
- ❌ 声明式 `TeamPlan` YAML 拓扑 — Coordinator 运行时决定更灵活
- ❌ `AgentCheckpoint` 新表 — 已有 `t_session_message` + `RecoveryPayloadBuilder` (P9-5)
- ❌ 单独的 reviewer agent seed — 用户明确不做；REQ-7 用 session analyzer 模式覆盖
- ❌ TeamCreate 新增 `timeoutSeconds` / `consumes` / `reviewer` 等参数 — 留给后续 REQ-3/4/5
- ❌ Agent roster (`allowed_sub_agents`) — REQ-6 范畴
- ❌ Coordinator 自动触发 reviewer / outcome rubric loop — 不做
- ❌ Steer / interrupt UI（运行中给子 agent 发消息 / stop）— REQ-3 范畴，本期看板只展示，不交互（除点开子 session 查看详情）
- ❌ 历史 collab run 的 progress 数据回填 — 本期只对新 collab run 生效

## 用户流程

### 流程 1：Coordinator 派 3 个并行 research agent

1. 用户在 Coordinator session 提问"调研 JWT / RBAC / 前端方案"
2. Coordinator 调 3 次 `TeamCreate(handle="research-jwt"/...)`，触发 `CollabRunService.createRun` + 3 次 `spawnMember`
3. 前端收 `collab_member_spawned` × 3，看板树状视图渲染 4 节点（Coordinator + 3 research）
4. 各子 agent 进入 loop，**自动**发 `collab_member_progress {iter:1, currentTool:"WebSearch", tokensUsed:1200}` 等
5. 看板节点实时更新当前 iter / tool / tokens
6. 任一子 agent ≥30s 无 progress → 节点描边 warning
7. 子 agent 完成 → `collab_member_finished`，节点变绿色 + 简短 outputSummary
8. 全员完成 → `collab_run_status: COMPLETED`，看板顶部状态 banner 切换

### 流程 2：用户从看板看历史 collab run

1. 用户从某 Coordinator session 详情页点"协作详情"链接，进入 `/collab-runs/:id`
2. 看板渲染整个 collab run 的最终拓扑（已完成态）
3. 每个子 agent 节点可点击 → 跳转该子 agent 的 session 详情页查看完整 chat history

### 流程 3：rename 平滑过渡

1. 部署后启动 server，Flyway 跑 V70 migration：`UPDATE t_agent SET name='Coordinator' WHERE name='Main Assistant'`
2. 既有 collabRunId / sessionId 的 leader agent 引用不变（依赖 agentId 非 name）
3. dashboard 既有页面文案 "Main Assistant" 字串 → "Coordinator"
4. 历史 chat message 中的 "Main Assistant" 字样**不追溯改**

## 功能需求

### F0 — App.tsx 路由注册（r1 W12）

- F0.1 `App.tsx` 路由配置加 `<Route path="/collab-runs/:collabRunId" element={<CollabRunDashboard />} />`

### F1 — Agent rename

- F1.1 V70 migration 更新 `t_agent` row：`Main Assistant` → `Coordinator`，**不**改 id
- F1.2 该 row 的 `behavior_rules` JSON 内自描述文本（如"你是主 Agent"）改"你是 Coordinator"
- F1.3 dashboard 所有 "Main Assistant" 硬编码字串改"Coordinator"
- F1.4 server 端 init / seed / 日志中所有 "Main Assistant" 引用改"Coordinator"

### F2 — 进度事件

- F2.1 `ChatEventBroadcaster` 新增方法 `collabMemberProgress(collabRunId, handle, sessionId, iter, currentTool, tokensUsed, status)` 和 `collabMemberStarted(collabRunId, handle, sessionId)`
- F2.2 `AgentLoopEngine.runInternal` 在 `LoopContext.collabRunId != null` 时：
  - 进入第一个 loop iter 前发 `collab_member_started`
  - 每个 loop iter 开始时发 `collab_member_progress` (含 iter, tokensUsed_so_far, currentTool=null)
  - 每个 tool 开始执行时发 `collab_member_progress` (含 currentTool="ToolName")
  - 每个 tool 结束时发 `collab_member_progress` (currentTool=null)
- F2.3 进度事件**节流**：见 tech-design D6（同 sessionId 同状态 1 秒内不重复发）
- F2.4 非 collab 上下文（普通 chat）**不**发这些事件 —— 不增加单 session 流量

### F3 — Dashboard 协作看板

- F3.1 新增路由 `/collab-runs/:collabRunId`，新文件 `CollabRunDashboard.tsx`
- F3.2 进入时 fetch `GET /api/collab-runs/{id}/snapshot` 拿当前完整拓扑（Coordinator + 所有子 agent 当前状态）
- F3.3 WebSocket 订阅该 collabRunId 的事件，增量更新树节点
- F3.4 每个子 agent 节点显示：handle / agentName / status badge / 当前 iter / 当前 tool / tokensUsed / 跑了多少秒
- F3.5 节点 status 视觉态：running 蓝 / completed 绿 / failed 红 / stuck (≥30s 无 progress) warning 橙
- F3.6 节点点击 → 路由 `/sessions/:childSessionId`（已有页面）
- F3.7 顶部 banner 显示 collab run status (RUNNING / COMPLETED / CANCELLED) + 总耗时
- F3.8 现有某处入口跳转看板：在 Coordinator session 页面顶部加 "查看协作详情" 按钮（仅当 session.collabRunId != null 时显示）

### F4 — 后端 snapshot API

- F4.1 新增 `GET /api/collab-runs/{id}/snapshot`：返回 `{ run: {...CollabRunEntity 字段}, members: [{handle, sessionId, agentId, agentName, runtimeStatus, currentIter, currentTool, tokensUsed, startedAt, lastProgressAt}, ...] }`
- F4.2 currentIter / currentTool / tokensUsed / lastProgressAt 持久化到 `t_session` 新增列（见 tech-design 数据模型）—— 否则 server 重启后看板回到空白

## 验收标准

### 后端

- [ ] V70 migration 成功：`SELECT name FROM t_agent WHERE id=<原 Main Assistant id>` 返 `Coordinator`
- [ ] V70 自验通过：`behavior_rules` 不再包含 "主 Agent" 字符串（migration 内 DO $$ 块）
- [ ] `mvn -pl skillforge-server -am test` 全绿（baseline 1290 + 新增测试 ≥11 case）
- [ ] `ChatEventBroadcaster` 单测覆盖 `collabMemberProgress` / `collabMemberStarted` 4+ case；progress 事件 JSON **无** `runtimeStatus` 字段
- [ ] `AgentLoopEngine` 集成测：collab 上下文（`collabRunId != null && handle != null`）下跑一个 mock tool 的 agent，断言事件序列含 started → progress(iter=1) → progress(currentTool=X) → progress(currentTool=null) → finished
- [ ] `AgentLoopEngine` 集成测：**非** collab 上下文（collabRunId=null）跑一遍，断言**无**任何 collab_member_* 事件
- [ ] `AgentLoopEngine` 集成测：collabRunId 非空但 handle 为 null（异常路径）→ 断言**无** progress 事件（双条件 isCollab 防御）
- [ ] `CollabProgressWriteService` IT 用 testcontainers 真 Postgres：executor 线程内 `@Transactional` 正确建立，UPDATE 生效
- [ ] `CollabProgressPersister` 单测：5s 节流 + Caffeine TTL evict + `evictSession` 主动清理
- [ ] `ProgressThrottler` 单测：1 秒内 10 次相同 (sessionId, iter, tool) → emit ≤1；token-only 变化 1s ≤1；不同 (iter,tool) 不互相节流
- [ ] snapshot API IT：起 collab → spawn 2 子 → curl snapshot 返 2 个 member 节点 + status
- [ ] snapshot 鉴权 IT：跨用户调用 → 403
- [ ] `ChatService.chatAsync` preCtx wiring 单测：collab session 入 chatAsync 后 preCtx 含 collabRunId + handle；非 collab session 两字段 null
- [ ] `AgentRoster.resolveHandle` 单测：sessionId → handle 反查正确 / 不存在返 null
- [ ] `last_progress_at` 类型测试：JPQL UPDATE 写入后 SELECT 返 `Instant` 而非 `LocalDateTime`（Hibernate 6 + Postgres TIMESTAMPTZ 路径）

### 前端

- [ ] `cd skillforge-dashboard && npm run build` EXIT=0
- [ ] `npx tsc --noEmit` 通过
- [ ] `App.tsx` 路由注册 `/collab-runs/:collabRunId`
- [ ] `useChatWsEventHandler` 扩展 dispatch list 含 `collab_member_progress` / `collab_member_started`，单测覆盖
- [ ] `useCollabRunStore` zustand reducer 单测：spawned + started + progress + finished 序列后 state 正确
- [ ] `CollabRunDashboard.tsx` 单测：mock snapshot fetch + store push → 节点 status / currentTool 正确更新
- [ ] **timer cleanup**：mount → unmount，断言 stuck detection `setInterval` 被 `clearInterval`（无定时器泄漏）
- [ ] TS 类型 compile-time 检查：reducer 内 `CollabWsEvent` discriminated union exhaustive，无 `any` 类型
- [ ] `fetchCollabRunSnapshot` 返回 `CollabRunSnapshot` 严格类型，不是 `any`
- [ ] dashboard 全局 grep "Main Assistant" 返 0 命中（除 V70 migration 文件 + archive 文档）

### 浏览器

- [ ] `npx agent-browser goto http://localhost:5173/collab-runs/<test-run-id>` 渲染树状结构
- [ ] `eval "document.querySelectorAll('.collab-node').length"` 等于实际 member 数 + 1（含 Coordinator）
- [ ] mock 一个子 agent 30s 无 progress → snapshot DOM `.collab-node.stuck` 出现
- [ ] 子节点点击 → 路由跳转到对应 sessionId

### 数据库

- [ ] V70 跑完 `SELECT name FROM t_agent ORDER BY id` 列表里有 Coordinator 无 Main Assistant
- [ ] V70 跑完 `SELECT behavior_rules FROM t_agent WHERE name='Coordinator'` 不再含 "主 Agent" 字符串
- [ ] `t_session` 新列：`current_iter INT` / `current_tool VARCHAR(64)` / `tokens_used BIGINT` / **`last_progress_at TIMESTAMP WITH TIME ZONE`**（**不是** `TIMESTAMP`，r1 W2）全部 nullable
- [ ] 既有 row 上述新列均为 NULL（无 backfill 必要）
- [ ] `idx_session_collab_run` 索引存在

## 依赖

- 现有 `CollabRunService` / `AgentRoster` / `SubAgentRunSweeper` 不动业务逻辑，只接事件
- 现有 `ChatEventBroadcaster` 接口扩展，**不破坏** existing channel 实现
- Flyway 当前最新 V69，本需求占 V70
- 触碰核心文件清单：`AgentLoopEngine.java` → 红灯，按 [`pipeline.md`](../../../../.claude/rules/pipeline.md) 走 Full 档对抗 reviewer

## 验证预期

- 后端：`mvn -pl skillforge-core,skillforge-server -am test` BUILD SUCCESS；新增 unit / IT ≥10 case
- 前端：`npm run build` 0 错；新增 `CollabRunDashboard.test.tsx` ≥3 case
- 浏览器：起 server + dashboard，触发一个真 Coordinator + 2 子 agent 的 collab run，full path 验证（spawn → started → progress 多次 → finished）
- 数据库：psql 直连 verify V70 跑过 + t_session 新列存在
- 回归：跑一个无 collab 的普通 chat session，确认无任何 collab 事件产生（性能不退化）
