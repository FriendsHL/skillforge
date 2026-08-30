# AG-UI 兼容层 MRD

---
id: AG-UI-COMPATIBILITY
status: backlog-disabled
created: 2026-08-27
---

## 背景

SkillForge 已有 Dashboard 和原生 SwiftUI iOS App，两端通过 REST 与自定义 WebSocket 事件消费 Agent 的消息、运行状态、Tool、Task、确认、Reasoning 和 Artifact。未来还计划支持专业 Agent、无限画布和可替换 Runtime，继续扩展私有事件会增加跨端重复实现与外部接入成本。

AG-UI 是 Agent 与用户界面之间的开放事件协议，覆盖流式消息、运行生命周期、Tool Call、共享状态和 human-in-the-loop。它是正在发展的行业事实标准候选，不是 IETF/W3C/ISO 正式标准，因此应兼容而非绑定。

## 用户问题

- Dashboard 与 iOS 对同一 Agent 行为维护两套事件解释。
- 新的 Goal Brief、专业 Agent 状态、Workflow、媒体生成和画布节点会继续扩大事件面。
- 外部 Agent Runtime 或第三方客户端接入时需要重复适配 SkillForge 私有协议。
- 私有协议缺少公开的互操作边界和通用调试工具。

## 产品机会

通过 Adapter 将 SkillForge Domain Event 映射为 AG-UI，可以在不替换内部 Runtime 的前提下获得跨端统一和外部兼容，并保留 SkillForge 的权限、目标对齐、自进化与 Artifact 差异化能力。

## 非目标

- 不用 AG-UI 替换 Agent Loop、Context、Compaction 或 Plugin Runtime。
- 不让 AG-UI 决定权限、Goal Gate 或生产晋级。
- 不因为协议兼容而重写现有 Dashboard/iOS。
- 不承诺立即支持任意第三方 Generative UI。
