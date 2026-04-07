# 会话状态感知 + 交互确认 + 执行模式设计

## 背景与目标

当前 Chat 流程有三个痛点:

1. **刷新即失忆**：Chat API 同步阻塞,Agent Loop 跑完才批量写库,中途刷新看不到正在跑的 query
2. **运行黑盒**：没有会话级运行状态,不知道 agent 当前在哪一步、是否卡住、是否挂了
3. **过度自主**：Skill 失败时 LLM 不问用户原因,自己找变通,用户无法确认是否符合意图

目标:

- 刷新后立即看到完整的消息历史 + 当前运行状态
- 后端 Loop 每一步都推到前端
- 提供"问用户"的能力,和"执行模式"的开关

## 技术决策

| 项 | 决策 | 理由 |
|----|------|------|
| 推送方式 | WebSocket(项目已有 `spring-boot-starter-websocket`) | 实时,避免轮询 |
| AskUser 阻塞方式 | CountDownLatch 阻塞 Loop 线程(同 SubAgentExecutor) | 实现简单,线程数少 |
| AskUser 实现形态 | **独立特殊 Tool**(不走 SkillRegistry,engine 识别后走阻塞等 UI 分支) | 吸收 Claude Code 经验,未来可复用;实现仍保持简单 |
| AskUser schema | 多选题格式 `{ question, options[2-4], allowOther }` | 降低用户回答成本,让 LLM 问题更结构化(借鉴 Claude Code `AskUserQuestionTool`) |
| confirmation_level 配置 | **改为 execution_mode**(ask / auto),Agent 默认 + Session 可覆盖 | 更符合用户心智,单次会话可临时切换 |
| 线程池规格 | core=8 / max=64 / queue=128,溢出返 429 | Browser 类长任务占线程,给足空间 |
| 状态粒度 | 只广播 session 状态变更和消息追加 | 数据量可控 |

## 执行模式

AgentEntity 新增字段 `executionMode`(默认 `ask`),SessionEntity 也新增同名字段作为**覆盖值**:
- 创建 Session 时从 Agent 拷贝一份到 Session
- 用户可在会话 UI 里临时切换当前 Session 的模式(不影响 Agent 默认)
- Loop 每轮取 `session.executionMode`


| 模式 | 行为 |
|------|------|
| `ask` | Agent 在关键节点主动向用户确认,尤其是:① Skill 调用失败 ② 原始请求有歧义 ③ 打算采用明显偏离用户描述的替代方案 |
| `auto` | Agent 自主执行,只有完全无法推进时才求助用户 |

两种模式通过 **不同的 system prompt 片段** 注入,不需要改 Loop 代码。

### system prompt 模板片段

**ask 模式追加:**
```
## 用户交互原则
你正在 ask 模式下工作,用户希望清楚地看到你每一步的意图。遇到以下情况必须调用 AskUser 工具,而不是自己尝试变通:
1. 某个 Skill 返回错误,你无法确定是用户环境问题、网络问题还是工具 bug —— 把错误原样告诉用户,让他判断
2. 用户原始请求存在多种合理解读 —— 列出你的理解,请用户确认
3. 为了完成任务,你打算采用用户没明确提到的替代路径 —— 先说明思路,等用户同意
4. 你对结果的置信度很低 —— 说明不确定性,让用户决定是否接受

不要臆测原因,不要擅自兜底。
```

**auto 模式追加:**
```
## 用户交互原则
你正在 auto 模式下工作,用户希望你自主完成任务。尽量独立解决问题,仅在以下情况调用 AskUser:
1. 任务完全无法推进(不是单个 Skill 失败,而是整条路径都走不通)
2. 执行过程中发现用户请求本身存在错误或冲突
3. 涉及不可逆操作前的最后确认(删除、发送、付费等)
```

## 运行时状态模型

### SessionEntity 新增字段

```java
// 运行时状态: idle / running / waiting_user / error
private String runtimeStatus = "idle";

// 当前步骤描述,如 "Calling Browser.goto" / "Waiting for your reply"
@Column(length = 256)
private String runtimeStep;

// 最近一次错误消息(runtimeStatus = error 时填)
@Column(columnDefinition = "TEXT")
private String runtimeError;
```

### 状态迁移

```
idle ──(user sends msg)──> running ──(loop done)──> idle
                            │  │
                            │  └──(exception)──> error ──(next user msg)──> running
                            │
                            └──(AskUser called)──> waiting_user ──(user answers)──> running
```

## 后端改造

### 1. ChatController 流程重构

原来:
```
POST /api/chat/{sid}
  │
  ├─ run Agent Loop (blocking)
  ├─ save all messages
  └─ return final response
```

新的:
```
POST /api/chat/{sid}
  │
  ├─ 1. persist user message to DB (role=user)
  ├─ 2. update session.runtimeStatus = running
  ├─ 3. broadcast WS event: message_appended + session_status
  ├─ 4. submit Agent Loop to thread pool (async)
  └─ 5. return 202 Accepted { sessionId } immediately

[Async thread]
  Agent Loop runs, each step:
    ├─ save message to DB
    ├─ broadcast WS event
  Loop ends:
    ├─ update session.runtimeStatus = idle (or error)
    └─ broadcast final session_status
```

### 2. AskUser:独立特殊 Tool

**设计形态**:AskUser 不是 Skill,也不走 SkillRegistry/ToolExecutor,但在 LLM 眼里它就是一个普通 tool。engine 在 tool schema 注入阶段,根据 `session.executionMode` 决定要不要把它塞进 tools 列表;在解析 tool_use 时,engine 识别出是 AskUser 就走特殊分支(阻塞等 UI),而不是普通的 skill 执行。

这是吸收 Claude Code `AskUserQuestionTool` 的做法:让它和 Skill 同构(都是 tool),便于 LLM 理解;但实现上是 engine 内置的(不需要 SkillRegistry 查找)。

**Tool schema**(注入到 LLM 请求):
```json
{
  "name": "ask_user",
  "description": "当前方案走不通、需要换路径或需要用户决策时,向用户提一个多选题。仅当你判断 ①skill 持续失败且原因不明 ②需要采用用户未提及的替代方案 ③原始请求有歧义 时调用。",
  "input_schema": {
    "type": "object",
    "properties": {
      "question":    { "type": "string", "description": "给用户的问题,具体、完整、以问号结尾" },
      "context":     { "type": "string", "description": "可选,为什么问这个问题(例如刚才什么失败了)" },
      "options": {
        "type": "array",
        "minItems": 2,
        "maxItems": 4,
        "items": {
          "type": "object",
          "properties": {
            "label":       { "type": "string", "description": "短标签,1-5 个词" },
            "description": { "type": "string", "description": "可选,对该选项的额外说明" }
          },
          "required": ["label"]
        }
      },
      "allowOther":  { "type": "boolean", "default": true, "description": "是否允许用户输入自由文本作为第 N+1 个选项" }
    },
    "required": ["question", "options"]
  }
}
```

借鉴要点:
- **强制 2-4 个选项**,LLM 被迫想清楚有哪些路径,用户一键选择
- **allowOther** 默认 true,用户随时可以回退到自由文本,兜底
- `context` 字段让 LLM 把"为什么问"说清楚,用户不用猜

**引擎处理流程**(在 AgentLoopEngine 解析 tool_use 时优先匹配):
```
if (toolUse.name == "ask_user") {
    askId = UUID.random()
    latch = new CountDownLatch(1)
    pendingAsks.put(askId, PendingAsk(latch, null))

    // 1. 存一条 assistant message(文本 = context + question)
    messageRepo.save(assistant, context + "\n\n" + question)
    // 2. 更新 session 状态
    session.runtimeStatus = "waiting_user"
    session.runtimeStep   = "Waiting for your reply"
    // 3. 广播 ask_user 事件(带 askId/options/allowOther 给前端渲染)
    broadcaster.send(sessionId, { type:"ask_user", askId, question, context, options, allowOther })
    broadcaster.send(sessionId, { type:"session_status", status:"waiting_user" })
    // 4. 阻塞等待
    boolean ok = latch.await(30, MINUTES)
    PendingAsk ans = pendingAsks.remove(askId)
    // 5. 把答案塞回 messages 作为 tool_result,继续 loop
    String answerText = ok ? ans.answer : "User did not respond within timeout.";
    messages.add(toolResult(toolUse.id, answerText))
    session.runtimeStatus = "running"
    broadcaster.send(..., running)
    continue loop;
}
```

**实现位置**:
- `AskUserTool`(core 模块,纯常量类):`NAME = "ask_user"`, `INPUT_SCHEMA = {...}`, `DESCRIPTION = "..."`。没有 execute 方法,只是个 schema 载体
- `PendingAskRegistry`(core 模块):维护 `Map<askId, PendingAsk>`,提供 `register / complete / cancel`
- `ChatEventBroadcaster`(core 接口,server 实现):推 WS 事件
- `AgentLoopEngine`:
  - `buildTools()`:根据 `session.executionMode == "ask"` 决定是否 append `AskUserTool.toToolSchema()`
  - `handleToolUse()`:if `toolUse.name == "ask_user"` → 走上面的特殊分支
- server 的 `POST /api/chat/{sid}/answer` → `PendingAskRegistry.complete(askId, answer)` → release latch

**auto 模式**:buildTools 不 append AskUser schema,LLM 看不到这个 tool,自然不会调用。

### 3. WebSocket 端点

复用 `WebSocketConfig` 注册新 endpoint `/ws/chat/{sessionId}`,或用现有 handler 加订阅逻辑。

**事件格式 (JSON):**
```json
{ "type": "session_status", "sessionId": "...", "status": "running", "step": "Calling Browser.goto", "error": null }
{ "type": "message_appended", "sessionId": "...", "message": { "role": "assistant", "content": "...", "toolCalls": [...] } }
{ "type": "ask_user", "sessionId": "...", "question": "...", "options": [...], "askId": "uuid" }
```

**服务端广播:**
- 维护 `Map<sessionId, Set<WebSocketSession>>`
- Agent Loop 通过注入的 `ChatEventBroadcaster` 接口推送

### 4. 新 REST API

```
POST /api/chat/{sid}/answer
Body: { answer: string, askId: string }
  → 找到对应 latch release,unblock Loop
```

## 前端改造

### 1. 状态管理

`Chat.tsx` 新增状态:
- `sessionStatus: 'idle' | 'running' | 'waiting_user' | 'error'`
- `sessionStep: string`
- `sessionError: string`
- `pendingAsk: { askId, question, options } | null`

### 2. WebSocket 连接

进入 session 时建立 WS 连接,订阅该 sessionId。离开时断开。

### 3. UI 呈现

| 状态 | 头部 banner | 输入框行为 |
|------|------------|----------|
| idle | 无 | 正常发送消息 |
| running | "🟢 Agent 正在运行:{step}" + 加载指示 | 输入框禁用(或允许排队? 先禁用) |
| waiting_user | "💬 Agent 在问你:{question}" + 选项按钮/输入框 | 专用的 answer 输入 |
| error | "❌ 出错了:{error}" (红色) | 可点击重新发送 |

### 4. Agent 编辑页新增 executionMode 下拉框

Form 加一个 `executionMode` 字段,选项:`ask` / `auto`,默认 `ask`。

## 实施步骤

**本次一把做完 Phase 1+2(状态感知 + AskUser + 模式),Phase 3 为收尾项。**

### Phase 1+2:状态感知 + AskUser + 执行模式
1. SessionEntity 加字段(runtimeStatus/Step/Error + executionMode)
2. AgentEntity 加 `executionMode` 字段,Agent 编辑页暴露下拉框
3. DB schema 自动 update(H2 ddl-auto)
4. core: `ChatEventBroadcaster` 接口 + `PendingAskRegistry`
5. core: `AgentLoopEngine` 注入 broadcaster/registry,新增:
   - 每步广播 session_status + message_appended
   - 根据 session.executionMode 注入 ask_user tool schema
   - 识别 ask_user tool_use → latch 阻塞 → 回填 tool_result
6. server: `ChatWebSocketHandler` 实现 broadcaster,维护 sessionId→sessions map
7. server: `ChatController` 异步化,用 ThreadPoolExecutor(core=8, max=64, queue=128),溢出 429
8. server: 新增 `POST /api/chat/{sid}/answer` 路由到 PendingAskRegistry
9. server: 新增 `PATCH /api/chat/sessions/{sid}/mode` 切换当前 session 模式
10. 前端 Chat.tsx 重构:
    - 建立 WS 连接,处理 3 种事件
    - 4 种状态 banner(idle/running/waiting_user/error)
    - 发送改为 fire-and-forget,不再等 HTTP 响应
    - waiting_user 时显示回答输入框 + 选项按钮
    - 顶部加 session 模式切换器(ask/auto)

### Phase 3:收尾
11. Session 列表页面显示 runtimeStatus 徽章
12. 超时处理:Loop 线程卡死 > 10 分钟自动置 error
13. 手动测试:各种失败/成功/交互场景

## 关键风险与应对

| 风险 | 应对 |
|------|------|
| Agent Loop 异步化后,线程池满 | ThreadPoolExecutor(core=8, max=64, queue=128),溢出拒绝新请求并返回 429 |
| WebSocket 连接掉线 | 前端自动重连,重连后先 GET 一次 session 状态和消息列表补齐 |
| Latch 阻塞超时 | AskUserSkill 默认 30 分钟超时,超时返回 `"User did not respond"` 让 LLM 自行决定下一步 |
| 前端切窗口回来消息错位 | 重连时 `GET /api/chat/sessions/{sid}/messages` 全量覆盖本地状态 |
| Loop 中途进程重启 | 本次先不做恢复,重启后 session 状态直接置 error |

## 不在本次范围

- Loop 中断/取消按钮(后续做)
- Session 列表实时刷新(后续做)
- Agent Loop 进程重启的状态恢复
- 多用户并发的权限隔离
- 流式 text delta(每个 token 推前端),先整消息推
