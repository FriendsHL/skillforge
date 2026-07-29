# Delivery Plan — Agent Context Runtime

## 总体策略

- P0 已交付，P1–P9 独立交付，不允许合成一个超大变更。
- 每期开始前重新核对 dirty worktree 和前一期交付 commit。
- P1–P5 不改数据库；P6 才引入 Memory Migration。
- P8 路由先 Shadow 后 Enforce。
- 每期用户批准后才 commit/push。

## P0：真实基线与同源观测（已交付）

### 范围

- Prompt Fragment Observation。
- Tool/Skill/MCP Schema token 统计。
- Context Breakdown 读取同源数据。
- Prompt Cache prefix hash 和 Assembly hash。
- Dashboard 只读 Prompt/Capability 元数据表。

### 不做

- 不改变最终 Prompt。
- 不隐藏 Tool。
- 不改变 Memory 注入。

### 验收

- 相同输入下 Provider 请求快照与改造前字节一致。
- Context Breakdown 不再独立复制拼装规则。
- 能列出所有 System 片段和 Tool Schema token。
- Trace 不包含 Secret、完整 Memory、网页正文。
- P95 Assembly Observation 开销小于 20ms。

### 回滚

关闭 `skillforge.context.observation.enabled`，恢复旧 Breakdown；无数据库依赖。

交付基线：`0ec321b4`。需求包状态校准不得覆盖当前工作区既有 RightRail 未提交视觉调整。

## P1：PromptAssembly、ContextAttachment 与信任边界

实施进度（2026-07-29）：**已完成**。

- 已完成 `ContextAttachment`、`PromptAssembly` 和保持旧字节形状的兼容 Renderer。
- 已迁移 Global、Agent、Soul、Tool Guidance、Behavior、Runtime、Session Context 和 User Memory。
- 已让 Context Breakdown 同源读取 Assembly，并输出 authority、trustLevel、lifecycle、compactPolicy。
- Memory/RAG/File/Web/SubAgent 已使用平台生成的闭合边界并转义正文。
- `skillforge.context.assembly.enabled` 可回到原字节路径；Memory 同时生成 legacy/new hash
  做无正文 shadow 比对。
- Web/MCP、Read/Grep/Glob、Memory RAG 的 `tool_result` 只改变正文边界，不改变
  `Message`/`ContentBlock` shape；SubAgent 回投明确标记为 model-generated data。

### 范围

- `PromptFragment`、`ContextAttachment`、`PromptAssembly`、兼容 Renderer。
- Global/Agent/Soul/Tool Guidance/Behavior/Runtime/Memory 迁移。
- stable/dynamic cache boundary 保持。
- Memory/Web/File/SubAgent 低信任边界。
- Claude/OpenAI-compatible 请求契约测试。

### 验收

- stable prefix byte-identical。
- 相同业务内容 System Prompt token 增幅不超过 5%。
- Memory/RAG/File/Web/SubAgent 五类闭合标签和伪指令测试通过。
- Prompt Renderer 不修改原 `Message`/`ContentBlock`。
- Context Breakdown 与 Assembly hash 一致。
- Attachment 的 placement/lifecycle/compactPolicy 可观测，但 Provider wire shape 不变。

### 灰度

1. Shadow 生成新旧文本并比较 hash/diff category。
2. 测试环境切新 Renderer。
3. 单个内部 Agent 灰度。
4. 全局启用。

### 回滚

关闭 `skillforge.context.assembly.enabled`，回到旧 Renderer。

## P2：Structured Reminder V2

实施进度（2026-07-29）：**已完成**。

- `ReminderEntry` 已具备稳定 ID、source、severity、reasonCode、debounce、placement、
  lifecycle、compactPolicy 与 expiry。
- `ReminderBuilder.buildResult()` 先生成结构化 Entry/Attachment，再由兼容 Renderer
  保持现有 `<system-reminder>` 字节和 `ContentBlock` 持久化形状。
- `skillforge.context.structured-reminder.enabled=false` 回到旧算法；关闭后连 expiry
  过滤也不介入旧路径。
- Context/Todo/Memory/File 四个现有 Source 已提供结构化原因和生命周期。
- Context Breakdown 只显示最近一次 Reminder 的哈希与 metadata，并以 0 token
  诊断节点呈现，避免与 Messages 重复计费或泄漏正文。
- 已覆盖未触发零 token、Source 异常隔离、debounce、expiry、state-change 清理、
  用户伪造 `<system-reminder>` 仍留在原始用户数据块，以及开关前后兼容字节。

### 范围

- `ReminderEntry` 增加 source/severity/reason/placement/lifecycle/compactPolicy/expiry。
- 保留现有 Source 顺序、debounce、总预算和异常隔离。
- Builder 先输出结构化 Attachment，兼容 Renderer 生成现有 `<system-reminder>`。
- 区分 `BEFORE_NEXT_MODEL_CALL`、`AFTER_TOOL_RESULT`、`AFTER_COMPACT_SUMMARY` 等 placement。
- 普通 Reminder 保持在 Runtime；跨重启状态复用现有 Checkpoint/Recovery。

### 验收

- Feature Flag 关闭时请求字节与当前实现一致。
- 用户输入同名 XML 标签不能产生 Runtime Authority。
- 未触发 Reminder 新增 token 为 0。
- Source 失败不阻断 Agent Loop。
- 同一 Reminder debounce、expiry 和 state-change 失效测试通过。
- Context Breakdown 可解释每条 Reminder 的来源、位置和生命周期。

## P3：Instruction Registry、嵌套加载与去重

> 产品决策（2026-07-29）：本阶段跳过。SkillForge 当前没有形成以 CLAUDE.md
> 为核心的指令加载机制，后续方向更可能是移除兼容入口，而不是引入嵌套扫描、
> Include 和路径作用域注册表。以下内容保留为历史调研，不进入当前交付范围。

### 范围

- Global/User/Agent/Project/Nested/Path Rule/Include Descriptor。
- Canonical Instruction Identity、content/version hash。
- `loadedInstructionIds` 和加载原因观测。
- Session-start、路径命中、Include 和 Compact 重载。

### 验收

- 首次 Query 只加载启动作用域正文。
- Nested/Path Rule 在命中目录或文件类型后加载。
- 同一文件经 traversal/include/worktree 多路径只注入一次。
- 不按内容 Hash 合并不同作用域指令。
- Compact 后 Root 立即重载，Nested 再次命中路径时重载。
- 旧 Global/Agent Prompt 配置继续可读。

## P4：ToolCatalog、ToolSearch 与 Deferred Schema

> 实施状态（2026-07-29）：跨 Provider 客户端路径已实现，默认处于
> `Catalog + ToolSearch 开启、Deferred enforcement 关闭` 的兼容灰度阶段。
> Claude 服务端 Tool Search 协议与 Ark/OpenAI-compatible 不同，继续由独立
> Feature Flag 承载，不与当前 Ark 主链路混用。

### 范围

- Tool Descriptor/Catalog，包装 Java/MCP/Media Tool。
- Always-loaded 与 Deferred 分类。
- 名称、Tag、描述/BM25 搜索。
- Ark/OpenAI-compatible `discoveredToolIds` 模拟加载。
- Claude 原生 `defer_loading + tool_reference` 作为独立 Provider Feature Flag；
  该协议要求请求仍携带完整工具集，由 Anthropic 服务端控制上下文暴露，不能复用
  Ark/OpenAI-compatible 的“下一轮补 Schema”请求形态。
- Tool 暴露和隐藏 reason code。

### 验收

- Router/Search 结果始终是现有授权集合子集。
- 普通 Tool Result 返回 Schema 文本不能直接调用未暴露 Tool。
- ToolSearch 发现后，下一轮 `tools[]` 含完整权威 Schema。
- Ark 图片、普通文本 Tool、MCP 各一条 E2E。
- 高 Tool Agent Schema token P50 至少降低 25%。
- 任务成功率相对 P0 下降不超过 2 个百分点。
- 模型猜到未暴露 Tool 名称仍被执行层拒绝。

### 灰度

1. Catalog/Decision observation only。
2. ToolSearch 可调用，但所有当前 Tool 仍保持 loaded。
3. 明确低频 Tool Deferred。
4. Claude native path 单独启用。

## P5：Tool、Skill Compact 恢复

> 实施状态（2026-07-29）：已实现版本化 `ContextRuntimeSnapshot`，以
> `t_session.context_runtime_json` 持久化 Tool ID/Schema Hash 与最近 Skill
> Invocation 引用；Compact/kill-recovery 均从当前授权 Registry 重建，不保存 Schema
> 或 Skill 正文，不创建伪造 tool pairing。

### 范围

- `ContextRuntimeSnapshot`。
- `discoveredToolIds + schemaHash` 恢复。
- `invokedSkillIds + versionHash + invocationSequence` 恢复。
- 同进程 Compact 与 kill-recovery Checkpoint Adapter。

### 验收

- Compact 后 Tool Schema 从当前 Registry 重建，数组/数字/required 约束不退化。
- Schema 版本变化产生 reason code，不静默复用旧摘要。
- 每个 Skill 只恢复最近一次 Invocation。
- 默认单 Skill 5,000 tokens、总计 25,000 tokens，最近调用优先。
- 超预算 Skill 可重新调用并显示 `DROPPED_BY_COMPACT_BUDGET`。
- 恢复不产生伪造 `tool_use/tool_result`，Persistence Shape 专项回归通过。

## P6：Memory Provenance、渐进加载和 CAS

> 实施状态（2026-07-29）：复用既有 `memory_search → memory_detail` 渐进加载，
> 新增 provenance/confirmation/confidence/version、断线编辑版本冲突、注入/Tool
> 结果来源元数据、Proposal 审批标记和 rollback snapshot 来源保留。

### 范围

- Flyway 增加 provenance/source/confirmation/confidence/version。
- Entity、Repository、Service、Proposal、Synthesis、Rollback、Batch、恢复路径。
- `MemoryInjection` 结构化。
- Memory 索引常驻和正文按需加载。
- Dashboard Memory metadata。

### 验收

- 旧数据统一为 `LEGACY_UNKNOWN/UNVERIFIED`。
- 助手建议不会成为用户决定。
- 两线程 CAS 冲突无静默覆盖。
- 内容冲突进入 Proposal。
- Compact、重启、审批和 rollback 保留来源。
- 无关 Memory 不进入 Prompt；正文 Tool 加载可观测。

### 数据门

- Migration 必须在 PostgreSQL 真实实例执行。
- 检查所有 rewrite/snapshot/export/import 路径。
- 不提供破坏性 down migration；回滚代码兼容新增列。

## P7：Result Provenance、MCP Artifact Bridge

### 范围

- `ResultProvenance`。
- Web/MCP/File/Memory/SubAgent Tool 分类。
- MCP Image/Resource → Attachment。
- Result 摘要和稳定 Artifact ID。

### 验收

- MCP 图片消息历史无 Base64，刷新后仍可下载。
- 超大 MCP JSON 按预算截断并标记。
- Attachment ownership、MIME、大小和 URL allowlist 安全测试通过。

### 回滚

Result Provenance 可保留只读；关闭 Artifact Bridge 后恢复文本占位，不删除已生成 Attachment。

## P8：Capability Descriptor 与 Router

### 范围

- Tool/Skill/MCP/Media Descriptor Adapter。
- 确定性 Provider/Runtime/Media 过滤。
- Approval Scope/Target。
- Schema budget 与 ToolSearch/Catalog 决策整合。
- Router reason code 和 Dashboard。
- 意图路由只做 Shadow。

### 验收

- Router 允许集合始终是现有授权集合子集。
- 猜测未暴露 Tool 名称仍被执行层拒绝。
- Ark 图片、文本 Tool、MCP、Skill Loader、SubAgent 各一条 E2E。
- 高 Tool Agent Schema token P50 至少降低 25%。
- 任务成功率相比 P0 不下降超过 2 个百分点。
- 关键能力被 Deferred 时，模型可通过 P4 ToolSearch 取回。
- 审批不能跨目标复用。

### 灰度

1. Descriptor/Decision only。
2. Shadow 路由。
3. 确定性过滤 Enforce。
4. Schema budget Enforce。
5. 意图路由是否启用另行审批。

## P9：Compact Continuity 与业务恢复

### 范围

- 固定结构的 Continuity State。
- accepted/rejected/verified/assumed/pending/reference/constraint。
- Attachment/Artifact/MediaJob/Memory/SubAgent/Workflow 稳定引用。
- P5 Runtime Snapshot 之外的业务 Continuity、stable reference reload 和 stale/missing/denied。
- 最多三次 Full Compact 路径兼容。

### 验收

- Compact 前后用户决定、否决方案、未完成步骤保留。
- Verified/Assumed 不互相升级。
- 图片编辑在 Compact 后仍使用同一 source attachment ID。
- Media Job 和 SubAgent Run 可恢复定位。
- Missing/Denied 引用明确显示，不使用旧正文伪成功。
- `tool_use/tool_result` pairing 和 persistence-shape 专项回归通过。
- 真实 Claude/OpenAI-compatible context overflow 各完成一次联调。

## Full Pipeline 门禁

每期必须完成：

1. 需求和技术设计对应关系检查。
2. Core/Server 聚焦测试。
3. Core+Server Maven reactor。
4. Provider compatibility review。
5. Compact/persistence/database/security 专项审查，按实际改动触发。
6. Dashboard 变更执行真实浏览器测试和 DOM 断言。
7. 真 Agent shadow/dogfood。
8. 验证报告区分 confirmed、assumed、blocked。
9. 用户批准后再 commit/push。

## 建议提交边界

```text
P0-1 observation model + tests [done]
P0-2 context breakdown + dashboard [done]

P1-1 context attachment + prompt assembly
P1-2 source migration + provider contracts

P2-1 reminder entry/source migration
P2-2 placement/lifecycle + compatibility renderer

P3-1 instruction registry/identity
P3-2 nested/path/include loading + compact reload

P4-1 tool catalog + discovery observation
P4-2 ToolSearch + Ark/OpenAI-compatible deferred path
P4-3 Claude native path + dashboard

P5-1 runtime snapshot + tool schema restore
P5-2 skill/instruction restore + kill-recovery adapter

P6-1 migration/entity/CAS
P6-2 progressive injection + proposal/recovery
P6-3 dashboard memory metadata

P7-1 result provenance
P7-2 MCP artifact bridge

P8-1 descriptors/decision observation
P8-2 deterministic router + approval scope
P8-3 schema budget/dashboard

P9-1 continuity state
P9-2 compact/recovery/provider E2E
```

同一期可有多个 commit，但禁止跨期混合。

## 实施前置条件

1. 当前未提交的 RightRail 视觉调整不属于本需求，实施时不得覆盖或混入提交。
2. 图片编辑已进入 `0ec321b4`，可作为 P9 多模态 Compact 验收样本。
3. 实时语音保持关闭，不作为本需求验收依赖。
4. P0 基线至少积累内部 Agent 的代表性文本、MCP、多模态、SubAgent 会话样本。
