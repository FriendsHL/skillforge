# AG-UI 兼容层 PRD

---
id: AG-UI-COMPATIBILITY
status: backlog-disabled
risk: Full
created: 2026-08-27
---

## 产品目标

在未来需要跨端统一或外部 Runtime 接入时，为 SkillForge 提供可关闭、可回滚、版本固定的 AG-UI 兼容入口，同时保持现有用户体验和安全边界。

## 功能需求

### 协议适配

- 将运行开始/结束/错误映射为 AG-UI Run lifecycle。
- 将文本和 Reasoning 增量映射为标准 message events。
- 将 Tool 生命周期映射为 Tool Call events。
- 将 Session Message 恢复映射为 `MESSAGES_SNAPSHOT`。
- 将 Task、Goal Brief 和长期任务状态映射为版本化 State Snapshot/Delta。
- 将 Confirmation、Pending Ask、Artifact 和媒体任务映射为标准 interrupt 或版本化 Custom Event。

### 客户端

- Dashboard 可实验性消费 AG-UI，但默认继续使用现有 WebSocket。
- iOS 使用原生 Swift Codable 客户端；不默认引入 KMP 或其他跨平台 Runtime。
- 客户端必须支持断线后 Snapshot 恢复，不能把旧 Snapshot 覆盖到更新的流式状态。

### 治理

- AG-UI 只传递交互，不成为权限或授权来源。
- Goal Brief 确认不能代替 CreateAgent、外部代码、权限、发布等现有 Gate。
- 未知事件必须可忽略或安全降级，不能导致 Session 崩溃。
- 事件、Custom schema 和 AG-UI 版本必须进入 Trace provenance。

## 验收标准

- 同一 Session 的现有 WebSocket 与 AG-UI shadow stream 可确定性归一为相同 UI 状态。
- Dashboard 与 iOS 对 Run、Message、Tool、Task、Goal Brief、Confirmation 的合同测试通过。
- 断线、重连、重复、乱序、取消和错误恢复不产生重复消息或重复副作用。
- 禁用 feature flag 后生产路径与当前行为一致。
- 不修改 Agent Loop、Message 持久化形状或权限决策权威源。

## 暂不实施

本 PRD 仅作为后续需求基线；当前版本不提供 endpoint、SDK、feature flag 或 UI 开关。
