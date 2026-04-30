# P9-2 技术方案

---
id: P9-2
status: design-draft
prd: ./prd.md
risk: Full
created: 2026-04-28
updated: 2026-04-30
---

## TL;DR

新增 tool-result archive 表和 context replacement 路径，对单轮超大 tool result 做归档；同时新增 request-time tool_result budgeter，在每次 provider 请求前对消息副本做聚合裁剪。BUG-32 的 `Token budget exceeded` 与 `max_tokens` 截断恢复问题纳入同一 Full Pipeline，避免归档落地后请求层仍能被多条工具结果撑爆。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| 按单条消息聚合预算归档 | 痛点通常是某一轮 tool output 过大，而不是整个 session 总量。 | session-wide archive 不够精准。 |
| 2KB preview + archive ID | 保留可读上下文，同时显著减小 token 压力。 | 直接删除会丢失可追溯性。 |
| 增加 request-time 聚合裁剪 | BUG-32 证明多条 40K 以下 tool_result 仍会在单次 request 聚合成巨大输入；持久化归档不覆盖所有历史和重试路径。 | 只做持久化归档会留下旧消息、compact young-gen、runtime 消息副本的缺口。 |
| 默认取消累计 input token 硬停 | `totalInputTokens` 是整轮消耗统计，不等同于 provider context window；用它终止长任务会产生错误的“best answer”。 | 继续扩大 `max_input_tokens` 只是延后同类失败。 |
| `max_tokens` 采用 continuation 恢复 | 截断输出已经通过 stream 广播，重新发送同一个 request 会重复输出；续写语义能保留已输出内容并继续完成答案。 | fall through 会误判成功；重试同一个 request 会导致 UI 重复。 |

## 现场复核

- 用户新给的 `019dddb4-7951-7bd3-b7cd-f87eabf74245` 未在当前仓库文件、PostgreSQL `t_session` / `t_trace_span` / `t_llm_trace` / `t_llm_span`、旧 H2 `data/skillforge.mv.db` 中命中；后续如果它来自另一套环境，需要补导出 trace 或 raw payload。
- 本地 PostgreSQL 中可复现同类现场的 session 是 `919c2bda-1867-40eb-8c7c-4bb49d341ee2`：`t_session.total_input_tokens=910390`，`message_count=135`。
- 首个 trace `574ad812-bd5d-44da-beea-cfb4c0ae3cbb` 有 15 次 LLM 调用，累计 input 371,433 tokens；iteration 13 连续两次 `finish_reason=max_tokens`。
- 对应 raw request `94298a05-...-request.json` 与 `e2f45b7d-...-request.json` 完全同形：request body 126,243 bytes、44 条 messages、29 条 role=`tool` messages、tool content 合计 81,690 chars、单条最大 40,037 chars、`max_tokens=4096`。这证明当前 `max_tokens` 恢复是在重发同一请求，不是 continuation。
- 另一个 trace `e17262bf-6fc5-4944-8d29-f5c7ac5603d6` 的 `eba4ca6e-...-request.json` 更明显：request body 166,785 bytes、56 条 messages、33 条 role=`tool` messages、tool content 合计 99,372 chars、单条最大 40,037 chars。
- 因此根因不是单条工具输出完全未截断，而是只有 per-result 40K cap，没有 request-level aggregate budget；同时累计 input token 硬停和 `max_tokens` fall-through 把长任务变成错误结束。

## 本地参考结论

- **Claude Code 源码（本地笔记 `claude code 源码/06 上下文压缩流水线.md`）**：5 级流水线 `applyToolResultBudget` → `snipCompactIfNeeded` → `microcompact` → `contextCollapse` → `autoCompactIfNeeded` + `:1060-1200` 反应式 413 恢复。**关键借鉴点**：
  - **归档幂等不翻转**：`partitionByPriorDecision` 把候选 tool_result 分成三状态 —— `mustReapply`（历史已替换，必须沿用占位符）/ `frozen`（历史见过但未替换，冻结为原状）/ `fresh`（首次出现，可选择替换）。同一 `tool_use_id` 在整个会话里要么永远是原文，要么永远是占位符，**绝不翻转**，否则破坏 prompt cache。
  - **`hasAttemptedReactiveCompact` 一次性防护**（`query.ts:1104, 1157`）：每次成功反应式压缩后置为 `true`，下一次再 413 不再触发，避免 "压缩 → 超限 → 再压缩" 死循环。本设计的 `max_tokens` 恢复 1 次性防护是这条机制的等价物。
  - **基于 context window 决策**：`autoCompact` 阈值 = `contextWindow - MAX_OUTPUT_TOKENS_FOR_SUMMARY(20K) - AUTOCOMPACT_BUFFER_TOKENS(13K)`；**不存在跨调用累计闸门**。
- **OpenClaw / pi-mono（本地笔记 `OpenClaw源码研究/04-Agent引擎深度解析.md` + `11-pi-mono源码深度解析.md`）**：决策树明确 "context overflow → 自动 compact（不 retry）" / "rate_limit / 5xx → 指数退避" / "max_tokens → stop_reason=length 进入 failover"；**同样无累计 token kill switch**。
- `claude-code` changelog 显示大 bash / tool output 改为持久化到磁盘并通过引用读取，且另有 30K chars + file path reference 的背景任务溢出修复。可借鉴点：大输出不应只截断丢弃，应保留可追溯引用。
- `agentscope-java` 的 `AutoContextMemory` 先压缩历史 tool invocation，再 offload 大消息，并用 `ContextOffloadTool.context_reload` 按 UUID 回填。可借鉴点：working memory 与 original/offload 分离 —— 但本任务首版不交付模型可见的 retrieve skill（与 Claude Code 一致，详见 PRD 非目标）。
- `deer-flow` 的 summarization 文档强调达到 token 阈值前触发、保留最近消息、AI/Tool message pair 不拆分。可借鉴点：request-time budgeter 必须保护 tool_use/tool_result 成对协议，不能为了裁剪破坏 provider replay。

## 架构

预计包含两层：

1. 持久化归档层：`ToolResultArchiveEntity`、Repository、Service helper，以及 `SessionService` context-building 集成点。**归档决策幂等不翻转** —— 同一 `(session_id, tool_use_id)` 在整个 session 生命周期内决策只发生一次：要么从未归档（始终原文），要么已归档（始终 preview）。`t_tool_result_archive` 表本身就是这条决策的 source of truth：表里有记录 = 已归档，没有 = 未归档；归档 service 入口必须先做 lookup（参考 Claude Code `partitionByPriorDecision` 的 `mustReapply` / `frozen` / `fresh` 三状态）。
2. 请求预算层：core 内新增纯函数 budgeter，接收即将发送给 provider 的 `List<Message>`，返回裁剪后的深拷贝、裁剪统计和 trace metadata。**request-time 裁剪是 ephemeral 的**，不写 archive 表、不影响幂等不变量；同一 tool_use_id 下次 request 仍可能保留原文（取决于当时的聚合预算压力）。两类 preview 文本格式不同（见 [数据模型](#数据模型--migration)），便于运维和 reviewer 区分。

持久化归档负责长期缩小 session history（一次性决策）；request-time budgeter 负责最后一道防线（每 request 重算），覆盖旧数据、compact 后 young-gen、并行工具调用聚合和 provider retry 请求。

## 后端改动

- 新增归档持久化。
- 识别超预算的 tool_result blocks。
- **归档前必查 `t_tool_result_archive`**：若 `(session_id, tool_use_id)` 已存在记录，沿用原 archive 决策（mustReapply 等价），不允许"已归档的某条 tool_result 被恢复成原文"或"未归档的某条 tool_result 在后续 turn 被新归档"。schema 层用 `UNIQUE (session_id, tool_use_id)` 约束兜底。
- 生成 preview/reference replacement。
- 确保 context builder 能理解 archive reference。
- 在 `AgentLoopEngine` 构造 `LlmRequest` 后、估算 / preemptive compact / provider call 前，调用 request-time budgeter 生成 provider 专用消息副本。
- preemptive compact 的 `RequestTokenEstimator.estimate(...)` 必须使用裁剪后的 request messages，避免估算和真实请求不一致。
- provider call、observability request blob、LLM_CALL trace input summary 必须使用同一份裁剪后的 request messages。
- 原始 `messages` 继续用于 loop 状态、tool execution、broadcast、session persistence 和 `LoopResult`。
- `max_tokens` 恢复固定采用 continuation 语义：
  - 已经 streamed 的 assistant text 保留为当前可见输出。
  - 构造 request-only continuation messages：在裁剪后的 request messages 后追加一条 assistant partial text，再追加一条内部 user 指令 `Continue exactly from the previous assistant response. Do not repeat text already written.`
  - continuation request 默认不带 tools，避免模型在补全文本时开启新的 tool_use 分支。
  - continuation 前如需 compact，compact 成功后必须 `request.setMessages(...)`，否则会继续发送 compact 前的消息。
- **`max_tokens` 恢复在同一 turn 内最多触发 1 次**：`AgentLoopEngine` 的 turn 状态需新增 `hasAttemptedMaxTokensRecovery` 等价标志（参考 Claude Code `query.ts:1104, 1157` 的 `hasAttemptedReactiveCompact`）；首次恢复后置 `true`，第二次再触发 `max_tokens` 直接走 `max_tokens_exhausted` 失败路径，不再尝试续写或 compact，避免 "压缩 → 续写 → 再超 → 再压缩" 死循环。
- `max_tokens` 恢复耗尽时返回明确状态，例如 `max_tokens_exhausted`，并在 root trace 标记失败；不得 fall through 到普通 assistant response。
- 默认移除 `max_input_tokens=500000` 硬停，或改为只在用户显式配置 `enforce_max_input_tokens=true` 时生效。

## 前端改动

- MVP 不要求前端改动；如果现有消息展示需要更清楚展示 archive reference，再补 UI。

## 数据模型 / Migration

- 新增 `t_tool_result_archive`，包含 session/message 关联、content、preview、size、timestamps。

建议字段：

| 字段 | 说明 |
| --- | --- |
| `id` | bigint primary key |
| `archive_id` | 对模型和 API 暴露的稳定 UUID |
| `session_id` | session id |
| `session_message_id` | 对应 `t_session_messages.id` |
| `tool_use_id` | 原 tool_result 的 tool_use_id |
| `tool_name` | 可从相邻 assistant tool_use 反查，无法反查时为空 |
| `original_chars` | 原始正文字符数 |
| `preview` | 2KB preview |
| `content` | 原始正文，TEXT |
| `created_at` | Instant |

约束：

- `UNIQUE (session_id, tool_use_id)` —— DB 层兜底"同一 tool_use_id 在 session 内归档决策只发生一次"的不变量；归档 service 必须用此 key 做 idempotent upsert（先 lookup → 存在则沿用 → 不存在再 insert），避免并发场景下两次归档同一 tool_use_id。
- `UNIQUE (archive_id)` —— `archive_id` 是对外可见 UUID。

消息替换文本格式固定为：

```text
[Tool result archived]
archive_id: <uuid>
tool_use_id: <id>
original_chars: <n>
preview:
<first 2048 chars>
```

request-time 裁剪文本格式固定为：

```text
[Tool result trimmed for request]
tool_use_id: <id>
original_chars: <n>
retained_chars: <m>
reason: request_tool_result_budget
preview:
<head/tail preview>
```

## 旧 ToDo 原文要点

| 子任务 | 说明 |
| --- | --- |
| P9-2 Per-message 聚合预算 + 归档持久化 | 单条 user message 的 tool_result 总量超 200K chars 时，按大小降序归档到 `t_tool_result_archive` 表，消息替换为 2KB preview + 引用 ID。`ToolResultRetrieveSkill`（让模型按 archive ID 取回原文）已在 PRD 非目标中显式排除（2026-04-30 决议），首版不交付，与 Claude Code 设计一致。 |

## 错误处理 / 安全

- archive 写入失败应显式失败，不能静默丢 tool 内容。
- 不向客户端泄露内部路径或原始异常。

## 实施计划

- [ ] Full Pipeline plan review。
- [ ] 新增 core `ToolResultRequestBudgeter` 及单元测试。
- [ ] 在 `AgentLoopEngine` 接入 request-time budgeter，并修 `max_input_tokens` 与 `max_tokens` 语义。
- [ ] 新增 schema/entity/repository。
- [ ] 新增 archive service。
- [ ] 接入 `SessionService` context message preparation。
- [ ] 补 observability metadata 和回归测试。

## 测试计划

- [ ] 阈值归档测试。
- [ ] preview replacement 测试。
- [ ] session reload 测试。
- [ ] compaction interaction 测试。
- [ ] 多条 tool_result 聚合超过 request cap 时，发送给 provider 的消息副本被裁剪，原始 messages 不变。
- [ ] request-time 裁剪后仍满足 OpenAI / Claude provider 的 tool_use/tool_result pairing 转换测试。
- [ ] **归档幂等不翻转**：同一 `(session_id, tool_use_id)` 二次进入归档路径时直接沿用首次决策，DB 不产生重复行；并发归档同一 tool_use_id 时由 `UNIQUE` 约束保证只有一条记录。
- [ ] **session resume 后归档决策可重建**：reload session 时归档过的 tool_result 仍呈现为 preview，未归档的仍保留原文，未出现"reload 后某条 tool_result 决策翻转"。
- [ ] **`max_tokens` 单 turn 恢复 1 次**：第一次 `max_tokens` 走 continuation，不重复已 streamed 文本；continuation 前若触发 compact，`request.messages` 同步被替换；**第二次仍 `max_tokens` 直接返回 `max_tokens_exhausted` 失败状态，不再续写**（`hasAttemptedMaxTokensRecovery` 标志生效）。
- [ ] 默认 `totalInputTokens > 500000` 不再生成 `token_budget_exceeded` 最终答案。
- [ ] 基于 session `919c2bda-1867-40eb-8c7c-4bb49d341ee2` 的同形 fixture 覆盖 30+ tool messages 聚合裁剪。

## 风险

- replacement 错误可能破坏 tool_use/tool_result 不变量。
- archive reference 需要跨 provider 稳定序列化。
- request-time budgeter 如果直接 mutate 原始 messages，会破坏 append-only history；必须深拷贝并用测试锁住。
- streaming 已经广播出截断文本后再“重试同一个答案”会造成 UI 重复；实现必须走 continuation 语义，恢复耗尽才返回失败状态。
- 持久化归档和 request-time 裁剪的 preview 格式不同，测试需要分别锁住，避免模型误以为 request-time trimmed 内容可按 archive_id 读取。

## 评审记录

当前 P9-2 收窄任务尚未跑对抗评审。
