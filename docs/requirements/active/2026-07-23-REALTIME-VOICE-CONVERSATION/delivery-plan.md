# Delivery Plan — 实时语音对话

## Phase 0 — Ark 活体 Spike

状态：完成。

- 使用当前 `ARK_API_KEY` 完成最短 TTS 2.0 真生成，记录首包延迟、音色、PCM/Opus 参数和用量。
- 使用固定测试音频完成 ASR 2.0 流式识别，记录 interim/final、VAD、首字和 final 延迟。
- 验证 `doubao-seed-2.0-pro` 流式文本输出可以增量送入双向 TTS。
- 不改生产 UI；样本与日志脱敏，不提交音频或凭据。

退出门：三段分别可用，并完成一次本机级联往返；否则停止生产开发并记录账号/协议 blocker。

## Phase 1 — Backend Voice Runtime

状态：MVP 完成，等待集成环境验收。

- 独立 WebSocket、复用 Mobile Bearer + Session 归属鉴权、session 状态机和帧边界。
- Ark ASR/TTS adapter、`RealtimeVoiceProvider` SPI、统一事件与 Qwen fixture adapter contract。
- 接入 Agent Loop 和 Tool 配对；不新增音频持久化表。
- TDD 覆盖乱序、迟到音频、cancel、断线、429、Tool 等待和重复 final。

退出门：服务端 fixture E2E 与 Ark 本机活体通过；key/音频/transcript 不泄漏。

## Phase 2 — iOS Foreground Voice

状态：MVP 完成，等待真机验收。

- 麦克风权限、AVAudioSession/AVAudioEngine、采集/播放 coordinator。
- Voice 面板、字幕、状态、静音、结束和打断。
- 网络/系统音频中断处理；首期进入后台即结束 Voice Session。
- XCTest 锁定 reducer/state/cancellation，XCUITest 锁定 UI 状态；真机验证音频路由和回声。

退出门：真机三轮对话、barge-in、Tool、耳机和网络切换验收通过。

## Phase 3 — Product Hardening

- 延迟 dashboard、Provider/错误维度指标、额度错误文案。
- transcript 对账、重连和普通 Chat 切换。
- VoiceOver、Reduce Motion、弱网和长会话 soak。
- 明确是否开放后台通话；未批准前不增加 Background Audio capability。

## Phase 4 — Qwen 可选 Provider

进入条件：获得有余额、可握手标准 Realtime endpoint 的 `QWEN_REALTIME_API_KEY`。

- 实现 Qwen adapter，不修改 iOS wire contract。
- 对比 Ark/Qwen 首包、打断、识别、自然度、Tool 和成本。
- 灰度配置切换；Provider 失败不在同一 turn 静默跨 Provider 重放 Tool。
