# Tech-Design: Channel Async Delivery Fix

> 需求包：2026-06-18-CHANNEL-ASYNC-DELIVERY
> 状态：Draft — 待 ratify（Plan 阶段产出，architect + 主会话核）
> Pipeline 档：Full

## 1. 根因（已诊断 + 源码核实）

两个投递参与方：

- **入站路径**：`ChannelSessionRouter.routeInternal` 在每个非 slash 入站轮前调 `chatWebSocketHandler.registerChannelTurn(sessionId, platformMessageId, ackReactionId, ...)`，往 `channelContexts` map 填一条 turn 上下文。`ChatService.runLoop` teardown 调 `broadcaster.sessionStatus(sid,"idle")` → `ChatWebSocketHandler.sessionStatus` 里 `channelContexts.remove(sid)`，非空且 finalText 非空 → 发 `ChannelSessionOutputEvent` → `ChannelReplyEventListener` 投递。
- **异步续跑路径**：SubAgent/Team 结果注入后调 `chatService.chatAsync(parentSid, ..., preserveActiveRoot=true)` 续跑父 loop。**没人调 `registerChannelTurn`** → loop 结束时 `channelContexts.remove` 返回 null → 不发 `ChannelSessionOutputEvent` → 不投递。

通用 `SessionLoopFinishedEvent`（`ChatService.runLoop` teardown 每轮都发）渠道层没监听。

## 2. 候选方案

### 方案 A — 双监听 + turn 归属三态标记（推荐）

`ChatWebSocketHandler` 加 `ConcurrentHashMap<String,Boolean> channelTurnHandled`，三态：
- **absent**：无入站 turn 注册（异步续跑 / 非渠道会话）
- **false**：入站 turn 已注册但未投递（loop 未结束 / 结束但 blank/error）
- **true**：入站 turn 已注册且已发 `ChannelSessionOutputEvent`

新增 `ChannelAsyncDeliveryListener`（`@Async @EventListener` on `SessionLoopFinishedEvent`）：`channelTurnHandled.remove(sid)` 原子读删 —— 非 null（入站路径已处理）→ SKIP；null（异步/非渠道）→ 查 conversation，命中则投递。

**优点**：入站路径零改（仅加两处 map 写）；dedup 由三态构造保证；覆盖所有异步触发类型；无 schema 改、`ChatService` 零改。

### 方案 B — 统一到单一 SessionLoopFinishedEvent
渠道层只监听 `SessionLoopFinishedEvent`，移除 `sessionStatus` 的双发布，platformMessageId/ack 存到存活到 loop 结束的 per-session 上下文。**blast radius 大**（动已验证的入站路径 + slash 命令走的是另一条 `ChannelSessionRouter` 直发布，统一不了）。作为 A 上线后的未来清理，不作本次。

### 方案 C — 在每个异步续跑 callsite 重填 channelContexts
只改 SubAgent/Team 续跑处调 `registerChannelTurn`。**不完整**（漏定时任务 / startup recovery / 异步工具等其它触发），且每加新异步源都要记得补，脆弱。仅作 A 受阻时的兜底。

## 3. 推荐：方案 A
理由：对已验证的入站路径零改；dedup 可由三态迁移逐条检验；所有现有异步 caller（SubAgent / Team / 定时 / startup recovery）自动受益无需逐个改；slash 命令路径不受影响。

## 4. Dedup 三态状态机

| 事件 | 动作 | 迁移 |
|---|---|---|
| `registerChannelTurn` | `put(sid,false)` | absent→false |
| `sessionStatus(idle)` ctx非空 finalText非空 | 发 event 后 `put(sid,true)` | false→true |
| `sessionStatus(idle)` ctx非空 finalText空 | `remove(sid)` | false→absent |
| `sessionStatus(error)` ctx非空 | 不发；`remove(sid)` | false→absent |
| `SessionLoopFinishedEvent` listener `remove(sid)`==true | 入站已投 → SKIP | true→absent |
| 同上 ==false | 入站决定不投 → SKIP | false→absent |
| 同上 ==null | 异步/非渠道 → 查 conv 后投递 | absent 不变 |

**不会重发**：listener 仅在 `remove` 返回 null 时投递；入站一旦 `registerChannelTurn`，key 存在直到 listener 移除。**不会漏**：异步轮从不 `registerChannelTurn` → key absent → 投递。同 session 两 loop 不并发（`compactionService.lockFor` 锁）。ConcurrentHashMap 提供 happens-before。

## 5. 异步轮的合成 inbound_message_id
`t_channel_delivery.inbound_message_id` 有唯一索引（V17）+ VARCHAR(1024)（V156）。异步轮无真实入站 id → 合成 `"async:" + UUID`（42 字符）。不与平台 id 冲突（微信 `wx1|...` / 飞书 opaque）。`WeixinChannelAdapter.deliver` 对非 `wx1|` 前缀 decode 返 null → fallback `conversationId`（即 from_user_id），正是主动推送的正确收件人。每轮新 UUID → 不误 dedup。

## 6. 边界
- **waiting_user**：**投递**（OQ-1 已拍=发）。异步轮（channelTurnHandled absent）以 waiting_user 结束时把反问文本当普通消息投到渠道。入站轮以 waiting_user 结束时 entry=false（register 设的，sessionStatus 不处理 waiting_user）→ listener 见 false → SKIP，即入站 waiting_user 维持现状（走 WS ask_user，不投渠道）；只有**异步轮**的 waiting_user 才投——正是要的：子Agent续跑后父若反问，反问要能到微信。
- **空/blank finalMessage**：SKIP（镜像 `ChannelReplyEventListener:43`）。
- **非渠道 / 子(SubAgent)session**：`findBySessionIdAndClosedAtIsNull` 返空 → SKIP（渠道绑在父 root session，子 session 不投，父投合并产出）。
- **error / aborted_by_hook / cancelled**：SKIP（非用户向产出 / null 文本）。
- **缺 platformMessageId 的 ack**：异步轮 `ackReactionId=null`，`ChannelReplyEventListener:64` 已 null-guard；listener 直接调 `deliveryService.deliver`（不再发 event），避免 `removeAck(null,...)`。
- **rebind(/new)**：旧 session `findBySessionIdAndClosedAtIsNull` 返空 → SKIP。
- **并发**：`compactionService.lockFor` 保证同 session loop 串行。

## 7. 改动文件
| 文件 | 改动 |
|---|---|
| `websocket/ChatWebSocketHandler.java` | 加 `channelTurnHandled` 字段；`registerChannelTurn` put false；`sessionStatus` put true / remove；加 public `removeChannelTurnHandled(sid)` |
| `channel/listener/ChannelAsyncDeliveryListener.java` | **新文件** ~80 行，`@Async("channelRouterExecutor") @EventListener` on `SessionLoopFinishedEvent` |
| `ChatService.java` | **零改**（核心文件） |
| 其它（event / router / ReplyDeliveryService / WeixinChannelAdapter） | 零改 |

无 schema migration。

## 8. ChannelAsyncDeliveryListener 逻辑
1. `handled = chatWebSocketHandler.removeChannelTurnHandled(sid)`；非 null → SKIP。
2. status ∈ {error, aborted_by_hook, cancelled} → SKIP（**注意：不含 waiting_user**，OQ-1 已拍要投递 waiting_user 的反问文本）。
3. finalMessage null/blank → SKIP。
4. `conversationRepo.findBySessionIdAndClosedAtIsNull(sid)` 空 → SKIP。
5. 解析 adapter + config（缺 → warn + SKIP）。
6. 构造 `ChannelReply(syntheticId="async:"+UUID, platform, conv.conversationId, text, true, null)`。
7. **直接** `deliveryService.deliver(reply, adapter, config, sid)`（不发 ChannelSessionOutputEvent，避免 removeAck(null)）。

## 9. 测试计划
- **`ChannelAsyncDeliveryListenerTest`**（Mockito）：异步渠道→投递 / 非渠道→skip / 已 handled(true)→skip / handled(false)→skip / blank→skip / error→skip / cancelled→skip / waiting_user→skip / 子 session 无 conv→skip。
- **`ChatWebSocketHandlerChannelTurnTest`**：register→false / idle非空→true / idle空→removed / error→removed / 无 register→removeReturnsNull。
- **冒烟**（见 index.md §冒烟用例，部署后 qa-reviewer 跑）：SubAgent 异步投递 / Team 异步投递 / 普通入站不重复(count=1) / slash 命令 / 微信回复窗口观察。

## 10. Open Questions（已拍 2026-06-19）
- **OQ-1 → 发**：异步轮以 `waiting_user` 结束时**投递**反问文本（当普通消息）。listener 不 skip waiting_user。已知限制：渠道用户回复时 agent 不一定能关联回该 ask（pending 应答与渠道普通消息两条路）；"渠道内 ask 应答打通"另立项。参考 openclaw（无 pending 机制，直接发文本）。
- **OQ-2 → 暂不抽**：直接复用 `ReplyDeliveryService.deliver`（已是 openclaw "按会话发" 原语的等价物）。listener 直接调它，不新建 `ChannelPushService`。记 backlog：出现第二个主动推送客户（agent 主动推送 tool / 外部事件）时再抽。
