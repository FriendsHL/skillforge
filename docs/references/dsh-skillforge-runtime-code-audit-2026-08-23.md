# DSH 与 SkillForge Runtime 源码对照审计

> 类型：dated source audit（后续代码演进会使结论过期）
> 日期：2026-08-23
> SkillForge 基线：`main@f18f4ecaba26c0bf2b7dfb974ffea090d599bcff`
> DSH 基线：`dsh-v0.1.1-rc.2@b150a551b8d465e31e418e1b2eaf5e79bbb7d28e`
> 方法：两个源码审计 subagent 独立取证，两个 reviewer 分别从架构事实和实施收益角度挑刺，主会话裁决。
> 边界：本报告只提供事实审计和优化建议，不代表任何 Runtime 改造已经实现。

## 1. 最终结论

SkillForge **不需要按照 DSH 重建运行时骨架**，也不应新增第二套通用 Event Store、第二套 LLM Request Snapshot 或第二个 Replay 产品。

SkillForge 已经具备：

- `t_session_message` 对话持久化与 Compact 边界；
- LLM/Tool/Event Trace、跨 Session root trace 和 Dashboard Waterfall；
- 真实 Provider request body、流式聚合响应和 raw SSE Blob；
- Session Replay 页面和 API；
- Root Session、`waiting_user`、SubAgent 的启动恢复；
- Schedule、Workflow、Media、Task 各自的持久状态；
- 长期 Memory、Skill 渐进加载、MCP、多模态、Dashboard、iOS 和内部 Eval。

DSH 最值得 SkillForge 吸收的是少数**语义不变量**，而不是 Cordis 或 Event Sourcing 形态：

1. 区分状态投影、请求重建、执行重试，不把它们都叫 Replay。
2. 区分 `TOOL_NOT_STARTED` 和 `TOOL_OUTCOME_UNKNOWN`。
3. 副作用结果未知时优先对账，不能盲目重试。
4. canonical structured value 与 model-facing content 分层。
5. Compact 改变模型视图，不应破坏原始事实和稳定资产引用。
6. World Verification 属于测试/验收方法，不应成为每轮 Agent Loop 的强制步骤。

基于源码和 reviewer 裁决，当前最有用户价值的迭代顺序是：

1. 先建立真进程 kill/restart 测试矩阵，锁定现有 Root/SubAgent 行为。
2. 修复 Schedule in-flight run 在重启后失去 owner 映射、无法正确收口的问题。
3. 单独推进 Workflow running crash reconciliation。
4. 在既有 Context Governance P7 中兼容式增强 Tool Result provenance 和 MCP Artifact Bridge。
5. 把现有 Replay 与 Trace/Waterfall 打通，取消按位置猜测 Tool timing 的错误归因。
6. 在现有 Eval/Benchmark 上启动跨 Harness 五题 smoke，把 DSH 作为新 Adapter，而不是另建评测系统或把 DSH 当成架构目标。

## 2. 审计口径

### 2.1 SkillForge 状态标签

- **HEAD 已实现**：存在于 `f18f4eca` 已提交代码中。
- **工作区候选实现**：当前 dirty worktree 中存在，但尚未提交，不能视为交付事实。
- **文档规划**：仅在 active/backlog requirement package 中，不能视为代码能力。
- **运行已证明**：除代码和测试外，还有当前环境的真实运行证据。本次审计没有重新执行所有活体链路，因此不会把“代码存在”写成“线上已验证”。

当前 Team Task Graph 的 attempt/event/lease、V192 migration 和相关测试属于**工作区候选实现**。`docs/README.md` 已写成 implemented/deployed，但对应代码尚未提交；最终交付状态应以 commit 和 fresh verification 为准。

### 2.2 DSH 状态标签

DSH 整体仍是 Developer Preview。即使部分 package 标记为 stable API，也不能覆盖整个产品仍允许 breaking changes 的事实。

本报告区分：

- **源码实现**：官方仓库可以直接确认。
- **文档契约**：官方文档声明，且有实现对应。
- **审计推断**：由多个实现拼接出的能力边界，不代表 DSH 官方用语。

## 3. 四个容易混淆的概念

### 3.1 Waterfall / Trajectory

SkillForge Waterfall 是基于 `t_llm_trace/t_llm_span` 的观测时间线，包含 LLM、Tool、部分 Event、父 Session 和 SubAgent/Team 子 Trace。

DSH Trajectory 是基于 Session Event Log 的 Agent 事件账本，能够展示 request、step、assistant stream、tool、compaction 和 turn，但不是通用 APM：不包含 DNS/TLS、Provider 原始 SSE、MCP transport、数据库查询或每个 Tool pipeline stage 的独立 span。

两者关注点不同：

| 维度 | SkillForge Waterfall | DSH Trajectory |
| --- | --- | --- |
| 数据源 | OBS trace/span | Session append-only events |
| Provider raw request body | 有，Blob，best-effort | 无；只有 Harness 语义 request/header |
| raw SSE | 有，受截断/保留期限制 | 无，保存规范化 chunk |
| LLM/Tool/Event 时间轴 | 有 | 有 |
| 父子 Agent | root trace + session tree | session/team/subagent events |
| Compact/Turn/Step 语义 | 部分 Event | 更完整 |
| 通用网络/数据库 APM | 无 | 无 |

SkillForge 当前真实缺口不是“没有瀑布流”，而是 native span 的 `parent_span_id` 多数指向 trace root。页面能显示统一时间线，却不能稳定回答：

```text
哪一次 LLM invocation 产生了哪个 tool_use
哪个 tool_result 触发了哪一次后续 LLM invocation
```

修复应优先补因果关联，而不是新建事件系统。

关键 SkillForge 入口：

- [`SessionWaterfallPanel.tsx`](../../skillforge-dashboard/src/components/sessions/detail/SessionWaterfallPanel.tsx)
- [`PgLlmTraceStore.java`](../../skillforge-observability/src/main/java/com/skillforge/observability/store/PgLlmTraceStore.java)
- [`TraceLlmCallObserver.java`](../../skillforge-observability/src/main/java/com/skillforge/observability/observer/TraceLlmCallObserver.java)

DSH 入口：

- `packages/client/ui-trajectory/**`
- `packages/client/runtime/src/client/sessions/request-inspection.ts`
- `packages/core/session/**`

### 3.2 Request Snapshot

SkillForge 的 `TraceLlmCallObserver` 捕获真正交给 Provider HTTP Client 的 request body 字节，并把 request、response/stream aggregation、raw SSE 写入 Blob。Request body 中已经包含最终 system、messages 和 Tool Schema。

但它不是恢复真相源：

- 写入是异步 best-effort，失败会 drop；
- 单 Blob 有 50 MB 上限；
- Trace/Blob 默认 30 天清理；
- 最终持久化的是 body，method/URL/脱敏 header 没有一并落 Blob；
- request build 前失败只能产生最小 error span。

DSH `request/header` 持久化 `LlmCallConfig`、adapter defaults、rendered system 和 tools。Provider adapter 后续仍会做模型字段映射、图片上传/base64、credential/header 注入和 JSON 序列化，因此它只能重建 **Harness 语义请求**，不能还原真实 Provider HTTP 字节。

裁决：

- 不新增一张重复的全量 LLM Request Snapshot 表。
- 日常诊断继续使用 SkillForge request Blob。
- 被选为 Benchmark/Regression Fixture 的调用，显式提升为版本化、脱敏、可长期保留的 fixture。
- UI 必须显示 `ok/truncated/write_failed/expired/legacy`，不能把 best-effort 证据伪装成完整审计记录。

### 3.3 Replay

SkillForge 当前 [`ReplayService.java`](../../skillforge-server/src/main/java/com/skillforge/server/service/ReplayService.java) 做的是：

```text
t_session_message + t_model_usage
                ↓
turn → iteration → tool call 的历史展示投影
```

它不会重新请求模型、重新调用工具、验证外部世界，也不会在沙箱里重演副作用。

当前实现还存在归因边界：

- usage 与 turn 主要按顺序消费；
- ToolCallRecord 与 tool_use 主要按列表位置合并；
- 并行 Tool、缺失 ToolResult 或历史格式变化时可能归错 timing/status/output。

DSH 的 Session replay 同样不是重新执行。它从 append-only events 推导当前 Surface 和 `Message[]`；Telemetry replay 与 adapter replay metadata 也都不等于执行回放。

裁决：保留现有产品名和 API，文档/UI 增加语义说明，并在内部区分三层能力：

| 层级 | 含义 | 当前状态 |
| --- | --- | --- |
| Timeline Projection | 展示历史 turn/iteration/tool | 已有，需修正关联 |
| Behavior Replay | scripted/model-stub 驱动，验证 Tool/Task/目标连续性 | 有专项 fixture，未通用化 |
| Execution Replay | 固定环境/模型/预算，真实执行并用 verifier 判断 | Harness Benchmark 待实现 |

### 3.4 World Verification

World Verification 的含义是：不相信 Assistant 自己说“完成了”，而是由独立检查验证真实世界状态。例如：

- 文件修改后重新读取并运行测试；
- 发布 Artifact 后查询 DB、重新访问资源并验证 manifest；
- 浏览器提交后检查 DOM、URL 和服务端结果；
- 创建 Schedule 后检查定义，运行后检查 run/session/channel delivery；
- 声称生成 4K 图片时解码原始文件并检查真实像素。

DSH 没有通用 Runtime verifier；“verify the world, not self-report”是其测试方法。

SkillForge 的合理落点只有：

1. Full/Mid 开发 Pipeline 的 Final Verification；
2. Harness Benchmark 的官方 verifier；
3. 少数高价值写 Tool 内部的 read-after-write/reconcile receipt。

明确不做：每个 Agent Turn 强制再调用一次模型或 Tool 验证结果。

## 4. 功能模块对照矩阵

| 模块 | DSH | SkillForge HEAD | 结论 |
| --- | --- | --- | --- |
| 扩展架构 | Cordis plugin/scope/disposer | Spring DI + Skill/Tool/Hook/MCP registry | 不迁移 Cordis；P8 Descriptor 补元数据即可 |
| Agent Loop | 明确 turn/step/session events | 多轮、stream、Tool、Compact、取消、预算完整 | 保留 Loop；只补稳定关联标识 |
| Observability | Agent event trajectory | raw request/SSE + LLM/Tool/Event waterfall | 各有优势；SkillForge 不缺 Waterfall |
| Session 事实 | append-only event log | message 真源 + trace/usage/domain rows | 不增加第二 Event Store |
| Request | durable semantic header | best-effort raw provider body | 保留双用途差异，不复制 |
| Replay | Surface projection | Timeline projection +专项 fixture | 打通关联；执行重放归 Benchmark |
| Root 恢复 | repair log、关闭边界 | running/waiting_user startup recovery，最多 3 次 | SkillForge 已有，不重做 |
| Tool 未决结果 | NOT_STARTED/OUTCOME_UNKNOWN | persisted orphan fail-closed；内存 Tool 副作用可能重复 | 借鉴语义，按副作用 Tool选择性补 receipt |
| SubAgent 恢复 | provider-dependent continuation | child startup recovery、漏 finally 补投递 | 已有；真 kill 验证优先 |
| Schedule | live Session timer，冷 Session 不运行 | 服务端 Scheduler，但 in-flight owner 映射在内存 | SkillForge 产品更强；存在真实重启收口 bug |
| Workflow | worker script，冷恢复弱 | journal replay 支持人工审批 resume | running JVM crash 尚未恢复 |
| Media Job | 无视频/音频 durable runtime | DB state + lease + poll/download + SUBMIT_UNKNOWN | SkillForge 更成熟，可作为副作用恢复参考 |
| Tool contract | input/output schema、value/content 分离 | input schema + String output + artifact sidecar | 在既有 P7 兼容增强，不做全量重写 |
| Tool policy | pipeline + approval/sandbox/concurrency | 多处 hook/确认/安全分支 | P8-A Descriptor-only，Router 后置 |
| Skill | catalog + 按需 body + compact restore | ToolSearch、Skill Loader、compact restore、自进化 | 已基本对齐，SkillForge产品能力更强 |
| MCP | tools bridge、structured content、image attachment | tools bridge、管理 UI、secret masking；结果偏文本 | P7 MCP Artifact Bridge；新版协议后置 |
| Attachment | 图片 ref | 图片/视频/文档/Artifact + Dashboard/iOS | SkillForge 更强，继续现有 MULTIMODAL 包 |
| Compact | event 保留、Surface replace | Light/Full/range/checkpoint/recovery payload | 不重写，只增强 replay correlation |
| Memory | 无一等长期 Memory | FTS/vector/RRF/provenance/proposal/consolidation | SkillForge 明显更强 |
| Task/Team | Todo/Goal/实验 Team DAG | Session Task DAG；Team Task Graph dirty candidate | 先完成当前独立交付与 dogfood |
| Dashboard/iOS/Channel | 本地 Web/TUI/SDK | Dashboard+iOS+多渠道 | SkillForge 明显更强 |
| Eval/Benchmark | 强 snapshot/E2E，无公开横评 | 已有完整 Eval、Benchmark 场景和任务对比；尚不能用同一协议驱动外部 Harness | 复用现有 Eval 数据与结果模型，增加跨 Harness adapter/manifest 层 |

## 5. 恢复能力矩阵

### 5.1 已实现且应保留

#### Root Session

[`PendingConfirmationStartupRecovery.java`](../../skillforge-server/src/main/java/com/skillforge/server/init/PendingConfirmationStartupRecovery.java) 已实现：

- `waiting_user` 原样保留，不自动回答；
- user/tool_result 安全消息边界恢复；
- assistant final 尾部转 idle；
- persisted orphan tool_use 标记 interrupted/error；
- 自动恢复最多 3 次；
- Web Server 接收请求前执行启动恢复。

#### SubAgent

[`SubAgentStartupRecovery.java`](../../skillforge-server/src/main/java/com/skillforge/server/init/SubAgentStartupRecovery.java) 已实现：

- child session 缺失/未附着时取消并通知父级；
- running child 从持久消息边界恢复；
- child 已结束但 finally 丢失时补父级投递；
- roster 从 DB 重建；
- 最多恢复 3 次。

#### Media Job

Media Runtime 已把外部副作用的不确定窗口显式建模：

- submit 前先落 `SUBMITTING`；
- provider job ID 持久化后进入可轮询状态；
- polling/downloading 使用 lease；
- provider submit 结果未知时进入 `SUBMIT_UNKNOWN`，不自动重提，避免重复计费。

这比“所有 Tool 一张统一 Receipt 表”更贴合实际，应作为外部计费/发布型 Tool 的参考模式。

### 5.2 真实缺口

#### Schedule in-flight run

[`ScheduledTaskExecutor.java`](../../skillforge-server/src/main/java/com/skillforge/server/service/scheduling/ScheduledTaskExecutor.java) 使用进程内 `inFlightSessions` 将 Session finish event 关联到 scheduled run。

服务 kill 后：

1. Schedule 定义会重新注册；
2. Root Session 可能被 startup recovery 成功续跑；
3. 新 JVM 中 `inFlightSessions` 为空；
4. Session 完成事件找不到原 run context，直接忽略；
5. `t_scheduled_task_run`/task 可能长期停在 running，渠道结果无法按原 run 正确收口。

建议优先复用现有 `ScheduledTaskRunEntity.taskId/triggeredSessionId/status` 做启动对账或完成事件 DB fallback。只有现表无法保证唯一归属时，才申请最小 migration。

#### Workflow running crash

当前 journal replay 解决的是 human approval resume：重新执行脚本、用 `step_index`/journal cache 跳过已完成步骤，并检查 source hash。

它不等于 JVM crash recovery：running workflow 没有 startup reconciliation，executor Future、进程内 lock 和当前 frontier 会丢失。

该问题应并入 `TASK-RESUME-ON-RESTART` 的 Workflow phase，作为独立 Full 增量；第一版遇到未知副作用时 fail closed，不与 P7 或 Schedule 同批实现。

### 5.3 副作用 Tool

大多数 AgentLoop Tool messages 在一次 Loop 收尾时整体持久化，所以 kill 时数据库通常停留在原 user message，而不是持久化孤立 Tool Call。

真实风险是：

```text
外部副作用已发生
→ JVM 在最终消息持久化前被 kill
→ DB 仍是 user tail
→ startup recovery 重新请求模型
→ 模型再次调用同一工具
```

策略应按 Tool 分类：

| Tool 类型 | 恢复策略 |
| --- | --- |
| 纯读 | 允许 at-least-once |
| 本地幂等写 | 稳定 idempotency key |
| 外部发布/计费 | 领域 Job/Receipt + provider ID + reconcile |
| 提交结果未知 | `outcome_unknown`，查询外部世界或请求用户确认 |

不为所有 Tool 强制新建统一 Receipt 表。

## 6. Tool、MCP 与 Context 的正确归属

当前 [`SkillResult.java`](../../skillforge-core/src/main/java/com/skillforge/core/skill/SkillResult.java) 以 String output/error 为主，另有 `PublishedArtifact` sidecar。结构化结果是实际缺口，但已经被现有需求覆盖。

### 6.1 合并到 Context Governance P7

既有 [`AGENT-CONTEXT-GOVERNANCE`](../requirements/active/2026-07-26-AGENT-CONTEXT-GOVERNANCE/index.md) P7 已定义：

- `ResultProvenance`；
- 旧 Tool 默认适配；
- MCP Image/Resource → Attachment；
- 超大 JSON 有界预览；
- 稳定 Artifact ID。

建议在 P7 中 additive 增加可选 envelope：

```text
textFallback
structuredValue?      // 仅有真实程序消费者时启用
schemaVersion?
provenance
artifacts
error { code, category, retryable, suggestedAction }
```

约束：

- 保留 `SkillResult.success(String)`、`error(String)` 和现有 getter；
- legacy Tool 默认 `structuredValue=null`；
- 第一批只迁 MCP、PublishInteractiveArtifact、GenerateImage/GenerateVideo；
- 不强迫普通文本 Tool 声明 output schema；
- Artifact 身份不能因为 model-facing output 被截断而丢失；
- Provider wire shape 和 message persistence shape 必须有回归测试。

### 6.2 合并到 P8-A Descriptor-only

P8 已有 `CapabilityDescriptor` 和 sideEffect/approval/async/recoverable/media/schemaCost。第一阶段只交付 Descriptor 与 observation，不启用意图 Router：

```text
sideEffect
approval
recoverable
idempotency
async
media
schemaCost
```

只有 Schedule/Workflow/P7 出现明确消费者后再实施；Level 3 Router 继续 Shadow/延期。

### 6.3 MCP

P7 先解决有直接用户价值的内容边界：

- 保留 structured content；
- MCP 图片导入受管 Attachment；
- Session 只保留稳定引用，不写 base64/临时 URL；
- ownership、MIME、size、provider URL 做服务端校验。

MCP Resources、Prompts、Sampling、Elicitation 和新版协议兼容属于后续独立增量，优先级低于恢复和 Benchmark。

## 7. Replay 与 Observability 的最小增强

不新建 Replay 表，不重命名外部 API，不让 OBS 成为恢复强依赖。

第一阶段仅做 projection/service 层增强：

1. Replay DTO 增加可空 `traceId/spanId/toolUseId/blobStatus`。
2. 使用已有 Message trace ID、LLM Span iteration/toolUse 字段和 OBS 查询关联，停止纯位置归因。
3. 老历史无法关联时显示 `partial/legacy`，不伪造完整结果。
4. Replay 中点击 LLM iteration 可跳转到 Waterfall/Request Blob。
5. Waterfall 增加 caused-by link，不能滥用 `parentSpanId` 表示非嵌套因果。
6. Compact、Schedule fire、recovery claim、父子交付逐步补 Event Span，但保持 OBS best-effort。

只有现有列无法建立稳定关联，并由查询/测试证明后，才评估最小 migration。

## 8. 现有 Eval 的跨 Harness 扩展与 World Verification

SkillForge 已经具备 Eval Dataset/Version、单轮与多轮 Scenario、沙箱执行、Judge/Behavioral Oracle、Task/Item、Trace 关联、A/B Eval、失败归因、任务对比和 Dashboard；现有系统也能执行标记为 benchmark 的场景。因此这里不是“建立评测能力”，更不新建第二套 Eval。

当前缺口是：`EvalTaskController` 和 `ScenarioRunnerTool` 只接受 SkillForge `agentId/AgentDefinition`，最终调用内部 `AgentLoopEngine`；它们不能用同一运行协议启动 DSH、Claude Code、Codex CLI 等外部 Harness，也还没有跨 Harness 的环境封装和统一 Run Manifest。

因此复用现有 [`HARNESS-BENCHMARK-COMPARISON`](../requirements/active/2026-07-29-HARNESS-BENCHMARK-COMPARISON/index.md)，把它定位为现有 Eval 的**跨 Harness Adapter 扩展**：场景、数据集、结果展示和尽可能多的指标继续复用，只补外部运行适配、公平性配置、官方 verifier 桥接和可复现 manifest。

现有准入条件中的 Context Governance P1–P6 已基本交付，可以先启动 Phase 0：五题 smoke，而不必等待 P7/P8 全部完成。

建议 Adapter：

```text
SkillForge HEAD baseline
DSH Minimal
DSH Standard Native
Claude Code
Codex CLI
```

DSH Code Mode 在 P7、Sandbox 和基线数据完成前不进入首批。

每次 Run Manifest 固定：

- harness/version/commit/config hash；
- provider/model/reasoning parameters；
- system/tool schema revision；
- environment/image/network policy；
- token/cache/cost/latency/loop/tool metrics；
- artifact hashes；
- verifier version/result；
- infrastructure failure category。

World Verification 使用公开 benchmark 官方 verifier 或独立确定性检查，不接受 Assistant final text 作为通过证据。

## 9. 不采纳项

以下建议经双 reviewer 挑刺后明确不采纳：

- 新建 `HARNESS-RUNTIME-V2` 大需求包；
- 默认新建 `t_agent_run_event` 或第二套 Event Store；
- 重复保存所有 LLM Request Snapshot；
- 新建另一个 Replay 产品或破坏现有 Replay API；
- 把 World Verification 强制塞进每轮 Agent Loop；
- 首期强制所有 Tool output schema、structured value 和 presentation；
- 因为 DSH 使用 Cordis 而重写 Spring DI；
- 统一 Job Runtime 大改；
- 默认启用 DSH Code Mode；
- 当前单实例阶段直接实施多实例通用 lease/claim ledger；
- 把 iOS 做成完整 Trace/Replay 管理后台。

## 10. Reviewer 争议与主会话裁决

### 10.1 Event Store / Recovery Ledger

两个 reviewer 都反对第二套通用 Event Store，但对恢复账本有分歧：

- 实施 reviewer：当前单实例先复用现有领域表，修 Schedule/Workflow，不新增账本。
- 架构 reviewer：现有 Message/Trace/Usage 不能承担可靠恢复真相源，应保留专用 execution claim/receipt/recovery ledger，以防未来多实例重复派发。

裁决：

1. 当前不建设通用 Event Store。
2. 当前单实例 MVP 先用真 kill E2E 验证现有 Root/SubAgent，并修复 Schedule/Workflow。
3. 领域已有外部副作用 ID 时优先复用领域表和 idempotency key。
4. 只有出现多实例部署、真实副作用重复事故或现有表无法表达 `outcome_unknown` 时，才启动 `TASK-RESUME-ON-RESTART` 的 ledger/receipt 后续阶段。
5. 先校准该需求包中“单实例 MVP”和“多实例第一版”的冲突文案，避免把远期设计误认为当前承诺。

### 10.2 P0 范围

架构 reviewer 建议先统一 Recovery Coordinator；实施 reviewer 建议 Schedule 优先、Workflow 次之。

裁决：当前部署与用户明确范围是单实例 kill recovery，因此不先做统一多实例 Coordinator：

- Test harness 先覆盖 Root/SubAgent/Schedule/Workflow。
- Schedule 独立 Full 增量。
- Workflow 独立 Full 增量。
- Root/SubAgent 只有测试暴露问题时才修改。
- 多实例 Coordinator 延期。

### 10.3 Structured Tool Result

裁决：接受“兼容增强”，拒绝“全量重写”。结构化 value 只在 MCP、Workflow、Artifact/Media 等有真实消费者的地方使用；普通 Tool 继续允许纯文本结果。

## 11. 推荐实施路线

### Phase 0：文档与测试基线

归属：`TASK-RESUME-ON-RESTART`、OBS、Harness Benchmark。

- 校准恢复需求包 Current MVP / Deferred ledger 的矛盾。
- 说明现有 Replay 是 Timeline Projection，不重新执行。
- 建立真进程 kill harness。
- 覆盖 Root user-tail、paired tool-result、waiting_user、SubAgent running、Schedule running、Workflow running。
- World verifier 检查数据库状态、消息配对、外部 effect counter，而不是 Assistant 文本。

退出门：Root/SubAgent 现状有 fresh evidence；Schedule/Workflow 缺口能稳定复现。

### Phase 1：Schedule in-flight 收口

归属：`TASK-RESUME-ON-RESTART` 独立 Full 增量。

- 启动时重建 running run → triggered session 关联，或完成事件按 sessionId 查询 DB fallback。
- 明确 one-shot、cron、manual fire 的重启语义。
- 保证 run 只终结一次。
- 明确恢复后的渠道投递策略。

退出门：真 kill 后 run/task/session/channel 状态一致，无并发重复 fire。

### Phase 2：P7-A Result Envelope

归属：Context Governance P7，独立 Full 增量。

- Result provenance。
- 可选 structured value/schema version。
- 稳定 artifact identity 与 text truncation 解耦。
- Legacy adapter。
- MCP/Artifact/Media 三类代表 Tool 回归。

退出门：旧 Tool wire shape 不变；新 Tool 可以被 Workflow/跨端可靠消费。

### Phase 3：Replay ↔ Waterfall

归属：OBS follow-up。

- DTO 增加可空关联 ID 和证据完整度。
- 移除位置猜测的 Tool timing 合并。
- LLM→Tool caused-by link。
- Request Blob 深链。

退出门：并行 Tool、缺失 ToolResult、legacy history 不发生错误归因。

### Phase 4：Workflow crash recovery

归属：`TASK-RESUME-ON-RESTART` Workflow phase，独立 Full 增量。

- 启动 reconciliation。
- source hash/frontier/journal 校验。
- 已完成 step 不重复执行。
- 未决副作用进入 interrupted/outcome_unknown。
- 真 kill E2E。

退出门：已完成步骤不重跑；未知副作用不自动重放；状态可解释。

### Phase 5：跨 Harness Adapter smoke

归属：Harness Benchmark 现有需求包；它是现有 Eval 的扩展，不是新评测系统。

- 从现有 Dataset 选择五题，固定模型、预算和环境。
- SkillForge/DSH Minimal/DSH Native/Claude Code/Codex CLI。
- 复用现有 Task/Item、失败归因和 Dashboard；新增 Harness Adapter、官方 verifier bridge、Run Manifest。

退出门：结果可复现，报告从 manifest 自动重算。

### Phase 6：P7-B/P8-A

只有前述阶段出现真实消费者后推进：

- MCP Artifact Bridge；
- Descriptor-only；
- Provider conformance suite；
- Router 保持 Shadow。

## 12. 验收原则

所有后续实现必须遵守：

1. 不以单元测试替代真进程 kill/restart。
2. 不以 Assistant 自报完成替代外部 verifier。
3. 不把 OBS 写入失败升级为主流程失败。
4. 不让新的 Tool Result 形状破坏 `tool_use/tool_result` pairing 和 message JSON shape。
5. 不混合 Schedule、Workflow、P7、OBS 多个 Full red-light 改动。
6. 每期必须有独立 feature flag 或兼容回滚路径。
7. 当前 dirty Team Task Graph 必须独立收尾，不能混入上述任何增量。

## 13. 主要代码与需求证据

SkillForge：

- [`AgentLoopEngine.java`](../../skillforge-core/src/main/java/com/skillforge/core/engine/AgentLoopEngine.java)
- [`Tool.java`](../../skillforge-core/src/main/java/com/skillforge/core/skill/Tool.java)
- [`SkillResult.java`](../../skillforge-core/src/main/java/com/skillforge/core/skill/SkillResult.java)
- [`TraceLlmCallObserver.java`](../../skillforge-observability/src/main/java/com/skillforge/observability/observer/TraceLlmCallObserver.java)
- [`ReplayService.java`](../../skillforge-server/src/main/java/com/skillforge/server/service/ReplayService.java)
- [`PendingConfirmationStartupRecovery.java`](../../skillforge-server/src/main/java/com/skillforge/server/init/PendingConfirmationStartupRecovery.java)
- [`SubAgentStartupRecovery.java`](../../skillforge-server/src/main/java/com/skillforge/server/init/SubAgentStartupRecovery.java)
- [`ScheduledTaskExecutor.java`](../../skillforge-server/src/main/java/com/skillforge/server/service/scheduling/ScheduledTaskExecutor.java)
- [`WorkflowRunnerService.java`](../../skillforge-server/src/main/java/com/skillforge/workflow/WorkflowRunnerService.java)
- [`AGENT-CONTEXT-GOVERNANCE`](../requirements/active/2026-07-26-AGENT-CONTEXT-GOVERNANCE/index.md)
- [`TASK-RESUME-ON-RESTART`](../requirements/backlog/TASK-RESUME-ON-RESTART/index.md)
- [`HARNESS-BENCHMARK-COMPARISON`](../requirements/active/2026-07-29-HARNESS-BENCHMARK-COMPARISON/index.md)
- [`MULTIMODAL-MEDIA-RUNTIME`](../requirements/active/2026-07-22-MULTIMODAL-MEDIA-RUNTIME/index.md)

DSH 官方源码与文档：

- <https://github.com/deepseek-ai/deepseek-harness/tree/b150a551b8d465e31e418e1b2eaf5e79bbb7d28e>
- <https://github.com/deepseek-ai/deepseek-harness/blob/b150a551b8d465e31e418e1b2eaf5e79bbb7d28e/docs/architecture.md>
- <https://github.com/deepseek-ai/deepseek-harness/blob/b150a551b8d465e31e418e1b2eaf5e79bbb7d28e/docs/subsystems/session.md>
- <https://github.com/deepseek-ai/deepseek-harness/blob/b150a551b8d465e31e418e1b2eaf5e79bbb7d28e/docs/subsystems/tools.md>
- <https://github.com/deepseek-ai/deepseek-harness/blob/b150a551b8d465e31e418e1b2eaf5e79bbb7d28e/docs/subsystems/persistence.md>
- <https://github.com/deepseek-ai/deepseek-harness/blob/b150a551b8d465e31e418e1b2eaf5e79bbb7d28e/docs/subsystems/compaction.md>
- <https://github.com/deepseek-ai/deepseek-harness/blob/b150a551b8d465e31e418e1b2eaf5e79bbb7d28e/docs/testing.md>

## 14. 报告状态

本报告已经完成：

- DSH 源码审计；
- SkillForge HEAD/dirty 状态拆分；
- 架构事实 reviewer 挑刺；
- 实施收益 reviewer 挑刺；
- 主会话争议裁决。

本报告没有授权或实施任何 Runtime、数据库、Tool、Replay 或恢复代码改动。后续应先由用户确认推荐路线，再分别进入对应需求包的 Full pipeline。
