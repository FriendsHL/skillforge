---
id: CTX-1
mode: full
status: done
priority: P1
risk: Full
created: 2026-04-30
updated: 2026-04-30
delivered: 2026-04-30
---

# CTX-1 Token 计量语义分离 + Preflight 估算 + 超窗 compact/retry 优化

## 摘要

把"累计 token 消耗"和"当前 context pressure（即将被送入 LLM 的窗口占用）"在数据模型 / observability / 决策路径上正式分开；补全 preflight 估算的覆盖面（system prompt / messages / tools schema / prompt template 四块），并基于更准的估算优化"窗口将爆"时的 compact 触发与 retry 行为。

## 当前状态

**done,2026-04-30 交付**。Mid Pipeline 走完:Backend Dev 实现 → 2 Reviewer 对抗 1 轮(0 blocker)→ Judge NEEDS_FIX 一次 fix(3 项必修)→ 主会话目检 + mvn test 通过 → 归档。详见 [delivery-index.md 2026-04-30 行](../../../delivery-index.md)。

scope 经过两轮收敛大幅缩水:
- 第一版(初稿):基于不完整勘查设计了 6 个功能(F1-F6),包含新增 SessionEntity 字段、Flyway migration、dashboard UI 等
- 第二版(2026-04-30 重写):用户指出 dashboard 已展示 token 花费,二次勘查发现 `SessionEntity.totalInputTokens` / `totalOutputTokens` 已存在,`ContextBreakdownService` 完整估算算法已存在 → 大量功能砍掉,只剩 3 个核心改动

最终 scope:接线 + 配置化 + 单点 retry,**纯代码改动无 schema**。

**Pipeline 档位**:用户授权 Mid 降档(原本红灯触发 Full,因改动小、不动 schema、不动并发路径,实质风险低)。详见 [tech-design.md Pipeline 档位说明](tech-design.md#pipeline-档位说明)。

## 问题域（三块）

### 1. Token 语义分裂：累计 budget vs 当前 context pressure

两个概念在工程上是不同的物理量:

| 概念 | 含义 | 用途 |
|---|---|---|
| **累计 token budget** | 一个 session 自开启以来总共消耗了多少 token(可能包含已被 compact 掉的历史) | Cost dashboard、quota、计费、用户视角的"花了多少" |
| **当前 context pressure** | 下一次 LLM 请求会送出去多少 token(system + 当前消息列表 + tools + prompt 模板)| 决定要不要 compact、要不要 retry、是否接近窗口上限 |

两者关系：累计 budget 单调递增；context pressure 在 compact 后会下降。**Compact 决策必须看 pressure,不能看 cumulative**。

**现状**(详见 [现状勘查](#现状勘查)):
- 累计侧:`AgentLoopEngine.LoopContext.totalInputTokens` 累加自 LLM response 的 `usage.input_tokens`(精确值,但只覆盖**单次 loop 内**),且没有持久化到 session 级别。`SessionEntity` 只有 `totalTokensReclaimed`(compact 回收量),**没有** cumulative input/output 字段。
- Pressure 侧:`AgentLoopEngine` 用 `TokenEstimator.estimate(messages) / contextWindowTokens` 算 ratio,**只估 messages 内容**,没含 system prompt / tools schema / output reservation。
- 两者目前**不混用**(因为 pressure 是估算,cumulative 是 LlmResponse 实测),但 dashboard 端要不要分别展示、累计是否要落库到 session 级别是开放问题。

### 2. Preflight 估算覆盖不全

调用 LLM 前需要预估请求大小:

```
estimated_request_tokens
  = tokens(system_prompt)             # ① 现状缺失
  + tokens(messages_array)             # ② 已有 (TokenEstimator.estimate)
  + tokens(tools_schema_json)          # ③ 现状缺失
  + max_tokens (output reservation)    # ④ 现状缺失 (request.maxTokens)
  + tokens(prompt_template_overhead)   # ⑤ 现状缺失 (各 provider chat template / system frame)
```

四块缺一,估算偏低,**当前的 0.60 / 0.80 / 0.85 三档触发阈值实际有效水位被推高**(因为 ratio 分子只算 ② 一块,真实的 input token 还要加 ①③⑤,output 上限 ④ 要从 window 减掉)。后果:估算说"还在 60% 以下不用 compact",实际 LLM 调用时已经超窗,撞 413 / context_length_exceeded。

### 3. 超窗 compact / retry 优化

**现状**(详见 [现状勘查](#现状勘查) "Compact 触发架构"):

- 预防式 compact 已经存在三档(B1 light>0.60, B2 full>0.80 必须 B1 已跑, preemptive full>0.85), 但分子估算缺失(见上节), 实际有效阈值偏宽
- 超窗 retry 路径**不存在** —— LlmProvider.chat 只在 `SocketTimeoutException` 重试; chatStream **完全不重试**(known footgun #3); context_length_exceeded 错误目前似乎是直接抛给上游
- Provider 差异: Claude 200k / DeepSeek / Ollama / vLLM 窗口在 `ModelConfig.lookupKnownContextWindow` + `LlmProperties.providers[].contextWindowTokens` 已参数化

需要做的是:
- 把估算分子补齐(①③④⑤),让阈值有真实意义
- 决定要不要加"撞窗后自动 compact + retry"路径,还是保持现状让用户感知错误
- 分子修正后可能要重校准三档阈值,因为实际 ratio 会比现在大 5-15%(取决于 system prompt 和 tools schema 大小)

## 待决策点（讨论用）

1. **数据模型**：`SessionEntity` 加 `cumulative_input_tokens` / `cumulative_output_tokens` 字段，vs 单开 `t_session_token_metrics` 表（按 turn 累计）。前者简单但失去 per-turn 粒度;后者干净但要写聚合查询。
2. **Pressure 是落库还是即时算**：每次 preflight 算出的 pressure 是只用于决策（不持久化）,还是写到 `t_session_messages` 行上做 observability 数据源？
3. **估算 vs 真实计数**：用 provider 返回的 `usage` 字段（精确，但只在请求后才有）还是用 tokenizer 库做估算（preflight 必需，但有偏差）？多数情况下两者并存,但谁是 source of truth？
4. **Tools schema token 怎么估**：各 provider tools 字段格式不同（Claude `input_schema` JSON / OpenAI `parameters` JSON / Ollama 结构略异）,要不要统一抽个 `TokenEstimator` 接口让 provider 自己实现？
5. **预防式 compact 阈值**：高水位定多少（70% / 80% / 90%）？是写死还是 per-provider 配置？
6. **Retry 策略**：超窗后是 (a) 自动按 pressure 计算 compact 量,一次性压到 60%,然后 retry；还是 (b) 走 P9-4/P9-5 的 partial compact 路径？(c) 直接报错让用户处理？
7. **OBS-1 的 trace 是否承载 pressure 历史**：OBS-1 已交付 session trace,把每次 preflight 的 pressure 数据塞进 trace 是顺手 vs 加耦合。
8. **是否同 wave 解 P9-4/P9-5 阻塞**：P9-4/P9-5 卡在"compact 后最近文件数据源"上,如果 CTX-1 把 token metrics 落库 per-turn,那"最近 N turn 的文件引用"也能从同一张表查 → 顺势解决。但绑定就意味着 CTX-1 范围扩大。

## 风险点

- **核心文件触碰**: `CompactionService`（核心清单 + 3-phase split + stripe lock，known footgun 区域）、`AgentLoopEngine`（核心清单）、各 `LlmProvider` 实现（核心清单 + SSE streaming，known footgun #3 不重试）。
- **不变量**: tool_use ↔ tool_result 配对在 compact 时已经是 footgun,加预防式 compact 可能在 streaming 中途被触发,要小心 lock 顺序。
- **多 provider 差异**: Claude / OpenAI / DeepSeek / Ollama / vLLM 计费规则各异,统一 estimator 难做精,但每家单独实现又破坏抽象。
- **估算偏差容忍度**: 估算保守上估 → 误杀（提早 compact 浪费窗口）；估算激进 → 漏放（撞窗 retry 浪费时间和 token）。需要 SLO。
- **新增 schema**: 如果走方案 1 + 2 的扩字段或新表,触发红灯。

## 现状勘查

> 2026-04-30 完成。事实陈述,不含设计决策。

### 已有组件

- **`TokenEstimator`** at `skillforge-core/src/main/java/com/skillforge/core/compact/TokenEstimator.java`
  - jtokkit cl100k_base 单例,WeakHashMap identity-based per-Message 缓存
  - `estimate(List<Message>)`: 每条 +4 PER_MESSAGE_OVERHEAD + content tokens
  - `estimateString(String)`: ordinary 编码(允许特殊 token 字符串)
  - 处理 `text` / `tool_use`(name + input.toString) / `tool_result`(content) ContentBlock
  - **不覆盖** system prompt、tools schema JSON、各 provider chat template overhead

- **`CompactionService`** at `skillforge-server/src/main/java/com/skillforge/server/service/CompactionService.java` (注:**不在** `core/context/`,详见 [发现的 doc 不一致](#发现的-doc-不一致))
  - Light: 单阶段 stripe lock + tx
  - Full: 三阶段(Phase 1 guard 持锁 → Phase 2 LLM call 不持锁 → Phase 3 持久化持锁+tx)
  - Phase 1.5: session memory compact 零 LLM 路径(P9-6)
  - Idempotency guard: gap < 5 messages skip(user-manual 和 agent-tool light bypass)
  - `resolveContextWindowForSession`: 三档优先级(YAML > ModelConfig.lookupKnownContextWindow > 32000 默认)

- **三档触发**(`AgentLoopEngine.java`):
  | 档 | 阈值 | 动作 | 来源 label |
  |---|---|---|---|
  | B1 engine-soft | `ratio > 0.60` OR waste detected | compactLight | `engine-soft` |
  | B2 engine-hard | B1 已跑 AND `ratio > 0.80` | compactFull | `engine-hard` |
  | Preemptive | LLM call 前 `ratio > 0.85` | compactFull | `engine-preemptive` |

  ratio = `TokenEstimator.estimate(messages) / contextWindowTokens`,**分子只含 messages**

- **每次 LLM response 抓 `usage.input_tokens` / `output_tokens`**(`ClaudeProvider` / `OpenAiProvider`),累加到:
  - `LoopContext.totalInputTokens`(单 loop 内,不持久化到 session;`> maxInputTokens * 0.8` 触发 prompt suffix 提示)
  - `TraceSpan` / `TraceSpanEntity` 的 `inputTokens` / `outputTokens`(per LLM call,OBS-1 交付)
  - `ModelUsageEntity`(dashboard 数据源,`DashboardController.getUsageByModel/Agent`)

- **Compact 落库字段**:
  - `SessionEntity.totalTokensReclaimed`、`lightCompactCount`、`fullCompactCount`、`lastCompactedAtMessageCount`、`lastCompactedAt`
  - `CompactionEventEntity.beforeTokens` / `afterTokens` / `tokensReclaimed` / `before/afterMessageCount` per event

### 当前缺口

1. **Preflight 估算只覆盖 messages**:system prompt(`request.systemPrompt`)、tools schema(`request.tools`)、output reservation(`request.maxTokens`)、provider template overhead 都没扣
2. **Session 级累计 input/output tokens 没落库**:LoopContext 只是单 loop 累计,跨 loop / 跨 session restart 都丢
3. **没有"撞窗后 compact + retry"路径**:`chatStream` 不重试(footgun #3),context_length_exceeded 直接传播
4. **`tools` schema 估算没有抽象**:各 provider tools 字段格式不同(Claude `input_schema` / OpenAI `parameters`),要做 preflight 就要决定统一接口还是 per-provider 实现

### 发现并修复的 doc 不一致(2026-04-30)

原 `.claude/rules/pipeline.md` 和 `.codex/rules/pipeline.md` 的核心文件清单把 `CompactionService.java` 写成 `skillforge-core/src/main/java/com/skillforge/core/context/CompactionService.java`,但实际:

- `core/context/` 目录下只有 `BehaviorRule*` / `ContextProvider` / `SystemPromptBuilder` 四个文件,**没有 CompactionService**
- compact 子系统是**两层**:
  - 算法层 `skillforge-core/src/main/java/com/skillforge/core/compact/*`(9 个文件):`ContextCompactorCallback` 接口、Light/Full/SessionMemory 三种策略、`TokenEstimator`、`CompactableToolRegistry` 等
  - 编排层 `skillforge-server/src/main/java/com/skillforge/server/service/CompactionService.java`(单文件):实现回调接口,负责 stripe lock + 3-phase split + idempotency + DB 持久化

**已修复**:两份 pipeline.md(`.claude/rules/` + `.codex/rules/`)都拆成两条核心文件项。CLAUDE.md 没有此路径(原以为有,核实后是误记)。

## 链接

| 文档 | 链接 |
|---|---|
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | 见 [delivery-index 2026-04-30 行](../../../delivery-index.md) |
| 关联需求 | [P9-4/P9-5 compaction recovery](../../active/P9-4-P9-5-compaction-recovery/index.md)(独立做,不合并)<br>[OBS-1 session trace](../2026-04-29-OBS-1-session-trace/index.md) (已交付,TraceSpan 加估算字段是 P2 follow-up) |
| 关联规则 | [pipeline.md 核心文件清单](../../../../.claude/rules/pipeline.md)(CompactionService / LlmProvider / AgentLoopEngine 都在)|
