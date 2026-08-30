# AG-UI 兼容层技术方案

---
id: AG-UI-COMPATIBILITY
status: backlog-disabled
risk: Full
created: 2026-08-27
---

## 推荐架构

```text
AgentLoop / ChatService / Task / Confirmation / Artifact
                         |
                SkillForge Domain Events
                    /                 \
       Existing WebSocket Adapter    AG-UI Adapter (disabled)
                 |                         |
       Dashboard / iOS today     Experimental clients/runtime
```

Domain Event 是唯一内部真相。AG-UI Adapter 只负责版本化映射、传输和兼容性，不反向控制权限或持久化。

## 初步事件映射

| SkillForge | AG-UI |
| --- | --- |
| `session_status` | `RUN_STARTED / RUN_FINISHED / RUN_ERROR` |
| text stream | `TEXT_MESSAGE_START / CONTENT / END` |
| message catch-up | `MESSAGES_SNAPSHOT` |
| `tool_started / tool_use_delta / tool_finished` | `TOOL_CALL_START / ARGS / END / RESULT` |
| `reasoning_delta` | Reasoning events |
| `session_tasks_snapshot` | `STATE_SNAPSHOT`，后续可评估 RFC 6902 `STATE_DELTA` |
| Goal Brief | versioned state slice 或 `CUSTOM` |
| `confirmation_required` / Pending Ask | interrupt outcome 或 `CUSTOM` |
| Artifact / Media Job | versioned `CUSTOM` |

## 传输

- 首个实验入口优先 HTTP/SSE，避免与现有 Chat WebSocket 共用所有权。
- WebSocket 仍是现有生产路径；AG-UI transport 必须可插拔。
- Mobile Token、配对、scope 和用户归属继续使用 SkillForge 认证。
- runId/threadId 与 Session/turn 的映射必须稳定且可追踪。

## iOS

- 原生 Swift `Codable` 解析标准事件，`URLSession` 消费 SSE 或 WebSocket。
- Adapter 输出先归一到现有 `MobileRealtimeState`，避免建立第二套 Chat 状态所有权。
- 必须测试 actor isolation、Sendable、取消、Session 切换、后台/前台和 REST catch-up。
- Swift 官方 SDK 成熟后再评估替换轻量客户端；不提前绑定社区 SDK。

## 分期

1. 只做规范锁定、mapping table 和 conformance fixtures。
2. 后端 shadow Adapter + 事件对比，不暴露用户入口。
3. Dashboard 实验客户端。
4. iOS 原生实验客户端与 Simulator/live-backend 验证。
5. 根据证据决定是否成为可选生产协议；默认关闭直到单独批准。

## 风险与门禁

- 协议仍快速演进：锁定版本，升级走独立 review。
- State 双真相：Domain Event/数据库为权威，AG-UI snapshot 只是投影。
- Custom Event 泛滥：每个 schema 必须有 namespace、version、上限和降级策略。
- 重复副作用：resume/interrupt 需要幂等 key，前端事件不得直接构成授权。
- 全量迁移风险：禁止 big-bang 替换，必须 shadow/canary/rollback。

## 预期验证

- Java mapping/unit/contract tests。
- WebSocket 与 AG-UI shadow parity test。
- Dashboard parser/reducer/browser test。
- iOS XCTest、URLProtocol、XCUITest、Simulator + live backend。
- 乱序、重复、断线、取消、恢复、权限绕过红队测试。
