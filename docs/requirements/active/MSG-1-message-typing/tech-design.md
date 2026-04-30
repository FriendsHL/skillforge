# MSG-1 技术方案

---
id: MSG-1
status: design-draft
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
prd: ./prd.md
created: 2026-04-30
updated: 2026-04-30
---

## 决策摘要

`ask_user` 和 install / agent mutation approval 都属于“阻断式交互卡片”：模型或框架需要 human 决策后才能继续当前工具链路。底层处理方式应该统一为“持久化 continuation + 释放 worker + 用户显式操作后开启新的 trace loop 恢复”。

当出现阻断卡片时，后端持久化卡片和它对应的 continuation，将 session 标记为 `waiting_user`，并从当前 loop 返回，不再阻塞 `chatLoopExecutor` worker。用户点击卡片后，后端补齐对应的 `tool_result` 或执行被拦截的工具，然后开启新的 trace loop 继续。

主输入框自然语言不是显式卡片动作：对 `ask_user`，它表示“用户用后续消息继续/替代了这个 ask”，pending ask 标记为 `superseded`，新消息作为普通 user turn 开启新 trace；对 install / mutation approval，它不能隐式 approve，避免用户一句自然语言触发危险工具执行。

## 方案选型

### 方案 A：统一阻断卡片 continuation

阻断卡片出现时，持久化卡片和 continuation，停止当前 loop 并释放 worker。用户点击卡片时，按卡片类型恢复：

- `ask_user`：补齐原 `ask_user` tool call 的 `tool_result`，开启新 trace loop 继续。
- install / mutation approval：根据 approve / deny 补齐对应工具链路，approve 时执行被拦截工具，deny 时返回拒绝 `tool_result`，开启新 trace loop 继续。

用户在主输入框自然语言输入时，不当作卡片点击；`ask_user` 标记为 `superseded` 后按普通 user turn 继续，审批卡片不能被自然语言 approve。

优点：

- 保留阻断式卡片的真实语义：用户明确操作的是上一轮卡片。
- 与当前阻塞式实现的模型语义一致，但不占 worker。
- 统一 `ask_user` 和审批卡片的生命周期、持久化、恢复、trace 表达。
- 浏览器刷新、WebSocket 重连、服务重启后仍可从 DB 恢复卡片。

缺点：

- 需要持久化 continuation 状态，复杂度高于纯 user turn 恢复。
- 需要严格保护 `tool_use` / `tool_result` 配对和 provider conversation reconstruction。

结论：采用此方案。

### 方案 B：ask_user 简化成新 user turn

模型调用 `ask_user` 后只持久化卡片，用户点击或直接输入都作为新的 user turn 继续，不补原 `tool_result`。

优点：

- 实现较简单。
- 不需要跨请求恢复 provider tool continuation。

缺点：

- 点击 ask 卡片和“普通发一条消息”的语义混在一起。
- 与审批卡片的处理方式不一致。
- 模型不会收到原始 ask_user 工具调用对应的字面 `tool_result`。

结论：不采用。用户已经确认阻断式卡片应该用同一类 continuation 处理。

### 方案 C：仅保留当前阻塞 latch

保留当前 `PendingAskRegistry.await()` / `PendingConfirmationRegistry.await()` 阻塞等待，只把卡片 UI 做持久化。

优点：

- 改动最小。
- 当前 `tool_result` continuation 语义无需大改。

缺点：

- 仍然占用 `chatLoopExecutor` worker。
- 服务重启后 latch 丢失，卡片和后端等待状态仍可能不一致。
- 不能真正解决 BUG-31 的资源占用和恢复问题。

结论：不采用。

## 范围

### 本轮包含

- 给 `t_session_message` 增加 UI / 控制语义字段。
- 新产生的 Team/SubAgent 结果写入 `message_type`。
- 将 `ask_user` 持久化为消息流内联卡片。
- 将 ask_user 从 `PendingAskRegistry.await()` 阻塞等待改成“持久化 continuation + 新 trace loop 恢复”。
- 在设计层统一 install / mutation approval 的阻断式卡片模型；实现时复用同一套持久化和恢复语义，不能用自然语言隐式 approve。
- 支持用户点击 ask 卡片回答，也支持直接输入 supersede pending ask 并恢复 `waiting_user` session。
- 更新 WebSocket snapshot 和前端消息 normalize，按 `messageType` 渲染。

### 本轮不包含

- 改变 install / mutation approval 的安全策略、匹配规则或审批文案。
- 改变模型是否调用 `ask_user` 的 prompt / policy。
- 对历史 session 行做全量结构重写。
- 引入通用 workflow engine。

## 数据模型

### Migration

新增 Flyway migration，版本号使用当前 mainline 后的下一个版本。2026-04-30 当前仓库已有 `V39__tool_result_archive.sql`，本方案预期为：

`skillforge-server/src/main/resources/db/migration/V40__session_message_ui_types.sql`

### `t_session_message` 字段

新增字段：

| 字段 | 类型 | 是否为空 | 默认值 | 用途 |
| --- | --- | --- | --- | --- |
| `message_type` | `VARCHAR(32)` | 否 | `'normal'` | UI / 控制语义类型，不替代 `msg_type`。 |
| `control_id` | `VARCHAR(64)` | 是 | null | 控制消息稳定 ID，例如 `askId`。 |
| `answered_at` | `TIMESTAMPTZ` | 是 | null | 一次性交互消息完成时间。 |

新增索引：

```sql
CREATE INDEX idx_session_message_control
    ON t_session_message(session_id, message_type, control_id);
```

`message_type` 值域：

| 值 | LLM 上下文 | UI 渲染 |
| --- | --- | --- |
| `normal` | `msg_type='NORMAL'` 且未 pruned 时进入上下文 | 现有 user / assistant 气泡 |
| `team_result` | 作为 user-role context 进入上下文 | Team result 卡片，不渲染成用户气泡 |
| `subagent_result` | 作为 user-role context 进入上下文 | SubAgent result 卡片，不渲染成用户气泡 |
| `ask_user` | pending 时不进入上下文；卡片点击后由 continuation 补 `tool_result` | 内联 ask 卡片 |
| `confirmation` | pending 时不进入上下文；卡片点击后由 continuation 补工具结果 | 内联审批卡片 |

阻断式卡片行使用 `msg_type='SYSTEM_EVENT'`。它对 UI 可见，但 pending 状态不直接进入 provider conversation reconstruction；真正恢复时由 continuation 生成对应的 provider 消息。

### 阻断卡片 Metadata

阻断卡片行存储：

- `role='assistant'`
- `content_json`：question / approval title 文本，便于简单 snapshot 展示
- `metadata_json`：

```json
{
  "controlId": "uuid",
  "interactionKind": "ask_user | confirmation",
  "toolUseId": "provider-tool-use-id",
  "toolName": "ask_user | Bash | CreateAgent | UpdateAgent",
  "question": "string or null",
  "context": "string or null",
  "options": ["string"],
  "allowOther": true,
  "state": "pending | answered | approved | denied | superseded | expired",
  "answer": "string or null",
  "answerMode": "card | direct_input | approval | denial | timeout | null",
  "continuation": {
    "mode": "tool_result | execute_then_tool_result | user_turn",
    "assistantToolUseMessageRef": "message id or serialized snapshot",
    "toolInput": {}
  },
  "answeredBySeqNo": 123
}
```

`answered_at IS NULL` 表示卡片仍可操作。`answered_at IS NOT NULL` 表示卡片已完成，前端折叠为“已回答 / 已批准 / 已拒绝 / 已替代 / 已过期”历史摘要。

## 后端流程

### Engine Pause

当前路径：

- `AgentLoopEngine.handleAskUser()` 注册 `PendingAskRegistry`。
- 广播 `ask_user`。
- 阻塞等待 `PendingAskRegistry.await()`。
- 返回 synthetic `tool_result`。
- install / mutation confirmation 也会注册 `PendingConfirmationRegistry` 并阻塞等待。

目标路径：

1. `AgentLoopEngine` 检测到阻断式交互：`ask_user`、install confirmation、CreateAgent / UpdateAgent confirmation。
2. 构造统一 `InteractiveControlRequest` payload，包含 `controlId`、`interactionKind`、`toolUseId`、`toolName`、展示字段和 continuation 字段。
3. 返回 `LoopResult.status='waiting_user'`，并携带 control payload。
4. 不在当前 worker 内调用 `PendingAskRegistry.await()` 或 `PendingConfirmationRegistry.await()`。
5. 当前 loop 结束，worker 释放。

如果模型在同一 assistant turn 同时发出多个阻断式交互，按当前互斥原则只允许一个进入 pending；其他工具不在本 turn 执行，恢复后由模型重新发起。

### ChatService 持久化

`ChatService.runLoop()` 收到 `LoopResult.status='waiting_user'` 时：

1. 持久化阻断卡片前已经完成的普通消息。
2. 追加一条 `SessionMessageEntity`：
   - `msgType=SYSTEM_EVENT`
   - `messageType=ask_user` 或 `confirmation`
   - `controlId=controlId`
   - `answeredAt=null`
   - metadata 使用上面的 control payload
3. 设置 session runtime status 为 `waiting_user`。
4. 广播 `session_status` 和 message snapshot。
5. async worker 返回。

ask_user / confirmation 不再需要内存 latch 作为恢复的唯一事实来源。Registry 可以作为过渡兼容保留到调用方迁移完成，之后收敛到 DB-backed continuation。

### 卡片动作接口

改造 `POST /api/chat/{sessionId}/answer`，并让 confirmation action 使用同一类 DB-backed control resolution：

1. 校验 session ownership。
2. 按 `(sessionId, messageType IN ('ask_user', 'confirmation'), controlId=controlId)` 查找 control 行。
3. 找不到返回 `404`。
4. 如果 `answeredAt` 已设置，返回 `409` 和当前状态。
5. 按卡片类型更新 metadata：
   - `ask_user` 点击：`state='answered'`、`answer=answer`、`answerMode='card'`、`answeredAt=now`
   - confirmation approve：`state='approved'`、`answerMode='approval'`、`answeredAt=now`
   - confirmation deny：`state='denied'`、`answerMode='denial'`、`answeredAt=now`
6. 创建新的 trace loop：
   - `ask_user`：基于 continuation 补原 `toolUseId` 的 `tool_result`，内容为用户答案。
   - confirmation approve：执行被拦截工具并产生对应 `tool_result`。
   - confirmation deny：直接产生拒绝 `tool_result`。
7. 设置 runtime status 为 `running`。
8. 广播更新后的 snapshot 和 status。

前端提交后会把卡片折叠为历史摘要，但后端仍是防重复提交的权威。

### 直接输入恢复

`POST /api/chat/{sessionId}/message` 收到用户输入时，如果 session 存在 pending `ask_user` 行：

1. 将最新 pending ask 行标记为：
   - `state='superseded'`
   - `answer=input`
   - `answerMode='direct_input'`
   - `answeredAt=now`
2. 只追加一次用户消息。
3. 用同一条用户消息启动正常 loop。

自然语言输入不能隐式 approve / deny `confirmation` 卡片。若 session 存在 pending confirmation，后端返回 `409` 并提示用户先处理审批卡片；不能执行被审批工具。

ask 行状态更新和 user message 追加必须放在同一个 public service-layer transaction 中，避免用户双击发送时重复恢复。

### Timeout / Cancel

因为没有 worker 阻塞，阻断卡片 timeout 变成状态清理问题，而不是 latch timeout。

初版策略：

- 不自动 expire pending ask。
- confirmation 保留现有业务 timeout 语义，但 timeout 由 DB 状态和恢复任务处理，不靠 worker 阻塞。
- 用户 cancel session 时，将 pending control 行标记为 `state='expired'`，设置 `answered_at`，并将 session 置为 `idle`。
- startup recovery 保留 `waiting_user` session 中的 pending control 行为可点击状态，因为 DB 状态已经足够恢复。

## API / DTO 变更

`SessionMessageDto` 以及 WebSocket `message_appended` / `messages_snapshot` payload 增加：

| 字段 | 来源 |
| --- | --- |
| `messageType` | `SessionMessageEntity.messageType`，默认 `normal` |
| `controlId` | control id 或 null |
| `answeredAt` | ISO timestamp 或 null |
| `metadata` | 现有 metadata map，包含 control payload |

兼容策略：

- 老 API response 缺少 `messageType` 时按 `normal` 处理。
- 现有 `msgType` 继续用于 compact boundary / summary 等存储层行为。

## 前端方案

### Message Model

扩展 `ChatMessage`：

```ts
type ChatMessageType = 'normal' | 'team_result' | 'subagent_result' | 'ask_user' | 'confirmation';
```

`useChatMessages.normalizeMessages()` 映射：

- `messageType || 'normal'`
- `controlId`
- `answeredAt`
- control metadata 字段

如果 `messageType` 缺失，对历史 `[TeamResult ...]` 和 `[SubAgent Result ...]` marker 做轻量兼容识别。

### 渲染

`ChatWindow` 优先按 `messageType` 渲染：

| `messageType` | 组件 |
| --- | --- |
| `normal` | 现有 message bubble |
| `team_result` | result card / bubble |
| `subagent_result` | result card / bubble |
| `ask_user` | 内联 `PendingAskCard` |
| `confirmation` | 内联审批卡片 |

`PendingAskCard` 从 dialog-like 全局控件改成消息行组件：

- `answeredAt == null`：可操作。
- `answeredAt != null`：折叠为历史摘要，只读不可操作。
- 请求提交中禁用 submit。
- 完成后再次点击不触发 UI 行为；后端仍拒绝 stale submit。

`Chat.tsx` 不再把 `pendingAsk` / `pendingConfirm` 作为卡片 truth source。过渡期可以保留 WebSocket `ask_user` / `confirmation_required` 事件，但它们只触发 snapshot refresh，不再创建独立 React state。

## 测试计划

### 后端单测 / 服务测试

- migration / entity mapping 包含 `messageType`、`controlId`、`answeredAt`。
- 新 TeamResult 消息持久化为 `message_type='team_result'`。
- 非 collab SubAgent 消息持久化为 `message_type='subagent_result'`。
- `ask_user` loop result 创建 `SYSTEM_EVENT + ask_user` 消息，并设置 session `waiting_user`。
- ask_user 测试路径不再调用 `PendingAskRegistry.await()`。
- answer endpoint 只回答一次、补齐 ask_user `tool_result`、恢复新 trace loop。
- 重复 answer 返回 conflict，不追加第二条 result。
- `waiting_user` 下直接输入会把 pending ask 标记为 superseded，并且只追加一条 user message。
- pending confirmation 下自然语言输入返回 conflict，不 approve / deny。
- context reconstruction 排除 pending 阻断卡片行。

### 前端测试

- `normalizeMessages` 将缺失 `messageType` 的消息映射为 `normal`。
- `team_result` 和老 `[TeamResult ...]` marker 不渲染成 user bubble。
- `ask_user` 渲染为内联卡片，完成后折叠为“已回答 / 已替代 / 已过期”摘要。
- `confirmation` 渲染为内联审批卡片，不能被主输入框文本 approve / deny。
- 已处理 confirmation 折叠为“已批准 / 已拒绝 / 已过期”摘要。
- waiting 期间直接输入不依赖内存 `pendingAsk`。

### 浏览器检查

- 启动 backend 和 dashboard。
- 触发或 mock 一个 ask_user response。
- 验证 ask 卡片出现在 Chat 中，刷新后仍恢复，回答后折叠摘要。
- 再触发一个 ask_user，不点击卡片而直接发送普通文本；验证卡片变为只读并恢复 loop。
- 触发或 mock 一个 confirmation card；验证必须点击 approve / deny，主输入框不能隐式通过审批，完成后折叠摘要。
- 验证 WebSocket 重连不会丢失卡片状态。

## Full Pipeline 检查点

1. 对照 PRD 和核心协议不变量评审本方案。
2. 实现 DB / entity / DTO 变更。
3. 实现后端 ask pause / resume。
4. 实现 Team/SubAgent message typing。
5. 实现前端 normalize / rendering。
6. 运行后端 focused tests。
7. 运行前端 unit / build checks。
8. 浏览器检查 Chat 关键交互。

## 实现注意事项

- repository 方法命名跟随现有 `SessionMessageRepository` 风格。
- transaction boundary 放在 public `ChatService` / `SessionService` 方法上，不放在 private helper。
- ask metadata JSON 使用 Spring-managed `ObjectMapper`。
- `answeredAt` 使用 `Instant`。
