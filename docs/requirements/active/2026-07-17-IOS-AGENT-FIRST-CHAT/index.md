# IOS-AGENT-FIRST-CHAT — 以 Agent 结果为中心的手机聊天页

> 状态：implemented / Full automated verified（337/337）；待用户真机视觉与 VoiceOver 确认
> 模式：Full pipeline
> 优先级：P1

技术设计：[tech-design.md](tech-design.md)

## 背景与调研结论

旧版 SkillForge iOS Chat 延续了较多管理端式卡片与状态容器。Agent 返回长 Markdown、任务结论、文件或
Personal App 时，可阅读内容所占宽度偏小，手机屏幕没有充分服务于“查看 Agent 结果”这一主要任务。

豆包官方将产品界面描述为“简单清爽”。结合其官网与公开移动端页面，本需求提炼内容优先、弱化回答容器、
紧凑用户消息等通用移动阅读原则，不复制其品牌、素材或逐像素界面：

- [豆包官网](https://www.doubao.com/)
- [豆包 App Store](https://apps.apple.com/cn/app/%E8%B1%86%E5%8C%85-%E9%9A%8F%E6%97%B6%E5%B8%AE%E5%BF%99%E7%9A%84-ai-%E5%8A%A9%E6%89%8B/id6459478672)

## 已实现产品形态

1. Agent 回答使用接近完整的内容宽度，不再把整段回复包进窄消息卡片。
2. 用户 Query 保持右对齐和 78% 普通字号宽度上限；后续 Mid 视觉批次已按用户选择改为柔和蓝，深色模式
   使用深海军蓝，辅助字号下允许扩为全宽以避免逐字换行。
3. Agent 名称、连接与运行事实压缩到紧凑 header；异常状态仍使用既有结构化语义色和 Retry 入口。
4. Tool、普通文件与 Personal App 继续作为 Agent 回答流中的结果模块，保留 provenance、权限、离线、过期和
   unavailable 事实。
5. Composer 收为紧凑胶囊并紧邻真实 Tab Bar；附件、发送、移除附件和回到底部按钮均保留至少 44pt 点击区。
6. Current 原型已同步真实 SwiftUI；柔和蓝 Query 和 Markdown 语义块完成 Mid 实现后也已迁入 Current，
   三色比较只作为已完成的 Design record 保留：
   [iOS Companion Prototype](../../../prototypes/ios-assistant-companion/index.html)

## Markdown 与操作边界

本需求只调整现有内容的呈现层级，不新增消息协议或任意 HTML 执行能力。

- 当前真实覆盖：标题、段落、无序列表、有序列表、代码块、引用和行内链接。
- pipe table 与 footnote/citation 没有因本需求新增解析器；收到时仍按现有文本能力展示，若要结构化呈现应另立需求。
- 不新增回复级复制、重新生成、朗读、分享或模型/模式切换按钮；原型中曾出现但生产不存在的控件已删除。
- Retry 继续使用 `IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH` 已实现的失败归因与安全重试语义，不复制副作用。

## 功能与交互要求

1. **Agent 内容优先**：assistant 正文使用全部可用阅读宽度；头像和元信息不得持续挤压正文列。
2. **用户消息紧凑**：user 消息右对齐、有清晰但低强调度的柔和蓝 surface，并保留轮次边界。
3. **结果内联**：附件、工具状态和 Personal App 复用既有数据、安全模型和能力描述，只改变呈现层级。
4. **运行反馈**：streaming、等待工具、失败归因、停止、Retry 与等待用户输入仍由既有状态机呈现。
5. **输入体验**：键盘、滚动、回到底部、附件入口与发送按钮不得遮挡消息内容；Reduce Motion 开启时不执行
   请求的 scroll animation。
6. **无障碍**：关键交互至少 44pt；支持 Dynamic Type、VoiceOver 语义锚点、深色模式和系统 Reduce Motion。
7. **方向策略**：当前 iPhone Companion 为竖屏应用；收到设备旋转请求后仍须保持 transcript/composer 可用。
   全 App 横屏支持会影响四个 Tab 和 Viewer，应作为独立产品决策，不由本视觉需求暗中开启。

## 验收标准

1. 普通字号下用户 surface 不超过内容阅读宽度 80%，Agent surface 不小于 92%；辅助字号允许用户 surface 放宽。
2. user/assistant 使用各自真实 accessibility surface ID，不能用无尺寸的伪锚点冒充布局证据。
3. 标题、两种列表、代码、引用和链接有真实 SwiftUI/XCUITest 覆盖。
4. Tool、普通附件和 Personal App 在浅/深色模式保持可读；点击目标至少 44pt。
5. 最大辅助字号下 header、runtime、消息和 composer 不裁切关键操作；生产 `CompanionTabView` 中 composer 与 Tab Bar
   间距不超过 16pt。
6. 设备旋转请求、Reduce Motion policy、用户暖色 Query、生产 shell 和关键截图均通过 simulator 验证。
7. Current 原型与生产控件、文案和状态一致；不存在的回复操作不得继续出现在 Current。

## 实施边界

- owner：`ChatView.swift`、`MessageBubbleView.swift`、`ComposerView.swift`、相关卡片视图、debug fixture 和 XCUITest。
- 不修改服务端协议、消息持久化结构、工具权限边界、Harness 故障分类或 Retry 状态机。
- 不为 Chat 单页新增全 App 横屏产品能力，不复制豆包商标、素材或专有视觉。Full gate 发现 XcodeGen 未表达既有
  iPhone 竖屏策略时，只恢复 source of truth；iPad 原有多方向能力保持不变。
- `ChatView` 是 iOS red-light path，本需求按 Full pipeline 执行并需要两类 reviewer 复核。

## 当前验证事实

- 用户 Query 暖色改版：浅色/深色命名 XCUITest 2/2 PASS，截图已人工复核。
- Chat interaction policy（含宽度与 Reduce Motion）：17/17 XCTest PASS。
- 旋转请求下竖屏 scene 可用性：命名 XCUITest 1/1 PASS。
- Agent-first 完整命名套件：7/7 XCUITest PASS，覆盖 light/dark、XXXL、标准附件、44pt 操作区、生产 shell
  与方向策略。
- 完整 scheme：337/337 PASS（268 XCTest + 69 XCUITest，0 failure、0 skipped），最新结果包：
  `/tmp/SkillForge-Markdown-Full-20260718.xcresult`。
- Release simulator build：PASS；XcodeGen 连续生成的 `project.pbxproj` SHA-256 均为
  `048f4ac6b474317a5fc9d7718020a1d327a3b2f80e1357e4c4547fe28dab86ed`。
- HTML Current：DOM/JavaScript 冒烟与 Quick Look 静态视觉复核 PASS；自动浏览器运行时不可用，未声称点击交互 PASS。
- 真机视觉、VoiceOver 顺序、Xcode GUI no-prompt、Keychain、附件真实分享、APNs、后台与 LAN/Tailscale：`NOT_RUN`；
  generic iOS device signing build 已连续两次 PASS。
