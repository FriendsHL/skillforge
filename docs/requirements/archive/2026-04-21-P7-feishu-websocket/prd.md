# P7 PRD

---
id: P7
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-21
updated: 2026-04-29
---

## 摘要

实现 Feishu WebSocket connector、事件分发、ACK、重连和前端配置切换。

## 目标

- 支持飞书 WebSocket 长连接。
- ChannelPushManager 管理连接生命周期。
- 支持模式切换和重启提示。
- 增加 dispatcher / manager 测试。

## 非目标

- 不在此需求中重做整个 Channel Gateway。

## 功能需求

- `ChannelPushConnector` SPI。
- `FeishuWsConnector`。
- `FeishuWsEventDispatcher`。
- `FeishuWsReconnectPolicy`。
- `ChannelPushManager`。
- 前端 ChannelConfigDrawer mode 切换。

## 验收标准

- [x] WS 事件可解析并路由到 session。
- [x] ACK 带 service_id。
- [x] 断线后按退避策略重连。
- [x] 前端能配置 websocket/webhook mode。

## 验证预期

- Feishu WS dispatcher tests。
- ChannelPushManager tests。
- E2E 验证。
