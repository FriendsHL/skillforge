# PRD — iOS 实时语音对话

## 1. 核心交互

### 进入

- Chat 输入区提供独立 Voice 按钮；点击后先请求麦克风权限，再进入 Voice 面板。
- Voice Session 绑定进入时的 `agentId` 与 `sessionId`；通话期间不得静默切换目标 Agent。
- 若当前有文本任务运行，允许进入监听状态，但发送新语音前必须遵循现有 Session 并发策略。

### 对话中

- 显示连接、倾听、识别中、Agent 思考、Agent 说话、Tool 执行、重连和失败状态。
- 展示用户临时字幕与 Agent 流式字幕；final 前的临时字幕不得写入正式消息。
- 提供静音、扬声器/蓝牙路由、结束通话；系统电话或音频中断时暂停采集和播放。
- Agent 说话时检测到用户有效语音，100ms 级清空本地待播 buffer，并向服务端发送 cancel。
- `···`/波形只表示真实运行状态，不使用固定计时假动画。

### 结束

- 正常结束后回到同一 Chat，展示已确认的 user/assistant transcript 和 Tool 卡片。
- 未完成临时字幕不伪装成已发送消息；用户可选择重试最后一句。
- 首期不上传或保留完整原始音频。

## 2. Tool 行为

- Provider 只负责发现 tool call；真正授权、参数校验和执行仍由 SkillForge 服务端完成。
- Tool call 必须映射为现有稳定 `toolUseId`，持久化 `tool_use/tool_result` 配对后才把结果送回 Voice Provider。
- 用户打断普通播报只取消语音输出，不自动取消已经开始的有副作用 Tool。
- 需要确认的 Tool 进入 waiting 状态；Voice 播报确认问题，并在 App 显示既有确认 UI。

## 3. 错误与降级

- ASR 不可用：停止发送音频，展示可恢复错误，不把乱码 transcript 写入 Session。
- TTS 不可用：继续显示文本回答，并允许用户切回普通 Chat。
- Seed Pro/Agent Loop 不可用：播报简短错误或只显示错误文本，不循环重试。
- 弱网重连：指数退避且有明确上限；重连后只从最后已确认消息继续，不重放未确认音频。
- Provider 429/额度耗尽：提示“语音额度不可用”，不泄露上游响应或 key。

## 4. 可访问性与隐私

- VoiceOver 可读当前状态、字幕、静音与结束按钮。
- 支持 Reduce Motion；不依赖波形颜色表达唯一状态。
- 麦克风使用期间显示系统指示；结束、退出、后台或错误时必须释放 Audio Session。
- UI 清楚说明文本记录会保存；首期不保存原始录音。

## 5. 首期验收

1. 真机完成连续三轮中文语音问答。
2. Agent 开始播报后用户插话，旧音频立即停止且不恢复。
3. 语音请求触发一个只读 Tool，结果被 Agent 继续用语音回答，Chat 中配对完整。
4. 拔插耳机、一次系统音频中断、一次 Wi-Fi/蜂窝切换后无崩溃和双重播放。
5. 结束通话后，刷新或重启 App，确认 transcript 与普通 Chat 一致。
6. Ark adapter contract tests 与 Qwen fixture contract tests 均通过；两者映射到相同内部事件。

