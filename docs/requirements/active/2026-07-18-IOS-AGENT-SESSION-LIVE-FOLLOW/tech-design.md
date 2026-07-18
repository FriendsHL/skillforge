# IOS-AGENT-SESSION-LIVE-FOLLOW — 技术设计

> Mid 本地交互方向已获用户批准；若需要改 APNs/后台、wire contract 或 WebSocket/REST reconciliation，则该部分升级为独立 Full hard gate。

## 方案比较

### 方案 A：继续复用现有 `isTranscriptPinnedToBottom` 布尔值

改动最小，但无法可靠区分程序滚动、用户拖动、跳转历史与真实几何贴底；新消息计数和跨 Session 提醒会继续散落在 View 事件里。不推荐。

### 方案 B：本地 scroll-follow 状态机 + 本地未读聚合（推荐）

- 状态：`following / pausedByUser / programmaticJump / keyboardSettling`。
- 通过滚动几何和用户手势意图决定状态；流式 delta 只更新内容，节流后的 scroll owner 决定是否跟随。
- 当前运行期间按 Session 聚合本地 unread turn；WebSocket completion、REST catch-up 去重后只计一次。
- Agent detail 和 Session filter 使用现有 Agent/Session API，不新增服务端协议。

优点是可以先解决用户当前真机体验，风险与协议范围可控。缺点是重装、跨设备或系统清理后未读状态不会保留。

### 方案 C：服务端持久化 read cursor / unread count

支持跨设备一致未读，但需要 session/message read cursor、推送去重、设备级还是用户级已读语义，以及数据库/API/WS 全链路变更。适合作为后续 Phase 2，不应绑进本轮 iOS 交互修复。

## 推荐架构

1. `ChatScrollFollowPolicy`：纯状态转换和 near-bottom 阈值；Phase 1 使用 `following / pausedByUser` 两态，不直接操作 proxy。程序跳转和键盘 settling 继续由既有 coordinator 的短生命周期任务管理，不扩展为持久状态。
2. `ChatScrollCoordinator`：继续作为唯一滚动命令 owner，合并 manual bottom、streaming follow、keyboard fallback 和 source jump。
3. `SessionUnreadStore`（Phase 2）：按 Session ID 聚合完整 Assistant turn；当前 Session 到底或用户打开目标 Session 时清零。
4. `CompanionTabView`：Phase 1 持有 Agent/Session route；Phase 2 再持有跨 Session badge/banner。两者均不得复用新会话 Agent preference。
5. `AgentsView`：详情动作通过显式 route 回调进入 filtered Sessions 或 Chat。
6. `AssistantActivityPresentationPolicy`：从发送已接受、runtime、streaming text 与 Tool 状态纯函数派生 `hidden / running / replying / usingTool`。

## Phase 1 已锁定语义

- Scroll 持久状态为 `following / pausedByUser`；真实 bottom distance 是贴底事实源。只有明确阅读更早历史的手势暂停，真实回到底部或点击到底按钮恢复。
- streaming delta 经现有节流后触发 follow；不得逐 token 新建动画或滚动任务，Coordinator 继续是唯一命令 owner。
- 未读仅覆盖当前 Session：暂停后同一 Assistant turn 的首个可见新内容使计数 +1，同 turn 后续 delta 不累加；下一 Assistant turn 才再次 +1。点击到底、真实回到底或重新进入该 Session 清零。
- Phase 1 不实现跨 Session unread store、Tab badge/banner、APNs、后台通知或 WS/REST 对账；这些能力没有可靠现有事件源时不得用轮询或伪状态冒充。
- Agent 详情三个动作使用显式 route；打开旧 Session 只更新 current Agent，只有成功创建新会话才更新新会话偏好。

## `···` 指示器状态转换

| 输入状态 | 展示 |
| --- | --- |
| send accepted，runtime running，尚无 Assistant delta | `··· 正在运行` |
| Assistant 首个文本 delta 到达 | 移除占位，由同一稳定 streaming row 展示正文 |
| Tool pending 且正文为空 | `··· 正在使用工具` + 现有 Tool activity card |
| waiting_user | 移除动效，展示现有 Ask/Confirmation card |
| idle/completed | 移除动效 |
| error/cancelled | 移除动效，展示现有 runtime error/cancelled 状态 |

指示器必须复用稳定的 streaming-tail identity，不能在首 delta 时插入第二条 Assistant row。动画只驱动视觉 opacity/scale，不触发网络、滚动或业务状态写入。

## 关键不变量

- streaming text 使用稳定 message identity，不能因每个 delta 重建 transcript。
- delta 继续节流；scroll follow 与 UI state 更新不能逐 token 执行。
- WebSocket completion 与 REST catch-up 必须按 message identity 去重，避免未读 +2 和重复通知。
- 用户手势暂停优先级高于自动跟随，只有明确到底动作或真实 near-bottom 才恢复。
- 后台 APNs 和前台 banner 对同一 completion 只选择一个展示面。

## 验证

- XCTest：状态机、阈值、计数、Assistant activity presentation、Session 切换、Reduce Motion；只有进入 Full 增量时才加入 WS/REST 去重。
- XCUITest：发送后 `···` 出现并在首 delta/失败时消失、贴底流式跟随、上滑暂停、计数与恢复、Agent filtered Sessions；跨 Session banner route 随对应增量验证。
- Phase 1 已完成：33 个 focused unit tests、4 个关键 XCUITest、额外 route reset/activity retry 回归，以及 Release Simulator build。
- Phase 1 已安装到连接真机，供物理惯性和长回复体验验收；前后台通知深链属于 Phase 2。
