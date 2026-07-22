# Delivery Plan — 多模态四期交付

## 交付原则

- 每一期独立设计审批、实现、review、验证和 commit。
- 先锁后端契约和持久化，再并行 Dashboard/iOS。
- P0 不写生产功能；先证明账号、接口、价格、限流和输出格式。
- P1 先完成统一底座与图片闭环；P2 再进入音视频异步链路。
- 当前生产范围只启用 Ark。其他图片/视频 Provider 作为后续独立增量，不进入本计划验收。

## P0 — Account & Provider Capability Spike

### 当前结果

- 已验证现有 `ARK_API_KEY` 可鉴权订阅专用 Seedream 5.0 lite 生图 endpoint。
- 标准 `/api/v3` 图片和视频 endpoint 均返回 HTTP 401，且官方警告使用它们会产生套餐外费用。
- Seedance 1.5 Pro 当前返回 `UnsupportedModel`；该模型即将下线且不支持新增接入，Medium 套餐暂不支持 Seedance 2.0。
- 最小真实图片已生成：HTTP 200 / 13 秒 / JPEG 2048×2048 / 50,500 bytes；视频记录为套餐能力阻塞，
  不阻塞 P1 图片闭环。

### 输出

- 当前订阅账号能否调用 Seedream、Seedance、短 TTS、长 TTS。
- 每类接口的 request/response 样本、错误映射、限流、耗时、费用和 URL 有效期。
- 确定首批模型、默认规格、文件上限和并发。
- OpenAI 只做账号/计费可用性确认，不要求首期接入。

### 验收

- 使用本地环境变量完成真实 curl，输出脱敏。
- 至少成功生成一张图片；音频和视频若受套餐限制，必须留下官方能力证据与后续进入条件。
- 记录一个审核拒绝或参数校验错误响应。

## P1 — Media Runtime Foundation + Image

P1 作为同一期产品验收，按四个 Full 增量串行交付：

- **P1-A Backend**：Provider 配置、Seedream adapter、GenerateImage Tool、受控下载与 Attachment 导入。
- **P1-B Dashboard**：生成结果沿现有 `image_ref` 呈现，补生成来源、错误与重新生成入口。
- **P1-C iOS**：复用鉴权图片下载/预览/分享，补生成来源与错误状态。
- **P1-D Live E2E**：真实 Main Agent 生图，Dashboard/iOS 各显示一次，刷新/重连不重复。

同步图片 MVP 复用现有 `t_chat_attachment` 与 `source_tool_use_id` 幂等，不为了单次 13 秒同步调用提前引入
异步 Job 表。`t_media_generation_job` 延后到 P2-A/P2-B 首个异步音视频增量，在真实异步状态机出现时落库。

### 已交付后端

- Ark Seedream 订阅 endpoint adapter、严格 `/api/plan/v3` 计费保护和下载 host/size/MIME 边界。
- `GenerateImage` Tool、输入校验、临时文件清理、受管 Attachment 导入。
- 以 `(session_id, source_tool_use_id)` 复用已有成品，重放时不再次请求 Provider。
- 环境变量注入密钥；密钥不进入 Dashboard、业务表、日志或文档。
- V178 为活跃用户 Agent 增加 `GenerateImage` allowlist；运行时 feature flag 默认关闭。

### 已交付 Dashboard

- 复用现有 `image_ref` 卡片、鉴权缩略图、预览与下载链路。
- tool card 继续显示生成执行结果；同步 P1 不引入伪 Job 状态。

### 已交付 iOS

- 复用现有 typed `image_ref`、鉴权下载、缓存、预览与分享链路。
- 对同一 Attachment ID 保持单次呈现；刷新由服务端消息快照对账。

### 延后 P2/P3

- `t_media_generation_job`、Provider SPI/capability descriptor、lease worker、Jobs/Providers 管理页和 job WebSocket event。
- `EditImage`、专用“再次生成”按钮与 Media Creator Agent；当前可通过新 query 再次调用 `GenerateImage`。

### 验收

- 普通 Main Agent 完成一次图片生成；Media Creator Agent 的同 Tool 复用在 P3 验收。
- Dashboard/iOS 的既有图片组件回归通过；真实 Main Agent 消息刷新后保持单个 `image_ref`。
- 真实订阅调用成功，成品转存为受管 JPEG，下载端点返回相同文件。

## P2 — Audio & Video Async Delivery

P2 内部按 P2-A 音频、P2-B 视频串行合入；共享异步 job、恢复、播放器与通知基础，但各自独立 commit。

### P2-A 音频

#### 后端

- `audio_ref`、短 TTS adapter、长文本异步 adapter。
- duration/codec/sample rate 元数据和可选 transcript。
- 音色 allowlist；声音克隆明确不在本阶段。

#### Dashboard

- 内联播放器、seek、单实例播放、下载。

#### iOS

- AVPlayer 音频卡片、播放 coordinator、缓存和分享。

#### 验收

- 短音频同步成功；长音频重启后恢复同一 provider task。
- 切换 Session 后停止播放；VoiceOver 和后台前台切换不崩溃。

### P2-B 视频（代码闭环已交付，Provider 真活阻塞）

当前已交付 `t_media_generation_job`、`media_job_ref`、Ark adapter、幂等提交、lease polling、服务重启恢复、
不确定提交结果的 `SUBMIT_UNKNOWN` 保护、取消、受控下载/Attachment 转存、WebSocket 状态事件，以及
Dashboard/iOS 任务卡和播放器。`ARK_VIDEO_ENABLED` 默认关闭。

当前订阅 endpoint 对 Seedance 1.5 Pro 返回 `UnsupportedModel`，因此无法完成真实生成与 kill/restart 真活。
待火山侧为订阅账号开放可用视频模型后，只需配置模型并开启 flag，再补真实验收；不会降级到可能产生套餐外费用的
标准 `/api/v3` endpoint。

#### 后端

- `media_job_ref`、`video_ref`、Ark video adapter。
- polling/callback reconciliation、取消、poster、转存和恢复。
- playback ticket 与 Dashboard/mobile Range endpoint（正式大文件播放增量）。
- media ready/failed APNs（通知增量）。

#### Dashboard

- 视频任务卡片、poster、懒加载播放器、全屏和下载。

#### iOS

- VideoPlayer/AVPlayer、ticket、Range、全屏、缓存和分享。
- 通知点击定位 job。

#### 验收

- Agent turn 结束后视频继续生成。
- 服务 kill/restart 不重复提交，最终两端可播放。
- 前台当前 Session 不产生重复系统通知；后台 APNs 可定位。

## P3 — Multimodal Understanding & Media Creator

### 范围

- 音频上传、ASR、时间戳和说话人分离。
- 视频上传、音轨、字幕、关键帧和结构化摘要。
- Agent/model capability-aware materialization。
- 原生 audio/video input 只在 provider 明确支持时启用。

### 验收

- Agent 可回答带时间锚点的音频/视频内容问题。
- compact、重写和重连后 media refs 不丢失、不膨胀消息 JSON。

### Media Creator 范围

- 预置 Media Creator Agent。
- 图片提示词、视觉风格、音色、分镜和连续性模板。
- 用户显式切换 Agent；复杂任务的 Main Agent 委派作为可选 Phase 2。

### 验收

- Media Agent 与 Main Agent 复用相同 provider/job/asset 实现。
- 删除 Media Agent 不影响底层媒体能力和既有资产。

## Realtime Voice（独立需求包）

### 范围

- microphone permission、VAD、实时 ASR/原生音频模型、流式 TTS。
- interruption/barge-in、回声消除、网络抖动和会话恢复。
- iOS Audio Session、后台策略和系统中断。

### 进入条件

- P2 音频文件链路稳定。
- 明确实时 provider、延迟目标、通话计费和后台产品边界。
