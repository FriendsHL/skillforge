# Session State MRD

---
id: SESSION-STATE
status: done
source: historical-backfill
created: 2026-04-17
updated: 2026-04-29
---

## 用户诉求

历史补录：用户需要在不同执行模式下控制 Agent 行为，特别是需要确认或提问时，session 状态要清楚可见。

## 背景

Agent 可能处于 running、waiting confirmation、idle、error 等状态。没有清晰状态机时，用户难以判断是否需要输入、确认或等待。

## 期望结果

建立 session 状态感知和 ask mode 交互原则，让用户确认和执行模式可追踪。

## 约束

- 状态机不能与 ChatService / AgentLoopEngine 主流程冲突。
- 前端要清楚展示状态。

## 未决问题

- 无。需求已交付 / 参考。
