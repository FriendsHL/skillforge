# IOS-ASSISTANT-COMPANION PRD

## 产品目标

做一个 SkillForge 原生 iOS Assistant Companion，让用户通过手机使用自己的 SkillForge 助理：

- 扫码配对。
- 默认进入聊天。
- 处理等待用户输入和确认。
- 上传图片/文件。
- 接收任务通知。

## 非目标

- 不做 dashboard 全功能移动版。
- 不做手机作为 tool node。
- 不做自建语音/录音/转写链路。
- 不做 Apple Watch。
- 不做官方 relay。
- 不做跨不同 SkillForge Server、官方 relay 或会重放业务 POST 的透明重试。
- 不做 trace 详情页。

## 关键用户流程

### Pairing

1. 用户在 Dashboard 点击 Pair iPhone。
2. Server 生成短期一次性 QR。
3. iOS App 扫码。
4. App probe `endpoints[]`，保存第一个可达 endpoint。
5. App claim pairing。
6. Server 返回 device token。
7. App 进入默认助理 Chat。

### Chat

1. 用户打开 App。
2. App 校验 device token 并拉取默认 session。
3. 用户发送文本消息。
4. App 展示 assistant 流式回复和 session 状态。
5. 如果 agent 进入 running/waiting_user/error，App 以移动端友好的状态呈现。

### Human-In-The-Loop

1. Agent 触发 `ask_user` 或 confirmation。
2. App 在当前 session 内展示卡片。
3. 用户回答/批准/拒绝。
4. Server 继续原 agent loop。
5. App 刷新状态和消息。

### Attachments

1. 用户在聊天输入区选择图片或文件。
2. App 上传到现有 attachment 路径。
3. 用户发送消息时绑定 attachment。
4. Agent 在后端现有多模态/附件逻辑里处理。

### Agent 交付产物

1. Agent 在本轮工作目录内生成图片或文档。
2. Agent 调用 `PublishChatArtifact` 发布产物，可附带简短说明。
3. Server 校验路径、真实文件类型、大小和 session/user 归属，将文件复制到受管附件目录。
4. 产物引用进入本轮最终 assistant 消息；工具调用卡片、文字回复和附件卡片按同一消息历史展示。
5. iOS 用户可查看图片缩略图，或下载并用 Quick Look 预览文档；下载后可调用系统分享面板。
6. 下载失败时附件卡片保留在 transcript 中并支持重试，不清空历史消息。

### Push

1. App 注册 APNs token。
2. Server 在任务完成、失败、需要输入、需要确认时发送 push。
3. 用户点通知进入对应 session。
4. App 前台 REST catch-up，展示最新状态。

## 功能需求

- FR-1：支持 QR 扫码配对。
- FR-2：支持 setup code/manual fallback。
- FR-3：QR payload 使用 `endpoints[]`。
- FR-4：保存二维码中的完整 endpoint 集和当前活动 endpoint；活动 endpoint 优先可达私有 LAN，
  LAN 不可用时自动回退 Tailscale/公网 HTTPS。
- FR-5：device token 存 Keychain。
- FR-6：支持默认助理 Chat。
- FR-7：支持消息历史拉取和实时更新。
- FR-8：支持 `ask_user` 回答。
- FR-9：支持 confirmation approve/deny。
- FR-10：支持图片/文件上传。
- FR-11：支持 APNs 注册和通知。
- FR-12：支持设备撤销。

### Navigation Foundation

- FR-13：配对完成后显示 `Chat / Control / Agents / Settings` 四个原生底部导航，
  默认选择 Chat。
- FR-14：切换 tab 后返回 Chat，当前 session、消息和输入草稿不能被重建或清空。
- FR-15：Control 将 Session、Schedule 和 Run 作为不同对象呈现，不再将 Session 投影为 Task。
- FR-16：Agents 展示当前用户可检查的 Agent 配置，支持按名称、角色和模型搜索；
  点击列表项进入 Agent 详情，不创建 session、不跳转 Chat。
- FR-17：Settings 展示 endpoint、连接状态、设备名、授权 scopes 和 App/Server 诊断信息，
  不显示 device token。
- FR-18：本地断开需要二次确认，并明确说明只清除本机连接信息。
- FR-19：Control 展示当前用户可见的定时计划，包含 Agent、调度规则、时区、下次运行、
  最近状态和启用状态。
- FR-20：用户可在 Control 立即运行、暂停或启用计划，并查看该计划的最近运行历史。
- FR-21：Control 的 Session 入口复用 Chat 的会话选择与路由，不维护第二份会话状态。
- FR-22：Agent 列表至少展示名称、描述摘要、角色、模型、状态、可见性和默认标识。
- FR-23：Agent 详情按 Overview、Runtime、Capabilities 三组展示移动端安全配置：
  execution mode、thinking mode、reasoning effort、max loops、Skills 和 Tools；Tools
  必须区分“全部已注册工具”和显式 allowlist，不能把未配置 allowlist 显示为 0 个工具。
- FR-24：Prompt 仅展示配置状态和长度摘要，不在首版移动 API 返回正文；provider credentials、
  lifecycle hook 原始 JSON、ownerId 和内部 config JSON 永不下发。
- FR-25：Chat 的“新会话”流程独立选择对话 Agent；浏览 Agent 配置不能修改 Chat 当前 session、
  transcript、composer draft 或默认 Agent。
- FR-26：Owned、shared/default Agent 需要有明确来源标识；shared Agent 只能查看公开安全元数据，
  不得借移动详情接口读取其私有配置。
- FR-27：Agent 能通过独立的 `PublishChatArtifact` 工具把本轮生成的图片、PDF、Word、Excel 或
  CSV 发布到当前 Chat session；非 channel session 也必须可用。
- FR-28：发布产物必须作为 assistant 消息中的附件引用持久化，不能只存在于临时工具结果、
  WebSocket 事件或本地文件路径中。
- FR-29：包含附件但没有正文的 assistant 消息仍需显示；正文、工具卡片和多个附件可以共存。
- FR-30：iOS 图片卡片支持缩略图、全屏预览、失败重试；文档卡片支持鉴权下载、Quick Look、
  系统分享和明确的下载状态。
- FR-31：历史拉取、实时事件和前台 catch-up 对同一附件消息去重，附件加载不得更换消息身份、
  清空 transcript 或触发白屏。
- FR-32：Dashboard 同样展示 assistant 附件，不能继续只在 user 消息上渲染 attachment refs。
- FR-33：首版仅接受服务端当前能够通过 magic bytes/MIME 双校验的图片、PDF、Word、Excel 和
  CSV；其它类型返回可理解的工具错误，不做静默降级。
- FR-34：Chat 首页右上角 `+` 打开 New Conversation，不再放置 Session 浏览或断开连接。
- FR-35：Chat 与 Session List 的新建入口复用同一 Agent 选择流程；创建成功后进入新 Session，
  创建失败时保留当前 Session、历史和输入草稿。
- FR-36：Session List 支持当前 Agent 范围内的本地搜索、状态筛选和下拉刷新；筛选不能触发
  Session 创建，也不能清空当前 Chat。
- FR-37：Settings 增加通知权限真实状态、外观选择和隐私说明；未接通 APNs 服务端注册前不得
  显示“已注册”或可用的通知类型开关。
- FR-38：断开连接只出现在 Settings，并保持二次确认；`+` 菜单不得混入破坏性设备操作。
- FR-39：Settings 的连接状态必须区分未检查、检查中、正常、网络不可达和服务异常；摘要可进入
  诊断详情，并提供明确的重新检查动作。诊断不得展示服务端原始错误正文或伪造实时连接状态。
- FR-40：REST catch-up、前台恢复和 WebSocket 消息交接必须把同一 assistant turn 从 transient
  流式气泡原子替换为持久化消息；不得同时展示两份相同回复，也不得按全局正文去重合法历史消息。
- FR-41：配对首屏以扫码为主，扫码或粘贴 payload 后必须先展示服务器名称、endpoint host 和
  过期状态，再由用户确认 claim。没有受限 lookup/claim 协议前，不得展示或声称六位 setup code
  能验证或完成配对；备用入口只能粘贴完整配对 payload，且不得持续暴露一次性 secret。
- FR-42：发送被客户端接受后 composer 必须立即清空；发送失败时仅在用户尚未输入新草稿时恢复
  原草稿，不能用旧请求覆盖新的输入。
- FR-43：同一 Session 的 transcript 容器身份在键盘、流式增量、工具卡片和 REST catch-up 期间
  保持稳定；后台刷新不得因列表暂时为空或漏项切走当前 Session。
- FR-44：运行时 endpoint 监测只切换后续请求和 WebSocket 使用的活动地址，不对可能已送达的
  Chat/confirmation/schedule 等写请求做自动重放。

## 安全需求

- QR secret 短期有效、一次性使用。
- QR 不包含长期 API token。
- device token 仅返回一次，服务端只存 hash 或等价安全表示。
- mobile API 从 token 推导 user，不接受客户端传入 userId 作为权限依据。
- 设备可被撤销。
- V1 scope 限定为 chat、confirmation、attachment、push、schedule read/write 和 agent read；
  Schedule 写权限只开放立即运行与启用状态切换。
- Agent 提供的路径是不可信输入：只允许当前 session 的专用 artifact staging 目录和受管附件
  目录内的普通文件，必须使用 real path 阻止 `..`、符号链接逃逸、跨 session 读取和目录/设备
  文件读取；整个 `/tmp`、home、仓库根目录和进程工作目录均不允许。
- Agent 产物下载要求 `chat:read`，从 device principal 推导 user；越权与不存在统一按 404 处理，
  不泄露 attachment 是否存在。

## 验收标准

- AC-1：用户能从 Dashboard 生成 QR 并用 iOS App 配对。
- AC-2：iOS App 能保存 endpoint 和 device token。
- AC-3：配对后打开 App 直接进入聊天优先界面。
- AC-4：用户能发送消息并看到 assistant 回复。
- AC-5：用户能在 iOS 端回答 `ask_user`。
- AC-6：用户能在 iOS 端批准/拒绝 confirmation。
- AC-7：用户能上传图片/文件到 session。
- AC-8：服务端能发送任务完成/失败/需要输入/需要确认 push。
- AC-9：撤销设备后，该设备无法再访问 session。
- AC-10：QR payload 已包含 `endpoints[]`，未来自动 failover 不需要改 pairing 协议。
- AC-11：四个底部导航在小屏和键盘弹起时不遮挡 Chat composer。
- AC-12：连续切换四个 tab 后，Chat 的当前 session 和可见消息保持不变。
- AC-13：从 Control 的 Sessions 选择 session 后，Chat 打开该 session；从 Agents 打开配置详情后，
  Chat 当前 session、Agent、历史消息和输入草稿保持不变。
- AC-14：Settings 能执行连接诊断和经确认的本地断开，且不会泄露 device token。
- AC-15：Control 中计划与运行记录不是 session 列表的重命名，运行记录仅在存在真实执行时出现。
- AC-16：移动端 Schedule API 从 device principal 推导 userId，不接受客户端 userId；
  读操作要求 `schedule:read`，运行和开关要求 `schedule:write`。
- AC-17：立即运行返回已排队状态；暂停/启用成功后列表状态可恢复刷新；运行历史可跳转到
  已关联的 Chat session。
- AC-18：Agents 列表点击后进入对应配置详情，返回列表后搜索条件和滚动位置保持不变。
- AC-19：Agent 详情能看到模型、角色、执行策略、Skills、Tools 和 Prompt 配置状态；响应中不存在
  systemPrompt/soulPrompt/toolsPrompt 正文、credentials、lifecycleHooks、ownerId 或原始 config。
- AC-19a：`toolIds` 为空时显示 `All registered` 及当前有效工具数量；非空畸形配置 fail closed，
  不得回退为全部工具。
- AC-20：缺少 `agent:read` 返回 403；token 失效返回 401；不可见、停用和 system Agent 返回 404，
  避免泄露其存在性。
- AC-21：Agent 发布图片后，Dashboard 和 iOS 都能在同一 assistant turn 看到图片卡片，iOS 可
  打开全屏预览并分享。
- AC-22：Agent 发布 PDF/Word/Excel/CSV 后，iOS 可鉴权下载并用 Quick Look 打开；跨 user、跨
  session 或已撤销 device token 无法下载。
- AC-23：`tool_use` / `tool_result` 顺序保持完整，发布动作只产生一个 attachment 记录和一个
  assistant 引用；REST catch-up、WebSocket 重连和 compact 后不重复。
- AC-24：路径穿越、符号链接逃逸、超限、伪造 MIME 和不支持类型均被拒绝，且不会留下可下载的
  已发布附件。
- AC-25：纯附件 assistant 消息、文字加附件消息和多附件消息在 iOS 上都不会被过滤或导致
  transcript 白屏。
- AC-26：两个 `+` 都打开同一 New Conversation 流程，可选择 Agent 并创建对应 Session。
- AC-27：取消或创建失败后，原 Session、transcript、composer draft 和附件草稿保持不变。
- AC-28：Session 搜索和状态筛选可组合使用；刷新后当前 Session 仍被标识且可继续打开。
- AC-29：Settings 的通知状态与 iOS 系统授权一致，Appearance 可在 System/Light/Dark 间切换并
  持久化；页面不显示 device token。
- AC-30：首页和 Session List 均不再提供断开连接入口。
- AC-31：Settings 打开后会执行一次连接检查；诊断详情分别展示 endpoint、SkillForge 服务、
  设备认证和实时连接说明。网络或 5xx 失败不会清空配对信息，401 仍返回重新配对流程。
- AC-32：`message_appended` 先于持久化、REST stale snapshot、最终 committed snapshot 和前台恢复
  的组合流程结束后，同一 assistant turn 只显示一个气泡；不同 seqNo 的合法相同正文仍保留。
- AC-33：Pairing 首屏不再默认展示空白 JSON 编辑器和无效 setup-code 输入；扫码/粘贴后进入
  review，确认前不 claim，页面和可访问性树不展示 pairingSecret 或完整 raw payload。
- AC-34：发送包含首尾空白的 query 后输入框在 1 秒内清空；网络失败可恢复旧草稿，期间新输入
  不被覆盖。
- AC-35：连续键盘开合、流式输出、工具完成和 session 列表刷新后，只要 transcript 有数据，
  视口内始终存在可见消息，不出现只有下拉按钮的空白区域。
- AC-36：二维码同时包含 LAN 与 Tailscale 时，LAN 可达优先使用 LAN；关闭 Tailscale 后仍可通过
  LAN 通信，离开 LAN 后可回退 Tailscale。该项需真机验收。
