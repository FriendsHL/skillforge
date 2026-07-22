# MULTIMODAL-MEDIA-RUNTIME — 图片、音频、视频统一能力

> 状态：P0 / P1 同步图片闭环 complete；P2-B 视频异步代码闭环 complete、真实 Provider 验收 blocked；P2-A 音频未开始
> 模式：Full
> 优先级：P1
> 日期：2026-07-22

## 摘要

为 SkillForge 建立统一 Media Runtime，使普通 Agent 和专用 Media Agent 能生成、理解、持久化、
恢复并跨 Dashboard/iOS 交付图片、音频和视频。底层能力以 Tool 暴露，生成过程以持久化 Media Job
管理，完成文件进入现有 Chat Attachment/Artifact 链路，REST snapshot 为真相源，WebSocket 和 APNs
分别承担实时刷新与后台提醒。

本包不是“增加几个模型按钮”，也不把媒体协议塞进现有文本 `LlmProvider`。实时语音对话与普通音频
文件生成是不同的生命周期，放在独立后续需求包。

## 阅读顺序

1. [research-report.md](research-report.md) — 本地实现、`research-docs` 与官方 API 调研结论
2. [mrd.md](mrd.md) — 用户目标和产品边界
3. [prd.md](prd.md) — 跨端功能要求与验收标准
4. [tech-design.md](tech-design.md) — 数据模型、状态机、协议和恢复设计
5. [delivery-plan.md](delivery-plan.md) — 可独立验收的四期拆分

## 已锁定的架构决策

1. **Tool 是底层能力，Agent 是体验层。** Main Agent 可直接使用媒体 Tool；Media Creator Agent 使用同一组 Tool，不复制 provider 实现。
2. **媒体 Provider 与文本 LLM Provider 分离。** 图片、音频、视频不扩展当前 Chat Completions/SSE 契约。
3. **Media Job 与 Attachment 分离。** Job 表示生成过程，Attachment 表示已存在的受管文件。
4. **异步 Tool 当场完成配对。** `GenerateVideo` 提交任务后立即返回 `jobId`；完成事件不伪造成延迟 `tool_result`。
5. **数据库状态是真相源。** REST 用于首屏和重连对账；WebSocket 只加速更新；APNs 只提醒。
6. **媒体文件必须转存。** 不把 provider 临时 URL 当长期资产，不把 Base64/二进制写入消息历史。
7. **同一跨端消息形状。** Dashboard 和 iOS 消费相同 typed content block 与媒体任务 DTO。
8. **实时语音单独立项。** P1–P3 不增加持续麦克风、WebRTC、后台音频或 barge-in。
9. **当前生产 Provider 只支持 Ark。** 图片使用订阅专用 Seedream endpoint；视频仅保留 Ark/Seedance
   adapter 与关闭态能力，待当前订阅开放可用模型后启用。OpenAI GPT Image、阿里 Wan/HappyHorse、
   快手 Kolors/Kling 只列为候选，不进入当前实现、配置界面或验收范围。

## 四期交付

| Phase | 范围 | 主要表面 | 退出门 |
| --- | --- | --- | --- |
| P0 | 账号与 Provider Spike | 订阅专用 Media endpoint、Seedream 真调用、Seedance 可用性与套餐边界 | **PASS**：图片成功；视频阻塞已明确；密钥未入库/日志 |
| P1 | 同步图片闭环 | 订阅端点保护、GenerateImage、Attachment 幂等、Dashboard+iOS 既有 `image_ref` 交付 | Ark Main Agent 真生图、刷新不重复、跨端呈现回归 |
| P2 | 音频与视频异步闭环 | 视频 job、轮询/取消/恢复、转存与两端播放器已实现；TTS、Range/APNs 待后续增量 | 视频 Provider 开通后补真活；音频独立推进 |
| P3 | 理解与创作体验 | ASR、视频抽帧/字幕、受控 materialization、Media Creator Agent | 多模态问答与专用 Agent 共用同一 Runtime |

实时语音仍是独立需求包，不纳入上述四期，避免持续音频会话拖慢文件媒体能力交付。

## 与现有需求的关系

- 复用 [IOS-AGENT-FILE-DELIVERY](../2026-07-16-IOS-AGENT-FILE-DELIVERY/index.md) 的受管文件、消息 sidecar、移动鉴权下载和分享链路。
- 复用 [IOS-TASK-COMPLETION-PUSH](../2026-07-16-IOS-TASK-COMPLETION-PUSH/index.md) 的 APNs token、delivery worker 和 session 路由。
- 遵守 [TASK-RESUME-ON-RESTART](../../backlog/TASK-RESUME-ON-RESTART/index.md) 的 kill recovery 原则，但 Media Job 只恢复 provider 查询/下载，不重放已确认提交。
- 不把媒体 Job 混入 Session 的 `running/idle/error` 状态；一个 Session 可以在 Agent 已 idle 后仍有媒体任务运行。

## 开发门

本需求包已由用户批准进入 Full pipeline，P0 与 P1 同步图片闭环已经完成。P1 使用
`api/plan/v3` 订阅专用 Media endpoint，不得切换标准 `/api/v3` 产生套餐外费用。视频开发须等当前套餐
出现可新增接入的 Seedance 模型，或由用户明确批准另一计费方案。
文档和测试不得记录密钥。P2-B 默认关闭；只有订阅 endpoint 接受目标模型后才能开启并完成真实视频验收。
新增其他媒体 Provider 必须由独立需求增量批准，并继续保持图片与视频 Provider SPI 分离；不得为了兼容
候选厂商而提前扩展当前 Ark 请求协议。
