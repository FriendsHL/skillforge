# CHANNEL-ASYNC-DELIVERY — 异步续跑结果不投递回渠道（bug 修复）

> 创建：2026-06-18
> 状态：立项 / Full pipeline（触碰 ChatService 核心文件 + 渠道 + SubAgent 路径，跨 3 模块 → 红灯 Full）
> 来源：用户在微信 channel 用 main agent 派 SubAgent / Team 异步调研，结果回来后 main agent **没把结果发回微信**。已在 session `9d3eff0f-c22c-4568-bec8-a75ffe1f952d` 实测复现两次。

## 问题

channel-bound session 的 agent loop，**只有由"渠道入站消息"或"网页 WS turn"触发的那一轮**会投递回渠道。由 **SubAgent / Team 异步结果注入触发的续跑 loop**，其产出 stranded（落库但不发渠道）。

## 复现证据（session 9d3eff0f，微信 conv 6）

| seq | 时间(UTC) | 事件 | 投递微信? |
|---|---|---|---|
| 37 | 15:27:37 | `[SubAgent Result runId=7f0d6081]` 注入 → 续跑父 loop | — |
| 38 | 15:29:04 | 父产出完整早报 | ❌ 无投递记录 |
| 46/48 | 15:37:51/15:38:04 | 两条 `[TeamResult]` 注入 → 续跑父 loop | — |
| 49 | 15:39:01 | 父产出合并调研报告 | ❌ 无投递记录 |

对照：所有**渠道入站触发**的回复（15:26 / 15:35 / 15:37:16）全部 `DELIVERED`。

## 根因

- 渠道投递监听器 `ChannelReplyEventListener` 只监听 `ChannelSessionOutputEvent`。
- `ChannelSessionOutputEvent` 只在两处发布：`ChannelSessionRouter`（仅 slash 命令）+ `ChatWebSocketHandler.sessionStatus(→idle)`（用 `channelContexts` map，入站时填充、首次 idle 消费移除）。
- SubAgent/Team 续跑那一轮**没有人重新填充 `channelContexts`** → `ctx=null` → 不发 `ChannelSessionOutputEvent` → 不投递。
- 而通用的 `SessionLoopFinishedEvent`（`ChatService.runLoop` teardown，每轮都发）渠道侧没监听。

## 验收点

1. channel-bound session 的 loop **无论由什么触发**（渠道入站 / SubAgent 续跑 / Team 续跑 / 定时任务 / 异步工具续跑），只要产出非空 assistant 文本，都投递回渠道。
2. **不重复投递**：正常入站一轮只发一条（现有 `channelContexts` 路径 + 新增 fallback 路径不能同时发）。
3. 缺 `platformMessageId`（异步续跑无入站消息）时，从 conversation 解析 `to_user_id`（`deliver()` 已支持 `conversationId` fallback）；`t_channel_delivery.inbound_message_id` 仍需 per-turn 唯一（合成稳定 id）。
4. 不破坏现有飞书 / 微信入站回复路径 + slash 命令路径 + 网页路径。
5. `waiting_user` 终态不应误投递（除非该轮确有面向用户的 assistant 文本）。

## 冒烟用例（部署后 qa-reviewer 执行）

1. **SubAgent 异步**：微信里让 main agent 派 SubAgent 调研 → 父先回"已安排" → 子返回后父产出报告 → **报告到微信**（查 `t_channel_delivery` 该 turn 有 DELIVERED + 手机收到）。早失败检测：子 agent 真的 spawn 了（查子 session）。
2. **Team 异步**：TeamCreate 多 agent → 结果回来 → 合并报告投递到微信。
3. **回归**：普通入站一问一答只投递**一条**（不重复）。
4. **回归**：slash 命令（如 `/help`）渠道回复正常。
5. **微信回复窗口**：隔时主动推送是否被腾讯放行（真机观察，区分功能 bug vs 平台窗口限制）。

## 关联（同需求包后续 / 或拆子项）

- 微信原生**视频**消息（iLink 有 `video_item`，当前视频走 file type4）
- **卡片中性模型**：飞书原生交互卡片 + 微信降级（iLink 仅 text/image/voice/file/video，无 card/button）
- **ChannelPushService**：通用"按 sessionId 主动推送"能力（外部事件 / 异步工具 / agent out-of-band），本 bug 修复是它的第一个客户

## 阅读顺序

1. 本 index（问题 + 证据 + 根因 + 验收）
2. [tech-design.md](tech-design.md) — 修复方案（Plan 阶段产出，待 ratify）
