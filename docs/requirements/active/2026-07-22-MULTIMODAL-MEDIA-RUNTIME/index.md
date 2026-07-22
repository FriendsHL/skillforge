# MULTIMODAL-MEDIA-RUNTIME — 图片、音频、视频统一能力

> 状态：proposed，等待方案审批后按 milestone 开发
> 模式：Full
> 优先级：P1
> 日期：2026-07-22

## 摘要

为 SkillForge 建立统一 Media Runtime，使普通 Agent 和专用 Media Agent 能生成、理解、持久化、
恢复并跨 Dashboard/iOS 交付图片、音频和视频。底层能力以 Tool 暴露，生成过程以持久化 Media Job
管理，完成文件进入现有 Chat Attachment/Artifact 链路，REST snapshot 为真相源，WebSocket 和 APNs
分别承担实时刷新与后台提醒。

本包不是“增加几个模型按钮”，也不把媒体协议塞进现有文本 `LlmProvider`。实时语音对话与普通音频
文件生成是不同的生命周期，放在独立后续 milestone。

## 阅读顺序

1. [research-report.md](research-report.md) — 本地实现、`research-docs` 与官方 API 调研结论
2. [mrd.md](mrd.md) — 用户目标和产品边界
3. [prd.md](prd.md) — 跨端功能要求与验收标准
4. [tech-design.md](tech-design.md) — 数据模型、状态机、协议和恢复设计
5. [delivery-plan.md](delivery-plan.md) — 可独立验收的 milestone 拆分

## 已锁定的架构决策

1. **Tool 是底层能力，Agent 是体验层。** Main Agent 可直接使用媒体 Tool；Media Creator Agent 使用同一组 Tool，不复制 provider 实现。
2. **媒体 Provider 与文本 LLM Provider 分离。** 图片、音频、视频不扩展当前 Chat Completions/SSE 契约。
3. **Media Job 与 Attachment 分离。** Job 表示生成过程，Attachment 表示已存在的受管文件。
4. **异步 Tool 当场完成配对。** `GenerateVideo` 提交任务后立即返回 `jobId`；完成事件不伪造成延迟 `tool_result`。
5. **数据库状态是真相源。** REST 用于首屏和重连对账；WebSocket 只加速更新；APNs 只提醒。
6. **媒体文件必须转存。** 不把 provider 临时 URL 当长期资产，不把 Base64/二进制写入消息历史。
7. **同一跨端消息形状。** Dashboard 和 iOS 消费相同 typed content block 与媒体任务 DTO。
8. **实时语音单独立项。** M1–M4 不增加持续麦克风、WebRTC、后台音频或 barge-in。

## 需求拆分

| Milestone | 范围 | 主要表面 | 依赖 |
| --- | --- | --- | --- |
| M0 | Provider 真实能力与账号验证 | Ark/OpenAI spike、费用和限流记录 | 无产品改动 |
| M1 | Media Runtime Foundation | Job 状态机、Provider SPI、管理端 Jobs/Providers | M0 |
| M2 | 图片生成闭环 | Generate/Edit Image、Dashboard+iOS 图片交付 | M1 |
| M3 | 音频文件闭环 | TTS、长文本异步、audio_ref、双端播放器 | M1 |
| M4 | 视频生成闭环 | Generate Video、video_ref、播放票据、恢复、APNs | M1，建议 M2 |
| M5 | 多模态理解 | 音频转写、视频抽帧/字幕、受控 materialization | M2–M4 |
| M6 | Media Creator Agent | 专用创作 Agent、复杂工作流、可选委派 | M2–M4 |
| M7 | 实时语音 | VAD、实时 ASR/音频模型、流式 TTS、打断 | 独立需求包 |

## 与现有需求的关系

- 复用 [IOS-AGENT-FILE-DELIVERY](../2026-07-16-IOS-AGENT-FILE-DELIVERY/index.md) 的受管文件、消息 sidecar、移动鉴权下载和分享链路。
- 复用 [IOS-TASK-COMPLETION-PUSH](../2026-07-16-IOS-TASK-COMPLETION-PUSH/index.md) 的 APNs token、delivery worker 和 session 路由。
- 遵守 [TASK-RESUME-ON-RESTART](../../backlog/TASK-RESUME-ON-RESTART/index.md) 的 kill recovery 原则，但 Media Job 只恢复 provider 查询/下载，不重放已确认提交。
- 不把媒体 Job 混入 Session 的 `running/idle/error` 状态；一个 Session 可以在 Agent 已 idle 后仍有媒体任务运行。

## 开发门

开始 M1 前需要用户批准本需求包。开始真实 provider 接入前需由用户在本地配置对应 API Key/账号权限；
文档和测试不得记录密钥。每个 milestone 独立评审、验证、commit，不把所有模态合成一个大提交。
