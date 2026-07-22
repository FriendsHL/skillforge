# Tech Design — 多模态 Media Runtime

## 1. 架构边界

### 不修改的核心职责

- `LlmProvider` 继续负责文本/多模态理解模型的 Chat 协议，不承担媒体生成 job。
- Agent Loop 继续执行同步 Tool 契约；异步 Tool 在“任务已可靠提交”时完成。
- `t_session_message` 不保存二进制或 provider 临时 URL。

### 新组件

```text
MediaProviderRegistry
MediaGenerationService
MediaJobService
MediaJobWorker
MediaAssetImportService
MediaPlaybackTicketService
MediaEventPublisher
```

Provider SPI 按能力拆分：

```text
ImageGenerationProvider
AudioSynthesisProvider
AudioTranscriptionProvider
VideoGenerationProvider
```

Provider 必须暴露 capability descriptor，服务层先验证参数再调用外部 API。MVP provider secret 只从环境
变量或外部 secret 注入；Dashboard 配置不接受或持久化 API Key，直到独立 secret-store/RBAC 能力获批。

## 2. 数据模型

### `t_media_generation_job`

落地时点：P1 同步图片复用现有 `t_chat_attachment.source_tool_use_id` 做计费前重放去重，不创建本表；
本节从 P2 首个异步音频/视频增量开始生效。同步图片若未来转为异步，也统一迁入本状态机。

必需字段：

```text
id UUID PK
user_id BIGINT NOT NULL
session_id VARCHAR(36) NOT NULL
agent_id BIGINT
source_tool_use_id VARCHAR(128)
idempotency_key VARCHAR(128) NOT NULL UNIQUE

media_type VARCHAR(16) NOT NULL
operation VARCHAR(32) NOT NULL
provider VARCHAR(32) NOT NULL
model VARCHAR(128) NOT NULL
provider_job_id VARCHAR(255)

status VARCHAR(32) NOT NULL
request_json TEXT NOT NULL
provider_metadata_json TEXT
progress NUMERIC(5,2)

result_attachment_id VARCHAR(36)
thumbnail_attachment_id VARCHAR(36)
transcript_attachment_id VARCHAR(36)
retry_of_job_id UUID

usage_json TEXT
cost_amount NUMERIC(18,6)
cost_currency VARCHAR(8)
attempt_count INTEGER NOT NULL
next_poll_at TIMESTAMPTZ
lease_owner VARCHAR(128)
lease_expires_at TIMESTAMPTZ

error_code VARCHAR(80)
error_message TEXT
created_at TIMESTAMPTZ NOT NULL
submitted_at TIMESTAMPTZ
started_at TIMESTAMPTZ
completed_at TIMESTAMPTZ
updated_at TIMESTAMPTZ NOT NULL
version BIGINT NOT NULL
```

索引至少覆盖：

- `(status, next_poll_at)` worker 扫描
- `(session_id, created_at)` Chat 恢复
- `(user_id, created_at)` 用户视图
- `(provider, provider_job_id)` callback/对账

`provider_metadata_json` 只允许保存脱敏后的稳定 metadata/request ID，禁止保存 API Key、完整签名下载 URL
或声音授权材料。对终态任务执行“重试”时创建带 `retry_of_job_id` 的新 job，不把原终态改回 active。

### Attachment 扩展

`t_chat_attachment` 增加可空媒体字段：

```text
width
height
duration_ms
codec
container
sample_rate
channel_count
frame_rate
poster_attachment_id
media_metadata_json
```

常用检索字段独立成列；provider 私有扩展进入 JSON。所有新时间字段使用 `Instant/TIMESTAMPTZ`。

## 3. 状态机

```text
CREATED
  -> SUBMITTING
  -> QUEUED
  -> RUNNING
  -> DOWNLOADING
  -> PROCESSING
  -> READY
```

终态：

```text
SUBMIT_FAILED
GENERATION_FAILED
DOWNLOAD_FAILED
PROCESSING_FAILED
CANCELLED
EXPIRED
```

约束：

- READY 必须存在可读 `result_attachment_id`。
- provider job 已知时，kill recovery 只能查询/下载，不能重新 submit。
- `SUBMITTING` 且 provider job 未知属于不确定提交；adapter 提供 reconciliation 能力，否则进入人工可重试失败态，不自动双提。
- CANCELLED/READY 等终态不可被迟到 callback 改回 RUNNING。
- worker 更新使用 optimistic version 或 compare-and-set + lease，防多个 worker 重复下载。

## 4. Tool 与消息协议

### 同步图片/短音频

Tool 在受控 timeout 内生成并导入 Attachment，返回 `PublishedArtifact`。若转为异步，返回 `media_job_ref`。

### 视频/长音频

1. Tool 创建 job 并提交 provider。
2. 保存 provider job ID 后返回成功 tool result。
3. 最终 assistant message 包含 `media_job_ref`。
4. 客户端按 `jobId` 读取状态；READY 后同一卡片读取 result attachment。

建议 ContentBlock：

```json
{"type":"media_job_ref","job_id":"...","media_type":"video"}
```

```json
{"type":"audio_ref","attachment_id":"...","filename":"speech.mp3","duration_ms":12340}
```

```json
{"type":"video_ref","attachment_id":"...","poster_attachment_id":"...","filename":"clip.mp4","duration_ms":5000,"width":1280,"height":720}
```

Provider-bound materialization：

- `media_job_ref` -> 紧凑文本状态，不把内部请求 JSON 上送。
- `audio_ref` -> transcription 或 provider 原生 audio；由 agent/model capability 决定。
- `video_ref` -> transcript + selected frames + metadata，只有明确支持时才原生视频输入。

## 5. API 和事件

### REST

```text
GET    /api/media/jobs/{id}
GET    /api/media/jobs?filters...
POST   /api/media/jobs/{id}/cancel
POST   /api/media/jobs/{id}/retry
POST   /api/chat/sessions/{sessionId}/attachments/{attachmentId}/playback-ticket
GET    /api/media/play/{opaqueTicket}
GET    /api/media/providers
PUT    /api/media/providers/{name}
GET    /api/media/usage
```

Mobile endpoint 使用 device principal 推导 user，不接受客户端 `userId` 作为授权依据。

### WebSocket

新增用户/Session 可路由事件：

```json
{
  "type": "media_job_updated",
  "sessionId": "...",
  "jobId": "...",
  "status": "READY",
  "updatedAt": "..."
}
```

事件只作为 refetch/merge 信号。不得携带 provider response、prompt、媒体二进制或 playback ticket。

### APNs

沿用现有 notification outbox/delivery worker，新增 media ready/failed reason。去重键包含 job terminal transition；
payload 仅含 route 所需的 sessionId、agentId、jobId 和非敏感展示类型。

## 6. 安全播放

Dashboard `<audio>/<video>` 和 iOS AVPlayer 都需要 Range 访问。推荐 playback ticket：

- 128 bit 以上随机 opaque token，数据库只存 hash。
- 绑定 user/session/attachment/device 或 web principal。
- 短 TTL，可在一个播放窗口内多次 Range。
- 只允许 GET/HEAD，不允许目录或任意 path。
- 响应支持 ETag、If-Range、206、nosniff 和 private/no-store 策略。
- access log、异常和 metrics 对 ticket 脱敏。

MVP 可对小文件完整下载后播放，但 P2 视频阶段的正式验收必须走 Range。

## 7. 外部下载

- Adapter 返回结构化 remote asset，不接受 LLM 提供任意 URL。
- 下载前验证 scheme/host allowlist；DNS 重绑定和 redirect 后再次校验。
- 流式写临时文件并计算 hash，限制字节数、Content-Type、时长和分辨率。
- fsync/原子 move 后才创建或更新 READY attachment。
- 下载失败保留 provider job ID，允许重新获取临时 URL后继续，不重新生成。

## 8. Dashboard 状态

- Query cache 以 `jobId` 为 key；WebSocket 事件触发精确 merge/refetch。
- Chat 只初始化可见媒体；重组件动态加载。
- 一个页面共享 playback coordinator，切换播放自动暂停上一条。
- 任务列表分页，超过 100 行使用现有 virtualization 规则。

## 9. iOS 状态

- DTO decoder 对未知 media kind/status 前向兼容。
- `MobileRealtimeState` 只合并按 `jobId` 标识的状态，不让 REST catch-up 覆盖更新的 WebSocket 状态。
- Player 对象不放进可序列化 message state；由可见卡片局部持有，共享 coordinator 管单实例播放。
- session 切换、view disappear、app background 时取消观察任务并暂停。
- 大文件缓存独立于图片缩略图缓存，按总字节 LRU 淘汰。

## 10. 恢复与调度

- Worker 用 `(status,next_poll_at)` 拉取，短 lease 防并发执行。
- provider 429/5xx 使用 capped exponential backoff + jitter。
- callback 先鉴权/校验，再通过 CAS 推进状态；迟到或重复 callback 幂等忽略。
- 启动恢复扫描 lease 过期的非终态 job。
- 不依赖 JVM 内存保存 provider job、下载进度或通知状态。

## 11. 可观测性

每个 job 记录并暴露：

- submit/poll/download/process latency
- provider request ID
- status transition
- retry count
- bytes and duration
- usage/cost
- stable error code

日志禁止打印 API Key、完整 prompt、provider 临时 URL、playback ticket、声音授权材料和用户媒体内容。

## 12. 验证策略

- Provider adapter：MockWebServer 契约、错误映射、未知提交、限流、URL 过期。
- 数据库：真实 PostgreSQL migration、状态 CAS、索引查询和启动恢复。
- Core：tool pair、content block JSON round-trip、materialization 和 compact 边界。
- Dashboard：类型契约、WebSocket merge、播放器单实例、刷新恢复、浏览器真实 Range。
- iOS：decoder/reducer XCTest、URLProtocol、player policy、XCUITest 卡片/导航/无障碍。
- 真活：Ark 图片、短/长音频、视频；kill/restart；Dashboard+iPhone；provider URL 过期后的本地播放。
