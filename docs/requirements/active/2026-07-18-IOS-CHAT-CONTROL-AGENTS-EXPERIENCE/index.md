# IOS-CHAT-CONTROL-AGENTS-EXPERIENCE — Chat、Control 与 Agents 移动体验收口

> 状态：P0 code complete / focused verified（31/31 + stability 10/10）/ final Full gate BLOCKED_ENV；P1 Proposed prototype pending user review
> 模式：Full pipeline（`ChatView.swift` 为 iOS red-light path）
> 优先级：P0 滚动恢复；P1 信息架构与视觉收口

调研：[research-report.md](research-report.md)
技术设计：[tech-design.md](tech-design.md)
重启续跑交接：[resume-handoff.md](resume-handoff.md)
交互原型：[iOS Companion Prototype](../../../prototypes/ios-assistant-companion/index.html)

## 用户问题

1. Transcript 仍在滑动或减速时，点击“回到底部”偶发无效。
2. Chat 顶部把 Agent、固定的 `Personal workspace` 和连接状态拆成三层，占用过多纵向空间，且没有展示当前 Session。
3. Tool 调用卡片偏管理端：原始工具名和参数过强，成功、进行中、失败的阅读层级不够清晰。
4. Control 目前主要罗列定时任务与 Workspace 入口，无法快速回答“现在系统在做什么、哪里需要我处理”。
5. Agents 目前把 role/model/status/visibility/source 全塞进列表，扫描效率低，也没有清楚区分当前 Agent 与默认 Agent。

## 产品目标

- “回到底部”在拖动、惯性减速、键盘切换和 Reduce Motion 下都可靠。
- Chat 健康态 Header 用两行表达当前 Session、当前 Agent 与连接状态；异常态继续保留完整故障归因与安全 Retry。
- Tool 调用成为 Agent 答案中的紧凑活动模块，用户先看到发生了什么和结果，再按需展开原始技术详情。
- Control 成为运行总览与高频入口，不复制 Agents 配置页。
- Agents 成为可搜索、可筛选的轻量 roster；详情页再承载模型、能力和配置事实。
- Proposed 先进入原型供用户确认；确认后再迁入真实 SwiftUI，并把原型切换为 Current。

## 实施顺序

1. **P0 — Scroll recovery**：先复现并区分“按钮 action 未送达”与“action 已送达但被减速覆盖”，再做最小修复和回归测试。
2. **P1-A — Chat identity header**：Session 主标题；`Agent · connection/runtime` 次标题；健康态不再占第三行。
3. **P1-B — Tool activity cards**：运行中、成功、失败三态；结构化摘要优先，原始 name/input/output 收入 disclosure。
4. **P1-C — Control hub**：Gateway/连接摘要、当前工作、最近对话、自动化、Workspace。
5. **P1-D — Agents roster**：搜索、状态筛选、当前/默认标记、轻量行与分层详情。
6. **Full gate**：专属测试、完整 iOS scheme、Release build、XcodeGen 确定性、reviewer/judge、原型与真 App parity。

## 已确认的信息层级

### Chat Header

- 主标题：当前 Session 名称；没有标题时显示现有安全 fallback，不伪造名称。
- 次标题：当前 Agent 名称 + 语义状态点 + `已连接 / 运行中 / 等待确认 / 运行失败`。
- 左侧：Sessions；右侧：新建对话。
- 健康态移除独立全宽状态条；结构化错误详情仍在 Header 下方展开，保留 failure source、endpoint 与安全 Retry。
- 用户 Query 使用自适应宽度：短文本约 76%，中长文本约 86%，长文本最多 92%；辅助字号可使用全部阅读宽度。

### Tool Card

- `pending`：spinner + 人类可读动作（例如“正在发布 Personal App”）+ 1–2 行参数摘要。
- `success`：check + 简洁完成文案；只有存在有用结果时才显示“查看结果”。
- `error`：红色语义、简洁错误摘要和“查看详情”；不提供 Tool 级 Retry。
- raw tool name、input、output、故障 provenance 必须仍可访问，不能为了好看而丢失诊断事实。
- Retry 仍是 turn 级能力，且只能由服务端 `retryable` 判定开放，避免重复副作用。

### Control

- 顶部显示已配对 SkillForge Mac/Gateway、连接状态和当前 Agent。
- “当前工作”聚合正在运行、等待用户或失败的 Session，并提供跳回 Chat 的安全入口。
- “最近对话”展示最近 Session 与未读/状态事实。
- “自动化”展示 schedule 的 enabled/paused/next-run 状态。
- “Workspace”承载 Sessions 与 Personal Apps 等现有入口。

### Agents

- 列表只显示头像/状态、名称、一行用途、当前或默认标记；模型等事实最多作为一行弱化元信息。
- 支持搜索与 `全部 / 可用 / 默认` 筛选。
- 点入详情后再显示 model、role、execution/reasoning/max loops、tools/skills、prompt metadata 等现有事实。
- “当前 Agent”是当前 Session 的选择；“默认 Agent”是新 Session 默认值，两者不可混写。

## 验收标准

1. 快速 fling 后，在 transcript 仍有惯性时点击“回到底部”，一次操作即可最终停在底部；按钮 action 和最终位置都有自动化证据。
2. 普通健康态 Header 不超过两行文本；同时可识别 Session、Agent 和连接/运行状态。
3. 长 Session 名称、长 Query、XXXL Dynamic Type、深色模式下不遮挡 Sessions、New Session 或错误 Retry；长 Query 在普通字号下不超过 92% 且保持右对齐角色识别。
4. Tool 三态具有不同但克制的语义色；失败不能被绿色或中性成功态掩盖。
5. Tool 原始输入/输出默认不抢占阅读区，但 VoiceOver 和 disclosure 可访问；目标至少 44pt。
6. Control 首屏可回答“连接是否健康、当前是否有工作、最近会话、自动化和 Workspace 在哪里”。
7. Agents 列表可在不展开详情的情况下识别当前/默认/可用状态；配置详情不重复挤在 roster 行里。
8. Current 原型只表示已实现 App；本轮未实现部分明确标记 Proposed，用户批准并落地后才能迁入 Current。
9. 不新增服务端协议、数据库字段、任意工具重试或未有 API 支撑的虚假入口。
10. 完整 iOS scheme、Release simulator build、XcodeGen 连续生成与视觉/无障碍专项验证全部通过后，才可声明完成。

## 非目标

- 不照搬 OpenClaw 的 Talk、Terminal、Dreaming、Usage 等 SkillForge 当前没有产品和 API 支撑的入口。
- 不把 Control 变成第二个 Settings，也不在 Agents roster 直接编辑全部高级配置。
- 不在本包改变 Harness 故障分类、消息协议、Tool 安全边界或服务端 Retry 判定。
- 不以固定延迟的“多滚几次”替代滚动问题的根因调查。

## 当前门禁

- P0 Scroll：最终实现已通过 26 项聚焦 XCTest、5 项 Chat XCUITest，以及两个高风险场景各重复 5 次的稳定性验证。最终完整 scheme 在 Chat 相关用例通过后，被无关的 Interactive Artifact WebView accessibility runner 异常和随后的宿主 CoreSimulator 崩溃中断，未产生可声明为 PASS 的最终完整计数；正常 Asset Catalog packaging 同样被宿主 system service 阻塞。真机仍需做一次真实惯性触控 dogfood。
- P1-A 至 P1-D：先交付 Proposed 原型与调研结论，等待用户视觉确认后进入生产 SwiftUI。
- 未经用户明确批准，不 commit、不 push。

## P0 实施与验证（2026-07-18）

### 根因与修复

- Debug action marker 证明自动化能够区分“按钮 action 是否送达”；同时确认 XCUITest 会等待 App quiescence，无法真实点击仍在减速中的窗口。
- 初版只在 Button 事务内发出一次 animated `ScrollViewProxy.scrollTo`，不能终止底层 `UIScrollView` 的减速。第二版虽然先停止减速，但又直接依据 UIKit 的 `contentSize` 写 `contentOffset`；真机长会话证明该值可能处在 `LazyVStack` 的瞬态布局阶段，导致跳进尚未实体化的空白区域。
- 最终修复把职责拆开：`UIKitChatBottomScrollDriver` 只终止惯性（iOS 17.4+ 使用 `stopScrollingAndZooming()`，iOS 17.0–17.3 使用 scroll-enabled gesture reset fallback），随后由当前 SwiftUI `ScrollViewProxy` 定位真实 bottom anchor。代码不再计算或写入 bottom `contentOffset`。
- Coordinator 在 driver 尚未挂载时保留最新 request；driver 可用后严格按“停止 UIKit 惯性 → 调用 SwiftUI bottom anchor”执行，不使用固定 sleep、轮询或重复跳转。
- Coordinator 用 Session ID + generation 隔离、合并和取消请求。Session change、inactive、disappear、full cleanup 都会失效旧 request；键盘收起后的 deferred signal 也携带 Session ID，并校验当前会话、Chat active 与 scene active，不能落到新 Session。
- Debug 自动化分别记录 tap delivery 与 bottom 命令发出。该 marker 不冒充几何确认；XCUITest 另行断言 latest message 的完整 frame 位于 transcript 可视范围内。Release 不暴露 marker，普通自动滚动与 Reduce Motion 的既有 policy 保持不变。

### Fresh evidence

| 门禁 | 结果 |
| --- | --- |
| TDD RED | coordinator/SwiftUI positioning 契约不存在时按预期编译失败；随后新增的 deferred Session policy 也先按预期编译失败（均 exit 65） |
| 聚焦 XCTest | 26/26 PASS：`ChatInteractionPolicyTests` 19/19 + coordinator 7/7，覆盖 ordering、coalescing、Session invalidation、cancel、late driver attach、closure 内重入和 deferred Session 隔离 |
| 聚焦 XCUITest | 5/5 PASS；长 transcript 与 fast fling 同时断言 Button action、SwiftUI command marker，以及 latest message 完整位于 transcript frame 内 |
| 稳定性重复 | 两个高风险 XCUITest 各重复 5 次，10/10 PASS |
| XcodeGen | 连续生成 SHA-256 均为 `43bf7598b7fe5d7544bae927a5cfb55df7d0571439336c70f1b92dc2ebbebac2` |
| 完整 `SkillForge` scheme | 最终工作树尝试中，AgentFirst 9/9、ChatKeyboard 5/5 及后续多组 Control/Agents/connection 用例通过；随后无关 Interactive Artifact WebView accessibility 用例异常，runner 重启并拖垮 CoreSimulator。结果包因终止损坏，因此本门禁为 `BLOCKED_ENV`，不是 PASS |
| Release compile | generic iOS code-only Release PASS；此证据显式排除了 `Assets.xcassets`，只证明最终 Swift 代码可编译，不能代替 packaging |
| Release packaging | simulator 与 generic iOS device 的正常构建都被同一宿主错误阻塞：`AssetCatalogSimulatorAgent` 无法通过 CoreSimulator spawn，runtime `CoreGraphics.framework` 被 system policy 拒绝加载。不是本次 Swift compile failure |
| Result bundles | RED：`/tmp/SkillForge-BottomScroll-SwiftUIPosition-RED-20260718.xcresult`、`/tmp/SkillForge-DeferredBottomSession-RED-20260718.xcresult`；GREEN：`/tmp/SkillForge-DeferredBottomSession-GREEN-20260718.xcresult`、`/tmp/SkillForge-BottomScroll-Coordinator-GREEN-20260718.xcresult`、`/tmp/SkillForge-ChatKeyboard-Final-GREEN-20260718.xcresult`、`/tmp/SkillForge-BottomScroll-Stability-5x-GREEN-20260718.xcresult` |

### Full review

- Test reviewer：PASS WITH WARNINGS，无 Blocker；XCUITest quiescence 不能证明物理减速过程中真实点击，且最终形态没有一个可稳定复现旧实现失败的 UI RED。其建议的高风险重复测试已补跑 10/10。
- iOS reviewer：PASS WITH WARNINGS；指出 deferred keyboard signal 的跨 Session 风险后，已改为携带 Session ID 并新增 policy/coordinator 回归。剩余 warning 为真机 non-quiescent 触控与 iOS 17.0–17.3 fallback 尚未执行。
- Final judge：待根据上述最终工作树证据与宿主阻塞重新裁决。P0 code/focused verification 可标完成，但最终完整 scheme 与正常 packaging 不可标 verified。

### 尚未运行

- 真机手指在 transcript 仍处于惯性减速时的点击验收为 `NOT_RUN`。XCUITest 在手势后会等待 quiescence，因此聚焦与完整自动化不能替代这项物理交互确认。
- iOS 17.0–17.3 的 gesture-reset fallback 为 `NOT_RUN`；当前自动化在 iOS 26.5 执行官方 `stopScrollingAndZooming()` 路径。
- 完整 Release Asset Catalog packaging 为 `BLOCKED_ENV`；需要先恢复 CoreSimulator host（当前系统错误建议重启 Mac）后重新运行，不能用 code-only build 冒充正式 packaging PASS。
- 宿主恢复后必须补跑当前工作树完整 scheme，以及正常 Release simulator / generic iOS device packaging；31 项 focused 与 10 次稳定性重复已有本轮 fresh PASS，无需冒充完整门禁。
- P1 Header、Tool、Control、Agents 与长 Query 自适应宽度尚未写入生产 SwiftUI；当前只在 Proposed 原型中供用户确认。
