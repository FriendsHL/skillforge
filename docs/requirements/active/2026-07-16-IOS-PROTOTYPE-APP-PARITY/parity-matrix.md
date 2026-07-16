# iOS Prototype Parity Matrix

> 基线日期：2026-07-17
> 设备：iPhone 17 Pro simulator，iOS 26.5
> 事实源：生产 SwiftUI + `--ui-testing-tabs` deterministic fixture

## Current / As-built

| 页面 | 原型覆盖 | SwiftUI owner | 自动化锚点 | Phase 1 状态 |
| --- | --- | --- | --- | --- |
| Chat | assistant header、Mac 连接状态、运行状态条、消息区、composer、四 Tab shell | `CompanionTabView` / `ChatView` | `chat.transcript` | 已对齐 |
| Control | large title、refresh、Schedules、Workspace Sessions、四 Tab shell | `ControlView` | `control.schedule.7` | 已对齐 |
| Agents | large title、refresh、search、Agent roster、metadata、四 Tab shell | `AgentsView` | `agents.row.2` | 已对齐 |
| Settings | Connection health、Device、Notifications、四 Tab shell | `SettingsView` | `Settings` navigation bar | 已对齐 |

Current 原型不再包含早期的 Sessions、Pending、Push Inbox 或 Quick Actions 独立入口。这些入口并非当前
Companion Tab 信息架构的一部分。

## Proposed / Target

| 页面 | 对应需求 | 生产 SwiftUI owner | 状态 |
| --- | --- | --- | --- |
| Personal App message card | `IOS-INTERACTIVE-ARTIFACTS` | 无 | Proposed，未实现 |
| Personal App full-screen viewer | `IOS-INTERACTIVE-ARTIFACTS` | 无 | Proposed，未实现 |
| 离线预算滑杆、实时重算、重置 | `IOS-INTERACTIVE-ARTIFACTS` | 无 | 仅原型交互 |
| “把调整发给 Agent” | `IOS-INTERACTIVE-ARTIFACTS` 后续能力 | 无 | 仅视觉占位；本阶段不承诺 Tool Bridge |

## 可重复证据

截图测试：

```bash
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge \
  -destination 'platform=iOS Simulator,id=0482B203-676B-42C9-806D-380027BA2D83' \
  -only-testing:SkillForgeUITests/PrototypeParityUITests \
  -resultBundlePath /tmp/skillforge-parity.xcresult test
```

测试会生成四个 attachment：

- `parity-current-chat`
- `parity-current-control`
- `parity-current-agents`
- `parity-current-settings`

2026-07-17 验证结果：

- 定向 parity：1 个 XCUITest 通过、0 failure；四张 attachment 已人工对照原型 Current。
- 全量 scheme：117 个 unit test + 30 个 XCUITest 通过、0 failure，结果包为
  `/tmp/skillforge-parity-full.xcresult`。
- Release simulator build：成功。
- HTML DOM smoke：Current 四页、Proposed 卡片/全屏页、滑杆重算与重置通过。

HTML 原型：[`../../../prototypes/ios-assistant-companion/index.html`](../../../prototypes/ios-assistant-companion/index.html)

## 视觉评审结论

- Blocker drift：0。导航、页面命名、主要信息层级和关键入口已回到真实 App 基线。
- 非阻断差异：HTML 仅模拟 SwiftUI 系统字体、材质和 safe area，不作为逐像素快照。
- Proposed 不得合并进 Current，直到 `IOS-INTERACTIVE-ARTIFACTS` 实现并通过用户验收。
