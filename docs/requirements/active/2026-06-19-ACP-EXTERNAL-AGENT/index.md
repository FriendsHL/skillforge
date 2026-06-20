# ACP-EXTERNAL-AGENT — SkillForge 经 ACP 编排外部 coding agent（cc / codex）+ 全程可视

> 创建：2026-06-19
> 状态：**方案已固化（2026-06-19）：Track A = ACP runner + Track B = B1 OTel 适配器（cc OTel→翻译进现有 LlmSpan）**；待排期实现（Full + Plan）。**B3 真 OTel 大爆破已评估否决**（~70–100 dev-day + 还得不到纯标准 OTel，见 tech-design），改作长期可选的渐进迁移。
> 来源：用户「SkillForge 作 main agent，经 ACP 操作本地 Claude Code / Codex，并把它们的执行过程（含调了几个子 agent、各自干了啥、要不要确认）显示在 SkillForge session / 可观测里」。多轮讨论 + 联网核实 ACP / OTel 现状（见 mrd 出处）。

## 一句话

让 SkillForge 成为「指挥 + 全程可视」的 orchestrator：经 **ACP**（开放标准）驱动 cc/codex 作外部子 Agent（控制 + 实时顶层执行流 + 用户确认感应），并经 **cc 原生 OTel（经 B1 适配器翻译进现有 trace 模型）** 看到内部嵌套结构（子 agent 计数 + 各自内部 turn/工具/耗时/嵌套树）。**ACP + OTel 两层叠加才完整。**

## 两条轨（分清）

### Track A — ACP client runner（控制面 + 顶层执行流）
SkillForge 做 **ACP client**，cc（`@zed-industries/claude-code-acp`）/ codex（`zed-industries/codex-acp` 或社区 `cola-io/codex-acp`）作 **ACP agent 子进程**（JSON-RPC over stdio）。
- 控制：`session/new` `session/prompt` `session/cancel` `session/setModel` + 权限请求审批。
- 实时执行流：`session/update`（agent_message_chunk / reasoning / tool_call / tool_call_update / plan / token）→ 翻译进 SkillForge **Message/ContentBlock** → 现有 WS 流式渲染。cc 每次派子 agent = 一个 Task `tool_call` → **数 Task 调用即知调了几个子 agent**。
- 落进现有 **SubAgent 子 session 模型**（cc/codex 跑成可独立查看的子 session）+ **ask/confirmation** 做权限桥。

**Track A 能交付**：操作 cc/codex + 实时看顶层执行 + 子 agent 计数 + 最终结果回投原渠道（复用 CHANNEL-ASYNC-DELIVERY）。

### Track B — B1 OTel 适配器（cc OTel → 翻译进现有 trace 模型）【已固化方案】
**问题**：ACP 顶层流**看不到子 agent 内部**（子 agent 是 fresh context，扁平 session 不含其内部步骤）。要看进内部 + 完整嵌套树 → 需 cc 的**真 OTel trace**（gen_ai span + `gen_ai.turn.is_subagent` / `subagent_type` / `parent_tool_use_id`，W3C context 嵌套）。
**固化方案 = B1 适配器**（**不**改 SkillForge 自身写/读路径）：
- 起 **OTLP receiver**（Spring `@RestController /v1/traces`，cc 配 `http/json`）接 cc/codex 原生 OTel；
- **翻译器**把 OTel SpanData → 调用现有 `LlmTraceStore.write/writeToolSpan`（kind 派生：tool.name→tool / request.model→llm / is_subagent→SubAgent；ID 直存，VARCHAR(36) 装得下 OTel 32hex；聚合复用现成 upsert-SUM）；
- SkillForge 经 ACP 启动 cc 时注入 `TRACEPARENT` + `OTEL_RESOURCE_ATTRIBUTES=sf.session_id/sf.agent_id` → cc 整棵 span 树（含子 agent）嵌进 SkillForge 这次 session 的 trace。
- **关键收益**：翻译进现有 LlmSpan 后，**所有读路径 / 前端 / evolve / eval 原样工作，零 blast radius**。成本 **~5–7 dev-day**。

**为什么不做 B3 大爆破**（逐文件实测）：~70–100 dev-day；且 SkillForge 模型一大块（kind 索引列+判别器 / event span / t_llm_trace 聚合 + 生命周期 saga / cache·cost·reasoning / blob / origin）**无 OTel 语义约定** → 付全额成本还只能得到「OTel + 大量 sf.* 扩展 + 自写 exporter」，非纯标准 OTel；且 OTel SDK 活体 start/end 跟 SkillForge 事后构造 span 相冲；自进化读点静默退化风险高、big-bang 无并行验证期。→ **B3 改作长期可选渐进**（双写→按簇迁读→ETL），不阻塞本需求。

**Track B（B1）能交付**：深度可观测（子 agent 内部 waterfall + 准确耗时 + 完整嵌套树）。
**limitation**：cc OTel **默认只记结构不记内容**（tool 名/耗时/嵌套有，tool 输入输出内容需后续显式开 `OTEL_LOG_USER_PROMPTS`——用户已认可后续开）；cc tracing 仍 beta；翻译器是 cc/codex 形状（接别的 OTel 工具要扩）。

## 可观测分层（回答「我怎么知道 cc 在做什么 / 调了几个子 agent」）

| 想看的 | 用哪层 | 备注 |
|---|---|---|
| cc 实时说/做什么 + **子 agent 计数(Task 调用)** + 控制/审批 | **ACP session/update**（Track A） | 够用 |
| **每个子 agent 内部**（嵌套树、各自 turn/工具/token/延迟） | **cc OTel traces**（Track B=B1 适配器翻译进现有 trace，经 TRACEPARENT 挂进 session trace） | ACP 顶层看不到，必须 OTel |
| 子 agent/工具 生命周期细粒度 feed | cc **hooks**（SubagentStart/Stop、Pre/PostToolUse） | 可选补充；hook→span 需自己转 |

## 验收点（= 用户 4 条诉求，标清 ACP / OTel 各负责）

> 用户诉求（2026-06-19 固化）：① main agent 调 cc 时知道 cc 在干什么；② cc 调 subagent 知道调了什么 + 耗时；③ cc 需用户确认时 main agent 能感应；④ 可观测里看到 ACP→cc→subagent→subagent 执行 tool 的完整嵌套树。

| # | 验收 | 靠 | 判定 |
|---|---|---|---|
| AC-1 | 经 ACP 启动 cc 发 prompt，SkillForge session 实时渲染 cc 的文本/reasoning/tool_call | **ACP** session/update | session 里看到 cc 顶层活动流 |
| AC-2a | cc 派 subagent → session 可见 N 个 Task 节点（**计数正确**）+ 派发耗时 | **ACP** tool_call + start/end | 数 Task = 子 agent 数 |
| AC-2b | 每个 subagent **内部** turn/工具 + **准确耗时** | **OTel(B1)** 嵌套 span | trace 视图下钻可见 |
| AC-3 | cc 要用户确认时弹 SkillForge confirmation，批准/拒绝经 ACP 回传 | **ACP** permission request | （前提：cc 跑在 ask 权限模式）|
| AC-4 | 可观测里看到完整树：ACP→cc→cc 用 subagent→subagent 执行 tool（带 is_subagent/subagent_type/耗时/嵌套） | **OTel(B1)** + TRACEPARENT 挂进 session trace | trace 树渲染嵌套层级 |
| AC-5 | cc 跑完最终结果回投原渠道（微信/飞书） | 复用 **CHANNEL-ASYNC-DELIVERY** | 渠道收到 |
| AC-6 | codex 同样经 ACP 跑通 | ACP adapter | codex 路径 |

**注**：AC-3 仅当 cc 经 ACP 跑在「要批准」模式才有确认事件（自动批准模式无）。AC-2b/AC-4 默认**结构级**（工具名/耗时/嵌套有，内容需后续开 `OTEL_LOG_USER_PROMPTS`）。

## 实现进度（增量）

> SELF-ITERATE-VIA-CC 愿景的 F2 地基（cc 当 SubAgent 在真实仓库工作 + 拿到正确任务框架）。

- **F2-prompt（cc 任务说明组装）**（2026-06-20）：ACP `session/prompt` 无独立 system 字段，故 `AcpAgentRunner.buildCcPrompt` 把 session 对应 agent 的 `systemPrompt`（角色/规则框架）折进 prompt 文本 = `<systemPrompt>\n\n---\n\n# Task\n\n<task>`，覆盖 standalone + subagent 两路；缺 prompt 退化裸 task。`V160` 给 `claude-code` agent 一个真 persona（角色 + 遵守仓库规范 + 改完输出完成报告，种下 L2 确认环节习惯）。读 DB persona → 可在 dashboard 改 / codex 用另一行各自 persona。
- **F2-worktree（option A）**（2026-06-20）：`skillforge.acp.repo-root` 配了 → 每次 cc run 在该仓库的一个 `git worktree`（分支 `acp/cc-<sub-session-id>`，base `worktree-base-ref` 默认 HEAD）里跑，cc 改真实代码但隔离到可 review 分支，主工作树不动；默认 `keep-worktree-on-finish=true` 留分支给 review（不自动合）。repo-root 空 → 退回旧 throwaway 空 temp 目录（非破坏 opt-in）。git 经 ProcessBuilder（无 shell）调，branch/baseRef 过 `requireSafeGitValue`（防 argument injection），失败输出只进服务端 log。
- **验证**：单测（组装精确串 + worktree keep/remove 真 git repo）；全模块 `3143/0 fail`；live E2E：cc 真在 `~/.skillforge/acp-worktrees/acp-cc-<sid>` 隔离分支跑，读到真实 CLAUDE.md，报告分支名 + CLAUDE.md 首行，未改文件，分支保留。
- **L1+L3（测试门 + 建 PR，prompt 驱动）**（2026-06-20，`V161`）：不写 SkillForge 编排，靠 claude-code persona 指令让 cc 自己「改完 → 跑 build/test → commit → push 分支 → 绿则 `gh pr create`（带 diff + 测试结果）→ 报告 PR URL」。**验证**：smoke run 证明 cc 在 auto 权限模式、worktree 里能 `git push -u origin HEAD`（分支落 origin）+ 跑 `gh`（exit 0），无需改权限模式。**取舍**：L1 是 cc **自报**绿，v1 的真门是**人审 PR**；SkillForge 侧**独立硬门**推迟（只在 L4 无人回路自动部署时才需要，用户「硬门再说，不想太定制化」）。**待定**：PR base 分支策略（`feat/wechat-channel` 未在 origin → 当前 cc worktree base=HEAD，PR against main 会是巨型 diff；需用户定 base 策略）。
- **范围外（后续 phase）**：蓝绿自部署 supervisor（L4，最难）；渠道续连（L5）；SkillForge 侧独立测试硬门（L4 前提）；自动合并永不默认。

## 风险 / 取舍

- cc/codex 的 ACP 都靠 **adapter（非原生）**：codex 原生 ACP 还是 OpenAI open issue #9085；多 Node/Rust 运行时依赖 + 子进程/auth 管理。但 ACP 是**开放标准**，比逆向协议稳。
- 每 agent **自带 auth/billing**（cc `/login`、codex OpenAI key），按 agent 管。
- cc **tracing 还是 beta**（span 名/属性可能变 → 翻译器维护）；**默认只记结构不记内容**（AC-2b/AC-4 内容需后续显式开 `OTEL_LOG_USER_PROMPTS`——用户已认可后续开 → 敏感数据治理届时一并定）。
- **AC-3 取决于 cc 权限模式**：要感应确认，ACP 启动 cc 须置「要批准」模式；自动批准则无确认事件。
- cc subagent **没有** SkillForge 子 session（cc 内部）→ 可观测里以 span 树呈现，`subagentSessionId` 跳链可指向该子 agent 的 span 子树而非 SkillForge 会话。
- **tool_use ↔ tool_result 配对不变量**（仅 ACP session/update 翻译进 engine messages 路径需守；B1 适配器只写 trace store，不碰 message 持久化对账，规避此坑）。
- ACP 事件形态因 agent/adapter 而异 → 需规范化层（同 SkillForge 已有的多 provider SSE 归一化套路 ProviderProtocolFamily）。
- B1 适配器**零 blast radius**（不动读路径/前端/evolve/eval）；B3 真 OTel 大爆破已否决（见上 Track B），长期渐进版记 `OTEL-NATIVE-TRACING` backlog。

## 阅读顺序

1. 本 index（愿景 + 两轨 + 验收 + 风险）
2. [mrd.md](mrd.md) — 用户诉求 + 三轮讨论 + ACP/OTel 现状核实出处
3. [tech-design.md](tech-design.md) — 两轨设计草案 + 落进 SkillForge 现有积木 + 开放问题

## 关联

- SubAgent 子 session 模型 / Message·ContentBlock / WS 流式渲染 / ask-confirmation / `skillforge-observability` trace
- **CHANNEL-ASYNC-DELIVERY**（已交付）：cc 异步结果回投原渠道直接复用
- backlog **CHANNEL-PUSH-SERVICE**（异步主动推送）潜在交集
