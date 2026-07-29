# PRD — Agent 上下文与能力治理

## 1. 产品原则

1. **权限先于智能**：授权集合由后端确定，模型和 Router 只能在集合内选择。
2. **引用先于正文**：Memory、Skill、文件、媒体和外部资源优先常驻索引，正文按需加载。
3. **来源不等于内容**：相同文本因来源不同可具有不同 authority/trust，用户内容不能自报为 System。
4. **内部结构化、边界兼容**：内部统一描述，Provider Adapter 保持各自协议。
5. **Compact 不提权**：摘要只能压缩内容，不能把推断变成事实、把外部数据变成指令。
6. **先观测后优化**：没有真实 token、命中率和成功率基线，不启用动态能力裁剪。

## 2. 角色与权限

| 角色 | 能力 |
| --- | --- |
| 普通用户 | 查看本 Session 的简化 Context 摘要和能力不可用原因 |
| Agent Owner | 查看 Agent 的 Prompt/Skill/Tool/MCP 成本与配置来源 |
| 管理员 | 查看全量元数据、灰度状态和安全事件，不默认查看正文 |
| Runtime | 产生不可由客户端伪造的 authority/trust/provenance |

## 3. 功能要求

### FR-1 真实 Prompt Assembly

每个进入请求的片段必须具有：

```text
id
sourceType
authority
trustLevel
placement
stable/cacheable
estimatedTokens
compactPolicy
expiresAt
sourceIds
contentHash
```

`ContextBreakdownService`、Trace 和最终 Renderer 必须消费同一份 Assembly。

### FR-2 信任与 Prompt Injection 隔离

以下内容必须视为数据：

- Memory 正文
- RAG/网页/搜索结果
- 用户上传文件解析内容
- MCP Resource/外部 Tool Result
- 历史 Session
- SubAgent 报告
- Provider 返回的媒体描述

内容中出现 System 标签、Reminder 标签、越权指令或工具调用要求，不得改变 authority、授权和审批。

### FR-2A Context Attachment

进入模型请求的动态上下文必须先表示为内部 Attachment，而不是由各调用方直接拼接字符串。Attachment 至少包含：

```text
id/kind/source
authority/trustLevel
placement/lifecycle
compactPolicy
estimatedTokens/contentHash
expiresAt/sourceIds
```

首期 Renderer 必须保持现有 Claude/OpenAI-compatible wire shape；内部结构化不等于新增公开 Provider Block。

### FR-2B Instruction Registry

- Global、User、Agent、Project、Nested、Path-scoped 和 Include 指令具有稳定身份与作用域。
- Session-start 指令在首次 Query 前加载正文；Nested/Path-scoped 指令在命中路径时加载。
- 去重以 canonical source identity 为主，不能用正文 Hash 合并不同作用域的指令。
- 加载事件记录 `session_start/nested_traversal/path_glob_match/include/compact` 原因和触发路径。
- Compact 后根指令从权威来源重载；Nested 指令默认在再次命中路径时重载。

### FR-3 Memory Provenance

Memory 必须区分：

- `USER_CONFIRMED`
- `USER_STATED`
- `HUMAN_APPROVED_SUMMARY`
- `ASSISTANT_SUGGESTION`
- `MODEL_INFERENCE`
- `EXTERNAL_FACT`
- `SYSTEM_STATE`
- `LEGACY_UNKNOWN`

必须保存可用的来源 Session、Message、Role、生成 Run、置信度和最后验证时间。只有用户明确确认或人工审批
能产生 `USER_CONFIRMED`。

### FR-4 Memory 渐进加载

- 默认 System Context 只注入 `ACTIVE + CONFIRMED` 的长期事实，最多 6 条且总预算不超过约
  1,000 tokens。每条必须携带 ID、provenance、confirmation、confidence 和 version。
- `USER_TRANSCRIPT`、`AGENT_SUGGESTED`、Session Digest、Activity Log、Tool Result 和其他
  `UNVERIFIED` 内容不得自动进入 System Prompt，不因向量/FTS 相关性高而例外。
- 短期信息仅进入可检索层。模型先通过现有 `MemorySearch` 获取轻量引用，再用 `MemoryDetail`
  加载正文；检索结果本身不得升级 confirmation 或 authority。
- 本期不把个人 `research-docs`、项目文档库或历史 Session 全文纳入默认 Memory 注入。
- 不相关 Memory 不进入请求；无已确认长期 Memory 时省略整个 Memory Attachment。
- 加载失败不得伪造空事实；应允许任务继续或明确报告不可用。

### FR-4A Prompt 职责收敛

- Platform Prompt 包含跨 Agent 的稳定运行规则，以及文件定位、读取、编辑和 Memory 历史检索等
  通用 Tool Usage Guidelines。TodoWrite 的静态手册必须说明适用场景、跳过场景、完整列表替换、
  单一 `in_progress` 和及时更新要求；不包含 SkillForge 仓库结构、端口、构建命令、
  AnySearch 参数手册、Artifact 当前路径或 Main Agent 专属职责。
- Main Agent Prompt 只包含目标理解、最小计划、有限委派、结果整合和验证职责，不重复 Platform Prompt
  的安全、工具沟通或文件编辑规则。
- Agent `tools_prompt` 只承载可选、可变的 Agent/Provider/Capability 专属说明，位于 Cache Boundary
  之后；不得替换 Platform 的静态工具手册。
- AnySearch Tool 和 MCP 绑定继续保留，但现有 Main/Research Agent 的 AnySearch 路由说明从
  `tools_prompt` 删除，模型依据 Tool Description 自主选择。
- 当前 Artifact Workspace、Session ID 和其他 Run 级数据必须位于 Cache Boundary 之后，标记为
  `DYNAMIC_SYSTEM + REQUEST_ONLY + cacheable=false`。
- `research-docs` 本期不接入 Context Runtime；未来只能通过独立 Skill/Tool 按需加载，不得成为所有
  Main Agent 请求的永久 Prompt。

### FR-5 Memory 并发

- 更新必须使用版本条件。
- CAS 冲突不得静默 last-write-wins。
- 可机械合并的字段最多自动重试一次。
- 内容冲突进入现有 Proposal/Human Review 流程。
- 恢复任务携带旧版本时不得覆盖新事实。

### FR-6 Tool Result Provenance

Tool Result 必须能表达：

```text
sourceType
external
trusted
origin
contentType
truncated
artifactIds
provider
observedAt
```

本地确定性 Tool 可使用可信默认值；Web、MCP、外部 API、文件解析和模型生成结果必须显式标记。

### FR-7 Reminder 结构化

Reminder Entry 增加：

- source
- severity
- reasonCode
- tokenEstimate
- debounce
- placement
- lifecycle
- compactPolicy
- expiresAt

支持条件：

- approaching context limit
- post-compact recovery
- untrusted external data
- media reference present
- current-information request
- destructive/outbound action
- pending user confirmation

未触发时不得增加请求 token。普通用户文本不能伪造内部 Reminder。

Reminder 必须支持 `BEFORE_USER_MESSAGE`、`AFTER_TOOL_RESULT`、`BEFORE_NEXT_MODEL_CALL` 和
`AFTER_COMPACT_SUMMARY` 等位置。短生命周期 Reminder 保持在 Loop Runtime；只有等待审批、异步任务、
稳定引用等需要跨重启的状态进入现有 Checkpoint/Recovery，不为普通 Reminder 新建重复持久化表。

### FR-8 MCP 与多模态 Artifact Bridge

- MCP Image、Embedded Resource 和可下载 Resource 必须转换成受管 Attachment 或结构化外部引用。
- Base64、超大 JSON 和 Provider 临时 URL 不写入消息历史。
- Tool Result 只返回摘要、稳定 ID、MIME、大小和必要元数据。
- 后续模型理解媒体时，通过 Attachment Materializer 在 Provider 边界物化。
- 资产转存失败必须有明确错误状态，不能伪装为成功文本。

### FR-9 Capability Descriptor

Tool、Skill、MCP、Media 和 Agent delegation 统一暴露：

```text
id/kind/source
sideEffect
approvalPolicy
approvalScope
latencyClass
costClass
async
recoverable
inputMedia/outputMedia
providerRequirements
runtimeAvailability
schemaTokenCost
```

Descriptor 是索引层，不要求重写现有 Tool/Skill 接口。

### FR-9A ToolCatalog、ToolSearch 与 Deferred Schema

- 核心 Tool 保持 always-loaded；MCP、媒体和低频 Tool 可标记为 deferred。
- ToolSearch 在既有授权集合内按名称、Tag、描述和确定性兼容条件检索。
- 搜索结果必须在下一轮作为正式 `tools[]` Schema 暴露后才可调用；普通 Tool Result 中的 Schema 文本
  不获得 Function Calling 语义。
- Claude 原生 Provider 可使用 `defer_loading + tool_reference`；Ark/OpenAI-compatible 由 SkillForge
  维护 `discoveredToolIds` 并在下一轮补入 Schema。
- Tool 执行继续二次授权，历史中猜到未暴露名称不能绕过权限。
- 初次 Enforce 只 Deferred 明确低频能力；意图相关性只做 Shadow。

### FR-10 Capability Router

候选过滤顺序：

1. Agent/用户/后端授权。
2. SessionSkillView 和 MCP allowlist。
3. Provider 与运行时可用性。
4. 输入媒体和参数兼容性。
5. 审批状态与作用域。
6. Schema token budget。
7. 模型选择。

Router 必须输出 reason code。执行层继续做二次授权。首个上线版本只允许确定性规则，意图分类只做
Shadow Observation，不参与权限或隐藏关键能力。

### FR-11 审批作用域

审批至少区分：

- 单次 Tool Call
- 当前 Session
- 精确资源/目标
- Agent 配置级持久授权

对一个 URL、文件、MCP Server 或 Provider 的审批不得自动扩展到另一个目标。

### FR-12 Compact 决策状态

Full Compact Summary 必须保存：

- 当前用户目标
- 用户明确决定
- 已否决方案和原因
- 已验证事实
- 未验证假设
- 未完成步骤
- 等待用户输入/审批
- Attachment/Artifact/Media Job ID
- SubAgent/Workflow Run ID
- 安全与权限约束

Summary 不保存 Base64、完整外部正文、Secret 或 Provider 临时 URL。

### FR-12A Compact Runtime State

Compact 必须在自然语言 Summary 之外保存：

```text
loadedInstructionIds
discoveredToolIds + resolvedSchemaHashes
invokedSkillIds + versionHashes + invocationSequence
activeReloadableReminderIds
```

- Tool 只保存稳定 ID/旧 Hash，Compact 后从当前 Tool Registry 解析权威 Schema。
- Skill 每个 ID 只恢复最近一次 Invocation；单 Skill 默认最多 5,000 tokens，合计默认 25,000 tokens，
  最近调用优先；被预算丢弃的 Skill 必须可重新调用。
- Root Instruction 立即重载；Nested Instruction 按路径再次命中，除非 Pending Step 明确需要立即恢复。
- Runtime State 恢复不得创建伪造的 `tool_use/tool_result` 或改变历史 Message JSON shape。
- Schema/Skill 版本变化必须产生可观测 reason code，不得静默继续使用摘要中的旧正文。

### FR-13 恢复

- 重启后通过稳定 ID 重载 Memory、Attachment、Media Job 和运行状态。
- Prompt Fragment 本身不作为业务事实持久化。
- 恢复不得提升 Memory confirmation/trust。
- 已失效或找不到的引用必须显示为 stale/missing，而不是被摘要成仍然有效。
- 同进程 Compact 状态优先保存在 Loop Runtime；需要跨服务 kill 恢复时复用现有 Task
  Checkpoint/Recovery 扩展字段，不为 Tool/Skill 各建一套重复任务表。

### FR-14 可观测性

每轮可查看：

- 各 Prompt Fragment token、placement、cacheability。
- Tool Schema 总 token 和逐能力成本。
- 暴露/隐藏 reason code。
- Memory 索引命中、正文加载和去重。
- Compact 前后状态保留情况。
- 外部结果信任分类和截断。

正文默认不进入 Trace；管理员显式诊断也必须脱敏和审计。

### FR-15 管理端体验

Context Breakdown 增加四个视图：

1. Prompt：稳定/动态片段、来源、token、缓存。
2. Capabilities：Tool/Skill/MCP/Media 的暴露结果和原因。
3. Memory：只显示元数据、来源类型和加载状态。
4. Continuity：Compact、Checkpoint、Attachment、Run 引用状态。

普通用户只看到简化说明，不显示内部 Prompt 正文或安全规则。

### FR-16 跨端错误呈现

Dashboard 和 iOS 对能力不可用、Provider 不可用、需要审批、引用失效使用同一服务端错误码。Dashboard 可提供
面向 Agent Owner 的详细 reason；iOS 只显示用户可操作的简化说明，不增加 Context 管理入口。

## 4. 非功能要求

### NFR-1 兼容性

- Claude 和 OpenAI-compatible Provider 都通过契约测试。
- 旧 Session、旧 Memory、旧 Skill 和旧 MCP 配置继续可读。
- 第一阶段不改变 `t_session_message.content_json` shape。

### NFR-2 性能

- Assembly 元数据构建 P95 小于 20ms，不包含外部检索。
- Context Breakdown 不重复执行 Memory/RAG/文件解析。
- Descriptor 和静态 token estimate 可缓存，配置变化后失效。

### NFR-3 安全

- authority/trust 只由服务端产生。
- XML/Markdown Renderer 必须转义低信任内容边界。
- URL 下载、MCP Resource 和媒体继续使用现有 allowlist/ownership/size 限制。

### NFR-4 回滚

每期提供独立 Feature Flag。关闭后回到上一期稳定路径，不依赖回滚数据库 Migration。

## 5. 总体验收

1. 五类 Prompt Injection 攻击不能越过后端授权。
2. Context Breakdown 与真实 Assembly 同源。
3. Memory 不再把助手建议显示成用户决定，并能处理并发冲突。
4. MCP 图片进入 Attachment，消息历史不包含 Base64。
5. Router 输出始终是现有授权集合的子集。
6. Compact/重启后仍能定位历史图片、Media Job、SubAgent Run 和用户决定。
7. Claude/OpenAI-compatible 请求和流式 Tool Loop 无回归。
8. Dashboard 能解释 token 成本和路由，不泄露正文与 Secret。
9. Dashboard/iOS 对四类能力与引用错误具有一致且可操作的呈现。
10. Compact 后已发现 Tool 使用当前 Registry Schema 恢复，数组/数字/required 参数约束不退化。
11. Compact 后最近 Skill 按预算重挂，Nested Instruction 不重复注入且能在路径命中时恢复。
