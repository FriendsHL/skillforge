# MSG-1 消息类型化 + ask_user 持久化

---
id: MSG-1
mode: full
status: done
priority: P1
risk: Full
created: 2026-04-29
updated: 2026-04-30
delivered: 2026-04-30
---

## 摘要

把目前散落在"字符串 marker + 内存 latch"上的非常规消息正规化进消息流：在 `t_session_message` 增加 UI 语义型 `message_type` 字段，前端按 type 分发渲染；同时把 `ask_user` 和审批类阻断卡片从弹框 / 临时 React state / 阻塞线程等待，改成消息流内联卡片 + 非阻塞 `waiting_user` + continuation 恢复。

合并解决 [BUG-30](../../../bugs.md)（subagent 返回被渲染成用户消息）和 [BUG-31](../../../bugs.md)（ask_user 卡片在 WS 重连后丢失）。

## 当前状态

**done，2026-04-30 交付**（commit `543a60e` `feat(chat): inline human interaction cards`，V40 migration `session_message_ui_types`）。

实际落地：
- `t_session_message` 加 `message_type` (32) + `answered_at` (TIMESTAMPTZ) + `control_id` 列 + `idx_session_message_control` 索引（V40 migration）
- core 引擎 `AgentLoopEngine` (+361 行) + 新建 `InteractiveControlRequest.java`（104 行 control request 抽象）+ `LoopResult` 加 control 字段
- server `ChatService` (+221 行) 实现非阻塞 waiting_user + continuation 恢复 + 释放 worker；`ChatController` / `ChannelCardActionController` / `DefaultConfirmationPrompter` 配套
- 前端 `ChatWindow` (+123 行) + `Chat.tsx` 加 inline 卡片渲染分发；`PendingAskCard` 改造；`useChatWsEventHandler` 加 control message 重建（rawMessages 写路径，非阻塞 ask_user / confirmation 复活）
- 测试 `AgentLoopEngineToolUseInvariantTest`

解决 BUG-30 / BUG-31（详见下"关联 Bug"）—— 已 closed。

文档 governance 修订（2026-05-02 补回写）：本期实现完成时未及时更新 status 字段，今天 cleanup 一并修订。

## 关联 Bug

- **BUG-30** P1 — Subagent 返回（`[TeamResult ...]`）被渲染成用户消息
  - 根因：`SubAgentRegistry.maybeResumeParent` 调 `chatService.chatAsync(parent, payload, userId)`，把 TeamResult 当 user-role 注入父 agent loop；这是 LLM 协议要求（user/assistant 交替），但前端只看 role 就把它当真用户消息。
- **BUG-31** P1 — ask_user 卡片在 WS 重连 / 页面切换后丢失，且等待用户期间占用 chatLoop 线程
  - 根因 1：ask 卡片状态只在 Chat.tsx React `useState`；后端 `PendingAskRegistry` 只存 `sessionId + latch + answer`，**不存 question/options/allowOther**。前端没有任何数据源能恢复出卡片。
  - 根因 2：`AgentLoopEngine.handleAskUser()` 在 `chatLoopExecutor` worker 内调用 `CountDownLatch.await()`，默认最长 30 分钟；等待期间 worker 被占用，`cancelRequested` 也不会主动释放该 latch。

## 已定产品方向

### 1. `t_session_message` 增加 UI 语义型 `message_type`

新增字段 `message_type VARCHAR(32) DEFAULT 'normal'`，初始值域：

| message_type | 含义 | role | 来源 |
| --- | --- | --- | --- |
| `normal` | 普通用户/assistant 消息 | user / assistant | 现有路径 |
| `team_result` | TeamCreate 子 agent 返回 | user | `SubAgentRegistry.enqueueForParent` |
| `subagent_result` | 非 collab 的 subagent 返回 | user | 同上 |
| `ask_user` | Agent 发起的 ask_user 卡片 | assistant（或 system） | `AgentLoopEngine` ask_user 路径 |
| `confirmation` | 框架发起的审批卡片 | assistant（或 system） | install / CreateAgent / UpdateAgent 拦截路径 |

新增 Flyway migration 使用当前下一个版本号（P9-2 已占用 V39，预计为 V40）。

### 2. ask_user 持久化为消息流内联卡片

- `ask_user` 不是弹框，而是一条 assistant 侧特殊消息，直接出现在 Chat 消息流。
- `t_session_message` 增加 `answered_at TIMESTAMPTZ NULL`（仅对 `message_type='ask_user'` 行有意义）。
- 前端渲染 ask_user 消息行时：`answered_at == null` 可交互；`answered_at != null` 折叠为“已回答 / 已替代 / 已过期”历史摘要，不可再次点击。

### 3. loop 非阻塞停止与恢复

- 模型调用 `ask_user` 或框架触发审批后，后端持久化卡片、广播消息快照 / 状态，当前 loop 结束，session 进入 `waiting_user`。
- 不再在 `chatLoopExecutor` worker 内 `CountDownLatch.await()` 等待用户。
- 用户点击 ask 卡片提交答案时，补齐原 ask_user 的 `tool_result` 并开启新的 trace loop。
- 用户直接输入新消息时，当前未回答 ask 卡片标记为 `superseded`，新消息按普通 user turn 继续。
- 审批卡片必须显式点击 approve / deny，自然语言不能隐式 approve。

### 4. 前端 normalizeMessages + 渲染分发

- 按 `messageType` 分发到不同组件：`<MessageBubble>` / `<TeamResultBubble>` / `<PendingAskCard>`（消息流内嵌版）
- ask_user 卡片不再依赖 React `useState`，由 messages_snapshot 驱动

## 技术方案核心决策

1. 阻断式交互卡片统一为“持久化 continuation + 释放 worker + 用户显式卡片动作开启新 trace loop 恢复”。
2. `ask_user` 点击卡片时补齐原 `toolUseId` 的 `tool_result`；直接主输入框发消息时标记 `superseded`，按普通 user turn 继续。
3. install / mutation approval 是框架拦截工具后触发，点击 approve / deny 后补齐工具结果；自然语言不能隐式 approve。
4. 阻断式卡片行使用 `msg_type='SYSTEM_EVENT'`，`message_type='ask_user'` 或 `confirmation`，pending 时从 LLM conversation 重建中排除，由 continuation 恢复时生成 provider 消息。
5. 历史 `[TeamResult ...]` 不做全量回写；新消息写 `message_type`，前端对老 marker 做兼容识别。
6. migration 版本号以当前主干最高 Flyway 版本为准；当前设计预期为 V40。

## 风险点

- **核心文件触碰**：`ChatService`（消息持久化）、`SessionMessageRepository`、`PendingAskRegistry`、`ChatWebSocketHandler`、`AgentLoopEngine`（ask_user 路径） —— 全部在核心文件清单 / known footguns 范围。
- **不变量**：tool_use ↔ tool_result 配对、user/assistant 交替；新增 `message_type` 不能破坏 LLM 重建 conversation 的逻辑。
- **线程资源**：当前阻塞模型会占用 `chatLoopExecutor` worker；目标方案必须证明 waiting_user 不再长期占 worker，且 cancel / timeout 能收敛 session 状态。
- **向后兼容**：现有消息行默认 `message_type='normal'`，前端 normalizeMessages 要默认走 normal 分支，老消息不能漏渲染。
- **重启 / 崩溃**：latch 重启即丢，DB 行还在 → 前端"看到卡片但点不动"的 410 GONE 路径必须有清晰 UX。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | - |
| 关联 bug | [BUG-30 / BUG-31](../../../bugs.md) |
