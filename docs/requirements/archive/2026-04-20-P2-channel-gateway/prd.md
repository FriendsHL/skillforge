# P2 PRD

---
id: P2
status: done
owner: youren
priority: P0
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-20
updated: 2026-04-28
---

## 摘要

实现多平台消息网关，首批支持飞书和 Telegram，并保留扩展其他平台的 SPI。

## 目标

- ChannelAdapter SPI。
- 平台消息去重和投递记录。
- Channel conversation 到 SkillForge session 的路由。
- 飞书、Telegram 首批实现。
- Dashboard `/channels` 管理页面。

## 非目标

- 不在首版支持所有 IM 平台。
- 不把 channel gateway 做成独立外部服务。

## 功能需求

- V17 migration。
- webhook 路由和签名验证。
- conversation resolver。
- delivery retry / status。
- 前端 channel config、conversation list、delivery panel。

## 验收标准

- [x] 飞书和 Telegram 可接入。
- [x] 消息去重有效。
- [x] 会话路由稳定。
- [x] Dashboard 可管理 channels。

## 验证预期

- 后端 adapter / router / delivery tests。
- Full Pipeline review。
