# CHANNEL-MIDTURN-PROGRESS — 渠道中途进度推送（任务执行中把 assistant 文本同步给飞书/微信）

> 创建：2026-06-19
> 状态：**设计待批**（碰 channel delivery 多组件 + 微信 iLink 限流风险 → Full + 冒烟门禁）
> 来源：用户报 —— 任务执行中网页管理端能看到模型的中间输出，但飞书/微信只收到最终结果，长任务期间渠道一片寂静（也是"用户中途忍不住再发消息"的诱因之一）。

## 用户诉求 / 行为定义

任务执行中模型产出的**每一段 assistant 自然语言文本**：
- 网页端：**完全不变**，仍拿全量（thinking / tool / text 流式）。
- 渠道（飞书/微信）：**额外多一份** —— 若该 session 绑了 active channel conversation，则抽出纯文本、过节流、发一份给渠道；非渠道 session 照旧不多做。

**只推 ② assistant text。明确不推：① reasoning_content/thinking（独立通道，IM 刷屏 + 个人微信号封号风险）、③ tool_use/tool_result 原始 I/O。**

## 关键技术点（已取证）

| 项 | 落点 |
|---|---|
| **中途 assistant 消息 emit 点** | `AgentLoopEngine:1139` `broadcaster.messageAppended(sessionId, traceId, assistantMsg)` —— 每轮完整 assistant 消息。这是 hook。 |
| **thinking 天然隔离** | thinking 走 `broadcaster.reasoningDelta`（engine:957）独立通道，**不进 messageAppended**，无需额外过滤即不会被推。 |
| **是否渠道 session** | `ChannelConversationRepository` 按 sessionId 查 active conversation（即修过的那张绑定表）。 |
| **发送基础设施复用** | `ReplyDeliveryService.deliver(ChannelReply, adapter, config, sessionId)` —— 已有持久化 + 30s 重试 + orphan 恢复，直接复用，不重造。 |
| **终点 delivery 现状** | `ChannelReplyEventListener`(inbound) / `ChannelAsyncDeliveryListener`(loop 结束) 抽最终文本发。中途进度是**新增第三条**入口。 |

## 设计（定稿）

### Hook 机制（不进 lifecycle Hook 系统）
- `ChatWebSocketHandler.messageAppended`（已是引擎注入的 `ChatEventBroadcaster` 实现，收到每轮 assistant 消息）对 **role==assistant** 的消息 publish 一个 Spring `AssistantTurnAppendedEvent`（sessionId + traceId + message）。WS handler 只多一行 publish。
- 新增 `@Async("channelRouterExecutor") @EventListener ChannelProgressDeliveryListener` 消费该事件（跟现有 `ChannelAsyncDeliveryListener` / `ChannelReplyEventListener` 同套路）。所有渠道逻辑在此 listener，与 WS / 引擎解耦。

### ChannelProgressDeliveryListener 逻辑
1. **过滤**：
   - 该 session 有 active channel conversation 绑定（`ChannelConversationRepository`）否则 return。
   - **只处理"含 tool_use 的 assistant 轮"**（见 OQ-1 去重）：消息含 ≥1 个 tool_use block → 抽其 **text block** 拼接作为进度文本；不含 tool_use 的纯文本轮**跳过**（那是末轮最终答案，留给终点 delivery）。
   - text trim 后**空 / 过短（< `minChars`）跳过**（滤"让我看看…"噪音）。
2. **节流（关键，尤其微信）**：每 conversation 维护 `lastSentAt` + `sentCount`（按 run/turn-window）：
   - 间隔 < `minIntervalMs` → 丢弃
   - `sentCount ≥ maxPerRun` → 丢弃（仅保最终）
   - 状态随 loop 结束重置（监听 `SessionLoopFinishedEvent` 清理）。
3. **前缀**（OQ-2）：进度文本加可配前缀（默认 `🔄 `）区分于最终结果。
4. **发送**：复用 `ReplyDeliveryService.deliver(...)`（持久化 + 重试）。
5. **best-effort**：任何异常只 log，不打断 agent loop。

### 配置（per-channel，挂 channel config JSON）
```
progressDelivery: { enabled: bool, minIntervalMs: int, maxPerRun: int, minChars: int, prefix: string }
```
默认：飞书 `{enabled:true, 2000, 12, 8, "🔄 "}`、**微信 `{enabled:false, ...}`（默认关，见 OQ-3）**。

## 决策（已定）

- **OQ-1 末轮去重 → 用"只推含 tool_use 的轮"**：进度只推"narration-before-action"（assistant text 伴随 tool_use 的轮）；**末轮最终答案是 text-only 无 tool_use → 不被进度推,由现有终点 delivery 发**。天然零重叠,无需记账对比。（依据:agent loop 中 assistant 轮无 tool_use 即停 → text-only≈末轮。）
- **OQ-2 前缀 → 加**，可配，默认 `🔄 `。
- **OQ-3 微信默认 → 关（opt-in）**：iLink 逆向 + 个人号封号风险,微信 `enabled` 默认 false,用户按需开;飞书默认 on。

## 验收点

1. 渠道 session 任务执行中，每段（够长的）assistant 文本按节流推到渠道；网页端行为不变。
2. thinking / tool 原始 I/O **不**出现在渠道。
3. 节流生效：间隔 < `minIntervalMs` 的、或超过 `maxPerRun` 的中途文本被丢弃。
4. 末轮文本**不重复**（OQ-1）。
5. 进度推送失败不打断 agent loop。
6. 非渠道（纯网页）session 零行为变化。

## 冒烟用例（部署后 qa-reviewer）

1. 飞书/微信触发一个多轮 tool 任务 → 渠道应**陆续**收到 ≥1 条中途文本（带前缀），最后收到最终结果，**总数 ≤ maxPerRun+1**，无 thinking/tool 原文，无末轮重复。
2. 触发一个一轮就结束的简单任务 → 渠道只收最终结果（无多余中途）。
3. 把某渠道 `progressDelivery.enabled=false` → 该渠道回到"只发最终结果"。
4. 早失败检测：渠道未绑定 / adapter 缺失 → 不报错、不影响 loop。

## 风险 / 不变量

- 碰 channel delivery + 引擎 broadcaster hook（多组件）→ **Full + 冒烟门禁**。
- 微信 iLink 限流 / 个人号风险 → 节流保守 + per-channel 可配 + OQ-3。
- 不得阻塞 / 拖慢 agent loop（best-effort 异步）。
- 复用 ReplyDeliveryService，不新建发送/重试逻辑。
