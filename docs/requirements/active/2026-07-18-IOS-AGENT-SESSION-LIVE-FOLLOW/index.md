# IOS-AGENT-SESSION-LIVE-FOLLOW — Agent/Session 导航、智能跟随与新消息提醒

> 状态：Phase 1（Mid 本地交互）已实现并验证；Phase 2 提醒能力待设计
> 模式：Mid 起步（本地导航、智能跟随、运行指示器与本地未读）；APNs/后台、协议或 WebSocket/REST 对账改动拆成独立 Full 增量
> 优先级：P1
> 来源：用户 2026-07-18 真机反馈

技术设计：[tech-design.md](tech-design.md)

## 用户问题

1. 不清楚如何切换 Agent 聊天，也无法自然地查看某个 Agent 的全部 Sessions。
2. 用户停留在 transcript 底部并发送 Query 后，希望在没有手动干预时，页面跟随 Assistant 的流式回复向下移动。
3. 用户主动向上阅读历史后，不能被流式更新强行拉回底部；同时需要知道有多少新内容。
4. 当前 Session、其他 Session 或 App 后台收到新消息时，需要层级合适、不重复轰炸的提醒。
5. 用户发送 Query 后，在 Assistant 尚未产出正文前缺少明确反馈，不确定请求是否已经开始运行。

## 产品目标

- 以 Agent 为入口即可开始新对话、继续最近对话或查看该 Agent 的所有 Sessions。
- 将自动滚动定义为“用户授权的跟随模式”，而不是每个 streaming delta 都无条件跳转。
- 当前页面、其他会话和系统后台各有明确的新消息反馈，并能一键定位到来源 Session。
- “当前 Agent”与“新会话默认 Agent”继续保持独立语义。

## 推荐交互

### Agent → Session

- Agents roster 点击行进入详情，不直接静默切换当前 Chat。
- Agent 详情提供三个明确动作：`开始新对话`、`继续最近对话`、`查看全部 Sessions`。
- `查看全部 Sessions` 打开带固定 Agent filter 的 Session 列表；顶部可切换 `该 Agent / 全部 Agent`。
- 全局 Sessions 增加 Agent filter，并在每行显示 Agent 名称；搜索同时匹配 Session 标题与 Agent 名称。
- 选择已有 Session 后切到 Chat，并更新“当前 Agent”；只有显式创建新会话时才更新新会话 Agent 偏好。

### 智能跟随

- 用户位于底部、发送 Query 后进入 `following`。
- Assistant 流式增长期间按节流后的布局帧跟随真实最后内容，保持回复尾部可见；不对每个 token 单独动画。
- 用户向上拖动超过阈值后进入 `pausedByUser`，后续流式内容不得抢回滚动位置。
- 暂停时到底按钮显示新内容计数，例如 `↓ 3 条新消息`；点击后平滑回到底部并恢复 `following`。
- Session 切换、跳转到来源消息、键盘 settling、Reduce Motion 均由同一 scroll owner 处理。

### 发送后的运行/回复指示器

- Query 被服务端接受后，在 Assistant 消息位置显示紧凑的 `···` 呼吸/跳动动效，并提供“正在运行”或“正在回复”的可访问文案。
- 尚无文本、但 Agent 正在规划或等待首个 delta：显示“正在运行”。
- 收到首个 Assistant 文本 delta 后，`···` 占位平滑让位给真实流式正文，不得同时保留两个 Assistant 气泡。
- Tool 已开始但尚无正文时，可显示“正在使用工具”，具体活动继续由 Tool 卡片表达。
- 进入 `waiting_user`、成功完成、失败、取消或切换 Session 时立即停止动效，不得无限悬挂。
- Reduce Motion 下使用静态 `···` 加状态文案；VoiceOver 不逐点朗读，只宣布一次状态变化。

### 新消息提醒

| 场景 | 提醒 |
| --- | --- |
| 当前 Session 且正在跟随 | 不打扰；页面自然跟随 |
| 当前 Session 但用户在读历史 | 到底按钮显示新消息/新内容计数；Assistant turn 完成时最多一次轻触反馈 |
| 其他 Session，App 在前台 | Chat tab badge + 顶部轻量 banner，显示 Agent/Session，可点击跳转 |
| App 在后台或锁屏 | 复用 task completion APNs；点击通知深链到具体 Session |

不得按 streaming token 发通知、声音或 haptic。未读计数以“完整 Assistant 消息/turn”为单位，不以 delta 为单位。

## 验收标准

1. 从 Agents 详情最多两次点击进入该 Agent 的全部 Sessions，并可直接继续任意 Session。
2. 用户贴底发送 Query 后，在不操作的情况下持续看到流式回复尾部。
3. 用户主动上滑后，至少在点击到底按钮前不会被新 delta 拉回底部。
4. 暂停跟随期间新内容计数准确；回到底部后清零并恢复跟随。
5. 其他 Session 的完成消息产生可定位的前台 badge/banner；后台 APNs 不重复发送。
6. 当前 Agent、默认 Agent、Session Agent 不混写；打开旧 Session 不改变新会话偏好。
7. VoiceOver 能读出“有 N 条新消息”和来源 Agent/Session；Reduce Motion 使用非动画定位。
8. 长 transcript、键盘、快速惯性、Session 切换、前后台切换均有专项验证。
9. Query 被接受后、首个 Assistant delta 到达前存在明确运行反馈；首 delta、失败、取消和等待用户时无重复气泡或悬挂动效。

## 交付状态（2026-07-18）

Phase 1 已交付并通过 focused XCTest/XCUITest 与 Release Simulator build：

- Agent 详情支持开始新对话、继续最近对话、查看该 Agent 的 Sessions，并可在该 Agent/全部 Agent 间切换。
- 当前 Session 支持贴底流式跟随、阅读历史时暂停、按 Assistant turn 计数，以及平滑回到底部。
- Query 发送后提供 `···` 正在运行/正在使用工具反馈，并在正文、等待用户或终态出现时正确移除。
- Agent 路由仅影响当次新会话入口，不污染普通 `+` 的默认 Agent 偏好。

尚未交付、需作为独立 Full 增量继续设计：

- 其他 Session 的 Chat tab badge、前台 banner 与跨 Session 未读聚合。
- APNs、后台/锁屏提醒、通知深链及跨设备已读同步。
- WebSocket/REST completion 对账协议与服务端 read cursor。

## 非目标

- 本期不做跨设备已读同步；若需要持久化 unread，需要单独服务端协议与数据设计。
- 不让 roster 行点击直接创建会话，避免误操作和 Session 泛滥。
- 不把每个 Tool delta 计为独立未读消息。
