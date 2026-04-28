# P7 MRD

---
id: P7
status: done
source: historical-backfill
created: 2026-04-21
updated: 2026-04-29
---

## 用户诉求

历史补录：飞书渠道需要支持 WebSocket 长连接，提升接入稳定性和部署灵活性。

## 背景

Webhook 模式依赖外部可访问回调地址，不适合所有本地或内网场景。飞书提供长连接能力，可以降低部署门槛。

## 期望结果

用户可以在 channel config 中选择 websocket/webhook 模式，服务端能维护飞书 WS 连接、ACK 事件并自动重连。

## 约束

- 需要 ping/pong 和重连策略。
- 配置切换后需要清晰提示 restart。
- 本地和 CI 需要可测。

## 未决问题

- 无。需求已交付。
