# Tech Design — Agent Context Runtime

## 0. 架构决策

### 方案 A：继续增加 Markdown/XML 规则

优点是改动小；缺点是来源、预算、有效期和权限继续依靠模型理解，无法消除 Context Breakdown 漂移，
也无法支持统一 Capability/Memory provenance。

结论：只允许作为紧急安全补丁，不作为本需求主方案。

### 方案 B：一次性重写消息、Provider 和 Compact 协议

优点是最终模型统一；缺点是同时触碰 `AgentLoopEngine`、Provider SSE、消息持久化、Compact 和 Tool Pairing，
无法建立可信的回归边界。

结论：拒绝。

### 方案 C：内部结构化、Provider 边界兼容渲染

在 Core 内建立 Assembly/Descriptor/Provenance，Provider 前仍渲染成现有 System Prompt、Message 和 Tool Schema。
各期通过 Shadow、Feature Flag 和快照测试迁移。

结论：采用。

## 1. 目标架构

```text
Agent / Session / User Input
          │
          ▼
Context Sources
  Global · Agent · Soul · Behavior · Memory · Reminder · File · External
          │
          ▼
PromptAssembly ───── Context Breakdown / Trace
          │
          ├──── Instruction Registry
          │         └──── loadedInstructionIds
          │
          ├──── Capability Index
          │         │
          │         ▼
          │   ToolSearch / Capability Router
          │         │
          ▼         ▼
Provider Renderer + Tool Schemas
          │
          ▼
LlmRequest / Agent Loop
          │
          ▼
ToolResult + ResultProvenance + Artifact refs
          │
          ▼
Message persistence / Attachment / Media Job / Compact
```

权限仍由现有 Agent 配置、`SessionSkillView`、MCP allowlist、审批和执行层校验决定。Router 不能新增权限。

## 2. P0：真实基线和同源观测

### 2.1 Assembly Observation

先引入不参与渲染的只读模型：

```java
public record PromptFragmentObservation(
    String id,
    PromptSourceType sourceType,
    PromptPlacement placement,
    boolean stable,
    boolean cacheable,
    int estimatedTokens,
    List<String> sourceIds,
    String contentHash
) {}
```

现有 Builder 每追加一个逻辑片段时同步产生 Observation。最终请求仍使用当前字符串。

### 2.2 Schema Observation

对 `collectTools()` 最终输出逐个记录：

```text
capabilityId
kind
source
schemaHash
estimatedTokens
exposureReason
```

首期不隐藏任何当前合法 Tool。

### 2.3 Context Breakdown 同源

`ContextBreakdownService` 不再自行重建 global/agent/soul/tools/behavior/memory。它消费本轮或可重建的
Observation；需要正文估算时调用同一 Builder 入口，不触发外部检索和副作用。

### 2.4 基线指标

- System stable/dynamic tokens。
- Tool Schema tokens。
- Memory tokens 与 injected IDs。
- Prompt Cache prefix hash。
- 每轮 Tool 暴露数、调用数、任务成功/失败。
- Compact 触发原因和前后 token。

P0 是后续路由效果的对照组。

## 3. P1：PromptAssembly、ContextAttachment 和兼容 Renderer

### 3.1 数据模型

```java
public record PromptFragment(
    String id,
    PromptSourceType sourceType,
    PromptAuthority authority,
    PromptTrustLevel trustLevel,
    PromptPlacement placement,
    PromptCompactPolicy compactPolicy,
    String content,
    boolean stable,
    boolean cacheable,
    int estimatedTokens,
    Instant expiresAt,
    List<String> sourceIds,
    String contentHash
) {}
```

动态 Context 使用同一基础语义：

```java
public record ContextAttachment(
    String id,
    ContextKind kind,
    PromptSourceType sourceType,
    PromptAuthority authority,
    PromptTrustLevel trustLevel,
    PromptPlacement placement,
    ContextLifecycle lifecycle,
    PromptCompactPolicy compactPolicy,
    String content,
    String contentHash,
    int estimatedTokens,
    Instant expiresAt,
    List<String> sourceIds
) {}
```

`PromptFragment` 表示最终 Assembly 片段，`ContextAttachment` 表示运行时产生、尚待 Renderer 放置的动态上下文。

建议枚举：

```text
sourceType:
PLATFORM, AGENT, SOUL, BEHAVIOR_RULE, TOOL_GUIDANCE, RUNTIME_CONTEXT,
MEMORY, RAG, WEB, FILE, PAST_SESSION, SUBAGENT, REMINDER

authority:
PLATFORM, AGENT_CONFIG, USER_CONTEXT, EXTERNAL_CONTEXT

trustLevel:
TRUSTED_INSTRUCTION, TRUSTED_RUNTIME_DATA, CONFIGURED_INSTRUCTION, USER_DATA,
STORED_DATA, UNTRUSTED_EXTERNAL_DATA, MODEL_GENERATED_DATA

placement:
STABLE_SYSTEM, DYNAMIC_SYSTEM, USER_REMINDER, REQUEST_ONLY

compactPolicy:
KEEP_METADATA, SUMMARIZE, RELOAD_BY_ID, DROP_ON_COMPACT
```

### 3.2 Assembly

```java
public final class PromptAssembly {
    private final List<PromptFragment> fragments;

    public RenderedPrompt render(PromptRenderer renderer);
    public ContextBreakdown breakdown(TokenEstimator estimator);
}
```

Assembly 保持确定性顺序：

1. Global Platform。
2. Agent。
3. Soul。
4. Tool Guidance。
5. Behavior Rules。
6. Cache Breakpoint。
7. Runtime Context。
8. Session Identity。
9. Memory/External Context。
10. Runtime Suffix。

### 3.3 Renderer

首期只有兼容 Renderer：

```java
public interface PromptRenderer {
    RenderedPrompt render(PromptAssembly assembly);
}
```

`RenderedPrompt` 继续提供当前 `stableSection`、`dynamicSection` 或等价字符串。Claude/OpenAI-compatible
Provider 不直接感知 `PromptFragment`。

低信任片段用平台生成的边界包裹，正文必须转义。边界的作用是帮助模型理解来源，不承担安全授权。

### 3.4 Cache 不变量

- 同一 stable 输入必须产生字节一致前缀。
- dynamic 内容不得移动到 cache breakpoint 之前。
- source ID、时间和 trace metadata 不渲染到 stable 文本。
- Observation/Trace 不影响请求 Hash。
- TodoWrite 的通用决策规则属于 Global Platform 的静态 Tool Usage Guidelines；Tool Schema
  继续只描述参数形状，不重复承载长篇使用策略。

### 3.5 兼容入口

`SystemPromptBuilder` 保留当前公开方法，内部委托 Assembly。迁移完成后再单独评估删除兼容入口，本需求内
不做无关清理。

## 4. P2：Structured Reminder V2

### 4.1 数据模型

```java
public record ReminderEntry(
    String id,
    ReminderSourceType source,
    ReminderSeverity severity,
    ReminderReasonCode reasonCode,
    String content,
    int estimatedTokens,
    int debounceTurns,
    PromptPlacement placement,
    ContextLifecycle lifecycle,
    PromptCompactPolicy compactPolicy,
    Instant expiresAt
) {}
```

现有 `ReminderSource`、顺序、总预算、debounce 和异常隔离继续复用。`ReminderBuilder` 输出
`List<ContextAttachment>`，兼容 Renderer 再生成当前 `<system-reminder>` 文本。

### 4.2 Placement

```text
ContextUsage         BEFORE_NEXT_MODEL_CALL
MemoryAge            BEFORE_NEXT_MODEL_CALL
FileChanged          AFTER_TOOL_RESULT / BEFORE_NEXT_MODEL_CALL
ToolDiscovery        INITIAL_CONTEXT / UNTIL_TOOL_DISCOVERED
Permission           AFTER_TOOL_RESULT
CompactRecovery      AFTER_COMPACT_SUMMARY
```

首期允许当前调用点把多个 Attachment 合并成同一兼容 Block；内部必须保留各自 metadata，后续 Provider
Renderer 才能安全调整位置。

### 4.3 持久化边界

普通 Reminder 仅存在于 Loop Runtime。等待用户审批、异步任务、媒体 Job、稳定引用等跨重启状态写入现有
Checkpoint/Recovery；不新增通用 Reminder 表。客户端提交的 `<system-reminder>` 永远保持 `USER_DATA`。

## 4A. P2.5：Prompt 与 Memory 注入收敛

### 4A.1 Platform / Agent 边界

`prompts/global-system-prompt.md` 只保留所有 Agent 的稳定规则。Main Assistant 的 `system_prompt`
通过 Flyway 迁移收敛为角色职责；既有全局规则不在 Agent Prompt 中重复。

本期不实现 Project Registry 或 `research-docs` Corpus。SkillForge 项目知识、个人研究库和长篇工具手册
后续通过独立 Skill/Capability Guidance 按需加载。

### 4A.2 Run Context 动态化

`ChatService` 不再把 `ArtifactWorkspaceService.promptInstruction()` 追加到
`AgentDefinition.systemPrompt`。它把文本写入一次性 runtime context；`AgentLoopEngine` 将其转换为：

```text
id              artifact_workspace
kind            RUNTIME_CONTEXT
sourceType      RUNTIME_CONTEXT
placement       DYNAMIC_SYSTEM
lifecycle       REQUEST_ONLY
compactPolicy   DROP_ON_COMPACT
stable          false
cacheable       false
```

同一 Agent 跨 Session 的 stable prefix hash 不得因 artifact run path 变化。

### 4A.3 Long-term Memory admission

复用 P6 已交付字段，不新增另一套 scope 表：

```text
status == ACTIVE
confirmationStatus == CONFIRMED
```

作为默认注入的准入门。手工创建/编辑和人工批准 synthesis 已产生 `CONFIRMED`；自动 Session 提取和
Agent 建议保持 `UNVERIFIED`，因此只可由 `memory_search -> memory_detail` 按需读取。

默认注入采用确定性更新时间排序，不再为 System Prompt 执行 FTS、Embedding 或 Session Digest fallback：

```text
max entries        6
total char budget  3,200
per-entry chars    400
```

最终低信任边界中的每条内容渲染为：

```text
- [memory:<id> provenance=<source> confirmation=CONFIRMED confidence=<n> version=<v>]
  <title>: <content>
```

`MemorySearch`/`MemoryDetail` 保留现有全量 ACTIVE 检索能力，确保短期信息仍可被 Agent 主动加载。

### 4A.4 不变量

- 不改变 `t_session_message` JSON shape、Tool pairing 或 Compact summary role。
- Memory 默认注入减少不得删除数据库内容，也不得改变 Memory Search 结果集合。
- Preview 和真实注入必须使用同一个选择与渲染函数。
- Context Breakdown 必须显示实际注入条数、ID 和 token，不自行执行另一套召回。
- 无 CONFIRMED Memory 时不渲染空标题或占位文本。

### 4A.5 Tool Usage Guidance 分层

```text
STABLE_SYSTEM
  Global system prompt
    ├─ 平台角色与运行原则
    ├─ 安全与权限边界
    ├─ Read / Grep / Glob / Edit / Write 选择与调用顺序
    └─ Memory 历史指代、search/detail/save 使用规则
  Agent prompt
  Soul prompt
  Behavior rules

DYNAMIC_SYSTEM
  Agent tools_prompt（可选的 Provider/Capability 专属说明）
  Environment / Session / Artifact
  Confirmed long-term memory
```

通用手册只能在 Global Prompt 出现一次。`tools_prompt` 从“替换默认手册”改成独立动态附件；
为空时不生成占位。AnySearch 的 Tool Schema/MCP 注册保持不变，V186 只清理 Main Assistant
和 Research Agent 由 V154 写入的路由 Prompt。

## 5. P3：Instruction Registry、嵌套指令和去重

### 5.1 Descriptor

```java
public record InstructionDescriptor(
    String id,
    InstructionType type,
    InstructionScope scope,
    String canonicalSource,
    String contentHash,
    List<String> pathGlobs,
    InstructionLoadPolicy loadPolicy,
    PromptAuthority authority,
    int estimatedTokens
) {}
```

### 5.2 Identity 和加载

- Identity 使用 normalized canonical source + owner/scope；正文 Hash 只判断版本变化。
- `loadedInstructionIds` 位于 Session Runtime，防止 traversal/include/worktree 多路径重复注入。
- Session-start 加载 Global/User/Agent/Project Root/无条件 Rules。
- Nested CLAUDE.md 和 path-scoped rules 在文件访问事件命中时加载。
- 每次加载记录 reason、trigger path、parent include 和 content hash。

### 5.3 Compact

Project Root 和 always-on Instruction 在 Compact 后立即从权威来源重载。Nested/Path-scoped 只恢复已加载
ID 元数据，并在再次访问对应路径时重载；Pending Step 已绑定该目录时允许立即解析。Instruction 正文不由
Compact Summary 复制。

## 6. P4：ToolCatalog、ToolSearch 和 Deferred Schema

### 6.1 Catalog

```java
public record ToolDescriptor(
    String id,
    String name,
    String description,
    ToolKind kind,
    CapabilitySource source,
    ToolSchema schema,
    boolean alwaysLoaded,
    boolean searchable,
    SideEffectLevel sideEffect,
    ApprovalPolicy approvalPolicy,
    int schemaTokenCost,
    Set<String> tags
) {}
```

`ToolCatalog` 包装现有 Java Tool、MCP Tool 和媒体 Tool，不改变其执行接口。第一版 always-loaded 至少保留
ToolSearch、Skill Loader、必要 Core Tool、Ask/Compact；低频 MCP/媒体/管理 Tool 才进入 deferred。

### 6.2 搜索与发现状态

```java
public record ToolDiscoveryState(
    Set<String> discoveredToolIds,
    Map<String, String> resolvedSchemaHashes
) {}
```

ToolSearch 先使用名称、Tag、描述/BM25 和 Provider/媒体确定性过滤，不引入向量数据库。返回 Descriptor
引用后，下一轮 Assembly 才把匹配 Schema 正式加入 `tools[]`。

### 6.3 Provider 双路径

- Claude 原生：Provider 支持时使用 `defer_loading + tool_reference`。
- Ark/OpenAI-compatible：SkillForge 根据 `discoveredToolIds` 在下一轮显式补入 `tools[]`。
- 两条路径共享 Discovery State、权限二次校验、reason code 和 Context Breakdown。
- 代理不支持原生 ToolSearch 时不能把 Schema 仅作为普通文本结果后直接执行。

## 7. P5：Tool、Skill、Instruction Compact 恢复

### 7.1 Runtime Snapshot

```java
public record ContextRuntimeSnapshot(
    Set<String> loadedInstructionIds,
    Set<String> discoveredToolIds,
    Map<String, String> resolvedSchemaHashes,
    List<SkillInvocationRef> invokedSkills,
    Set<String> reloadableReminderIds
) {}
```

### 7.2 Tool 恢复

Compact 后用 Tool ID 从当前 Catalog 解析 Schema。旧/新 Hash 不同时使用当前 Schema并记录
`TOOL_SCHEMA_CHANGED_AFTER_COMPACT`；找不到则 `TOOL_MISSING_AFTER_COMPACT`。不得从 Summary 解析或复制
JSON Schema。

### 7.3 Skill 恢复

每个 Skill 只保留最近一次 Invocation Ref。恢复默认单 Skill 5,000 tokens、总计 25,000 tokens，按最近
调用优先；超预算记录 `SKILL_DROPPED_BY_COMPACT_BUDGET`，模型可再次调用 Loader。Skill version hash
变化时从当前 Package 重载并记录原因。

### 7.4 Pairing 和持久化

恢复的 Schema、Skill、Instruction 都是 Context Attachment，不创建伪造 `tool_use/tool_result`。同进程
Compact 使用 Loop Runtime；kill 恢复复用现有 Task Checkpoint/Recovery 扩展字段，并保持
`t_session_message.content_json` byte-shape。

## 8. P6：Memory Provenance、索引和 CAS

### 8.1 表结构

优先扩展 `t_memory`，不新增重复 Memory 表：

```text
provenance_type       varchar
source_session_id     varchar nullable
source_message_id     bigint nullable，引用 t_session_message.id
source_role           varchar nullable
confirmation_status   varchar
confidence            numeric nullable
last_verified_at      timestamptz nullable
version               bigint not null default 0
```

`source_message_id` 采用现有 `SessionMessageEntity.id` 的 `BIGINT` 类型；不增加强外键，以容忍历史清理和
Compact rewrite 后的孤儿来源，但必须为可用引用建立索引。新时间字段使用 `Instant`。

旧数据回填：

```text
provenance_type = LEGACY_UNKNOWN
confirmation_status = UNVERIFIED
version = 0
```

不得根据文本或 role 猜测 `USER_CONFIRMED`。

### 8.2 运行时对象

```java
public record MemoryFact(
    Long id,
    String title,
    String content,
    MemoryProvenanceType provenanceType,
    MemoryConfirmationStatus confirmationStatus,
    double relevance,
    Double confidence,
    Instant updatedAt,
    Instant lastVerifiedAt,
    MemorySourceRef source
) {}
```

`MemoryInjection` 改为 facts + injected IDs，由 Renderer 统一生成 Memory Fragment。

### 8.3 渐进加载策略

直接正文注入需同时满足：

- 本轮用户刚陈述/确认，或高相关且短小。
- 未过期。
- 数量和 token 在直接注入预算内。

其他 Memory 只注入：

```text
memoryId
title/one-line summary
provenance
updatedAt
relevance
```

模型通过现有 Memory Tool 读取正文。Skill Loader 的 progressive disclosure 模式作为实现参考。

### 8.4 CAS

使用 JPA `@Version`。更新冲突：

1. 重读新版本。
2. 非内容统计字段允许自动合并一次。
3. 内容或 confirmation 冲突创建 Proposal。
4. API 返回明确 409/Conflict DTO。

Memory synthesis、审批、rollback、batch update、恢复任务都必须覆盖版本测试。

## 9. P7：Result Provenance 和 Artifact Bridge

### 9.1 Result Envelope

不改变 Provider 的 `tool_result` JSON shape，先在 SkillForge 内部扩展：

```java
public record ResultProvenance(
    ResultSourceType sourceType,
    boolean external,
    ResultTrust trust,
    String origin,
    String contentType,
    boolean truncated,
    List<String> artifactIds,
    String provider,
    Instant observedAt
) {}
```

`SkillResult` 或相邻执行结果对象携带该元数据。兼容旧 Tool 时按 Tool 类型提供服务端默认值。

### 9.2 分类默认值

| 来源 | 默认信任 |
| --- | --- |
| 本地确定性只读 Tool | `TRUSTED_RUNTIME_DATA` |
| 本地写 Tool 返回状态 | `TRUSTED_RUNTIME_DATA`，不代表输出正文可信 |
| Memory/RAG/File parse | `USER_OR_STORED_DATA` |
| Web/MCP/外部 API | `UNTRUSTED_EXTERNAL_DATA` |
| LLM/SubAgent 输出 | `MODEL_GENERATED_DATA` |

客户端和 Tool 输入不能覆盖默认 trust。

### 9.3 MCP/Artifact Bridge

新增适配层，不修改 MCP SDK：

```java
public interface ExternalContentPublisher {
    PublishedArtifact publish(McpContent content, ExecutionContext context);
}
```

处理：

- text：受预算 Result 文本。
- image/base64：校验 MIME/大小后导入 Attachment。
- embedded resource：文本小对象可内联；文件/媒体导入 Attachment。
- resource URI：按协议、allowlist、用户所有权和大小策略下载或保留受控引用。
- unknown：截断 JSON + provenance，不把任意超大 JSON写入历史。

消息只保存稳定 Artifact/Attachment Ref。

## 10. P8：Capability Index 和 Router

### 10.1 Descriptor

```java
public record CapabilityDescriptor(
    String id,
    CapabilityKind kind,
    CapabilitySource source,
    SideEffectLevel sideEffect,
    ApprovalPolicy approvalPolicy,
    LatencyClass latencyClass,
    CostClass costClass,
    boolean async,
    boolean recoverable,
    Set<MediaType> inputMedia,
    Set<MediaType> outputMedia,
    Set<String> providerRequirements,
    int schemaTokenCost
) {}
```

Descriptor 通过 Adapter 包装现有 Tool、Skill、MCP 和 Media，不要求它们继承新基类。

### 10.2 Decision

```java
public record CapabilityDecision(
    String capabilityId,
    boolean allowed,
    CapabilityReasonCode reasonCode,
    Set<ApprovalScope> requiredApprovals,
    int schemaTokenCost
) {}
```

Reason Code 至少包括：

```text
ALLOWED_BY_AGENT
DENIED_AGENT_ALLOWLIST
DENIED_SESSION_SKILL_VIEW
DENIED_MCP_SERVER
PROVIDER_UNAVAILABLE
MEDIA_INCOMPATIBLE
APPROVAL_REQUIRED
SCHEMA_BUDGET_DEFERRED
RUNTIME_UNAVAILABLE
```

### 10.3 路由层级

- Level 0：现有后端授权，硬边界。
- Level 1：Provider/运行时/媒体确定性兼容过滤。
- Level 2：Schema Budget 和 Deferred Tool。
- Level 3：意图相关性排序。

P8 首次发布只启用 Level 0–2。Level 3 先 Shadow，记录“如果启用会隐藏什么”，经过成功率评估后再单独批准。

### 10.4 执行时校验

即使 Capability 未暴露但模型从历史猜到名称，执行层仍使用当前 `SessionSkillView`、MCP allowlist、
Agent allowlist 和审批检查。Router Decision 不能作为唯一授权票据。

### 10.5 审批作用域

```java
public record ApprovalGrant(
    String capabilityId,
    ApprovalScope scope,
    String targetHash,
    String sessionId,
    Instant expiresAt
) {}
```

首期复用现有 Approval Token，增加服务端 scope/target 校验。外部发送、Provider 计费、文件覆盖和任意 URL
访问不得跨目标复用。

## 11. P9：Compact 决策状态和业务恢复

### 11.1 Continuity State

Full Compact 生成机器可解析但仍可兼容文本 Summary 的结构：

```java
public record ContinuityState(
    String currentGoal,
    List<Decision> acceptedDecisions,
    List<Decision> rejectedApproaches,
    List<Fact> verifiedFacts,
    List<Fact> assumptions,
    List<PendingStep> pendingSteps,
    List<Reference> references,
    List<Constraint> constraints
) {}
```

首期可先以固定标题 Markdown 渲染并做 Parser/Validator；不要求立即新增消息 ContentBlock。

### 11.2 Reference

只保存稳定标识：

```text
attachmentId
artifactId
mediaJobId
memoryId
subAgentRunId
workflowRunId
checkpointId
```

禁止 Base64、完整外部正文、临时 URL 和 Secret。

### 11.3 Trust 保持

Summary 中每个 Fact 带：

```text
status = VERIFIED | ASSUMED | USER_DECISION | EXTERNAL_UNVERIFIED
sourceIds
```

Compact 不允许把 `ASSISTANT_SUGGESTION` 变成 `USER_DECISION`，不允许丢失否决方案。

### 11.4 恢复

重启后由稳定 ID 重载业务对象。引用不存在、无权访问或已过期时标记 `STALE/MISSING/DENIED`。不得使用摘要
中的旧正文伪装对象仍有效。

Workflow Startup Recovery 和多实例 Lease 保持在 `TASK-RESUME-ON-RESTART`，本需求只定义上下文引用契约。

## 12. API 与管理端

### 12.1 Context Breakdown

扩展现有 Session endpoint，返回脱敏 DTO：

```text
promptFragments[]
capabilityDecisions[]
memoryReferences[]
continuityReferences[]
totals
assemblyHash
```

普通用户和管理员使用不同 Projection。默认不返回 `content`。

### 12.2 Dashboard

在现有 Context Breakdown 表面增加 Tab：

- Prompt
- Capabilities
- Memory
- Continuity

每个条目展示 metadata、reason 和 token。全文诊断如果后续需要，必须另设管理员审计入口，不纳入首期。

### 12.3 iOS

iOS 不增加 Context 管理后台。它继续消费现有消息、Tool/Attachment/Media Job 卡片，只新增统一的用户可理解
错误码映射：

- capability unavailable
- provider unavailable
- approval required
- stale/missing reference

内部 authority、trust 和完整路由原因不直接暴露给 App。

## 13. 安全不变量

1. 客户端不能提交 authority、trust、approval grant。
2. 低信任正文必须在 Renderer 边界转义。
3. Prompt 标签不是授权机制。
4. MCP/URL/媒体继续使用 allowlist、ownership、size 和 timeout。
5. Trace 只记录 metadata/hash，Secret 和完整正文默认禁止。
6. Tool 执行继续二次授权。
7. `tool_use/tool_result` pairing、消息 byte-shape 和 rewrite identity 不变。

## 14. Feature Flags

```text
skillforge.context.observation.enabled
skillforge.context.assembly.enabled
skillforge.context.structured-reminder.enabled
skillforge.context.instruction-registry.enabled
skillforge.tool-search.enabled
skillforge.tool-search.native-claude.enabled
skillforge.context.runtime-snapshot.enabled
skillforge.memory.progressive.enabled
skillforge.result-provenance.enabled
skillforge.mcp.artifact-bridge.enabled
skillforge.capability-router.enabled
skillforge.capability-router.intent-shadow-enabled
skillforge.compact.continuity-state.enabled
```

Feature Flag 用于安全灰度和回滚，不作为永久双实现借口。每期稳定后移除上一层 Shadow 分支需单独清理任务。
