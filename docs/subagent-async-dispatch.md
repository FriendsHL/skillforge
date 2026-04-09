# SubAgent 异步派发机制设计与端到端验证

## 背景与目标

在重写之前，SubAgent 是一个**同步阻塞**的 Skill：父 Agent 调 dispatch 后整个 loop 卡住，靠轮询 `SubAgentTask` 等子完成。这带来三个痛点：

1. **父 loop 占用线程**：长任务会让线程池堆积
2. **没有结果回推**：父必须主动 poll，prompt 里塞不下"等结果"的优雅控制
3. **重启即丢**：所有运行态在内存里，server crash 一切清零

目标改成 **fire-and-forget + 自动 resume**：
- 父 dispatch 立即拿到 runId 继续干别的或直接结束当前轮
- 子在自己的 session 里独立跑（独立线程池任务）
- 子结束时**结果作为新的 user message 自动注入父 session**，父 loop 被自动 chatAsync 唤醒
- 全部状态落 H2，重启不丢

## 架构

```
┌─────────────────┐  dispatch(agentId, task)  ┌────────────────────┐
│  Parent Session │ ───────────────────────► │  SubAgentSkill     │
│  (running loop) │                          │   (server module)  │
└─────────────────┘                          └────────────────────┘
        ▲                                              │
        │ chatAsync(parent, [SubAgent Result ...])     │ registerRun
        │                                              ▼
┌─────────────────┐                          ┌────────────────────┐
│  ChatService    │ ─── onSessionLoopFinished│  SubAgentRegistry  │
│  runLoop finally│       (callback)         │   - depth/concurr  │
└─────────────────┘                          │   - runRepository  │
        ▲                                    │   - pendingRepo    │
        │                                    └────────────────────┘
        │ chatLoopExecutor.execute                     │
        │                                    create+save
┌─────────────────┐                                    ▼
│  Child Session  │                          ┌────────────────────┐
│  (own loop)     │ ◄────────────────────────│  SessionService    │
└─────────────────┘   createSubSession        │  .createSubSession │
                                              └────────────────────┘
```

### 关键不变量

| 不变量 | 实现位置 |
|---|---|
| 父 dispatch 不阻塞 | `SubAgentSkill.handleDispatch` 立即 return runId，`chatService.chatAsync(child, ...)` 异步起 |
| 子结束 ⇒ 父被通知 | `ChatService.runLoop` finally 调 `subAgentRegistry.onSessionLoopFinished(sessionId, ...)` |
| 父 idle 时立刻 resume | `onSessionLoopFinished` → enqueue → `maybeResumeParent` → `chatAsync(parent, combinedPayload)` |
| 父 running 时不抢跑 | `maybeResumeParent` 检查 `parent.runtimeStatus == "idle"`，否则只 enqueue |
| 父自己 loop 结束时 drain 残留 | finally 钩子也对自己 `sessionId` 调一次 `maybeResumeParent` |
| 多子结果合并成单条 | drain 时把 pending 队列所有 row 的 payload 拼起来，一次 `chatAsync` |
| 重启不丢 in-flight | `t_subagent_run` + `t_subagent_pending_result` 两张表，H2 ddl-auto 自动建 |
| chatAsync 失败不丢消息 | 删行后 try chatAsync，catch 时把合并 payload 重新插回作为单行 |
| depth ≤ 3 / 每父并发 ≤ 5 | `SubAgentRegistry.MAX_DEPTH` / `MAX_ACTIVE_CHILDREN_PER_PARENT`，`registerRun` 检查 |

### 数据库 schema

`t_subagent_run`：每次 dispatch 一行
- `runId` (PK String 36)
- `parentSessionId` (indexed)
- `childSessionId`（attach 后回填）
- `childAgentId`、`childAgentName`、`task` (CLOB)
- `status`（RUNNING / COMPLETED / FAILED / CANCELLED）
- `finalMessage` (CLOB, nullable)
- `spawnedAt`、`completedAt`

`t_subagent_pending_result`：父的结果信箱队列
- `id` (Long auto-gen PK，决定 drain 顺序)
- `parentSessionId` (indexed)
- `payload` (CLOB)
- `createdAt`

`session` 表新增 3 列：`parent_session_id`、`depth`、`sub_agent_run_id`。

### 并发保护

`maybeResumeParent` 用 **64 槽固定 stripe lock 数组**（按 `parentSessionId` 哈希分桶）保证同一父只有一个线程在 drain，避免重复投递。

⚠️ 这是单 JVM MVP 方案。多实例部署时应换成 DB 行锁（`SELECT ... FOR UPDATE`）或乐观锁。

### REST 端点（dashboard 用）

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/chat/sessions/{id}/children` | 列出某个父 session 派生的所有子 session |
| `GET` | `/api/chat/sessions/{id}/subagent-runs` | 列出某个父 session 的所有 SubAgent dispatch run |

主 session 列表 `GET /api/chat/sessions?userId=...` 自动过滤掉子 session（`parentSessionId IS NULL`），不污染主列表。

### Dashboard UI

- `Chat.tsx` 跟 `useParams` 同步 sessionId，支持 `/chat/{childId}` 直接进子
- 子 session 顶部出现 "↑ Back to parent (SubAgent child · depth N)" 面包屑
- 父 session 顶部挂 `SubAgentRunsPanel`：列出子 agent 名 / 状态标签 / 短 runId / 派发时间 / task 摘要 / "View child" 链接
- 父 running 时面板每 3s 轮询 `/subagent-runs`，idle 时停止；running→idle 边沿再 fetch 一次保证终态

## 端到端验证（2026-04-08）

完整流程通过 **API + Playwright（agent-browser）+ 真实 LLM 调用** 验证。

### 环境

- Server: 重 build 后 PID 21565（带今天所有改动），:8080
- Dashboard: vite dev server :5180（PID 39733），HMR
- LLM: bailian / dashscope（DASHSCOPE_API_KEY 已 export）
- Agent 1 "General Assistant"：带 SubAgent skill，executionMode=ask
- Agent 2 "Calculator Helper"：bash + python 计算，auto

### Phase 1 — 后端契约 + 渲染 smoke

| 测项 | 命令 / 操作 | 结果 |
|---|---|---|
| `/children` 端点 | `curl /api/chat/sessions/324d3e71.../children` | ✅ 返回子 session JSON，含 depth=1、parentSessionId、subAgentRunId |
| `/subagent-runs` 端点 | `curl /api/chat/sessions/324d3e71.../subagent-runs` | ✅ 返回 `[]`（旧 in-memory run 重启丢了 — 反向证明持久化必要）|
| 主列表过滤 | `curl /api/chat/sessions?userId=1` | ✅ 26 session 中**不含**子 session `8bee2269` |
| 子 session 字段 | `curl /api/chat/sessions/8bee2269...` | ✅ depth=1, parentSessionId=324d3e71..., subAgentRunId=8a31683e... |
| 父 session 渲染 | 浏览器 `/chat/324d3e71...` | ✅ 历史消息完整，含蓝色 `[SubAgent Result]` 自动注入 user 消息和父的 fallback 回答 |
| 子 session 面包屑 | 浏览器 `/chat/8bee2269...` | ✅ "↑ Back to parent (SubAgent child · depth 1)" 显示并可点击导航 |

### Phase 2 — 真实 LLM dispatch 端到端

**测试**：新建 parent session `f4a9c7f8`（agent 1，auto 模式），发送提示词
> 请立刻调用 SubAgent 工具，参数 action=dispatch, agentId=2, task="计算 99 * 88 等于多少"。派发后只回我一句"已派发"，不要等结果。

**事件时间线**（API 轮询采样）：

```
t+0s    POST /api/chat/{sid} → 202 accepted
t+0s    /subagent-runs 立刻出现 1 行 RUNNING        ✅ 持久化生效
t+0s    child session 创建，run 状态 RUNNING        ✅ async dispatch
t+9s    parent idle (msgs=4: user, tool_use,
        tool_result, "已派发")                      ✅ 父不阻塞
t+15s   child error (LLM read timed out)
        → run FAILED，finalMessage 落库              ✅ 失败传播
t+15s   parent 自动 running                          ✅ 自动 resume
        (SubAgentRegistry 检测到父 idle → wake)
t+~30s  parent idle (msgs=6)
        父智能 fallback，自己算出 99 × 88 = 8,712   ✅ 容错
```

**Token 消耗**：
- Parent 两轮 loop: input **5887** / output **225**
- Child（超时未完成）: 0 / 0
- 估算 ¥0.01–0.02

**Dashboard `SubAgentRunsPanel` DOM 验证**（agent-browser eval 直接抓 DOM）：

```
SubAgent dispatches
Calculator Helper       ← 子 agent 名
FAILED                  ← 状态标签
1da9e8f5                ← 短 runId
19:31:30                ← spawn 时间
计算 99 * 88 等于多少    ← task 摘要
View child              ← 子 session 跳转链接
```

5 个字段全部正确渲染 ✅

### 自动化覆盖

| 层 | 覆盖 |
|---|---|
| **单元测试** `SubAgentRegistryTest` | depth/并发上限、enqueue 路径、父 idle 自动 resume、父 running 不抢跑、父 finally drain、多子合并 → **7/7 通过** |
| **API 契约** | curl 4 个端点，全部返回正确 schema |
| **端到端 LLM** | 上述时间线验证，含成功的 fallback 路径 |
| **重启持久化** | 未跑（只 cost 观察 t_subagent_run 表里的 row 还在），逻辑上 H2 file db 必然保留 |

## 后续 Claude Code 端到端测试 playbook

下次需要再跑完整 e2e 验证时，按这个顺序：

```bash
# 0. 确认 server 跑的是最新 jar
ps -p $(lsof -ti :8080) -o lstart,command   # 启动时间应晚于最近一次 build

# 1. 重 build 并重启 server (JDK 17 必须显式)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  mvn -pl skillforge-server -am clean package -DskipTests
kill $(lsof -ti :8080); sleep 2
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  DASHSCOPE_API_KEY=$DASHSCOPE_API_KEY \
  nohup java -jar skillforge-server/target/skillforge-server-1.0.0-SNAPSHOT.jar \
    > /tmp/skillforge-server.log 2>&1 &

# 2. dashboard dev server (注意 5173 可能被其他项目占)
cd skillforge-dashboard && nohup npm run dev -- --port 5180 \
  > /tmp/skillforge-dash.log 2>&1 &

# 3. 端点 smoke (应已存在 agent 1 = 父+SubAgent skill, agent 2 = 子)
curl -s http://localhost:8080/api/agents | python3 -m json.tool

# 4. 新建测试 session 并切 auto
SID=$(curl -s -X POST http://localhost:8080/api/chat/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"agentId":1}' | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
curl -s -X PATCH "http://localhost:8080/api/chat/sessions/$SID/mode" \
  -H "Content-Type: application/json" -d '{"mode":"auto"}'

# 5. 触发 dispatch
curl -s -X POST "http://localhost:8080/api/chat/$SID" \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"message":"请调用 SubAgent dispatch 给 agentId=2 task=...派发后回\"已派发\"不要等"}'

# 6. 轮询 /subagent-runs 直到 status 不是 RUNNING
for i in {1..30}; do
  curl -s "http://localhost:8080/api/chat/sessions/$SID/subagent-runs" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['status'] if d else 'none')"
  sleep 3
done

# 7. 浏览器打开父 session 截图验证 SubAgentRunsPanel
npx agent-browser goto "http://localhost:5180/chat/$SID"
npx agent-browser screenshot /tmp/result.png
# 或直接 eval 抓 DOM 文字（避免 viewport 折叠遗漏）
npx agent-browser eval "document.body.innerText.match(/SubAgent dispatches[\\s\\S]{0,300}/)"
```

### 易踩的坑

- **mvn 默认走 JDK 8**：parent pom 用 `--release 17`，必须 `JAVA_HOME=...temurin-17`
- **5173 已被 EvolvingAgent 占用**：起 dashboard 时显式 `--port 5180`
- **Agent 1 默认 ask 模式**：新建 session 后立刻 PATCH `/mode` 切 auto，否则 SubAgentSkill 会卡在 ask_user
- **截图可能折叠**：右栏内容长时 SubAgentRunsPanel 在 fold 上面，screenshot 看不到。用 `agent-browser eval` 抓 `document.body.innerText` 更可靠
- **Dashscope read timeout 120s**：偶发超时不是机制 bug，需要重试或换 model

## 后续工作的跟进状态（2026-04-09 更新）

### ✅ 已交付（本轮并行 3 agent 完成）

1. ~~**server 重启时正在跑的子线程会丢**~~ → **`SubAgentStartupRecovery`**（`init/SubAgentStartupRecovery.java`）：`ApplicationRunner` @Order(100)，启动时扫 `t_subagent_run.status=RUNNING`，按子 session 运行态分流：
   - 子 running → 调 `chatService.chatAsync(childId, "[Resume from restart] Continue your previous work.", userId)` 重新拉起
   - 子 idle/error → 通过 `subAgentRegistry.onSessionLoopFinished` 回放 finally 钩子，父会被 enqueue 并 wake
   - 子缺失 / childSessionId 为空 → 调 `notifyParentOfOrphanRun` 标 CANCELLED 并通知父
2. ~~**无 `/sub_agent_run` 超时清理 job**~~ → **`SubAgentRunSweeper`**（`subagent/SubAgentRunSweeper.java`）：`@Scheduled(fixedDelay=60_000, initialDelay=30_000)` 定时清理三种僵尸：stale idle/error child (>30s)、no child after 10min、child 被物理删除。需要新加的 `@EnableScheduling` 在 `SkillForgeApplication`。走 `SubAgentRegistry.notifyParentOfOrphanRun` 统一入口，不复刻 drain 逻辑。新增 `SubAgentRunRepository.findByStatus(String)`。
3. ~~**`/children` 端点 UI 没用到**~~ → **`ChildSessionsPanel.tsx`**：Chat 页面在 `SubAgentRunsPanel` 下面挂了一个新面板，消费 `GET /api/chat/sessions/{id}/children`。展示 title / status tag / depth / 消息数 / agent 名 / "Open child" 按钮，3s 轮询与旧面板一致。意外收益：`SubAgentRunsPanel` 与 `ChildSessionsPanel` 是互补关系 —— 前者读 `t_subagent_run`、后者读 `SessionEntity`，两者 desync（如 in-flight 丢 run 但 child session 还在）时互相兜底。
4. ~~**端点没做 userId scoping**~~ → **`ChatController.requireOwnedSession`** 私有助手：所有 7 个 session-scoped endpoint 都加上 userId 校验。missing userId → 400，mismatch → 403，not found → 404。前端 `api/index.ts` 的 5 个函数签名加 userId 参数，Chat.tsx 调用点全部传 `1`。
5. ~~**LLM read timeout 硬编码 120s**~~ → **`ModelConfig` + `LlmProperties.ProviderConfig`** 现在接受 `readTimeoutSeconds`（默认 60）和 `maxRetries`（默认 1）。`OpenAiProvider` / `ClaudeProvider` 的 `chat()`（非流式）会在 `SocketTimeoutException` 上重试到 `maxRetries` 次；`chatStream()` 是单次尝试（mid-stream retry 会重复投递 delta）。`application.yml` 中 bailian provider 下给出了参考配置注释。

### 📋 仍未做

1. **多实例部署的并发锁**：单 JVM stripe lock 不够，需要换 DB 行锁或外部锁服务。单机 MVP 够用。
2. **启动恢复的 resume 提示词**："[Resume from restart] Continue your previous work." 是个粗糙的唤醒 prompt，child agent 未必能从上下文接着干。更好的做法是存储 snapshot（工具调用栈 / 待决 plan）并在恢复时注入，但需要先有 auto-compact 的元数据基础。

## UX 优化 Backlog（2026-04-08 对齐）

用户确认的下一束体感高价值项，这里统一跟进：

1. **Loop 中断 / 取消按钮** —— 目前用户没法停止跑飞的 loop，只能等 `session_status=error` 或杀 server。需要 `LoopContext` 加 `AtomicBoolean cancelRequested`、iteration 之间检查、新增 `POST /api/chat/{id}/cancel` 端点、前端 running 时显示取消按钮。
2. **流式文本增量推送 (per-token streaming)** —— 现在是整条 message 推一次，长回复时前端盯着空白等。LLM provider 的 `chatStream` 已经支持 SSE，需要把 text delta 通过 `ChatEventBroadcaster` 透传到 WS。first pass 只做 assistant text delta，tool_use input 的流式推后续再叠。
3. **Session 列表实时刷新** —— 进入列表页要手动刷新才能看到状态。需要 `ChatEventBroadcaster` 加一个 per-user 广播通道，`SessionList.tsx` 订阅并更新 runtimeStatus 徽章。
4. **Auto-compact 上下文压缩** —— 已有 150+ message 的长 session 接近模型 context 上限。建议混合触发：message 数 > N 或 token > 模型 context 60%，取早者。压缩时要小心 `tool_use` ↔ `tool_result` 成对出现，不能把其中一半塞进 summary。参考 Anthropic prompt caching + summarization 模式。

**冲突分析**（并行/串行调度提示）：①②④ 都会改 `AgentLoopEngine`；其中 ①② 在同一 agent 做比较安全，④ 必须等前两个 commit 落地后串行做（否则自我冲突）。③ 完全独立可以随时并行。
