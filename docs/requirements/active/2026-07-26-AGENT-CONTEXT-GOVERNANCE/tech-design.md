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
          ├──── Capability Index
          │         │
          │         ▼
          │   Capability Router
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

## 3. P1：PromptAssembly 和兼容 Renderer

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

建议枚举：

```text
sourceType:
PLATFORM, AGENT, SOUL, BEHAVIOR_RULE, TOOL_GUIDANCE, RUNTIME_CONTEXT,
MEMORY, RAG, WEB, FILE, PAST_SESSION, SUBAGENT, REMINDER

authority:
PLATFORM, AGENT_CONFIG, USER_CONTEXT, EXTERNAL_CONTEXT

trustLevel:
TRUSTED_INSTRUCTION, CONFIGURED_INSTRUCTION, USER_DATA, UNTRUSTED_EXTERNAL_DATA

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

### 3.5 兼容入口

`SystemPromptBuilder` 保留当前公开方法，内部委托 Assembly。迁移完成后再单独评估删除兼容入口，本需求内
不做无关清理。

## 4. P2：Memory Provenance、索引和 CAS

### 4.1 表结构

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

### 4.2 运行时对象

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

### 4.3 渐进加载策略

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

### 4.4 CAS

使用 JPA `@Version`。更新冲突：

1. 重读新版本。
2. 非内容统计字段允许自动合并一次。
3. 内容或 confirmation 冲突创建 Proposal。
4. API 返回明确 409/Conflict DTO。

Memory synthesis、审批、rollback、batch update、恢复任务都必须覆盖版本测试。

## 5. P3：Result Provenance、Reminder 和 Artifact Bridge

### 5.1 Result Envelope

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

### 5.2 分类默认值

| 来源 | 默认信任 |
| --- | --- |
| 本地确定性只读 Tool | `TRUSTED_RUNTIME_DATA` |
| 本地写 Tool 返回状态 | `TRUSTED_RUNTIME_DATA`，不代表输出正文可信 |
| Memory/RAG/File parse | `USER_OR_STORED_DATA` |
| Web/MCP/外部 API | `UNTRUSTED_EXTERNAL_DATA` |
| LLM/SubAgent 输出 | `MODEL_GENERATED_DATA` |

客户端和 Tool 输入不能覆盖默认 trust。

### 5.3 Reminder

```java
public record ReminderEntry(
    String source,
    ReminderSeverity severity,
    String reasonCode,
    String content,
    int estimatedTokens,
    int debounceTurns,
    PromptPlacement placement
) {}
```

保留现有 Source、debounce、budget 和异常隔离。若继续持久化为 user content block，必须使用内部构造路径和
不可由普通 Chat API提交的 block type；Provider Adapter 再转换成模型可理解文本。

### 5.4 MCP/Artifact Bridge

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

## 6. P4：Capability Index 和 Router

### 6.1 Descriptor

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

### 6.2 Decision

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

### 6.3 路由层级

- Level 0：现有后端授权，硬边界。
- Level 1：Provider/运行时/媒体确定性兼容过滤。
- Level 2：Schema Budget 和 Deferred Tool。
- Level 3：意图相关性排序。

P4 首次发布只启用 Level 0–2。Level 3 先 Shadow，记录“如果启用会隐藏什么”，经过成功率评估后再单独批准。

### 6.4 执行时校验

即使 Capability 未暴露但模型从历史猜到名称，执行层仍使用当前 `SessionSkillView`、MCP allowlist、
Agent allowlist 和审批检查。Router Decision 不能作为唯一授权票据。

### 6.5 审批作用域

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

## 7. P5：Compact 决策状态和恢复

### 7.1 Continuity State

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

### 7.2 Reference

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

### 7.3 Trust 保持

Summary 中每个 Fact 带：

```text
status = VERIFIED | ASSUMED | USER_DECISION | EXTERNAL_UNVERIFIED
sourceIds
```

Compact 不允许把 `ASSISTANT_SUGGESTION` 变成 `USER_DECISION`，不允许丢失否决方案。

### 7.4 恢复

重启后由稳定 ID 重载业务对象。引用不存在、无权访问或已过期时标记 `STALE/MISSING/DENIED`。不得使用摘要
中的旧正文伪装对象仍有效。

Workflow Startup Recovery 和多实例 Lease 保持在 `TASK-RESUME-ON-RESTART`，本需求只定义上下文引用契约。

## 8. API 与管理端

### 8.1 Context Breakdown

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

### 8.2 Dashboard

在现有 Context Breakdown 表面增加 Tab：

- Prompt
- Capabilities
- Memory
- Continuity

每个条目展示 metadata、reason 和 token。全文诊断如果后续需要，必须另设管理员审计入口，不纳入首期。

### 8.3 iOS

iOS 不增加 Context 管理后台。它继续消费现有消息、Tool/Attachment/Media Job 卡片，只新增统一的用户可理解
错误码映射：

- capability unavailable
- provider unavailable
- approval required
- stale/missing reference

内部 authority、trust 和完整路由原因不直接暴露给 App。

## 9. 安全不变量

1. 客户端不能提交 authority、trust、approval grant。
2. 低信任正文必须在 Renderer 边界转义。
3. Prompt 标签不是授权机制。
4. MCP/URL/媒体继续使用 allowlist、ownership、size 和 timeout。
5. Trace 只记录 metadata/hash，Secret 和完整正文默认禁止。
6. Tool 执行继续二次授权。
7. `tool_use/tool_result` pairing、消息 byte-shape 和 rewrite identity 不变。

## 10. Feature Flags

```text
skillforge.context.observation.enabled
skillforge.context.assembly.enabled
skillforge.memory.progressive.enabled
skillforge.result-provenance.enabled
skillforge.mcp.artifact-bridge.enabled
skillforge.capability-router.enabled
skillforge.capability-router.intent-shadow-enabled
skillforge.compact.continuity-state.enabled
```

Feature Flag 用于安全灰度和回滚，不作为永久双实现借口。每期稳定后移除上一层 Shadow 分支需单独清理任务。
