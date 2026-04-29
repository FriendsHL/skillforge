# MSG-1 消息类型化 + ask_user 持久化

---
id: MSG-1
mode: full
status: draft
priority: P1
risk: Full
created: 2026-04-29
updated: 2026-04-29
---

## 摘要

把目前散落在"字符串 marker + 内存 latch"上的两类非常规消息（subagent 返回、ask_user 请求）正规化进消息流：在 `t_session_messages` 增加 `message_type` 字段，前端按 type 分发渲染；同时把 ask_user 卡片从瞬态 React state 转成可持久化、可重连恢复、可标记 answered 的消息行。

合并解决 [BUG-30](../../../bugs.md)（subagent 返回被渲染成用户消息）和 [BUG-31](../../../bugs.md)（ask_user 卡片在 WS 重连后丢失）。

## 当前状态

**草稿，待 2026-04-30 讨论。** 改动横跨 core 引擎、server 持久化、Flyway migration、WS 协议、dashboard 渲染，触碰核心文件清单（`ChatService` / `SessionMessageRepository` / `PendingAskRegistry` / `ChatWebSocketHandler` / `Chat.tsx` / `useChatWsEventHandler.ts`），按 SkillForge 强制规则走 Full Pipeline。

讨论时需要先收敛下面"待决策点"，再决定要不要进入 mrd / prd / tech-design。

## 关联 Bug

- **BUG-30** P1 — Subagent 返回（`[TeamResult ...]`）被渲染成用户消息
  - 根因：`SubAgentRegistry.maybeResumeParent` 调 `chatService.chatAsync(parent, payload, userId)`，把 TeamResult 当 user-role 注入父 agent loop；这是 LLM 协议要求（user/assistant 交替），但前端只看 role 就把它当真用户消息。
- **BUG-31** P1 — ask_user 卡片在 WS 重连 / 页面切换后丢失
  - 根因：ask 卡片状态只在 Chat.tsx React `useState`；后端 `PendingAskRegistry` 只存 `sessionId + latch + answer`，**不存 question/options/allowOther**。前端没有任何数据源能恢复出卡片。

## 关键改动方向（候选，待讨论）

### 1. `t_session_messages` 增加 `message_type`

新增字段 `message_type VARCHAR(32) DEFAULT 'normal'`，初始值域：

| message_type | 含义 | role | 来源 |
| --- | --- | --- | --- |
| `normal` | 普通用户/assistant 消息 | user / assistant | 现有路径 |
| `team_result` | TeamCreate 子 agent 返回 | user | `SubAgentRegistry.enqueueForParent` |
| `subagent_result` | 非 collab 的 subagent 返回 | user | 同上 |
| `ask_user` | Agent 发起的 ask_user 卡片 | assistant（或 system） | `AgentLoopEngine` ask_user 路径 |

新增 Flyway `V38__add_message_type.sql`。

### 2. ask_user 持久化 + answered 标记

- `t_session_messages` 增加 `answered_at TIMESTAMPTZ NULL`（仅对 `message_type='ask_user'` 行有意义）
- `ChatController.answer` 在 `pendingAskRegistry.complete()` 之外，再 update 对应消息行的 `answered_at`
- 前端渲染 ask_user 消息行时：`answered_at != null` → disabled 灰显；`answered_at == null` → 可交互

### 3. PendingAskRegistry 双写 / 同步

- 内存 latch 仍然保留（给 await 阻塞用）
- 进 ask_user 时同时写一行 `message_type='ask_user'` 的消息（content 存 question/options/allowOther/askId 的 JSON）
- 服务重启 → latch 没了 + DB 行还在 → 用户点 answer endpoint 找不到 askId → 返回 `410 GONE` → 前端把卡片置 `expired` 状态

### 4. 前端 normalizeMessages + 渲染分发

- 按 `messageType` 分发到不同组件：`<MessageBubble>` / `<TeamResultBubble>` / `<PendingAskCard>`（消息流内嵌版）
- ask_user 卡片不再依赖 React `useState`，由 messages_snapshot 驱动

## 待决策点（明天讨论）

1. **持久化粒度**：ask_user content 用 JSON 存进 `t_session_messages.content`，还是单开 `t_pending_asks` 关联表？前者简单但 schema 复杂；后者干净但消息流渲染要做 join 或两次查询。
2. **role 怎么定**：ask_user 消息的 role 是 `assistant`（agent 发的）、`system`（控制流）、还是新增 `tool`？影响 LLM 重发时 conversation history 长什么样。
3. **answered 后保留不保留卡片**：disabled 灰显 vs. 折叠成一行简短摘要 vs. 答复后整行替换为普通 user message。三种选项各有 UX 权衡。
4. **`team_result` 是否值得单独类型**：现在前端能识别 `[TeamResult ...]` marker；如果 BUG-31 走持久化方案，`message_type` 字段无论如何都要加，那 `team_result` 顺手归一是接近零成本；但要不要在这一波也修 BUG-30，还是只解决 ask_user，BUG-30 留给后面（避免范围爆炸）。
5. **历史消息回填**：现有 `t_session_messages` 全部默认 `'normal'`，对历史 TeamResult 行就识别不出来。要不要写个一次性 ETL 把 `[TeamResult` 开头的内容回标？
6. **migration 顺序**：跟 P1-D / P9-2 的 schema 改动怎么排队？OBS-1 已经吃掉 V33-V37，下一个空位是 V38。

## 风险点

- **核心文件触碰**：`ChatService`（消息持久化）、`SessionMessageRepository`、`PendingAskRegistry`、`ChatWebSocketHandler`、`AgentLoopEngine`（ask_user 路径） —— 全部在核心文件清单 / known footguns 范围。
- **不变量**：tool_use ↔ tool_result 配对、user/assistant 交替；新增 `message_type` 不能破坏 LLM 重建 conversation 的逻辑。
- **向后兼容**：现有消息行默认 `message_type='normal'`，前端 normalizeMessages 要默认走 normal 分支，老消息不能漏渲染。
- **重启 / 崩溃**：latch 重启即丢，DB 行还在 → 前端"看到卡片但点不动"的 410 GONE 路径必须有清晰 UX。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | 待建（明天讨论后） |
| PRD | 待建 |
| 技术方案 | 待建 |
| 交付 | - |
| 关联 bug | [BUG-30 / BUG-31](../../../bugs.md) |
