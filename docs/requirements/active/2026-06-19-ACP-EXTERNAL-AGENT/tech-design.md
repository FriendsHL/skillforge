# Tech-Design（草案）— ACP-EXTERNAL-AGENT

> 状态：草案（未排期）。真做要 Full + Plan 对抗循环；本文是方向草图，非定稿。

## Track A — ACP client runner

### 组件
1. **AcpClient**（新）：JSON-RPC over stdio 客户端；启动/管理 cc/codex adapter 子进程；`initialize` / `authenticate` / `session/new` / `session/prompt` / `session/cancel` / `session/setModel`；处理 incoming `session/update` 通知 + 权限请求。
2. **AcpAgentRunner**（新）：把一次 cc/codex 任务跑成一个 SkillForge **子 session**（复用 SubAgent 的 parent_session_id / 结果回推模型）。父 agent 经一个工具（如 `RunExternalAgent`）派活。
3. **AcpUpdateTranslator**（新）：`session/update` → SkillForge `Message/ContentBlock`：
   - agent_message_chunk → text block（WS 节流渲染）
   - reasoning/thought → reasoning block（复用 reasoning_content）
   - tool_call / tool_call_update → tool_use + tool_result（**必须凑合法配对**，见不变量）
   - plan → TodoWrite 式展示；token → usage
   - **规范化层**：cc adapter vs codex adapter 字段差异 → 归一（同 ProviderProtocolFamily 套路）
4. **权限桥**：ACP permission request ↔ SkillForge ask/confirmation（pending-confirmation registry）。策略可配：逐个弹 / 自动批 + 高危弹。
5. **结果回投**：子 session 完成 → 复用 **CHANNEL-ASYNC-DELIVERY** 把最终结果投回原渠道。

### 落进现有积木
Message·ContentBlock / WS 流式 + 节流 / SubAgent 子 session / ask-confirmation / 异步投递 —— 大多复用。新写主要是 AcpClient + Translator + 权限桥 + 子进程/auth 管理。

### 子 agent 计数
cc 派子 agent = Task `tool_call` → Translator 产生一个 tool_use(Task) 节点 → session 里数 Task = 子 agent 数。（内部细节看不到，见 Track B。）

## Track B — OTEL-NATIVE-TRACING（观测基座）

### 目标
SkillForge 观测层说**真 OTLP**，以便：① 原生接 cc/codex OTel；② 经 TRACEPARENT 把 cc 整树嵌进 SkillForge session trace；③ 与标准 OTel 生态互通。

### 路径已固化（2026-06-19，用户）：B1 适配器先行，B3 否决/长期渐进

**B1 适配器（采用）= cc OTel → 翻译进现有 LlmSpan，不改 SkillForge 写/读路径。** 详见 index Track B。成本 **~5–7 dev-day**，零 blast radius。
- 映射：cc LLM turn→kind=llm(model/tokens)、tool→kind=tool(name/toolUseId/in·out summary)、子 agent(is_subagent)→kind=tool name=SubAgent；OTel id 直存 VARCHAR(36)；聚合复用现有 upsert-SUM。
- session/agent 绑定：启动 cc 时 `OTEL_RESOURCE_ATTRIBUTES=sf.session_id/sf.agent_id` 带进、ingest 取回；`TRACEPARENT` 注入挂进 session trace 树。
- limitation：内容默认不记（后续开 `OTEL_LOG_USER_PROMPTS`）；beta；翻译器 cc/codex 形状。

**B3 真 OTel 大爆破（已否决）**：逐文件实测 **~70–100 dev-day**；且 SkillForge 模型一大块无 OTel 语义约定（kind 索引列+判别器 / event span / t_llm_trace 聚合+生命周期 saga / cache·cost·reasoning / blob / origin / iterationIndex / subagentSessionId）→ 付全额成本只能得「OTel + 大量 sf.* 扩展 + 自写 exporter」非纯标准 OTel；OTel SDK 活体 start/end vs SkillForge 事后构造 span 相冲；自进化读点静默退化 + big-bang 无并行验证期。
**B3 改作长期可选渐进**（双写→按簇迁读 + 自进化簇单独并行验证→ETL→下线 LlmSpan），独立 `OTEL-NATIVE-TRACING` 包，**不阻塞本需求**。

### 关键机制
- **OTLP receiver**：gRPC 4317 / HTTP 4318（或内嵌 OTel Collector）。
- **TRACEPARENT 注入**：AcpAgentRunner 启动 cc 时设 `CLAUDE_CODE_ENABLE_TELEMETRY=1` + OTLP endpoint 指向 SkillForge + 注入本次 session 的 TRACEPARENT → cc（含子 agent）span 自动嵌入。
- **trace 树视图**：现有 TraceTreeService 扩展读通用 OTel span；子 agent span 按 `is_subagent`/`parent_tool_use_id` 归位。
- **内容治理**：默认结构-only；内容（prompt/输出）opt-in + 受限存储/保留。

## tool_use ↔ tool_result 不变量（必守）
cc 的 tool_call 翻译进 SkillForge 持久化路径，必须满足 tool_use↔tool_result 配对 + 持久化-engine 字节一致不变量（见 persistence-shape-invariant / 核心文件清单）。若不入 engine messages、仅展示 → 用展示专用变体，不碰持久化对账。**Plan 阶段必须显式定这条边界。**

## P1a 进度（实现中）
- **P1a-1 ✅（commit `acbd9db6`）**：纯 ACP 协议层（AcpClient + AcpTransport seam + AcpUpdate sealed + AcpUpdateTranslator 接口 + CcAcpUpdateTranslator）。28 单测 + gated live IT。
- **P1a-2 ✅（AC-1）**：AcpAgentRunner — cc 文本/reasoning 流进可查看的子 session + 持久化(Option A) + 最小触发 endpoint `POST /api/acp/runs`(demo-only,AuthInterceptor token 鉴权)。java + security reviewer PASS 0 blocker。
  - **P1c 前必修硬化项（P1a-2 review 延期,真 RunExternalAgent 工具 / 生产暴露时落地）**：
    - 并发上限 / `/api/acp/**` 限流（security WARN-1：防 loop-call spawn 大量 cc 进程 DoS+成本）
    - `model` 字符串校验（security WARN-2）
    - `workspaceRoot` 启动存在性 guard（security WARN-3）
    - 300s 阻塞请求线程改 async 包装（java W-4）

## 分期建议
- **P1（Track A 最小闭环）**：AcpClient + cc 单 agent + Translator(text/tool_call/reasoning) + 权限桥 + 子 session 渲染 + 结果回投。AC-1/2a/3/5。先不接 OTel 深度。
- **P2（Track B = B1 适配器,观测深度)**：OTLP receiver + 翻译器(OTel SpanData→LlmTraceStore) + TRACEPARENT/RESOURCE_ATTRIBUTES 绑定。AC-2b/4。~5–7d。
- **P3（codex）**：codex adapter 跑通；规范化层覆盖双 adapter 差异。AC-6。
- **P4（可选,后续)**：开 `OTEL_LOG_USER_PROMPTS` 拿内容（AC-2b/4 升级到内容级）+ 敏感数据治理。
- **长期可选**：B3 真 OTel 渐进迁移（`OTEL-NATIVE-TRACING` 包）。

## 开放问题（Plan / 用户决策）
- ~~OQ-1：Track B 走 B1/B2/B3 哪条？~~ **已固化 B1 适配器先行（2026-06-19）**。
- ~~OQ-2：adapter 部署形态？~~ **本机子进程（2026-06-19 用户）**：SkillForge 本地跑 + cc 本地，AcpClient 用 npx/预装拉起子进程。云端打包作后续。
- ~~OQ-3：权限默认策略？~~ **逐个弹确认（2026-06-19 用户）**：ACP 启 cc 置"要批准"模式，每个确认 → SkillForge confirmation，批/拒经 ACP 回传（AC-3 成立）。自动批+高危弹作可配项后续。
- ~~OQ-4：独立子 session vs 内联？~~ **独立子 session（2026-06-19 用户）**：复用 SubAgent 子 session 模型，可单独查看，结果经 CHANNEL-ASYNC-DELIVERY 回投。
- OQ-5：内容捕获默认关，按需开——谁有权开 + 存哪？（P4 再定）

## Spike 验证（2026-06-19，/tmp/acp-spike，真实协议流量）
握手端到端跑通（cc adapter `@zed-industries/claude-code-acp@0.16.2`，已**改名 `@agentclientprotocol/claude-agent-acp`** → P1 用新名）：
- **帧格式**：newline-delimited JSON-RPC 2.0 over stdio（非 LSP Content-Length）。
- **握手**：`initialize{protocolVersion:1, clientCapabilities}` → agentCapabilities(promptCapabilities image/embeddedContext、mcpCapabilities http/sse、loadSession、sessionCapabilities fork/list/resume) + authMethods(claude-login)。
- **`session/new{cwd, mcpServers}`** → `{sessionId, models{availableModels[default=Opus4.6/sonnet/haiku], currentModelId}, modes{currentModeId, availableModes[]}}`。→ **models 接 session/setModel；modes 接 OQ-3 权限模式**。
- **`session/prompt{sessionId, prompt:[{type:text,text}]}`** → 流式 `session/update` notification，`update.sessionUpdate` 判别器实测见：`available_commands_update`（cc 斜杠命令）、`agent_message_chunk{content:{type:"text",text}}`（**文本增量**）；prompt 完成回 result `{stopReason:"end_turn"}`。
- **嵌套守卫**：cc adapter 继承 `CLAUDECODE` 会拒绝"nested session"→ **AcpClient spawn 必须 strip `CLAUDECODE`/`CLAUDE_CODE_ENTRYPOINT`/`CLAUDE_CODE_SSE_PORT`**（生产 JVM 无此变量，但防御性清理）。
- **Translator 已确认映射**：agent_message_chunk→text block。**待 P1 捕获**：`tool_call`/`tool_call_update`（→tool_use/tool_result 配对）、`reasoning`/thought、`plan`、permission request 形状（需带工具的 prompt 触发）。
