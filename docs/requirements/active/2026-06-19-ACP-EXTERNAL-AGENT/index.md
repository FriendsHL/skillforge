# ACP-EXTERNAL-AGENT — SkillForge 经 ACP 编排外部 coding agent（cc / codex）+ 全程可视

> 创建：2026-06-19
> 状态：**立项 / 草案（未排期）** —— 跨多模块大件，真做要 Full + Plan 对抗
> 来源：用户「SkillForge 作 main agent，经 ACP 操作本地 Claude Code / Codex，并把它们的执行过程（含调了几个子 agent、各自干了啥）显示在 SkillForge session 里」。三轮讨论 + 联网核实 ACP / OTel 现状（见 mrd 出处）。

## 一句话

让 SkillForge 成为「指挥 + 全程可视」的 orchestrator：经 **ACP**（开放标准）驱动 cc/codex 作外部子 Agent，把它们的执行过程实时收进 SkillForge 会话，并经 **真 OTel** 看到内部嵌套结构（子 agent 计数 + 各自内部 turn/工具/token/延迟）。

## 两条轨（分清）

### Track A — ACP client runner（控制面 + 顶层执行流）
SkillForge 做 **ACP client**，cc（`@zed-industries/claude-code-acp`）/ codex（`zed-industries/codex-acp` 或社区 `cola-io/codex-acp`）作 **ACP agent 子进程**（JSON-RPC over stdio）。
- 控制：`session/new` `session/prompt` `session/cancel` `session/setModel` + 权限请求审批。
- 实时执行流：`session/update`（agent_message_chunk / reasoning / tool_call / tool_call_update / plan / token）→ 翻译进 SkillForge **Message/ContentBlock** → 现有 WS 流式渲染。cc 每次派子 agent = 一个 Task `tool_call` → **数 Task 调用即知调了几个子 agent**。
- 落进现有 **SubAgent 子 session 模型**（cc/codex 跑成可独立查看的子 session）+ **ask/confirmation** 做权限桥。

**Track A 能交付**：操作 cc/codex + 实时看顶层执行 + 子 agent 计数 + 最终结果回投原渠道（复用 CHANNEL-ASYNC-DELIVERY）。

### Track B — OTEL-NATIVE-TRACING（观测基座，深度 + 互通）
**现状**：`skillforge-observability` 是「仿 OTel」—— 有 trace_id/span_id/parent_span_id 形状，但 LLM 专用、自定义、**无 OTLP wire、无通用 span、pom 无 OpenTelemetry 依赖**。
**问题**：ACP 顶层流**看不到子 agent 内部**（子 agent 是 fresh context，扁平 session 不含其内部步骤）。要看进内部 → 需 cc 的**真 OTel trace**（gen_ai span + `gen_ai.turn.is_subagent` / `subagent_type` / `parent_tool_use_id`，W3C context 嵌套）。
**方向已拍（2026-06-19）：B3 转真 OTel 协议**（独立成 `OTEL-NATIVE-TRACING` 包，自身分 5 阶段，注意迁 evolve/eval trace 消费方——见 tech-design）。SkillForge 观测层转真 OTel：
- 起 **OTLP receiver**（gRPC 4317 / HTTP 4318）接 cc/codex 的原生 OTel；
- SkillForge 经 ACP 启动 cc 时**注入 TRACEPARENT/TRACESTATE** → cc 整棵 span 树（含子 agent）自动嵌进 SkillForge 这次 session 的 trace → **一棵统一树**；
- SkillForge 自身 instrumentation 也产 OTel（迁移 / 双写 / 适配现有 LlmSpan）。

**Track B 能交付**：深度可观测（子 agent 内部 waterfall）+ 与任何 OTel 工具/标准后端互通；是 Track A「看到 cc 内部在干嘛」的前置基座。

## 可观测分层（回答「我怎么知道 cc 在做什么 / 调了几个子 agent」）

| 想看的 | 用哪层 | 备注 |
|---|---|---|
| cc 实时说/做什么 + **子 agent 计数(Task 调用)** + 控制/审批 | **ACP session/update**（Track A） | 够用 |
| **每个子 agent 内部**（嵌套树、各自 turn/工具/token/延迟） | **cc OTel traces**（Track B，经 TRACEPARENT 挂进 session trace） | ACP 顶层看不到，必须 OTel |
| 子 agent/工具 生命周期细粒度 feed | cc **hooks**（SubagentStart/Stop、Pre/PostToolUse） | 可选补充；hook→span 需自己转 |

## 验收点（草案）

1. SkillForge 经 ACP 启动 cc，发 prompt，收到流式 `session/update` 并在 session 里实时渲染（文本/reasoning/tool_call）。
2. cc 调工具需审批时，弹 SkillForge confirmation；批准/拒绝经 ACP 回传。
3. cc 派子 agent → SkillForge session 可见 N 个 Task 节点（子 agent 计数正确）。
4. cc 跑完，最终结果回投原渠道（微信/飞书，复用异步投递）。
5. （Track B）cc 的 OTel 经 OTLP 进 SkillForge；注入 TRACEPARENT 后 cc 整树（含子 agent span，带 is_subagent/subagent_type）嵌在 SkillForge session trace 下，可在 trace 视图下钻。
6. codex 同样经 ACP 跑通（adapter 路径）。

## 风险 / 取舍

- cc/codex 的 ACP 都靠 **adapter（非原生）**：codex 原生 ACP 还是 OpenAI open issue #9085；多 Node/Rust 运行时依赖 + 子进程/auth 管理。但 ACP 是**开放标准**，比逆向协议稳。
- 每 agent **自带 auth/billing**（cc `/login`、codex OpenAI key），按 agent 管。
- cc **tracing 还是 beta**（span 名/属性可能变）；**默认只记结构不记内容**（要内容显式开 `OTEL_LOG_USER_PROMPTS` → 敏感数据治理）。
- **tool_use ↔ tool_result 配对不变量** + 持久化字节一致：cc 的 tool_call 翻译进 SkillForge 必须凑合法对或走展示专用变体（踩过的核心坑）。
- ACP 事件形态因 agent/adapter 而异 → 需规范化层（同 SkillForge 已有的多 provider SSE 归一化套路 ProviderProtocolFamily）。
- Track B 是 SkillForge 观测子系统的较大迁移 → 可独立成 `OTEL-NATIVE-TRACING` 包；本包先把它作为 Track A 深度观测的前置依赖记清。

## 阅读顺序

1. 本 index（愿景 + 两轨 + 验收 + 风险）
2. [mrd.md](mrd.md) — 用户诉求 + 三轮讨论 + ACP/OTel 现状核实出处
3. [tech-design.md](tech-design.md) — 两轨设计草案 + 落进 SkillForge 现有积木 + 开放问题

## 关联

- SubAgent 子 session 模型 / Message·ContentBlock / WS 流式渲染 / ask-confirmation / `skillforge-observability` trace
- **CHANNEL-ASYNC-DELIVERY**（已交付）：cc 异步结果回投原渠道直接复用
- backlog **CHANNEL-PUSH-SERVICE**（异步主动推送）潜在交集
