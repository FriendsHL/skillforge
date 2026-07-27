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

- 高相关且本轮刚确认的小量事实可直接注入。
- 其他 Memory 默认注入索引：ID、标题、类型、相关性和更新时间。
- 模型通过现有 `MemoryDetail`/`MemorySearch` 加载正文。
- 不相关 Memory 不进入请求。
- 加载失败不得伪造空事实；应允许任务继续或明确报告不可用。

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

支持条件：

- approaching context limit
- post-compact recovery
- untrusted external data
- media reference present
- current-information request
- destructive/outbound action
- pending user confirmation

未触发时不得增加请求 token。普通用户文本不能伪造内部 Reminder。

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

### FR-13 恢复

- 重启后通过稳定 ID 重载 Memory、Attachment、Media Job 和运行状态。
- Prompt Fragment 本身不作为业务事实持久化。
- 恢复不得提升 Memory confirmation/trust。
- 已失效或找不到的引用必须显示为 stale/missing，而不是被摘要成仍然有效。

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
