# IOS-ASSISTANT-COMPANION — SkillForge iOS 个人助理入口

> 创建：2026-07-09
> 状态：active / V1 core implemented（Agent 文件交付真实 E2E 与 APNs 已拆独立 P1；TestFlight 和完整真机回归仍开放）
> 模式：Full（新增 SwiftUI iOS App + 后端移动端配对/API/auth/push + schema）
> 来源：用户希望参考 OpenClaw iOS 扫码连接方式，为 SkillForge 做偏个人助理入口的 iOS 产品。

## 摘要

SkillForge iOS 端不做 dashboard 缩小版，而做 **Assistant Companion**：
扫码配对自己的 SkillForge Server 后，默认进入助理 Chat，可以随时发起任务、查看运行状态、回答
`ask_user` / confirmation、上传文件或图片，并通过 push 接收任务完成/失败/需要确认通知。

## 已确认决策

- 产品定位：个人助理入口，不是移动管理后台。
- 用户模型：先单用户可用，但后端按多用户/多设备预留。
- 连接方式：参考 OpenClaw 的 Gateway pairing，Dashboard/Server 生成 QR，iOS 扫码配对。
- endpoint 策略：QR payload 使用 `endpoints[]`；App 保存完整候选集，运行时优先可达 LAN，
  LAN 不可用时回退 Tailscale/公网 HTTPS，不对可能已送达的业务 POST 做盲重试。
- 仓库形态：先放在 SkillForge monorepo，新增独立 `skillforge-ios/` iOS 工程；产品成熟后再评估是否拆 repo。
- 开发安装：V1 先用 Xcode 直装真机测试；TestFlight/APNs 证书链路在 Apple Developer Program 准备好后接入。
- 配对方向：不是 iOS 端展示二维码；是 SkillForge Dashboard/Server 生成二维码，iOS 端打开扫码器扫描管理端二维码。
- 连接环境：V1 支持 LAN/Tailscale/公网 HTTPS endpoint；Dashboard 可通过
  `VITE_SKILLFORGE_MOBILE_ENDPOINTS` 把额外地址加入二维码，iOS 持久化并监测完整候选集。
- 首屏：配对后默认进入 Chat。
- iOS 技术栈：SwiftUI 原生。
- 语音边界：V1 使用 iOS 系统键盘听写，不自建录音/转写/流式语音。
- V1 路线：Assistant Companion；V2 再做 Share Extension/真语音；V3 再做手机能力节点。

## V1 范围

- QR pairing + setup code fallback。
- 默认助理 Chat。
- session 消息历史和实时更新。
- running / idle / waiting_user / error 状态展示。
- `ask_user` / confirmation 卡片。
- 图片/文件上传。
- APNs push 注册与通知。
- 设备 token 可撤销。

## V1 不做

- 手机作为 agent tool node。
- 自建语音助手或流式音频。
- Apple Watch。
- dashboard 管理功能全集。
- trace 详情页。
- 高敏设备权限（通讯录/日历/定位等）。
- SkillForge 官方 relay。

## 阅读顺序

1. 本 `index.md`：状态、范围和已确认决策。
2. [mrd.md](mrd.md)：用户原始诉求、OpenClaw 对照、产品动机。
3. [prd.md](prd.md)：产品需求、非目标、验收标准。
4. [tech-design.md](tech-design.md)：扫码配对、数据模型、API、iOS 模块、安全和测试方案。
5. [implementation-plan.md](implementation-plan.md)：Full pipeline 实施切片、文件级任务、验证命令。

## 实现状态（2026-07-14）

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| QR pairing / setup code / device revoke | 已实现 | Dashboard 生成一次性 QR，iOS 扫码 claim，服务端只存 token hash，App 使用 Keychain。 |
| LAN + Tailscale endpoint fallback | 已实现 | QR 保存完整候选集；连接优先 LAN、失败后切 Tailscale/HTTPS，不盲重发业务 POST。 |
| Chat / sessions / realtime | 已实现 | 历史、发送、状态、流式文本、工具卡片、Markdown、断线 REST catch-up。 |
| ask/confirmation | 已实现 | 移动 facade 校验设备与 session ownership。 |
| 上传与 Agent 生成附件 | 基础设施已实现 / 用户流程未验收 | 图片/文件上传可用；生成产物已有 `PublishChatArtifact`、assistant ref 与鉴权下载，但 normal-chat image/document E2E 未通过且用户报告不可用，见独立 P1。 |
| Control / schedules | 已实现 | 计划列表、运行历史、立即运行、暂停/启用。 |
| Agents | 已实现（只读） | 查看身份、模型、执行策略、Skills、Tools 与 Prompt 配置摘要，不从该页创建 session。 |
| Settings / diagnostics | 已实现 | 连接健康、通知权限、外观、隐私、断开重配。 |
| APNs delivery | 未完成 / 已拆 P1 | 目前只有本地权限/注册骨架；证书、token 持久化、服务端发送和通知点击路由见独立需求。 |
| TestFlight / App Store | 未完成 | 当前正式支持 Xcode 开发签名直装真机。 |

## 当前执行

1. V1 core 已完成代码实现和本批 Full pipeline 验证，交付记录见 `docs/delivery-index.md`。
2. Agent 文件交付真实 E2E 见 [IOS-AGENT-FILE-DELIVERY](../2026-07-16-IOS-AGENT-FILE-DELIVERY/index.md)。
3. APNs delivery、证书链路和真实后台通知见 [IOS-TASK-COMPLETION-PUSH](../2026-07-16-IOS-TASK-COMPLETION-PUSH/index.md)。
3. Navigation Foundation：配对后使用原生四栏导航
   `Chat / Control / Agents / Settings`。Chat 仍是默认入口；Control 分别承载
   Schedule、Run 和 Session 入口；Agents 用于检查 Agent 配置与能力；Settings
   承载连接、设备和本地断开操作。
4. Outbound Artifact Foundation：Agent 能把本轮生成的图片、PDF、Word、Excel 或 CSV
   作为 assistant 附件消息交付给 Dashboard 和 iOS；移动端可鉴权下载、预览和分享，且不会破坏
   Agent Loop 消息对账、`tool_use` / `tool_result` 配对或 compact 恢复。
5. Interaction Polish：统一 Chat 首页与 Session List 的新会话入口；新会话显式选择
   Agent；Session List 提供搜索、状态筛选和刷新；Settings 增加真实的通知权限、外观、隐私与
   诊断状态，不展示尚未接通的管理能力。
6. Connection Health：Settings 使用 `/api/mobile/client/me` 提供可操作的连接摘要与诊断
    详情，安全区分网络、服务和认证失败，不展示原始错误正文，也不伪造实时连接状态。

## Navigation Foundation 已确认决策

- 底部导航固定为 `Chat / Control / Agents / Settings`，不在 V1 放置不可用的 Talk tab。
- Pending 继续在 Chat 内联处理；跨 session 待处理中心另行实现。
- Session 是对话上下文，Schedule 是触发规则，Run 是一次执行；三者不再互相伪装。
- Sessions 不单独占底部 tab；Chat 保留紧凑切换器，Control 提供完整会话入口。
- Control 首批提供定时计划列表、立即运行、暂停/启用、运行历史和 Session 入口；
  创建及复杂编辑继续在 Dashboard 完成。
- Agents 与 Chat 解耦：点击 Agent 打开配置详情，不创建 session、不切换 Chat，
  也不修改设备上的聊天 Agent 偏好。
- Agents Configuration Foundation 首版为只读移动配置中心：展示身份、状态、模型、
  执行策略、Skills、Tools 和 Prompt 配置状态；不返回 provider credentials、secret、
  lifecycle hook 原始 JSON 或其它内部持久化字段。
- 创建 session 和选择对话 Agent 继续由 Chat 的新会话流程负责。完整 Agent 创建、编辑、
  删除以及 Prompt/工具策略写入继续留在 Dashboard；移动端写配置另立高风险切片。
- Settings 的“断开并重新配对”只清理本机凭据；服务端撤销必须使用明确的撤销接口，
  两者不能混用文案。
- OpenClaw 的 Talk、手机节点、Workboard、Dreaming、Usage 和 Push Inbox 不进入本切片；
  Cron 只借鉴移动端聚合、运行和开关交互，不照搬 Gateway 管理全集。
- Chat 首页右上角 `+` 只表示新建对话，不再承载 Session 浏览或断开连接；Session 浏览由左侧
  入口负责，断开连接只在 Settings 出现。
- Chat 与 Session List 的 `+` 复用同一个 New Conversation 流程；创建前选择可对话 Agent，
  浏览 Agents 配置本身仍不修改 Chat。

## Outbound Artifact Foundation 已批准边界

- 新增独立的 `PublishChatArtifact` 工具；不扩展只面向微信 channel 的 `SendChannelFile`。
- 首版复用 `t_chat_attachment` 和现有 `image_ref` / `pdf_ref` / `word_ref` /
  `excel_ref` / `csv_ref`，不新建移动端专用 artifact 表；通过迁移补充来源、工具调用幂等键、
  内容哈希和 caption 元数据。
- 工具执行期间不旁路追加 session message。产物通过 typed sidecar 进入 Agent Loop，随后并入
  最终 assistant Message；广播与最终持久化使用同一个 Message JSON 形状。
- 首版发布类型限定为当前附件服务已验证的图片、PDF、Word、Excel 和 CSV。ZIP、可执行文件和
  其它任意二进制不在本切片开放。
- iOS 下载接口只接受 device token，从 `MobileDevicePrincipal` 推导 user，并同时校验 session 与
  attachment 所有权；消息和二维码都不携带带 token 的下载 URL。
- Agent 只能发布每个 session 的专用 staging 目录或该 session 已有受管附件目录中的文件，
  不能把整个 `/tmp`、用户 home、仓库根目录或进程工作目录加入 allowlist。
- 历史 assistant 附件传给 LLM provider 前只转换成安全文本占位符，不把 Agent 已交付的文件再次
  作为模型输入展开为图片或文档正文。

## Pipeline 要求

- 模式：Full。原因：新增 iOS App、schema migration、mobile auth、Dashboard pairing UI、chat/confirmation/attachment API facade。
- Reviewer 至少覆盖：backend/API/schema/security、frontend Dashboard UX、iOS/product。
- 本次历史工作树已包含压缩与 Agent Loop 相关改动；交付时必须按功能边界记录、跑最高风险
  Full gate，并在提交说明中明确各子系统，不再把它们描述成单一 iOS diff。

## 交付验证（2026-07-14）

- `xcodegen generate` 成功，工程不提交个人 Development Team ID。
- iPhone 17 Pro / iOS 26.5 Simulator：145 tests 全部通过，其中 116 个 unit tests、29 个 UI tests；
  UI 套件覆盖扫码复核、键盘与白屏回归、流式交接、附件、导航、Schedule、Agent、Settings 和离线诊断。
- Release simulator build 通过，deployment target 保持 iOS 17，Swift language mode 保持 6.0。
- 后端移动端、附件、压缩和 Workspace 相关测试随 server/core 全量 3302 tests 通过。
- 本轮没有重新执行真机签名、摄像头、LAN/Tailscale 切换、后台和 APNs 验收；其中 APNs delivery
  与 TestFlight 本来就不属于已完成范围，真机网络回归仍需在发布前执行。

## 关联

- OpenClaw iOS pairing 模型调研：本需求的产品参考。
- SkillForge channel 架构：[WECHAT-CHANNEL](../../archive/2026-06-21-WECHAT-CHANNEL/index.md)。
- Agent 文件交付缺口：[IOS-AGENT-FILE-DELIVERY](../2026-07-16-IOS-AGENT-FILE-DELIVERY/index.md)。
- Task 完成通知：[IOS-TASK-COMPLETION-PUSH](../2026-07-16-IOS-TASK-COMPLETION-PUSH/index.md)。
- 移动端 push / confirmation 相关现有路径：`ChatService`、`ChatController`、`PendingConfirmationRegistry`、`ChatEventBroadcaster`。
