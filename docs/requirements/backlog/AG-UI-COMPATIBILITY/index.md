# AG-UI 兼容层

---
id: AG-UI-COMPATIBILITY
status: backlog-disabled
priority: P3
risk: Full
created: 2026-08-27
updated: 2026-08-27
---

## 摘要

为 SkillForge 预留 AG-UI（Agent–User Interaction Protocol）兼容能力，以统一 Dashboard、原生 iOS、未来无限画布和外部 Agent Runtime 的消息、运行状态、Tool Call、共享状态及人工中断语义。

本需求仅记录，不进入开发，不增加依赖，不开放 endpoint，不修改生产事件协议。

## 当前决策

- 状态：**暂不启用、暂不开发**。
- SkillForge Domain Event 继续作为内部权威模型。
- 未来若启动，只增加可插拔 AG-UI Adapter，不以 AG-UI 数据结构侵入 Agent Loop、ChatService、数据库或权限模型。
- Dashboard 与 iOS 继续使用现有 REST + WebSocket 链路。
- Goal Brief、Confirmation、权限、Artifact 和自进化决策仍由 SkillForge 服务端治理。
- 不引入 Kotlin Multiplatform；iOS 优先考虑原生 Swift 轻量客户端。

## 启动条件

只有同时满足以下条件，才可把本包从 backlog 移入 active：

1. Goal Brief 的服务端确定性 Gate 已完成，不再只依赖 Prompt。
2. 现有 Dashboard/iOS WebSocket 事件契约和恢复不变量已有稳定测试基线。
3. 明确至少一个真实价值场景：外部 Runtime 接入、跨端统一、无限画布或第三方客户端。
4. 锁定 AG-UI 规范/SDK 版本并完成兼容性与供应链评审。
5. 用户明确批准启动独立 Full Pipeline。

## 阅读顺序

1. [MRD](mrd.md)
2. [PRD](prd.md)
3. [技术方案](tech-design.md)

## 参考

- [AG-UI 官方概览](https://docs.ag-ui.com/)
- [AG-UI 核心架构](https://docs.ag-ui.com/concepts/architecture)
- [AG-UI 开源仓库](https://github.com/ag-ui-protocol/ag-ui)
