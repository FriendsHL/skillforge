# REALTIME-VOICE-CONVERSATION — iOS 实时语音对话

> 状态：MVP Implemented / Full / 等待真机验收
> 优先级：P1
> 日期：2026-07-23

## 摘要

在 SkillForge iOS 中提供接近豆包、ChatGPT Voice 的连续实时语音对话：用户说话时实时转写，Agent
以流式语音和字幕回复，用户可随时插话打断；复杂请求继续使用现有 Agent Loop、Skill、Tool、Session
和持久化能力。

首期只启用 Ark Agent Plan：流式 ASR 2.0 + `doubao-seed-2.0-pro` + 双向流式 TTS 2.0。
Qwen Audio Realtime 不进入首期生产，但作为第二 Provider 的兼容性目标，防止实现绑定 Ark 私有协议。

## 阅读顺序

1. [mrd.md](mrd.md) — 用户目标、范围与边界
2. [prd.md](prd.md) — 交互、功能和验收标准
3. [tech-design.md](tech-design.md) — Provider SPI、状态机、协议与安全
4. [delivery-plan.md](delivery-plan.md) — 可独立验证的分期

## 已锁定决策

1. 第一版只调用 Ark 订阅专用 `/api/v3/plan` 语音接口，不走套餐外标准 endpoint。
2. `ARK_API_KEY` 仅保存在服务端；iOS 不直连 Provider、不持有 Provider Key。
3. 实时语音是独立运行时，不把持续音频流塞入现有 Chat WebSocket。
4. 复杂意图仍进入现有 Agent Loop；Tool 执行与文本 Chat 保持同一授权、配对和持久化语义。
5. Provider 原始事件必须在服务端归一化；iOS 只消费 SkillForge Voice 协议。
6. Qwen 兼容以 contract test 锁定，不要求当前无余额百炼账号完成真活。
7. 首期只支持前台单会话；后台锁屏通话、视频、声音复刻另行增量。

## 已验证事实

- 当前 `ARK_API_KEY` 可调用 `doubao-seed-2.0-pro`，真实请求成功。
- 当前 key 的 Ark 双向 TTS 2.0 已真实生成 22 个音频帧（171330 bytes PCM，首包约 473ms）。
- 当前 key 的 Ark 流式 ASR 2.0 已真实识别 TTS 回环样本并返回 final（约 621ms）。
- 当前 `DASHSCOPE_API_KEY` 对 `qwen-audio-3.0-realtime-flash` 握手返回 `401 InvalidApiKey`；
  不能将现有 Coding key 当成 Qwen Realtime key。
- Qwen Realtime 原生支持全双工 PCM、VAD、`smart_turn`、打断和 Function Calling，可由独立 adapter 接入。

## 当前实现

- 服务端：`/ws/mobile/voice/{sessionId}`、Ark ASR/TTS adapter、Provider SPI、读写 scope 与会话归属校验。
- iOS：聊天页语音入口、麦克风采集、实时字幕、静音自动断句、Agent Loop 复用、流式播报与插话打断。
- 数据：文本问答继续写入原 Session；原始音频不持久化，Provider Key 不进入 App。
- 待用户验收：真机麦克风/扬声器路由、连续三轮、耳机、弱网和实际环境回声。
