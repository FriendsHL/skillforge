# IOS-AGENT-FIRST-CHAT — Technical Design

> 决策状态：已实现，Full automated gate 通过
> 生产边界：仅 iOS Chat 呈现层；不改 wire contract、消息状态机或 Retry 语义

## 采用方案

采用“在现有 Chat 视图树中做角色感知布局”：

- `MessageBubbleView` 根据 role 使用两套 surface：user 为普通字号 78% 上限的紧凑气泡，assistant 为透明全宽正文。
- `ChatView` 保留既有 ScrollView、streaming identity、keyboard、runtime failure 和 Retry state，只重排 header、状态视觉
  与行间距。
- `ComposerView` 保留附件、草稿和发送 policy，只把外观收成紧凑输入胶囊。

没有新建第二套 transcript，也没有用 overlay 重写 safe-area/keyboard 生命周期，避免复制 WebSocket/REST handoff、
auto-scroll 与 streaming tail 逻辑。

## 视觉与自适应语义

1. user surface 右对齐；普通字号使用 78% 宽度上限，accessibility size 放宽为 100%。
2. user surface 使用动态暖色：light 为低饱和暖桃色 + 轻橙描边，dark 为暖棕色 + 深橙描边；文字仍使用系统
   `.primary`，避免固定黑/白破坏对比度。
3. assistant surface 使用完整内容宽度，无整段背景、边框或阴影；Tool/Attachment/Personal App 为正文内模块。
4. assistant header、Chat header 和 Composer chrome 限制自身 Dynamic Type 膨胀，正文保持用户选择的辅助字号。
5. Tool 与 Attachment surface 使用系统动态背景/边框，不硬编码白色。
6. 连接正常时状态压为单行；运行、失败、等待和 Retry 继续使用既有结构化 presentation policy。
7. Composer 位于真实 `CompanionTabView` Tab Bar 上方，生产 shell 自动化约束两者空隙不超过 16pt。

## 内容与操作边界

- `MarkdownText` 当前 UI 覆盖标题、段落、有序/无序列表、代码块、引用和行内链接，并为各 block 提供稳定
  accessibility identifier。
- 本需求不承诺新 table/citation parser，不执行任意 Markdown HTML。
- 不新增复制、重新生成、朗读、回复分享或模型/模式 chip；Retry 继续走既有安全运行恢复入口。
- `tool_use` / `tool_result` 聚合、Attachment download state、Personal App capability/provenance 均不变。

## Ownership Matrix

| 文件 | 责任 | 禁止事项 |
| --- | --- | --- |
| `Chat/MessageBubbleView.swift` | role-aware 宽度、user surface、assistant 元信息、结果模块 | 不改变 message/tool/attachment 数据 |
| `Chat/ChatView.swift` | compact header/runtime、transcript spacing、Reduce Motion scroll | 不改变请求、Retry、streaming、session state |
| `Chat/ComposerView.swift` | 紧凑输入 surface、44pt controls | 不改变草稿/附件/发送 policy |
| `Chat/MarkdownText.swift` | 现有 Markdown block 的可访问锚点 | 不扩张 parser/HTML 边界 |
| `Attachments/**` | 动态色、全宽结果模块、点击区 | 不改变下载或 Personal App 安全模型 |
| `App/DebugLaunchConfiguration.swift` | deterministic light/dark/attachment/shell fixture | 不访问真实网络或 Keychain |
| `SkillForgeUITests/AgentFirstChatLayoutUITests.swift` | 宽度、点击区、深浅色、Dynamic Type、方向策略、截图 | 不依赖真实后端 |

## 不变量

- Streaming tail stable ID、REST/WebSocket handoff 和 auto-scroll 触发条件保持不变。
- Runtime failure 红色状态、故障来源、operation token 和安全 Retry 不变。
- 用户只有附件时仍右对齐；assistant 只有工具或附件时仍占完整结果宽度。
- Reduce Motion 开启时请求的 scroll animation 被关闭，但滚动动作本身仍执行。
- iPhone 当前为竖屏 scene；本需求不修改全 App `UISupportedInterfaceOrientations`。
- debug fixture 只在 `DEBUG` 编译，不读取生产认证状态。

## 测试设计

### XCTest

- user/assistant 普通与 accessibility 宽度 policy。
- Composer send/draft、scroll、streaming identity、runtime presentation 回归。
- Reduce Motion 对请求动画的禁用 policy。

### XCUITest

- assistant surface ≥92%、user surface ≤80%，并直接查询真实 surface identifier。
- light/dark 用户 Query 截图；dark Markdown、Tool 与标准附件可读性。
- 标题、两种列表、代码、引用、链接存在且可访问。
- XXXL 下 header、runtime、composer containment 与关键 44pt controls。
- 上传附件移除按钮、回到底部、附件和发送按钮至少 44pt。
- 真实 `CompanionTabView` shell 中 composer 与 Tab Bar 相邻。
- 发出 landscape 设备方向请求后，当前 portrait-only scene 中 transcript/composer 仍存在且可操作。

### Full Gate

1. XcodeGen 连续生成 determinism。
2. 聚焦 XCTest 与完整 `AgentFirstChatLayoutUITests`。
3. 全部 `SkillForgeTests`。
4. 完整 scheme test，以 `.xcresult` 记录精确执行数与失败数。
5. Release simulator build。
6. light/dark/XXXL/production-shell 稳定截图 + accessibility frame 断言。

Agent-first 原始 Full gate 通过后，柔和蓝 Query 与 Markdown 语义块在独立 Mid 批次增加覆盖。最新结果：
完整 scheme 337/337 PASS（268 XCTest + 69 XCUITest）；Release simulator build PASS；XcodeGen 连续生成
SHA-256 一致。结果包为 `/tmp/SkillForge-Markdown-Full-20260718.xcresult`。

## 真实设备边界

Simulator 可证明布局和确定性交互，但真机视觉、VoiceOver 顺序、签名、Keychain、附件真实分享、APNs、后台恢复、
LAN/Tailscale 继续记录为 `NOT_RUN`，不由本需求冒充完成。
