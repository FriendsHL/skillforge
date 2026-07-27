# PRD — 多模态媒体能力

## 1. 能力入口

1. Media Tool 在全局 registry 注册，通过现有 Agent `toolIds` 决定可见性。
2. 普通 user Agent 默认可按产品策略获得基础媒体生成能力；system/eval Agent 不自动获得。
3. Main Agent 可直接调用媒体 Tool，不强制转交 Media Creator Agent。
4. Media Creator Agent 使用相同 Tool，增加视觉方案、音色、分镜、风格一致性和迭代提示。
5. 第一阶段 Main Agent 不自动调用 Media Creator 子 Agent，避免额外延迟、成本和状态嵌套。

## 2. Tool 产品契约

### 图片

- `GenerateImage`：文本生成图片，支持比例、数量和可选参考附件。
- `EditImage`：对已有图片附件进行编辑。
- 成功结果必须形成 `image_ref`，失败必须返回明确错误。
- `EditImage` 必须显式接收当前 Session 内、当前用户可读的
  `source_attachment_id`；不得依赖“刚才那张图”从自然语言历史猜测。
- 图片编辑不得覆盖源附件。新附件记录 `derived_from_attachment_id` 和
  `derivation_operation=EDIT_IMAGE`，形成可追溯版本链。
- Provider 图片输入只允许在调用边界从受管附件物化。Base64、供应商临时 URL、
  本机路径和完整 Provider 响应不得进入 Tool Result、消息历史或 Compact 摘要。

### 音频

- `GenerateSpeech`：文本转音频，支持 voice、format、speed 和可选风格指令。
- 短文本可同步完成；长文本自动进入异步 job。
- 成功结果形成 `audio_ref`。

### 视频

- `GenerateVideo`：文本或参考图生成视频。
- 提交成功后立即返回 job，不等待视频完成。
- `GetMediaJob` 查询当前 job。
- `CancelMediaJob` 取消尚未终止的 job。
- 成功结果形成 `video_ref`；生成期间形成 `media_job_ref`。

## 3. Chat 呈现

### 通用任务卡片

- 显示媒体类型、provider、model、状态、创建时间和关键规格。
- queued/running/downloading 显示稳定状态，不伪造不可靠百分比。
- 允许取消时显示取消操作；失败显示安全的用户错误和重试入口。
- 同一 `jobId` 在 WebSocket、REST catch-up 和刷新后只显示一次。

### 图片

- Dashboard 显示原比例缩略图和 lightbox；iOS 显示缩略图和全屏预览。
- 支持下载、分享、再次生成和作为参考继续编辑。
- “再次生成”只复用文本意图；“基于此图创作”必须提交卡片绑定的附件 ID，
  两个动作不得使用相同文案或静默互相降级。
- 大图必须使用缩略图，不在消息列表直接解码原始尺寸。

### 音频

- 显示播放/暂停、当前位置、总时长和 seek。
- 同一页面一次只播放一个音频；默认不自动播放。
- 有转写时可展开文本；支持下载和分享。

### 视频

- 消息列表先显示 poster，不为屏外卡片创建播放器。
- 点击后加载播放器；支持 seek、全屏、下载和分享。
- 离开 Session/页面时暂停并清理播放状态；默认不自动播放。

## 4. Dashboard 管理端

新增 Media 信息架构：

- **Jobs**：状态、provider、model、类型、Agent、Session、用户、耗时、错误、费用；可取消和安全重试。
- **Assets**：文件、大小、类型、引用、保留期、poster/transcript 衍生关系和孤立状态。
- **Providers**：启用状态、能力、默认模型、连通性、并发、超时和预算。MVP 密钥只来自环境变量或外部 secret，不通过 Dashboard 写入；未来接 secret store 后仍只写和掩码展示。
- **Usage**：请求数、成功率、P50/P95 耗时、图片张数、音视频秒数和费用。

涉及所有用户数据的管理 API 必须有真实管理员授权；不得复用仅校验非空 `userId` 的旧 admin 约定。

## 5. iOS

- `ChatAttachment.Kind` 增加 audio/video，并对未知未来类型保持兼容。
- 图片延续现有鉴权下载、预览、重试和分享。
- 音频使用系统播放器能力；视频使用 AVKit/AVPlayer。
- 大媒体通过短期 opaque playback ticket + Range 播放，不在 URL 暴露移动 device token。
- 本地缓存有总容量和 LRU 淘汰；撤销设备后清除受保护缓存。
- 前台当前 Session 使用 WebSocket 静默更新；后台或其他 Session 使用 APNs/未读提示。
- APNs 点击定位到对应 Agent、Session 和 job；payload 不含 prompt 或媒体 URL。
- VoiceOver、Dynamic Type、Reduce Motion、44pt 点击区域和 Light/Dark 是验收项。

## 6. 恢复、幂等和通知

1. 每个逻辑提交生成稳定 idempotency key。
2. 请求超时但是否提交未知时，先按 provider 能力查询/对账，不直接重提。
3. 服务启动扫描非终态 job，恢复查询或下载；不恢复已经终止的 job。
4. callback 只触发快速对账，不能直接信任外部状态或替代轮询。
5. 完成文件先安全下载、校验、转存，再把 job 标为 READY。
6. 通知采用 outbox/delivery 语义，重复事件不得产生重复 APNs 或重复消息。

## 7. 权限、安全和成本

- provider API Key 不进入业务数据库、响应、日志、tool result 或前端；MVP 只从环境变量/外部 secret 注入。
- 外部媒体下载只接受 provider adapter 产生的结果，校验 host、Content-Type、字节上限和 hash。
- voice clone 和真人素材必须保存授权/asset identity，不作为普通自由文本参数。
- 高成本操作支持用户确认、单次上限、每日预算和最大并发。
- 自动 provider fallback 默认关闭，避免双重生成和重复计费。
- 失败信息对用户脱敏，对管理员保留 request ID 和稳定错误码。

## 8. 验收标准

1. Main Agent 在普通 Chat 生成图片，Dashboard/iOS 各出现一次且可预览、下载、分享。
2. 生成短音频并在 Dashboard/iOS 播放、seek、暂停，切换 Session 后不继续播放。
3. 创建视频后 Agent turn 结束，任务继续；刷新两端仍显示同一 job。
4. kill 后重启服务，视频从原 provider job 恢复，不重复提交或计费。
5. 视频成功后转存、poster 生成、Range 播放和分享通过；provider URL 过期不影响已转存结果。
6. 当前 Session 前台更新不弹系统通知；后台完成通过 APNs 点击回到对应 Session。
7. 管理员能查询、取消任务和查看费用；非管理员被拒绝。
8. 跨用户、跨 session、撤销设备和过期 playback ticket 均无法读取媒体。
9. WebSocket 断线、REST catch-up 和 App 重启不产生重复卡片或附件。
10. 所有 provider 错误、审核拒绝、下载失败和预算拒绝有稳定状态与用户提示。
11. 对历史生成图片执行“基于此图创作”，服务端收到准确源附件 ID，产生新附件且不覆盖原图。
12. 源图片进入 Full Compact 后，用户仍可从历史图片卡片发起编辑；Compact 不保存或复制图片二进制。
