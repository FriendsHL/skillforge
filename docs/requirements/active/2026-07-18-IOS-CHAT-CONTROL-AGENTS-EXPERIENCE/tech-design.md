# IOS-CHAT-CONTROL-AGENTS-EXPERIENCE — 技术设计

## 风险与 pipeline

`ChatView.swift` 属于 iOS red-light path；本包按 Full pipeline。首批 production change 只允许包含有明确验收的 P0
scroll bug。Header、Tool、Control、Agents 在 Proposed 原型获用户确认后进入同一 Full batch，或按用户选择拆分为后续 batch。

## P0：滚动到底部

### 调查假设

当前 `handleBottomButtonTap` 只发出一次 animated `ScrollViewProxy.scrollTo`。fling 后 UIKit 仍在 deceleration 时，存在两种
互斥原因：

1. tap 被底层 scroll view 用于终止减速，Button action 未执行；
2. Button action 已执行，但随后 deceleration 更新覆盖了单次 proxy animation。

实现前必须通过 debug action marker / deterministic fixture 或可重复的 simulator 行为区分两者，不直接写固定 delay。

### 允许的修复形态

- 保留单一“滚到底部”命令 owner，避免 Button、keyboard fallback 和 streaming 各自竞争。
- 若 action 已送达：命令应显式终止/取代当前滚动目标，并以实际 bottom anchor 到达作为完成条件。
- 若 action 未送达：让 overlay control 获得明确的高优先级点击命中，同时维持至少 44pt hit target。
- Reduce Motion 使用非动画路径；键盘 settling 与现有 deferred fallback 继续共用 policy。
- 新命令必须可取消，Session 切换/视图销毁后不能继续操作旧 proxy。

### 测试

- policy unit tests：命令生成、取消、Reduce Motion、keyboard settling。
- XCUITest：长 transcript、fast fling、按钮 action marker、最终 bottom anchor。
- 现有 keyboard dismiss + bottom test 必须继续通过。

## P1-A：Chat Header

- 从现有 `selectedSessionTitle` 与 Agent/runtime state 派生一个纯 presentation model。
- healthy/running/waiting/error/cancelled 使用现有结构化状态，不靠错误字符串推断。
- Session title 视觉上最多一行，但完整文本通过 accessibility value 暴露。
- healthy compact line 合并进 subtitle；error detail panel 仍保持独立 view 和现有 Retry contract。
- `ChatMessageLayoutPolicy` 从固定 `0.78` 升级为可测试的短/中长/长 Query 分档；依据规范化可见文本长度与
  Dynamic Type 选择 0.76 / 0.86 / 0.92 / accessibility 1.0，不以屏幕像素或语言特定硬编码散落在 View 中。

## P1-B：Tool activity card

- 不改变 `ContentBlock` 或 mobile message 协议；只从现有 `toolCall` state/name/input/output 派生 presentation。
- 建立纯函数映射：raw tool name → humanized verb/subject；未知工具使用安全 fallback，不写死为成功动作。
- `pending/success/error` 各自拥有 icon、label、semantic color 和 disclosure policy。
- error summary 常显；input/output 使用 selectable monospace detail；长内容保留现有安全截断边界。
- Tool card 不触发 Tool 重执行。turn-level Retry 仍由 runtime error panel 提供。

## P1-C：Control

- 保留现有 Control 数据 owner；section view 独立处理 loading/error/empty，避免整页白屏。
- 推荐 section：Connection summary、Current work、Recent sessions、Automations、Workspace。
- 若现有 API 不提供聚合 Current work，则首版只展示可由 session/runtime list 可靠派生的状态，不新增伪数据。
- Personal Apps 继续从 Control → Workspace 进入。

## P1-D：Agents

- roster 行改为轻量 presentation model；search/filter 在本地对现有 Agent list 运行。
- default 与 current selection 使用不同字段和不同 accessibility 文案。
- 高级字段迁入 detail；若现有 detail route 不存在，先做 read-only sheet/navigation destination，不扩大到配置写 API。

## Prototype parity

- Proposed 新增 Chat identity + Tool states、Control hub、Agents roster 三屏。
- Current 不修改为尚未实现的形态。
- 用户确认后，将批准版本落地 SwiftUI；自动化和视觉证据通过后再把 Proposed 迁移为 Current。

## 验证矩阵

| 层级 | 证据 |
| --- | --- |
| Pure presentation/policy | XCTest |
| Scroll/keyboard/toolbar/tool disclosure | XCUITest |
| Light/Dark/XXXL/Reduce Motion | named UI fixtures + screenshot review |
| Existing Chat/Personal App regressions | complete iOS scheme |
| Distribution shape | Release simulator build |
| Project source of truth | XcodeGen twice + identical `project.pbxproj` hash |
| Prototype | DOM/JS smoke + browser interaction when runtime available + rendered visual review |
| Full review | iOS reviewer + test reviewer + judge verdict |

## P0 实施结果（2026-07-18）

- Button 的 authoritative path 由 `ChatBottomScrollCoordinator` 独占；Coordinator 通过 `ChatBottomScrollDriving` 注入底层执行器，不再依赖固定延迟或一次性动画与仍在运行的 UIKit 减速竞争。
- `UIKitChatBottomScrollDriver` 只负责停止当前滚动：iOS 17.4+ 调用 `stopScrollingAndZooming()`，17.0–17.3 使用保留当前位置的 scroll-enabled gesture reset fallback。停止后由当前 `ScrollViewProxy.scrollTo(bottomAnchor, anchor: .bottom)` 完成 SwiftUI 定位；不再读取瞬态 `contentSize` 计算或直接写 bottom `contentOffset`。
- request 包含 Session ID + generation；latest request coalescing、cancel、Session activation invalidation、closure 内重入切换与 driver-late-attach 都由 coordinator 契约管理。driver 未挂载时只保留最新 request；执行过 SwiftUI 定位命令后清理 pending。
- 键盘 dismiss fallback 使用独立且捕获 Session 的 task；跨 SwiftUI `.onChange` 的 deferred signal 也携带 Session ID，并在消费前校验当前 Session、Chat active 和 scene active。普通 realtime/streaming auto-scroll 继续使用原 owner，不能取消或误执行 manual request。
- Debug fixture 分别暴露 action count 与 command-performed count；后者只代表 proxy 命令已发出。最终几何位置由 UI 测试断言 latest message 完整包含在 transcript frame 内，Release 不包含可见测试 surface。
- 长会话 fixture 使用 46 条 history + 1 条 latest，并混入默认折叠的 180 行 Tool 输出，贴近真实会话中“大数据主要位于折叠 tool result”的消息形态。
- XCUITest 会等待应用 quiescence，无法制造真实的“减速尚未结束时点击”时序；该物理交互仍明确记为真机 `NOT_RUN`。
- Fresh focused evidence：policy 19/19、coordinator 7/7、Chat keyboard/scroll UI 5/5；两个高风险 UI 场景各重复 5 次，10/10 PASS。latest message 的 frame 断言负责几何确认，Debug command marker 只证明命令已发出。
- 最终完整 scheme 在本包 Chat 用例通过后，被无关 Interactive Artifact WebView accessibility runner 异常中断；随后现有、新建的 iOS 26.3/26.5 Simulator 均无法启动 `launchd_sim`。正常 Release simulator/device 构建也被宿主 `AssetCatalogSimulatorAgent` / system policy 阻塞，因此完整 scheme 与正式资源 packaging 仍为 `BLOCKED_ENV`。
- generic iOS code-only Release（显式排除 `Assets.xcassets`）已通过，只作为 Swift 编译证据，不视为正式 packaging PASS。

## 明确不改

- server runtime failure fact、Retry endpoint 与副作用策略；
- Tool execution contract、permissions、artifact scanner；
- database/schema；
- tab 数量与根导航结构。
