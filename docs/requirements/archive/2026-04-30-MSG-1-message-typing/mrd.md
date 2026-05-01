# MSG-1 MRD

---
id: MSG-1
status: mrd
source: user
created: 2026-04-29
updated: 2026-04-30
---

## 用户诉求

用户希望先解决 BUG-30 / BUG-31：SubAgent / Team 结果在 Chat 消息流里的归因错误，以及 `ask_user` 卡片在 WebSocket 重连、页面切换或服务重启后丢失的问题。

2026-04-30 讨论中，用户进一步确认 `ask_user` 的目标语义：

- 如果模型判断需要 human 介入，应把一个卡片给用户。
- 卡片应直接展示在 Chat 页面消息流里，而不是弹框。
- 当前 agent loop 应结束，不要一直阻塞等待用户回答。
- 用户可以点击卡片完成，也可以直接在输入框继续输入内容；两种方式都应能继续当前会话。
- 用户点击卡片后，卡片不能再次被点击。
- 是否走 `ask_user` 是模型决策，系统负责把这个决策变成可靠、可恢复、不会卡线程的消息状态机。

## 背景

当前实现把两类非常规消息放在临时机制上：

- SubAgent / Team 结果通过 `[TeamResult ...]` 字符串 marker 作为 `user` role 消息进入父 session。这个形态满足 LLM 对话交替的输入需要，但前端只看 `role=user`，导致消息流把 TeamResult 当成用户自己发的消息。
- `ask_user` 使用 WebSocket 临时事件和 React state 展示卡片。后端 `PendingAskRegistry` 只保存 `askId + sessionId + latch + answer`，不保存 question / options / allowOther，因此前端重连或刷新后没有数据源可恢复卡片。

## 期望结果

完成后，Chat 消息流应能可靠区分普通消息、Team/SubAgent 结果和 ask_user 请求。`ask_user` 应成为可持久化、可恢复、一次性操作的内联消息卡片；触发 ask 后 session 进入等待用户状态并释放执行线程，用户继续后恢复 agent loop。

## 约束

- 这是 Full 需求，必须走 Full Pipeline。
- 会触碰核心文件、schema、消息持久化、WebSocket 协议和前端 Chat 渲染。
- 新增消息类型不能破坏 LLM provider 的 `tool_use` / `tool_result` 配对不变量。
- 历史消息必须兼容，老 session 不能因为缺少新字段而渲染失败。

## 未决问题

- 技术方案需要确定非阻塞恢复时 `tool_use` / synthetic `tool_result` 的持久化形态。
- 技术方案需要确定历史 `[TeamResult ...]` 是否做 DB 回填，或仅前端兼容老 marker。
