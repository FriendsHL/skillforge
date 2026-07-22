# Delivery Plan — 多模态需求拆分

## 交付原则

- 每个 milestone 独立设计审批、实现、review、验证和 commit。
- 先锁后端契约和持久化，再并行 Dashboard/iOS。
- M0 不写生产功能；先证明账号、接口、价格、限流和输出格式。
- M2/M3/M4 不同时开发，避免图片、音频、视频同时修改消息协议和播放器。

## M0 — Provider Capability Spike

### 输出

- Ark 账号是否能调用 Seedream、Seedance、短 TTS、长 TTS。
- 每类接口的 request/response 样本、错误映射、限流、耗时、费用和 URL 有效期。
- 确定首批模型、默认规格、文件上限和并发。
- OpenAI 只做账号/计费可用性确认，不要求首期接入。

### 验收

- 使用本地环境变量完成真实 curl，输出脱敏。
- 至少成功生成一张图片、一个短音频和一个短视频。
- 记录一个审核拒绝或参数校验错误响应。

## M1 — Media Runtime Foundation

### 后端

- Provider SPI/capability descriptor。
- `t_media_generation_job`、repository/service、状态机、lease worker。
- 管理 REST、WebSocket event、usage/cost 基础。
- Provider 非敏感配置与能力探测；密钥由环境变量/外部 secret 注入，不进入 Dashboard 或业务表。

### Dashboard

- Media > Jobs/Providers 基础页面。
- Job 列表、筛选、详情、取消。

### iOS

- 只增加可前向兼容的 media job DTO/reducer fixture，不显示成品播放器。

### 验收

- fake provider 完成 queued→running→ready、失败、取消、kill recovery。
- 非管理员拒绝管理 API。

## M2 — Image Generation

### 后端

- Ark image adapter、`GenerateImage`、`EditImage`。
- 图片结果流式下载、校验、缩略图和 `image_ref`。
- Agent tool visibility 与成本确认。

### Dashboard

- 生成中卡片、图片 lightbox、下载、再次生成/编辑入口。

### iOS

- 复用现有图片卡片，补媒体 job 状态、尺寸和生成操作。

### 验收

- 普通 Main Agent 和 Media Agent 各完成一次图片生成。
- Dashboard/iOS 各显示一次，刷新/重连不重复。

## M3 — Audio File Generation

### 后端

- `audio_ref`、短 TTS adapter、长文本异步 adapter。
- duration/codec/sample rate 元数据和可选 transcript。
- 音色 allowlist；声音克隆明确不在本 milestone。

### Dashboard

- 内联播放器、seek、单实例播放、下载。

### iOS

- AVPlayer 音频卡片、播放 coordinator、缓存和分享。

### 验收

- 短音频同步成功；长音频重启后恢复同一 provider task。
- 切换 Session 后停止播放；VoiceOver 和后台前台切换不崩溃。

## M4 — Video Generation

### 后端

- `media_job_ref`、`video_ref`、Ark video adapter。
- polling/callback reconciliation、取消、poster、转存和恢复。
- playback ticket 与 Dashboard/mobile Range endpoint。
- media ready/failed APNs。

### Dashboard

- 视频任务卡片、poster、懒加载播放器、全屏和下载。

### iOS

- VideoPlayer/AVPlayer、ticket、Range、全屏、缓存和分享。
- 通知点击定位 job。

### 验收

- Agent turn 结束后视频继续生成。
- 服务 kill/restart 不重复提交，最终两端可播放。
- 前台当前 Session 不产生重复系统通知；后台 APNs 可定位。

## M5 — Multimodal Understanding

### 范围

- 音频上传、ASR、时间戳和说话人分离。
- 视频上传、音轨、字幕、关键帧和结构化摘要。
- Agent/model capability-aware materialization。
- 原生 audio/video input 只在 provider 明确支持时启用。

### 验收

- Agent 可回答带时间锚点的音频/视频内容问题。
- compact、重写和重连后 media refs 不丢失、不膨胀消息 JSON。

## M6 — Media Creator Agent

### 范围

- 预置 Media Creator Agent。
- 图片提示词、视觉风格、音色、分镜和连续性模板。
- 用户显式切换 Agent；复杂任务的 Main Agent 委派作为可选 Phase 2。

### 验收

- Media Agent 与 Main Agent 复用相同 provider/job/asset 实现。
- 删除 Media Agent 不影响底层媒体能力和既有资产。

## M7 — Realtime Voice（独立需求包）

### 范围

- microphone permission、VAD、实时 ASR/原生音频模型、流式 TTS。
- interruption/barge-in、回声消除、网络抖动和会话恢复。
- iOS Audio Session、后台策略和系统中断。

### 进入条件

- M3 音频文件链路稳定。
- 明确实时 provider、延迟目标、通话计费和后台产品边界。
