# P9 — Tool 输出精细裁剪 设计文档

> 创建于：2026-04-21
> 参考实现：Claude Code v2.1.88 (`claw-cli-claude-code-source-code-v2.1.88/src/`)

---

## 一、SkillForge vs Claude Code — 压缩能力对比分析

### 1.1 SkillForge 当前能力（5 层）

| 层级 | 机制 | 文件 | 触发条件 | 策略 |
|------|------|------|---------|------|
| L1 | `ToolResultTruncator` | `skillforge-core/.../engine/ToolResultTruncator.java` | 单个 tool result > 40K chars | 硬截断 head/tail（80/20 或 70/30 smart tail） |
| L2 | `LightCompactStrategy` | `skillforge-core/.../compact/LightCompactStrategy.java` | ratio > 0.60 或 waste 检测 | 4 条规则：大 result 截断(>5KB 取头尾各 10 行)、去重连续相同调用、折叠 3+ 连续错误重试、去无用旁白(<80 chars) |
| L3 | `FullCompactStrategy` | `skillforge-core/.../compact/FullCompactStrategy.java` | ratio > 0.80 / 0.85 / max_tokens | LLM 摘要旧消息，保留最近 20 条（young-gen） |
| L4 | `ContextCompactTool` | `skillforge-core/.../compact/ContextCompactTool.java` | LLM 自主 tool_use 调用 | 模型自己决定何时压缩（level=light/full） |
| L5 | `TokenEstimator` | `skillforge-core/.../compact/TokenEstimator.java` | 所有压缩路径依赖 | 粗估 ~3.5 chars/token（CJK 1:1） |

**触发链路（AgentLoopEngine 内）：**

```
每次迭代开始
├── B1 engine-soft: ratio > 0.60 || detectWaste() → LightCompactStrategy
├── B2 engine-hard: ratio > 0.80（B1 后仍高） → FullCompactStrategy
├── Preemptive: ratio > 0.85（LLM 调用前） → FullCompactStrategy
└── max_tokens recovery: stop_reason=length 第二次重试 → FullCompactStrategy
```

**LightCompactStrategy 四条规则详情：**

- **Rule 1 `truncate-large-tool-output`**：tool_result > 5KB → 保留 head 10 行 + tail 10 行，保护窗口最近 5 条消息不动
- **Rule 2 `dedup-consecutive-tools`**：连续相同 tool name + input hash 的调用对，保留后者删除前者
- **Rule 3 `fold-failed-retries`**：3+ 连续 is_error=true 的 tool result 折叠为 1 条 `"[folded] N retries failed. Last error: ..."`
- **Rule 4 `drop-empty-assistant-narration`**：< 80 chars 的过渡性 assistant 文本（"Let me"、"I'll"、"接下来"等 18 个前缀匹配）在下一条是 tool_use 时删除
- **提前终止**：任意规则执行后 token 回收率 >= 20% 则跳过后续规则

**FullCompactStrategy 详情：**

- `prepareCompact`（Phase 1，无 LLM）：找 safe boundary（确保 prefix 中所有 tool_use 都有 matching tool_result）
- `applyPrepared`（Phase 2，调 LLM）：序列化旧消息窗口（tool_result 截断到 500 chars），LLM 生成摘要（max 1000 tokens，temperature 0.2）
- 摘要注入：写入 COMPACT_BOUNDARY + SUMMARY 系统消息，后接保留的 young-gen 消息

### 1.2 Claude Code 完整能力（8+ 层）

| 层级 | 机制 | 文件 | 触发条件 | 策略 |
|------|------|------|---------|------|
| C1 | **Per-result 磁盘持久化** | `utils/toolResultStorage.ts` | 单个 result > `getPersistenceThreshold`（默认 50K chars，可 per-tool 覆盖） | 完整内容写入 `tool-results/{id}.txt`，消息体替换为 `<persisted-output>` 标签（2KB preview + 文件路径 + 原始大小） |
| C2 | **Per-message 聚合预算** | `utils/toolResultStorage.ts` `enforceToolResultBudget` | 单条 user message 内所有 tool_result 总量 > 200K chars（可 GrowthBook 覆盖） | 按大小降序持久化最大的 result 直到总量低于预算；`ContentReplacementState` 冻结决策保证 prompt cache 稳定 |
| C3 | **SnipTool 精准删除** | `services/compact/snipCompact.js`（ant-only） | 模型通过 tool_use 主动删除指定 message ID | 消息打 `[id:xxx]` 标签，模型调用 SnipTool 删除不需要的旧消息 |
| C4 | **Time-based 冷清理** | `services/compact/microCompact.ts` `maybeTimeBasedMicrocompact` | 最后 assistant 消息距今 > N 分钟（GrowthBook 配置） | 清理旧 compactable tool result 内容为 `[Old tool result content cleared]`，保留最近 N 个；`COMPACTABLE_TOOLS` 白名单只清理 Read/Bash/Grep/Glob/WebFetch/Edit/Write |
| C5 | **Cached Microcompact** | `services/compact/microCompact.ts` `cachedMicrocompactPath` | 注册的 tool result 数超过 triggerThreshold | 通过 `cache_edits` API 在服务端删除旧 tool result，不破坏客户端 prompt cache |
| C6 | **API-native Microcompact** | `services/compact/apiMicrocompact.ts` | input_tokens > 180K | 通过 `context_management` 请求字段让 API 服务端清理旧 tool uses/results，支持 `clear_tool_inputs` 和 `clear_thinking` 策略 |
| C7 | **Session Memory 零 LLM 压缩** | `services/compact/sessionMemoryCompact.ts` | autoCompact 触发时优先尝试 | 用已提取的 session memory 文件替代 LLM 摘要，保留最近 N 条消息（minTokens 10K / minTextBlockMessages 5 / maxTokens 40K） |
| C8 | **Full LLM Compact** | `services/compact/compact.ts` | session memory compact 不够 / 手动 `/compact` / reactive 413 | 9 段结构化 prompt 摘要；支持 partial compact（only head / only tail）；PTL 重试最多 3 次；post-compact 恢复文件附件（5 个 / 50K token）+ skill 附件（25K token） |
| C9 | **Reactive Compact** | `services/compact/reactiveCompact.js`（ant-only） | API 返回 413 prompt-too-long | 压缩后自动重试，最后防线 |
| C10 | **UI 折叠**（仅展示） | `utils/collapseReadSearch.ts` | 连续 Read/Search/Grep 调用 | 合并为 "Read 3 files, searched 2 patterns" 折叠组，不影响 API 发送内容 |

**Token 计数对比：**

| 维度 | SkillForge | Claude Code |
|------|-----------|-------------|
| 粗估 | `TokenEstimator`：CJK 1:1，非 CJK ÷3.5 | `roughTokenCountEstimation`：length ÷ 4（JSON ÷ 2） |
| 精确计数 | **无** | `countTokensWithAPI`：调用 Anthropic API 精确计数 |
| 预算追踪 | 每次迭代重新估算全部消息 | `tokenCountWithEstimation`：上次 API response 的 usage + 新消息粗估 |

### 1.3 Gap 分析

| # | Claude Code 能力 | 作用 | SkillForge 现状 | 优先级 |
|---|-----------------|------|----------------|--------|
| **G1** | **Per-message 聚合预算** (`enforceToolResultBudget`) | 单条 user message 内所有 tool_result 总量超 200K chars 时，最大的持久化替换为 preview | **完全没有**。只有 per-result 40K 截断，5 个并行 tool call 各返回 35K = 175K 无人拦截 | **高** |
| **G2** | **Tool result 磁盘持久化 + preview 引用** (`persistToolResult`) | 超阈值 result 写入文件/DB，消息体替换为 2KB preview + 引用路径，模型可通过 Read 恢复 | **完全没有**。SkillForge 只能截断丢弃，无法恢复原始内容 | **高** |
| **G3** | **Time-based 冷清理** (`maybeTimeBasedMicrocompact`) | session 空闲超 N 分钟后，旧 tool result 内容清空为占位符，保留最近 N 个 | **完全没有**。空闲 session 的 stale tool result 一直占用 context window | **中** |
| **G4** | **Session Memory 零 LLM 压缩** (`sessionMemoryCompact`) | 用已提取的 session memory 替代 LLM 摘要，零 API 调用完成压缩 | **完全没有**。full compact 必须调 LLM。P8 做完 LLM 提取后可复用 | **中** |
| **G5** | **Partial compact** | 支持 `up_to`（压缩头保留尾）和 `from`（压缩尾保留头）两种方向 | **完全没有**。只有"压缩全部旧消息 + 保留最近 20 条"一种模式 | **中** |
| **G6** | **Post-compact 上下文恢复** | 压缩后自动重新附加最近 5 个文件内容（50K token 预算）和 skill 内容（25K 预算） | **完全没有**。full compact 后模型丢失所有文件上下文，需要重新 Read | **中高** |
| **G7** | **ContentReplacementState 决策冻结** | 已替换的永远用相同 replacement，已保留的永远不替换，保证 prompt cache 稳定性 | **不适用**（SkillForge 无 prompt cache），但决策一致性仍有价值 | **低** |
| **G8** | **SnipTool 模型主动精准删除** | 模型通过 tool_use 精准删除指定 message ID | **完全没有**。`compact_context` 只能触发 light/full，不能精准删除 | **低** |
| **G9** | **Reactive compact**（413 后自动重试） | API 返回 prompt-too-long 时自动压缩重试 | **部分有**。有 max_tokens recovery，但无 413 PTL 专门处理 | **低** |
| **G10** | **Token 精确计数** (`countTokensWithAPI`) | 通过 API 获取精确 token 数 | **完全没有**。只有粗估 `TokenEstimator`，误差可达 30%+ | **中** |
| **G11** | **Compactable 工具白名单** | 只压缩 Read/Bash/Grep/Glob/WebFetch/Edit/Write，其他工具 result 不动 | **完全没有**。LightCompactStrategy 对所有 tool result 一视同仁 | **中** |

---

## 二、P9 子任务拆分

> 按优先级排序，覆盖 G1~G6 + G10~G11。

### P9-1 Compactable 工具白名单（G11）

**目标**：只有特定工具的 result 参与压缩/裁剪，Memory/SubAgent 等工具的 result 不动。

**设计要点**：
- 新建 `CompactableToolRegistry`，定义白名单：Bash、FileRead、FileWrite、Grep、Glob、WebFetch
- `LightCompactStrategy` Rule 1（大 result 截断）仅作用于白名单内的 tool result
- `ToolResultTruncator` 可选跳过非白名单工具（或保持现行为，白名单只影响 compaction 层）
- 白名单可配置（agent 级别覆盖）

### P9-2 Per-message 聚合预算 + 归档持久化（G1 + G2）

**目标**：单条 user message 内所有 tool_result 总量超预算时，最大的 result 归档到 DB 并替换为 preview 引用。

**参考**：Claude Code `enforceToolResultBudget` + `persistToolResult` + `buildLargeToolResultMessage`

**设计要点**：
- 新建 `ToolResultBudgetEnforcer`，在 `AgentLoopEngine` 中 tool 执行完毕、消息入队前调用
- 预算阈值：单条消息 200K chars（可配置）
- 超预算时按 result 大小降序，逐个归档：
  - 方案 A：归档到 `t_tool_result_archive` 表（archive_id, session_message_id, original_content, original_size, created_at）
  - 方案 B：归档到文件系统（类似 Claude Code 的 `tool-results/{id}.txt`）
  - 推荐方案 A，与 P6 行存储对齐
- 消息体替换为 preview：`"[Tool output archived] Original size: {size}. Preview (first 2KB):\n{head}\n...\nUse tool_result_id={id} to retrieve full content."`
- 可选：新增 `ToolResultRetrieveSkill` 让模型按需读取归档内容

### P9-3 Time-based 冷清理（G3）

**目标**：session 空闲超阈值后，自动清理旧的 compactable tool result。

**参考**：Claude Code `maybeTimeBasedMicrocompact`

**设计要点**：
- 在 `AgentLoopEngine` 每次迭代开始时（B1 之前）检查：距离最后一条 assistant 消息的时间间隔
- 超过阈值（默认 5 分钟，可配置）时：
  - 收集所有 compactable tool result（使用 P9-1 白名单）
  - 保留最近 N 个（默认 5，至少 1）
  - 其余内容替换为 `"[Old tool result content cleared]"` 占位符
- 与 LightCompactStrategy 互补：time-based 处理冷 session，light compact 处理热 session

### P9-4 Partial compact 支持（G5）

**目标**：支持只压缩消息历史的头部或尾部，而非全量压缩。

**参考**：Claude Code `partialCompactConversation` + `PARTIAL_COMPACT_PROMPT` / `PARTIAL_COMPACT_UP_TO_PROMPT`

**设计要点**：
- `FullCompactStrategy` 新增 `compactUpTo(messages, pivotIndex, provider, modelId)` 和 `compactFrom(messages, pivotIndex, provider, modelId)`
- `up_to`（压缩头部，保留尾部）：常用于 "保留最近工作上下文，摘要早期历史"
- `from`（压缩尾部，保留头部）：常用于 "保留初始需求上下文，摘要中间执行过程"
- `ContextCompactTool` 扩展参数：`level=partial_head` / `level=partial_tail` + `pivot`
- 为每个方向设计独立的 LLM 摘要 prompt

### P9-5 Post-compact 上下文恢复（G6）

**目标**：full compact 后自动重新注入关键上下文，减少模型 "失忆" 后的重复 Read。

**参考**：Claude Code `compact.ts` post-compact 恢复逻辑

**设计要点**：
- Compact 完成后，自动注入一条 system/user 消息，包含：
  - **最近操作的文件摘要**：从 compact 前的消息中提取最近 N 个 FileRead/FileWrite 的文件路径 + 内容摘要（每个文件限 5K token，总限 50K token）
  - **当前活跃的 skill 上下文**：如果有绑定的 custom skill，重新注入 skill description
  - **待完成任务提示**：从摘要中提取 pending tasks/TODOs
- 文件内容恢复策略：只恢复仍然存在于磁盘/DB 的文件，不恢复已删除的
- 恢复预算：总计不超过 50K tokens

### P9-6 Session Memory 零 LLM 压缩（G4）

**目标**：用已提取的记忆替代 LLM 摘要完成压缩，零 API 调用。依赖 P8 完成。

**参考**：Claude Code `sessionMemoryCompact.ts`

**设计要点**：
- `CompactionService` 在 `compactFull` 时优先尝试 session memory compact：
  1. 检查当前 session 是否有已提取的 memory 条目
  2. 如果有：用 memory 内容构造摘要消息，保留最近 N 条消息（minTokens 10K / minMessages 5 / maxTokens 40K）
  3. 如果 compact 后 token 仍超阈值：降级到 LLM full compact
- 优势：节省 LLM 调用成本、更快（毫秒级 vs 秒级）、摘要质量可控（来自已验证的 memory）
- 前提：P8 LLM 记忆提取完成，且 session 有足够的 memory 条目

### P9-7 Token 估算增强（G10）

**目标**：提升 token 估算精度，减少压缩触发的误判。

**设计要点**：
- **方案 A（推荐）**：集成 jtokkit（已在项目依赖中），用 cl100k_base 编码器精确计数
  - 对 Anthropic 模型仍是近似（Claude 使用自己的 tokenizer），但比 chars÷3.5 精确得多
  - 对 OpenAI 兼容模型（GPT-4o、DeepSeek）是精确的
- **方案 B**：调用 LLM provider 的 token counting API（如 Anthropic `/v1/messages/count_tokens`）
  - 精确但增加 API 调用延迟和成本
  - 适合在 full compact 决策前做一次精确检查，不适合每次迭代
- **混合方案**：日常用 jtokkit 本地计数，关键决策点（full compact 触发前）调 API 确认
- 优化：缓存已计算的消息 token 数（消息内容不可变），增量计算新消息

---

## 三、实现优先级与依赖关系

```
P9-1（白名单）── 无依赖，基础设施
  ↓
P9-2（聚合预算 + 归档）── 依赖 P9-1
P9-3（冷清理）── 依赖 P9-1
P9-7（Token 增强）── 无依赖，可并行
  ↓
P9-4（Partial compact）── 独立
P9-5（Post-compact 恢复）── 独立
  ↓
P9-6（Session Memory compact）── 依赖 P8（LLM 记忆提取）
```

**建议实施顺序**：P9-1 → P9-2 + P9-7 并行 → P9-3 → P9-5 → P9-4 → P9-6（等 P8）
