# Delivery Plan — Agent Context Runtime

## 总体策略

- 六期独立交付，不允许合成一个超大变更。
- 每期开始前重新核对 dirty worktree 和前一期交付 commit。
- P0–P1 不改数据库；P2 才引入 Memory Migration。
- P4 路由先 Shadow 后 Enforce。
- 每期用户批准后才 commit/push。

## P0：真实基线与同源观测

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

## P1：PromptAssembly 与信任边界

### 范围

- `PromptFragment`、`PromptAssembly`、兼容 Renderer。
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

### 灰度

1. Shadow 生成新旧文本并比较 hash/diff category。
2. 测试环境切新 Renderer。
3. 单个内部 Agent 灰度。
4. 全局启用。

### 回滚

关闭 `skillforge.context.assembly.enabled`，回到旧 Renderer。

## P2：Memory Provenance、渐进加载和 CAS

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

## P3：Result Provenance、Reminder、MCP Artifact Bridge

### 范围

- `ResultProvenance`。
- Web/MCP/File/Memory/SubAgent Tool 分类。
- Reminder 元数据和内部 block。
- MCP Image/Resource → Attachment。
- Result 摘要和稳定 Artifact ID。

### 验收

- MCP 图片消息历史无 Base64，刷新后仍可下载。
- 超大 MCP JSON 按预算截断并标记。
- 用户伪造 `<system-reminder>` 不获得内部来源。
- 未触发 Reminder 新增 token 为 0。
- Reminder Source 失败不阻断 Agent Loop。
- Attachment ownership、MIME、大小和 URL allowlist 安全测试通过。

### 回滚

Result Provenance 可保留只读；关闭 Artifact Bridge 后恢复文本占位，不删除已生成 Attachment。

## P4：Capability Descriptor 与 Router

### 范围

- Tool/Skill/MCP/Media Descriptor Adapter。
- 确定性 Provider/Runtime/Media 过滤。
- Approval Scope/Target。
- Schema budget 和 Deferred Tool。
- Router reason code 和 Dashboard。
- 意图路由只做 Shadow。

### 验收

- Router 允许集合始终是现有授权集合子集。
- 猜测未暴露 Tool 名称仍被执行层拒绝。
- Ark 图片、文本 Tool、MCP、Skill Loader、SubAgent 各一条 E2E。
- 高 Tool Agent Schema token P50 至少降低 25%。
- 任务成功率相比 P0 不下降超过 2 个百分点。
- 关键能力被 Deferred 时，模型可通过发现机制取回。
- 审批不能跨目标复用。

### 灰度

1. Descriptor/Decision only。
2. Shadow 路由。
3. 确定性过滤 Enforce。
4. Schema budget Enforce。
5. 意图路由是否启用另行审批。

## P5：Compact Continuity 与恢复

### 范围

- 固定结构的 Continuity State。
- accepted/rejected/verified/assumed/pending/reference/constraint。
- Attachment/Artifact/MediaJob/Memory/SubAgent/Workflow 稳定引用。
- post-compact reload 和 stale/missing/denied。
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
P0-1 observation model + tests
P0-2 context breakdown + dashboard

P1-1 prompt assembly + renderer
P1-2 source migration + provider contracts

P2-1 migration/entity/CAS
P2-2 progressive injection + proposal/recovery
P2-3 dashboard memory metadata

P3-1 result provenance/reminder
P3-2 MCP artifact bridge

P4-1 descriptors/decision observation
P4-2 deterministic router + approval scope
P4-3 schema budget/dashboard

P5-1 continuity state
P5-2 compact/recovery/provider E2E
```

同一期可有多个 commit，但禁止跨期混合。

## 实施前置条件

1. 先整理当前未提交的图片编辑、语音实验和文档改动，形成可重复基线。
2. 图片编辑进入正式 commit 后再把它纳入 P5 多模态 Compact 验收。
3. 实时语音保持关闭，不作为本需求验收依赖。
4. P0 基线至少积累内部 Agent 的代表性文本、MCP、多模态、SubAgent 会话样本。
