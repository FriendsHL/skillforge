# OpenClaw iOS 调研与 SkillForge 可操作性报告

> 调研日期：2026-07-18
> 结论：可操作，但应复用“移动信息层级”而不是复制 OpenClaw 的功能清单或视觉资产。

## 调研范围与证据等级

本报告只用官方一手材料判断 OpenClaw iOS 的现状：官方 App Store、官方 GitHub 仓库与 iOS 源码，以及 Apple HIG。
网页截图或第三方评测不作为产品事实来源。

- [OpenClaw App Store](https://apps.apple.com/us/app/openclaw-ai-that-does-things/id6780396132)
- [OpenClaw 官方仓库](https://github.com/openclaw/openclaw)
- [OpenClaw iOS README](https://github.com/openclaw/openclaw/blob/main/apps/ios/README.md)
- [OpenClaw iOS Root Tabs](https://github.com/openclaw/openclaw/blob/main/apps/ios/Sources/RootTabsNavigation.swift)
- [OpenClaw Phone Control Hub](https://github.com/openclaw/openclaw/blob/main/apps/ios/Sources/Design/RootTabsPhoneControlHub.swift)
- [OpenClaw Command Center](https://github.com/openclaw/openclaw/blob/main/apps/ios/Sources/Design/CommandCenterTab.swift)
- [OpenClaw Agent Tab](https://github.com/openclaw/openclaw/blob/main/apps/ios/Sources/Design/AgentProTab.swift)
- [OpenClaw Chat Tab](https://github.com/openclaw/openclaw/blob/main/apps/ios/Sources/Design/ChatProTab.swift)
- [OpenClaw Chat Message Views](https://github.com/openclaw/openclaw/blob/main/apps/shared/OpenClawKit/Sources/OpenClawChatUI/ChatMessageViews.swift)
- [Apple HIG — Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars)
- [Apple HIG — Disclosure controls](https://developer.apple.com/design/human-interface-guidelines/disclosure-controls)
- [Apple HIG — Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)
- [Apple HIG — Progress indicators](https://developer.apple.com/design/human-interface-guidelines/progress-indicators)

## 官方产品事实

OpenClaw 把 iOS 定位为私有 Gateway 的 companion，而不是独立管理后台。官方 App Store 强调聊天、语音、审批、分享、
设备能力、推送唤醒与状态；官方仓库把 Gateway 定义为 sessions/channels/tools/events 的本地优先控制面。

其当前 phone tab 是 Chat、Talk、Control、Agent、Settings。Control 是通向运行状态和运营功能的 hub；Agent 仍保留独立
top-level tab。这与 Apple 建议“tab 表示少量、稳定的顶层目的地，而不是动作按钮”的原则一致。

## 可复用的交互模式

### 1. Chat：标题承担身份，状态只做紧凑反馈

OpenClaw Chat 使用 Agent 名作为 navigation title，leading avatar 与 trailing connection pill；健康连接不会再占一条全宽 Banner。
Session、后台任务、导出与 reasoning/tool activity 放入菜单或后续页面。

SkillForge 需要比 OpenClaw 多表达 Session，因此最合适的适配是：

- Session 作为当前上下文主标题；
- Agent + 连接/运行状态作为次标题；
- 结构化错误仍展开，因为 SkillForge 已有 failure source 与安全 Retry，不能为了压缩 Header 而丢失。

### 2. Tool：活动摘要优先，技术详情按需展开

OpenClaw 对运行中工具使用紧凑列表、spinner、显示名和 1–2 行 detail；完成结果以弱化 card + preview 呈现，并提供
“Show full output”。这适合手机，因为用户先理解 Agent 正在做什么，原始参数和长 output 不会打断正文。

SkillForge 应在此基础上再加强失败语义：运行中为蓝/中性，成功为克制的绿色，失败为红色。Tool 卡不开放单独 Retry；
是否安全重试仍由 turn 级结构化状态决定。

### 3. Control：运行总览而非配置堆叠

OpenClaw 的 phone Control 顶部先给 Gateway identity、active Agent 和 connection，再提供 Chat/Talk 及更深层 operational
destinations。Command Center 进一步展示连接、地址、Agent 数量、默认 Agent Session 和最近 Session。

SkillForge 已有 Control、Schedules、Sessions、Personal Apps 与结构化 runtime，因此可以低风险组成：

1. SkillForge Mac / endpoint 健康摘要；
2. 当前工作（running / waiting / error）；
3. 最近对话；
4. 自动化；
5. Workspace。

这不要求发明 OpenClaw 的 Terminal、Dreaming 或 Usage。

### 4. Agents：列表负责选择，详情负责理解配置

OpenClaw Agent tab 采用 searchable roster，并按全部/在线/ready 筛选；row 主要是 avatar/status、name、single-line detail
和 active marker。Skills、Instances、Cron、Usage、Files 等信息在后续目的地，而不是塞进首屏 row。

SkillForge 可直接采用同一层级原则：roster 保持轻量，详情再展示 model、role、tools、skills、prompt metadata。当前 Agent
与默认 Agent 必须分别标记，因为前者属于 Session，后者属于新会话默认策略。

## Apple HIG 对方案的约束

- Tab 必须保持稳定且表示顶层目的地，所以继续保留 Chat / Control / Agents / Settings，不把 Retry 或 New Session 做成 tab。
- Feedback 应靠近它描述的对象；健康连接适合放在 Header 次标题，关键故障才需要更强的错误面板。
- Disclosure 适合隐藏长参数、output 和高级配置，但关键错误原因不能只藏在 disclosure 里。
- 不确定时长的异步工具适合 spinner；若操作停滞或失败，必须给出明确、可行动的反馈。

## 可操作性与风险

| 项目 | 可操作性 | 主要风险 | 控制方式 |
| --- | --- | --- | --- |
| Chat 两行 Header | 高 | 长 Session / Dynamic Type | truncation + accessibility label + XXXL fixture |
| Tool 紧凑三态 | 高 | 为视觉隐藏诊断信息 | raw details 保留 disclosure；失败摘要常显 |
| Control hub | 中高 | 入口多但数据源分散 | 只组合现有 API，逐 section 独立 loading/error |
| Agents roster | 高 | 当前与默认语义混淆 | 明确两种 badge 与来源字段 |
| fling 中滚到底部 | 中 | SwiftUI scroll action 被 UIKit deceleration 覆盖 | 先取行为证据，再选择可测试的 cancellation/position 策略 |

## 结论

这组改版不需要改变 Tool/Session/Agent 的核心服务端协议，主要是 iOS 信息架构和呈现层工作，因此总体可操作。
最重要的边界是：OpenClaw 只能证明这些移动层级已经在相似 companion 产品中成立，不能证明 SkillForge 应复制其所有入口。
本轮 Proposed 原型只使用 SkillForge 已有能力；任何新增 API 必须另行进入需求评审。
