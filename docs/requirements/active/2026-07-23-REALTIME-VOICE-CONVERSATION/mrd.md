# MRD — 实时语音对话

## 用户目标

用户希望在 iPhone 上像使用豆包或 ChatGPT 手机版一样与任意 SkillForge Agent 连续说话，而不是录制一段
语音、等待上传、再收到一段音频文件。实时语音必须保留 SkillForge 的差异化能力：Agent 身份、Session
上下文、Skill、Tool、长任务和可回看的文本记录。

## 成功标准

- 点击语音入口后，可在当前 Agent/Session 内开始连续对话。
- 用户说话时出现实时字幕；停顿后 Agent 尽快开始播报。
- Agent 播报时用户可以插话，旧播报立即停止且不会在稍后继续冒出。
- 简单问答与需要 Tool 的请求都能完成，且文本 Chat 中可回看最终对话和 Tool 结果。
- Provider 故障、网络切换、来电/耳机变化不会造成麦克风常驻、双重播放或重复 Tool 执行。
- Ark 首期完成后，接入 Qwen 不要求修改 iOS 页面、Session 数据模型或 Tool 实现。

## 非目标

- 首期不支持锁屏或切到其他 App 后持续通话。
- 首期不支持视频、屏幕共享、多人通话、电话/CarPlay。
- 首期不保存原始整段通话录音；只保存明确约定的 transcript 和 Tool/消息结果。
- 首期不做声音复刻、付费音色购买或用户上传声纹。
- 不保证服务进程 kill 后恢复同一条实时音频连接；重连创建新 Voice Session，并从已落盘文本继续。

## Provider 策略

- **首选实现：Ark Cascade** — 使用当前已付费套餐，ASR、Seed Pro、TTS 三段可观测、可替换。
- **后续候选：Qwen Audio Realtime** — 端到端体验更自然，但需要新的标准千问 API Key 和余额。
- Provider 选择是服务端配置；App 不展示模型私有 ID，除非后续增加面向高级用户的设置。

