# Research Report — SkillForge 多模态媒体能力

> 调研日期：2026-07-22；媒体传输与上下文管理补充调研：2026-07-24
> 范围：SkillForge 后端、Dashboard、iOS、`../research-docs`、Ark/OpenAI/Apple 官方能力

## 1. 本地能力基线

SkillForge 已有的可复用链路：

- `ChatAttachmentEntity` / `ChatAttachmentService` 管理用户上传和 Agent 生成文件。
- `PublishChatArtifact` 将受管 workspace 内的文件导入 Attachment。
- `SkillResult.artifacts` / `PublishedArtifact` 在成对 tool result 后合入最终 assistant 消息。
- `image_ref` 在数据库保持轻量引用，调用视觉模型前才由 `MessageMaterializer` 展开。
- Dashboard 可鉴权加载图片和文档；iOS 可鉴权下载、预览、Quick Look 和分享。
- Mobile attachment endpoint 已支持 ETag、private cache 和 HTTP Range。
- 用户级 WebSocket、APNs token、通知持久化和 delivery worker 已存在。

当前缺口：

- Attachment/ContentBlock 没有 `audio_ref`、`video_ref` 或 `media_job_ref`。
- Dashboard/iOS 没有音频或视频播放器。
- 没有 provider job、轮询、取消、幂等提交、费用和媒体任务恢复。
- Dashboard 普通附件下载仍以完整 `byte[]` 响应，不适合较大视频。
- `page_count` 已同时承载 PDF 页数和 Excel sheet 数，不应继续复用为媒体时长/帧数。
- 当前 attachment admin endpoint 代码已注明缺少真正 admin RBAC，不能直接扩成多租户媒体控制台。

## 2. `research-docs` 结论

### AgentScope

AgentScope 将 `TextBlock`、`AudioBlock`、`ToolUseBlock`、`ToolResultBlock` 作为强类型消息块，并把
multimodality tool、TTS、realtime 分成独立模块。可借鉴的是 typed blocks 和生命周期分离；不复制其
Python StateModule 或 provider 实现。

### Agent TARS

Agent TARS 的协议化 Event Stream 同时驱动 UI、上下文和 replay。SkillForge 对应采用“数据库状态 +
REST snapshot + WebSocket invalidation/update”的单一真相源，不让 Dashboard/iOS 各自维护不可恢复的
临时媒体状态。

### OpenHuman

OpenHuman 有 `agent/multimodal.rs`、vision routing 和本地 Whisper，但 `research-docs` 将其评为
prototype。它只作为方向参考，不构成生产设计依据。

### SkillForge wiki

已有研究确认当前正确原则是：持久化只存 `image_ref/pdf_ref`，provider call 前临时 materialize。
新模态延续该原则：音频优先转写或原生音频输入；视频优先字幕、关键帧和结构化摘要，不把完整媒体
反复注入 Agent 历史。

## 3. 官方接口约束

- Ark 图片生成可返回生成结果，支持文本及参考图片输入。
- Ark/Seedance 视频为异步任务，具有 queued/running/succeeded/failed、查询和取消接口。
- 火山长文本 TTS 为异步任务，结果 URL 有效期短，官方要求及时转存；callback 不能替代主动查询。
- OpenAI 图片、音频和视频使用不同 endpoint；视频同样是 job 模型。
- OpenAI GPT Image、阿里 Wan、HappyHorse 与快手 Kolors/Kling 的外部 HTTP 协议并不统一：静态图片
  通常同步或 SSE 返回 URL/Base64，视频通常创建异步任务后查询。SkillForge 只统一内部资产、Job、错误
  和跨端卡片协议，不强行统一厂商请求 DTO。
- Apple AVFoundation 要求异步加载媒体属性；AVPlayer/VideoPlayer 适合本地或远程媒体，但远程 Range 请求的鉴权不能依赖普通 SwiftUI 下载按钮。

`Doubao-Seed-2.0-pro` 的“多模态”指图片/视频输入理解与推理，不是图片或视频生成。火山官方产品页将其
列在文本生成/在线推理类别；图片生成单列 Seedream 5.0 lite/4.5/4.0，视频生成单列 Seedance
2.0/1.5-pro/1.0。因此 Seed 2.0 Pro 可以作为 Main Agent 理解素材、编写提示词和调用媒体 Tool，真正的
生成请求仍必须路由到 Seedream/Seedance。Coding Plan 中所谓支持“生图/生视频技能”，也是 Agent 调用
外部 Skill/Tool 的编排能力，不表示 Seed 2.0 Pro 自己通过 Chat API 输出图片或视频。

### 3.1 当前火山账号/密钥实测（2026-07-22）

本机 SkillForge 运行环境存在 `ARK_API_KEY`，当前文本模型配置使用
`https://ark.cn-beijing.volces.com/api/plan/v3`。在不提交付费生成任务的前提下进行了鉴权与路由探测：

| 探测 | 结果 | 结论 |
| --- | --- | --- |
| `GET /api/plan/v3/models` | HTTP 404 | 订阅端点不提供标准模型目录，能力需按套餐文档/具体 endpoint 核验 |
| `GET /api/v3/models` | HTTP 401 `AuthenticationError` | 当前订阅 key 不是标准方舟 API Key |
| `POST /api/v3/images/generations` | HTTP 401 `AuthenticationError` | 当前 key 不能调用标准图片生成 API |
| `POST /api/v3/contents/generations/tasks` | HTTP 401 `AuthenticationError` | 当前 key 不能调用标准视频生成 API |
| `/api/plan/v3/images/generations` + `doubao-seedream-5.0-lite`（故意省略 prompt） | HTTP 400 `MissingParameter: prompt` | 当前订阅 key 已通过 Seedream 5.0 lite 模型授权；补齐 prompt 会进入真实生图 |
| `/api/plan/v3/contents/generations/tasks` + `doubao-seedance-1.5-pro`（故意省略 content） | HTTP 404 `UnsupportedModel` | 当前账号未接入该视频模型；官方页面注明该模型即将下线且不支持新增接入 |

结论：**当前 SkillForge 的火山订阅 key 可以通过 `/api/plan/v3` 调用 Seedream 5.0 lite 生图，但当前
不能调用视频生成。** 用户提供的套餐文档明确给出订阅专用图片/视频 endpoint，并警告不要改用标准
`/api/v3`，否则会产生套餐外费用；Medium 套餐暂不支持 Seedance 2.0，Seedance 1.5 Pro 即将下线且
不支持新增接入。P0 因此先完成一张最小真实图片，再把视频作为“套餐升级/新模型开放”依赖，不用标准
API 绕过订阅计费边界。

P0 最小真生成已完成：`doubao-seedream-5.0-lite`、`size=2K`、HTTP 200、耗时 13 秒，返回一张
2048×2048 JPEG；下载后为 50,500 bytes，可正常解码。响应 usage 为 `generated_images=1`、
`output_tokens=16384`。测试未记录 API Key 或完整临时 URL，只记录结果 host、尺寸、hash 和 usage。

### 3.2 Provider 范围决策（2026-07-23）

当前阶段只交付 Ark Provider：Seedream 承担图片生成及后续参考图编辑，Seedance adapter 承担视频任务，
但视频保持 feature flag 关闭直到订阅账号获得可用模型。OpenAI GPT Image、阿里 Wan 是后续图片候选；
阿里 HappyHorse、快手 Kling 是后续视频候选。候选调研不等于已接入，也不在当前 Dashboard/iOS 暴露
Provider 选择器。ChatGPT、阿里或可灵的消费者订阅均不推定包含 API 权益，接入前必须分别验证 API Key、
地域、计费、并发和数据处理条款。

## 4. 模态差异

| 特征 | 文本 | 图片 | 音频 | 视频 |
| --- | --- | --- | --- | --- |
| 主要状态 | streaming/completed | generating/ready | streaming 或 queued/ready | queued/running/downloading/ready |
| 典型存储 | message JSON | 文件 + ref | 文件 + ref + duration | 文件 + ref + poster + duration |
| 首选交付 | token delta | thumbnail/lightbox | player/transcript | poster/player |
| 恢复语义 | 重新请求需防重复 delta | 幂等生成/转存 | 区分短流与长任务 | 查询原 provider job，禁止盲重提 |
| Agent 上下文 | 原文 | 视觉 materialize | transcription/native audio | frames/transcript/native video |

## 5. 研究结论

1. 当前 Artifact 基础足以承接图片成品，但不足以表示异步音频/视频生命周期。
2. 需要统一 Media Runtime，而不是三个互不相干的 Tool。
3. `t_media_generation_job` 是必要的新状态实体；`t_chat_attachment` 继续承担最终资产。
4. 管理端和 iOS 的核心不是“显示一个 URL”，而是可恢复任务卡片、安全流式播放、通知和缓存。
5. 实时语音的网络和生命周期与文件生成不同，必须独立拆包。
6. 火山 agent plan 订阅与标准 Media API 是不同授权面；订阅本身有专用 Media endpoint 和模型白名单，
   Provider 配置必须按套餐路由，不能复用标准 `/api/v3` 或假定所有视觉模型都可用。

## 6. 媒体传输与上下文管理补充调研（2026-07-24）

### 6.1 官方 API 的共同规律

官方接口并没有统一要求使用 Base64：

- Ark 图片生成的 `image` 输入支持 URL 或 Base64，输出支持临时 URL 或 Base64。
- OpenAI Responses 的图片输入支持公网 URL、Data URL 和已上传的 `file_id`；图片编辑还可引用
  `file_id`。生成结果和流式 partial image 可能返回 Base64。
- Anthropic Messages 的视觉输入支持 URL 和 Base64；其 Files API 可将上传文件作为可复用引用。
- Gemini 对图片、音频、视频和文档同时提供 inline data、Files API 和云存储引用。官方明确建议较大、
  较长或需复用的视频走 Files API；inline data 只适合小文件和一次性请求。

这说明 Provider 协议应在调用边界适配，不能让某一种外部载体成为 SkillForge 的内部资产协议。

官方参考：

- [Ark 图片生成 API](https://api.volcengine.com/api-docs/view?action=ImageGenerations&serviceCode=ark&version=2024-01-01)
- [OpenAI API Quickstart：图片与文件输入](https://platform.openai.com/docs/quickstart/make-your-first-api-request)
- [Anthropic Vision](https://docs.anthropic.com/en/docs/build-with-claude/vision)
- [Gemini File input methods](https://ai.google.dev/gemini-api/docs/file-input-methods)
- [Gemini Video understanding](https://ai.google.dev/gemini-api/docs/video-understanding)

### 6.2 URL、Base64 和 Provider File ID 的适用边界

| 载体 | 优点 | 风险/限制 | SkillForge 用法 |
| --- | --- | --- | --- |
| SkillForge `attachment_id` | 稳定、短、可鉴权、可 Compact | Provider 不认识 | 消息、Tool 参数、数据库中的唯一长期引用 |
| 公网短时签名 URL | 请求小、适合大文件、Provider 可拉取 | 需要 Provider 可访问的对象存储；会过期 | 图片优先、音视频默认；仅在调用时生成 |
| Provider File ID/URI | 可复用、请求小、适合视频处理 | Provider 专有、有过期和删除策略 | 按 provider+attachment+hash 缓存的远端副本 |
| Base64/Data URL | 无需公网存储、兼容性最好 | 体积约增加三分之一；JSON、内存和网络开销大 | 小图片的一次性最终回退；禁止进入历史 |
| Provider 临时结果 URL | 下载方便 | 有效期短、不可作为产品资产 | 只用于服务端受控下载并转存 |

因此“继续使用之前的地址”只有在该地址仍有效且 Provider 可访问时才成立。SkillForge 当前本地鉴权下载
地址对 Ark 等公网服务通常不可达；上一次生成结果的 Provider URL 又可能过期。长期复用必须以
`attachment_id` 为入口，再在每次调用时解析成当时可用的传输载体。

### 6.3 对当前 SkillForge 的评估

当前实现的正确部分：

- 数据库和 Agent 历史只保存 `image_ref + attachment_id`，不保存 Base64 和 Provider 临时 URL。
- `EditImage` 只在调用 Ark 的最后边界生成 Data URL，Tool Result 仍为轻量 `image_ref`。
- 视觉理解的 `MessageMaterializer` 创建请求副本，不改变持久化消息形状。
- 图片原件会转存到受管 Attachment，Provider URL 失效不影响展示和再次使用。

当前实现需要演进的部分：

- `EditImage` 固定使用 Base64，没有按大小、Provider 能力和复用次数选择 URL/File ID。
- 视觉理解每次 materialize 都可能重新读取、压缩、Base64 编码同一图片，缺少可复用 Provider handle。
- 尚无统一的媒体派生物模型；视频理解需要 transcript、poster、关键帧，音频需要 transcript/waveform，
  不应每次重新预处理。
- Compact 虽保留 `attachment_id`，但仍需要资产摘要索引来决定何时重新加载原媒体或派生物。

### 6.4 推荐结论

采用“稳定资产引用 + 延迟物化 + Provider 句柄缓存”的三层模型：

1. Agent 和数据库只认识稳定的 `attachment_id` 及紧凑 metadata。
2. 服务端根据操作、模型能力、媒体大小和复用预期，选择 inline、signed URL、provider upload 或派生物。
3. Provider File ID/URI 作为可过期缓存，不作为真相源；失效后从 Attachment 重新创建。

默认策略：

- 小图片、单次 Ark 编辑：允许 Base64/Data URL。
- 重复图片理解或编辑：优先 Provider File ID；Provider 不支持时使用对象存储短签名 URL。
- 音频：优先 transcript；确需原生音频时使用 Provider File ID/签名 URL。
- 视频：默认 transcript + 稀疏关键帧；确需原生视频理解时必须使用 Provider File ID/云存储 URL，
  禁止把完整视频 Base64 放入普通 Chat 请求。
- 输出无论返回 URL 还是 Base64，都必须流式/限额转存到 Attachment，随后丢弃外部载体。
