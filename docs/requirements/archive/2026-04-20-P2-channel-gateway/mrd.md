# P2 MRD

---
id: P2
status: done
source: historical-backfill
created: 2026-04-20
updated: 2026-04-28
---

## 用户诉求

历史补录：SkillForge 需要接入飞书、Telegram 等外部消息平台，让 Agent 能通过多渠道收发消息。

## 背景

仅有 Web dashboard 无法覆盖移动端和团队沟通场景。需要一个可扩展网关，未来能接入微信、Discord、Slack、iMessage 等渠道。

## 期望结果

建立 ChannelAdapter SPI、消息去重、会话路由、投递记录和前端管理页面。

## 约束

- CI / 本地测试需要 mock channel。
- 飞书 / Telegram 的签名、ACK、消息长度限制等平台差异必须处理。

## 未决问题

- 无。需求已交付。
