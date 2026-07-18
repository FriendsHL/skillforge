# iOS Prototype Parity Matrix

> 基线日期：2026-07-18
> 设备：iPhone 17 Pro simulator，iOS 26.5
> 事实源：生产 SwiftUI + `--ui-testing-tabs` deterministic fixture

## Current / As-built

| 页面 | 原型覆盖 | SwiftUI owner | 自动化锚点 | Phase 1 状态 |
| --- | --- | --- | --- | --- |
| Launch / restoring | 品牌标识、恢复文案、全屏自适应背景、进度状态 | `SkillForgeLaunchView` + `UILaunchScreen` | `app.launch` | 已对齐；系统冷启动待真机确认 |
| Chat | compact header/runtime、柔和蓝 user Query、全宽 Agent 正文、安全 Markdown、内联结果、紧凑 composer、四 Tab shell | `CompanionTabView` / `ChatView` / `MessageBubbleView` / `MarkdownText` | `chat.transcript` / `chat.message.<id>.*Surface` | Agent-first 与 Markdown 视觉批次已实现并迁入 Current |
| Control | large title、refresh、Schedules、Workspace Sessions、四 Tab shell | `ControlView` | `control.schedule.7` | 已对齐 |
| Agents | large title、refresh、search、Agent roster、metadata、四 Tab shell | `AgentsView` | `agents.row.2` | 已对齐 |
| Settings | Connection health、Device、Notifications、四 Tab shell | `SettingsView` | `Settings` navigation bar | 已对齐 |
| Personal App message card | preview、badge、capability/provenance、Open/Share/Retry | `PersonalAppCardView` | `attachment.card.<id>` | 已实现并迁入 Current |
| Personal App full-screen viewer | 离线 Viewer、state bridge、提交确认、权限诊断 | `PersonalAppViewer` | `personalApp.viewer.<id>` | 已实现并迁入 Current |
| Personal Apps Library | 搜索、范围、筛选、同构 loading skeleton、紧凑卡片与操作 | `PersonalAppLibraryView` | `personalApps.list` / `personalApps.loading` | 已实现并迁入 Current |

Current 原型不再包含早期的 Sessions、Pending、Push Inbox 或 Quick Actions 独立入口。这些入口并非当前
Companion Tab 信息架构的一部分。

## Design record / Next Target

| 页面 | 需求 ID | 候选内容 | 状态 |
| --- | --- | --- | --- |
| Query Color | `IOS-CHAT-MARKDOWN-VISUAL-POLISH` | 暖桃、柔和蓝、饱和蓝三色选择记录 | 已选择柔和蓝并迁入 Current |
| Markdown Report | `IOS-CHAT-MARKDOWN-VISUAL-POLISH` | 调研报告的标题、要点、引用与链接 | 已实现并迁入 Current |
| Markdown Result | `IOS-CHAT-MARKDOWN-VISUAL-POLISH` | 执行步骤、代码语言栏、精确复制和下一步 | 已实现并迁入 Current |

上述三项现在是已完成的设计记录，不再代表待实现 Target。后续 Chat 顶栏、工具卡片和 Control/Agents 改版会在
新的需求 ID 下先补 Target 原型，确认后才能迁入 Current。

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
- `launch-restoring-workspace`
- `personal-app-library-loading`
- `personal-app-library-normal`

2026-07-17 验证结果：

- 定向 parity：1 个 XCUITest 通过、0 failure；四张 attachment 已人工对照原型 Current。
- 全量 scheme：117 个 unit test + 30 个 XCUITest 通过、0 failure，结果包为
  `/tmp/skillforge-parity-full.xcresult`。
- Release simulator build：成功。
- HTML DOM smoke：inline JavaScript 可编译，Current Launch/四 Tab/Personal App/Library marker 与四张 Library 卡片存在；
  Quick Look 静态渲染通过。自动浏览器控制在本机环境不可用，因此未声称 HTML 点击交互通过。

2026-07-17 后续 Current 同步验证：

- 全量 iOS：263 个 XCTest + 60 个 XCUITest，323/323 通过；结果包
  `/tmp/SkillForge-Final-LoadingCards-20260717.xcresult`。
- `launch-restoring-workspace`、`personal-app-library-loading` 与 `personal-app-library-normal` 截图已人工复核；
  首帧有品牌恢复语义，Library loading 与真实卡片几何连续，普通字号长摘要最多两行。
- Release simulator build 成功；Release `Info.plist` 和 `Assets.car` 均包含命名 Launch Screen 资产；XcodeGen 连续两次
  生成的 `project.pbxproj` SHA-256 相同。

2026-07-18 Agent-first Current 同步（Full automated gate 通过）：

- user/assistant 使用真实 `userSurface` / `assistantSurface` 锚点；普通字号宽度阈值分别为 ≤80% / ≥92%。
- 柔和蓝 user Query light/dark XCUITest 已覆盖，截图已人工复核；Reduce Motion/宽度 policy 保持 PASS；
  设备旋转请求下 portrait-only scene 可用性 1/1 PASS。
- `AgentFirstChatLayoutUITests` 覆盖 light、dark Markdown/Tool、dark attachment、XXXL、附件移除 44pt、生产 Tab shell
  和方向策略；最新完整 scheme 337/337 PASS（268 XCTest + 69 XCUITest，0 failure、0 skipped），结果包为
  `/tmp/SkillForge-Markdown-Full-20260718.xcresult`；Release simulator build PASS。
- XcodeGen 连续生成的 `project.pbxproj` SHA-256 一致：
  `048f4ac6b474317a5fc9d7718020a1d327a3b2f80e1357e4c4547fe28dab86ed`。
- HTML Current 主 Chat 与 Personal App Card 场景均复用 Agent-first shell、柔和蓝 Query，并移除生产不存在的回复操作。
  自动浏览器插件不可用，因此只记录 DOM/script 冒烟、
  Quick Look 静态视觉和生产 XCUITest 证据，不声称 HTML 点击交互 PASS。

2026-07-18 Query / Markdown Current 证据：

- inline JavaScript 可编译；11 个 screen ID 唯一；Markdown Report / Result 均位于 Current，最终默认仅 Current Chat active。
- Query Design record、Markdown Report、Markdown Result 完成 Quick Look 静态渲染与人工复核；修正了长 Query
  对比卡片的横向溢出。
- 应用内浏览器当前没有可连接实例，且运行时故障说明资源缺失；未声称模式切换和点击交互 PASS。
- 生产完整 scheme 337/337 与 Release simulator build PASS；代码复制覆盖 exact payload 单元测试和可见反馈 UI 测试。

HTML 原型：[`../../../prototypes/ios-assistant-companion/index.html`](../../../prototypes/ios-assistant-companion/index.html)

## 视觉评审结论

- Blocker drift：0。导航、页面命名、主要信息层级和关键入口已回到真实 App 基线。
- 非阻断差异：HTML 仅模拟 SwiftUI 系统字体、材质和 safe area，不作为逐像素快照。
- 当前没有未确认的 Query / Markdown Proposed；下一批 Target 不得合并进 Current，直到用户确认、对应需求实现并通过
  开发门。真机视觉验收可以继续保留为明确的 `NOT_RUN` 发布项。
