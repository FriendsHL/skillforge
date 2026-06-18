# MRD — ACP-EXTERNAL-AGENT

## 用户原始诉求（逐步细化）

1. 「cc 和 codex 是不是能通过 ACP 接入？」
2. 「接入后能不能让它们上报（OTel）到 SkillForge，实现 SkillForge 自身为 main agent 操作 cc/codex？」
3. 「SkillForge 经 ACP 操作 cc/codex，让它们执行过程上报到 SkillForge session，实现能看到执行过程。」
4. 「但我怎么知道 cc 在做什么？比如 cc 调了几个 subagent？」
5. 「我们现在是仿 OTel 协议，看起来还是应该按 OTel 协议搞下了。」

→ 真实意图：**SkillForge 当指挥官**，把本地 cc/codex 当可被驱动 + 全程可视的执行单元；可视要深到「调了几个子 agent、各自干了啥」；为此观测层应转**真 OTel**。

## 关键概念澄清（讨论中拨正）

- **ACP = 控制面（双向，操作）**；**OTel = 观测面（单向，遥测）**。不能互替：OTel 看不了也发不了指令。
- 「上报到 session 看执行过程」由 **ACP `session/update`** 完成（顶层流），**不是** OTel。
- 但**子 agent 内部**顶层 ACP 流看不到（子 agent fresh context）→ **这才需要 OTel**（深度嵌套树）。所以最终是 **ACP + OTel 两层叠加**。

## 现状核实（联网，2026-06）

### ACP
- 开放标准（Zed 发起），JSON-RPC over stdio，编辑器/宿主无关（Neovim/JetBrains/VS Code/任意 client）。
- cc：`@zed-industries/claude-code-acp`（npm，Apache，包 Claude Agent SDK）。
- codex：`zed-industries/codex-acp`（官方 adapter）+ `cola-io/codex-acp`（社区，Rust，更全：initialize/authenticate/session.*/setModel，流 assistant/reasoning/token/tool_call）。**codex 原生 ACP 未有**（OpenAI issue #9085 在求）。
- 另有 AI SDK 的 ACP provider（把 ACP agent 当 LanguageModel）。

### OTel（cc 原生）
- cc CLI 内置 OTel：每 model 请求 + 工具执行打 span；metrics（token/成本/工具/权限决策）；logs/events；OTLP 导出（gRPC 4317 / HTTP 4318）到任意 OTLP 后端（Langfuse/自建 collector）。`CLAUDE_CODE_ENABLE_TELEMETRY=1` 开，opt-in。
- **W3C trace context 传播**：SDK 把 TRACEPARENT/TRACESTATE 注入 CLI 子进程 → cc 的 `claude_code.interaction` span 成为调用方 span 的子节点；并下传给 Bash/工具。
- **子 agent span 属性**：`gen_ai.turn.is_subagent` / `gen_ai.turn.parent_tool_use_id` / `gen_ai.turn.subagent_type` + token；子 agent 是 fresh context（父只传 prompt）。
- **hooks**：SubagentStart/Stop、Pre/PostToolUse 等生命周期事件（hook 触发 ≠ 自动成 span，转 span 需自己做）。
- caveat：tracing **beta**；默认只记结构不记内容（prompt/输出要显式开）。

### SkillForge 现状（代码核实）
- `skillforge-observability` 模块：`LlmTraceEntity`/`LlmSpanEntity`（span_id/trace_id/parent_span_id + provider/model/iteration/input_tokens/...）+ `TraceCollector`/`TraceSpan`（core）。
- **pom 无任何 OpenTelemetry SDK/OTLP 依赖** → 自定义「仿 OTel」、LLM 专用，无 OTLP wire、无通用 span。
- 有 Langfuse 式 trace 视图（TracesController / TraceTreeService）。

## 为什么值得做

- SkillForge 从「又一个 LLM 前端」升级为**真正的多 coding-agent 编排器 + 统一可观测平面**。
- 用户用例：微信/网页里指挥 SkillForge → SkillForge 派 cc/codex 跑本地代码任务 → 全程看它们（含子 agent）干活 → 结果回投。
- 转真 OTel 顺带让 SkillForge 成为标准 OTLP 后端，接任何 OTel 工具，不只 cc/codex。

## 未解决问题（留 tech-design / Plan）

- Track B 真 OTel 迁移：替换 vs 双写 vs 适配现有 LlmSpan？范围多大？
- cc/codex adapter 依赖（Node/Rust 子进程）在 SkillForge 部署形态下怎么管？
- 权限策略：cc 工具调用是逐个弹确认，还是按策略自动批 + 仅高危弹？
- 子 session 渲染：cc 跑成独立子 session，还是内联进父 session？
