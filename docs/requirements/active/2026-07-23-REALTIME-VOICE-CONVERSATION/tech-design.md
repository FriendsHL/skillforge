# Tech Design — 实时语音运行时

## 1. 总体架构

```text
iOS AVAudioEngine
   <-> SkillForge Voice WebSocket
       <-> RealtimeVoiceSessionService
           <-> ArkStreamingAsrAdapter
           <-> existing Agent Loop / Tools
           <-> ArkBidirectionalTtsAdapter

Future:
RealtimeVoiceSessionService <-> QwenAudioRealtimeAdapter
```

iOS 永远只连接 SkillForge。Provider Key、Provider WebSocket、Tool schema 和上游错误只存在于服务端。

## 2. Provider 边界

```text
RealtimeVoiceProvider
  open(VoiceProviderConfig, VoiceEventSink) -> RealtimeVoiceSession

RealtimeVoiceSession
  appendAudio(AudioFrame)
  commitInput()
  cancelResponse()
  submitToolResult(NormalizedToolResult)
  close(reason)
```

统一下行事件：

```text
CONNECTED
USER_SPEECH_STARTED
USER_TRANSCRIPT_DELTA
USER_TRANSCRIPT_FINAL
ASSISTANT_THINKING
ASSISTANT_TRANSCRIPT_DELTA
ASSISTANT_AUDIO_DELTA
TOOL_CALL
RESPONSE_COMPLETED
INTERRUPTED
ERROR
CLOSED
```

Ark 首期可以由 `ArkCascadeRealtimeVoiceProvider` 在内部编排 ASR、Agent Loop 与 TTS。Qwen adapter 将
`session.*`、`conversation.item.*`、`response.*` 映射到同一事件，不向 App 泄漏事件差异。

## 3. 音频契约

- App 上行统一为 PCM 16kHz、16bit、单声道、100ms frame。
- 服务端为每帧分配 session-local sequence；队列有上限，过载时关闭会话而不是无限积压。
- App 下行以 `codec/sampleRate/channels/sequence/payload` 封装；MVP 统一 PCM 24kHz 单声道。
- 每个 response 使用独立 generation ID。cancel 后该 generation 的迟到 frame 必须丢弃。
- iOS 播放 buffer 与 transcript 更新节流；不得按每个网络 delta 重建完整 Chat。

## 4. Voice Session 状态机

```text
CONNECTING -> LISTENING -> USER_SPEAKING -> COMMITTING
          -> THINKING -> TOOL_RUNNING -> SPEAKING
          -> INTERRUPTING -> LISTENING
          -> RECONNECTING -> LISTENING
          -> ENDING -> CLOSED
```

任意非终态可进入 `FAILED -> CLOSED`。MVP 由单个 iOS Voice 面板持有连接和 generation；后续并发多窗口时再
增加服务端 revision/lease，避免为当前前台单窗口引入无消费者的协议字段。

## 5. Agent Loop 与消息边界

- ASR final 才创建 user message，并复用现有 Chat 的幂等入口。
- Agent Loop 的文本 delta 同时送往 Chat 流和 TTS text buffer；TTS 失败不回滚文本回答。
- Tool call 仍由 Agent Loop 产生和持久化；Voice Provider 不直接调用 Java Tool。
- barge-in 只终止当前 TTS generation。若 Agent Loop 已完成，保留完整文本；若仍生成，首期停止继续播报但不
  擅自取消有副作用工作。是否支持“同时取消文本生成”作为后续显式策略。
- compact、恢复与 task restart 只处理已落盘消息/任务，不尝试恢复未落盘音频 frame。

## 6. SkillForge Voice WebSocket

MVP 复用现有 Mobile WebSocket Bearer 鉴权，握手时同时校验 `chat:read`、`chat:write` 和 Session 归属。
短期单次 voice ticket 留作公网规模化 hardening，不阻塞当前已配对设备。

客户端事件：

```text
asr.start
binary PCM frame
asr.commit
tts.start / tts.append / tts.commit
tts.cancel
```

服务端事件为 `voice.ready`、`transcript.delta/final`、`tts.started/completed/cancelled`、`voice.error`；TTS
generation 事件携带 `generationId`。音频 frame 使用 binary WebSocket，控制事件使用 JSON，避免 Base64 放大；
当前已限制单帧 64 KiB、单次 TTS 文本 4000 字符，并由绑定 Session 的 WebSocket 生命周期回收 Provider 连接。

## 7. 安全与可观测性

- `ARK_API_KEY`/未来 `QWEN_REALTIME_API_KEY` 只从环境变量读取，禁止写数据库、日志或客户端。
- Provider URL 固定配置并 allowlist；不接受客户端传入任意 WebSocket URL、模型或 Resource ID。
- 日志只记录 session/request ID、状态和延迟，不记录音频 payload、完整 transcript 或鉴权 header。
- 指标：建连、ASR first/final、LLM first token、TTS first audio、end-to-end first audio、打断停止、断线和错误率。
- 对 WebSocket 做认证、归属、帧大小和文本长度限制；速率、并发与空闲超时作为规模化 hardening 项。

## 8. Provider 兼容矩阵

| 能力 | Ark Cascade | Qwen Audio Realtime | 内部抽象 |
| --- | --- | --- | --- |
| 鉴权 | `X-Api-Key` | Bearer | server config |
| 输入 | ASR PCM stream | `input_audio_buffer.append` | PCM 16k frame |
| 轮次 | ASR VAD/final | server_vad/smart_turn | speech started/final |
| 输出 | Seed text delta -> TTS | `response.audio.delta` | transcript/audio delta |
| 打断 | cancel TTS + clear buffer | `response.cancel` | generation cancel |
| Tool | existing Agent Loop | normalized function call -> Agent Loop | tool request/result |
| 音色 | Ark speaker ID | Qwen voice ID | provider-scoped voice ref |

Provider 切换创建新 Voice Session；只注入已确认文本历史，不迁移上游私有 session object。
