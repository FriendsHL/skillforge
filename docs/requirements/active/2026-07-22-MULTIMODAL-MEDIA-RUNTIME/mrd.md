# MRD — 多模态媒体能力

## 用户问题

SkillForge 当前以文本和文档附件为主。用户希望已有的 Ark/OpenAI 能力可以在普通 Agent 对话中完成
图片、音频和视频生成，并在 Dashboard 与 iOS 中可靠看到、播放、下载、分享和接收完成提醒。

单独接 API 不能解决完整体验：视频和长音频生成会在 Agent turn 结束后继续运行，服务重启、页面刷新、
App 退到后台或 provider 临时 URL 过期时仍要恢复。管理员还需要看到任务、失败、费用和供应商状态。

## 目标用户

- 普通 Chat 用户：直接向当前 Agent 请求生成或分析媒体。
- 专业创作者：使用 Media Creator Agent 做提示词、分镜、风格一致性和多轮编辑。
- 管理员/开发者：配置 provider、模型、预算，检查任务与失败原因。
- iOS 用户：在移动场景接收完成提醒、播放、下载和分享媒体。

## 用户价值

1. 不必理解 provider API 或先切换专用 Agent，普通对话即可使用媒体能力。
2. 长任务可离开页面，回来后仍能看到正确状态和结果。
3. Dashboard 与 iOS 显示一致，不重复、不丢失。
4. 管理员能控制费用、并发、权限和失败重试。
5. 新 provider 可以通过适配层加入，不重写 Agent Loop 或跨端 UI 协议。

## 产品范围

### 首期范围

- Ark 作为首个图片、音频、视频 provider。
- 图片生成和编辑。
- 短/长文本转语音。
- 文生视频和参考图生视频。
- Dashboard/iOS 的生成状态和成品呈现。
- 服务重启恢复、取消、通知、费用与基础管理。

### 后续范围

- OpenAI GPT Image、Audio、Sora provider。
- 音频转写和视频理解。
- 多图参考、连续视频、尾帧续写。
- Media Creator Agent 与复杂编排。
- 实时语音对话。

## 非目标

- 自建 GPU 推理集群或训练扩散/音视频模型。
- 第一阶段支持任意媒体 provider 参数透传。
- 把 ChatGPT 网页订阅、Cookie 或非官方登录态当 API 凭据。
- 第一阶段做专业非线性视频编辑器、音轨混音台或时间线编辑器。
- 在 APNs payload 或日志中传 prompt、媒体 URL、API Key。
- 在未获授权时提供声音克隆或真人素材生成。

## 成功指标

- 图片、短音频任务成功后跨 Dashboard/iOS 各显示一次。
- 视频/长音频页面刷新、App 重启、服务重启后状态可恢复。
- 同一逻辑提交不因超时、回调重放或 worker 重启产生重复计费任务。
- 生成成功后 provider 临时 URL 被及时转存为 SkillForge 受管文件。
- 管理端可按 provider/model/type/status 查询任务和费用。
- 跨用户、跨 session、撤销移动设备均不能读取媒体。
