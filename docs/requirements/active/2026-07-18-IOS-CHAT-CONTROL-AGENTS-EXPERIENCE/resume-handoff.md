# IOS-CHAT-CONTROL-AGENTS-EXPERIENCE — 重启续跑交接

> 记录时间：2026-07-18
> 当前结论：P0 代码与聚焦验证完成，最终 Full 门禁被 Mac 宿主环境阻塞；P1-A～P1-D 已完成调研和确认原型，尚未落地真实 SwiftUI。
> 提交状态：未 commit、未 push。

## 一句话恢复上下文

用户报告 Chat 快速滑动后点击“回到底部”曾失效；第一版修复又会直接跳入空白区域。最终代码已经改为“UIKit 只停止惯性，SwiftUI `ScrollViewProxy` 定位真实 bottom anchor”，不再计算或写入底部 `contentOffset`。接下来应先在 Mac 重启后补齐 P0 完整门禁和真机验收，再继续实现已确认的 Chat Header、Tool 卡片、Control Hub 和 Agents Roster。

## 当前需求范围

| 阶段 | 内容 | 当前状态 |
| --- | --- | --- |
| P0 | Chat “回到底部”在拖动、惯性、键盘切换下可靠，且不能跳入空白区 | **代码完成；focused verified；final Full gate `BLOCKED_ENV`** |
| P1-A | Session 主标题 + Agent/连接状态两行 Chat Header；长 Query 自适应宽度 | **调研/原型已确认；生产未完成** |
| P1-B | Tool 运行中、成功、失败三态活动卡；原始输入/输出按需展开 | **调研/原型已确认；生产未完成** |
| P1-C | Control Hub：连接摘要、当前工作、最近对话、自动化、Workspace | **调研/原型已确认；生产未完成** |
| P1-D | Agents：搜索/筛选、当前/默认标记、轻量 roster、分层详情 | **调研/原型已确认；生产未完成** |
| Parity | 已实现页面迁入 Current 原型并完成视觉/无障碍校准 | **待 P1 生产实现后同步** |

用户已经批准 Proposed 方向。旧文档中的 “pending user review” 属于过期状态，恢复开发时不需要再次等待视觉确认。

## P0 已完成

### 根因

- 初始 Button 只调用一次 animated `ScrollViewProxy.scrollTo`，不能可靠取代仍在运行的 UIKit 惯性。
- 中间版本先停止惯性、再读取 `UIScrollView.contentSize` 并直接写 bottom `contentOffset`。
- 在长会话和 `LazyVStack` 瞬态布局下，UIKit 的内容尺寸可能尚未代表 SwiftUI 已实体化的真实列表，因而会跳进列表末端的空白区域。

### 最终实现

- `UIKitChatBottomScrollDriver` 只负责停止当前滚动：
  - iOS 17.4+：`stopScrollingAndZooming()`；
  - iOS 17.0–17.3：保留当前位置、toggle `isScrollEnabled` 终止 gesture、恢复当前位置。
- 真正的到底部定位只由当前 `ScrollViewProxy.scrollTo(bottomAnchor, anchor: .bottom)` 执行。
- Coordinator 使用 Session ID + generation 管理合并、取消、driver 延迟挂载和旧请求失效。
- Session 切换、视图 inactive/disappear、scene 非 active 都会取消旧请求。
- 键盘收起后的 deferred request 携带 Session ID，消费前校验当前 Session、Chat active 和 scene active，不能落到新会话。
- Debug marker 只证明 tap/command 已送达；几何到达由 UI 测试断言 latest message 完整位于 transcript frame 内。

### 主要文件

- `skillforge-ios/SkillForge/Chat/ChatBottomScrollCoordinator.swift`
- `skillforge-ios/SkillForge/Chat/ChatView.swift`
- `skillforge-ios/SkillForge/Chat/ChatInteractionPolicy.swift`
- `skillforge-ios/SkillForge/App/DebugLaunchConfiguration.swift`
- `skillforge-ios/SkillForgeTests/ChatInteractionPolicyTests.swift`
- `skillforge-ios/SkillForgeUITests/ChatKeyboardUITests.swift`

## P0 Fresh evidence

| 门禁 | 结果 |
| --- | --- |
| Policy XCTest | 19/19 PASS |
| Coordinator XCTest | 7/7 PASS |
| Chat keyboard/scroll XCUITest | 5/5 PASS |
| Focused 合计 | **31/31 PASS，0 failed/skipped** |
| 高风险稳定性重复 | long transcript + fast fling 各 5 次，**10/10 PASS** |
| XcodeGen | 连续生成 hash 相同：`43bf7598b7fe5d7544bae927a5cfb55df7d0571439336c70f1b92dc2ebbebac2` |
| Generic iOS code-only Release | PASS；显式排除了 `Assets.xcassets`，只能证明 Swift 编译 |
| Full judge | Spec PASS；Quality PASS WITH WARNINGS；Overall `BLOCKED_ENV` |

关键结果包：

- `/tmp/SkillForge-DeferredBottomSession-GREEN-20260718.xcresult`
- `/tmp/SkillForge-BottomScroll-Coordinator-GREEN-20260718.xcresult`
- `/tmp/SkillForge-ChatKeyboard-Final-GREEN-20260718.xcresult`
- `/tmp/SkillForge-BottomScroll-Stability-5x-GREEN-20260718.xcresult`

## 当前环境阻塞

- 最终完整 `SkillForge` scheme 已开始运行，AgentFirst 9/9、ChatKeyboard 5/5，以及后续多组 Control/Agents/connection 用例通过。
- 随后无关的 Interactive Artifact WebView accessibility 测试发生 WebKit runner 异常和重启，继而 CoreSimulator host 失效；被终止的结果包已损坏，不能声明完整 scheme PASS。
- 现有 iOS 26.3、iOS 26.5 和新建 Simulator 都无法启动 `launchd_sim`。
- 正常 Release simulator 和 generic iOS device 构建均在 Asset Catalog 阶段失败：`AssetCatalogSimulatorAgent` 无法通过 CoreSimulator spawn，runtime framework 被 system policy 拒绝加载。
- 用户级 CoreSimulator/Simulator 服务恢复和新建设备均已尝试，无效；当前系统错误建议重启 Mac。
- 这不是 P0 Swift 编译错误，也不是 App 修复本身要求重启；重启仅用于恢复 Xcode Simulator/Asset Catalog 宿主服务。

## 仍为 NOT_RUN

- 真机在 transcript 仍处于物理惯性减速时，单次点击按钮的最终验收。
- 真机确认最终版本不会再跳入整屏空白区域。
- iOS 17.0–17.3 gesture-reset fallback 分支。
- 最终工作树完整 `SkillForge` scheme。
- 正常 Release simulator 与 generic iOS device Asset Catalog packaging。

## P1 真实 App 差距

### P1-A — Chat Header / Query

当前真实 App 仍以 Agent 名为主标题，下面显示固定的 `Personal workspace`，运行状态另占一行。目标是：

- 当前 Session 名称作为主标题；
- `Agent · semantic status` 作为次标题；
- 健康态不再使用第三行状态条；
- 结构化错误详情、故障来源和安全 Retry 继续保留独立面板；
- Query 宽度按可见文本长度分档：短约 76%、中长约 86%、长文本最多 92%，Accessibility 可使用完整阅读宽度。

### P1-B — Tool activity card

当前真实 App 已有 pending/success/error icon 和 disclosure，但主标题仍直接使用 raw tool name，输入预览会抢占折叠态。目标是：

- 折叠态先显示人类可读动作、状态和 1–2 行结果摘要；
- pending / success / error 使用克制但明确的语义色；
- raw tool name、input、output、错误事实收入 disclosure；
- 错误摘要必须常显；
- 不增加 Tool 级 Retry，仍只允许服务端判定的 turn-level Retry。

### P1-C — Control Hub

当前真实 App 主要是简介、Scheduled Automations 和 Workspace。目标新增并重排为：

- Gateway/SkillForge Mac 连接摘要；
- 当前工作：running / waiting / error Session；
- 最近对话；
- 自动化；
- Workspace（Sessions、Personal Apps 等现有入口）。

只能组合现有 Session/Schedule/Agent API；不伪造 Usage、Terminal、Talk 等当前没有的数据和入口。

### P1-D — Agents roster

当前真实 App 已有搜索和 read-only detail，但 roster 行仍同时展示 role/model/status/visibility/source，扫描负担较重，也没有当前 Agent 筛选/标记。目标是：

- `全部 / 可用 / 默认` 筛选；
- 明确区分“当前 Agent”和“默认 Agent”；
- 行内只保留头像/状态、名称、一行用途、必要 badge 和一行弱化元信息；
- model、role、execution、skills、tools、prompt metadata 等继续放在现有 detail 中。

## Mac 重启后的执行顺序

1. **先恢复环境门禁**
   - 确认 Simulator 能正常 boot；
   - 确认 Asset Catalog agent 可运行。
2. **先关闭 P0**
   - 跑当前工作树完整 `SkillForge` scheme，要求 0 failed、0 unexpected skipped；
   - 跑正常 Release simulator build；
   - 跑正常 generic iOS device Release build；
   - 在真机安装最终版本，用 46+ 条消息和多个折叠大 Tool 输出验证：高速减速期间点击一次，latest 可见、没有空白屏、键盘路径正常。
3. **按 TDD 实现 P1-A**
   - 先补 Header presentation、Query width policy 和 UI tests；
   - 再改 `ChatView.swift` / `MessageBubbleView.swift`。
4. **按 TDD 实现 P1-B**
   - 先补 Tool presentation mapping / disclosure tests；
   - 再落地三态卡片和无障碍标识。
5. **按 TDD 实现 P1-C**
   - 先补 current work / recent sessions / summary policy；
   - 再重构 `ControlView.swift`，保持每个 section 独立 loading/error/empty。
6. **按 TDD 实现 P1-D**
   - 先补 filter/current/default policy；
   - 再精简 `AgentsView.swift` roster，复用现有 detail。
7. **同步 parity 与 Full gate**
   - 把已实现四屏从 Proposed 迁入 Current 原型；
   - 做 Light/Dark、XXXL Dynamic Type、Reduce Motion、VoiceOver/44pt 检查；
   - 聚焦 tests → 完整 scheme → Release → XcodeGen twice → iOS reviewer/test reviewer/final judge；
   - 用户批准后再分批 commit、一起 push。

## 恢复时不要做

- 不恢复“直接计算并写 bottom `contentOffset`”的方案。
- 不用固定 sleep 或重复 scroll 掩盖滚动根因。
- 不把 code-only Release 写成正式 packaging PASS。
- 不把被中断/损坏的 full result bundle 写成完整 scheme PASS。
- 不把 Proposed 原型写成已经落地的 Current App。
- 不在本包增加服务端协议、数据库字段或任意 Tool 重试。

## 相关文档

- [需求与当前门禁](index.md)
- [技术设计](tech-design.md)
- [调研报告](research-report.md)
- [交互原型](../../../prototypes/ios-assistant-companion/index.html)
