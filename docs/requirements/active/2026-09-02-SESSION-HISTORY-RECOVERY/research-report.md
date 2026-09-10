# 调研与现状审计 — Session History Recovery

> 日期：2026-09-02
> 证据边界：本文把“当前代码已存在”与“r6 目标设计”严格分开。目标章节不是完成声明；截至 Batch 0，没有 SessionHistory Search/Read、V196–V198 或 durable attempt 生产实现。
> 外部资料优先引用论文、官方仓库、官方文档和本地官方 Codex 协议证据。

## 1. 结论摘要

长程 Session 的主要问题不是原始数据消失，而是有限模型视图无法按需重新定位原始事实。适合 SkillForge 的闭环是：

```text
summary + uncovered tail
        |
        +-- 足够：继续，不调用 History
        |
        +-- 缺精确事实/连续性断裂/用户指出偏移
                 SessionHistorySearch
                         |
                 SessionHistoryRead
                         |
                 基于 ORIGINAL evidence 恢复事实
```

事实恢复不需要还原整段对话。Resume、branch、restore、runtime snapshot 与 History 分工不同：resume 续接同一 timeline；branch 从过去创建新 Session；restore 是显式破坏性同 Session rewrite；runtime snapshot 恢复 Tool/Skill 控制面；History 只读当前 Session 的事实。

外部研究支持“保留 raw history、按需 locate/expand、fork 优先”的方向，但没有任何外部项目可以直接证明 SkillForge 的 restore-in-place、多来源 cursor、Tool archive 或两阶段 Tool side-effect 协议正确。r6 因 SkillForge 自身存在同 Session restore/rewrite、JPA 多表存储和大 Tool result archive，必须额外引入 epoch、immutable ref、generation fence、occurrence identity 和审计消歧。

## 2. 外部调研

### 2.1 Context as an Environment

论文 *Context as an Environment: Programmatic Context Management for Long-Horizon Agents* 的核心思想是把完整上下文留在外部环境，把有限模型窗口作为可编程工作集：先 locate，再 expand，只把当前需要的原始证据装回模型。Lossy summary 仍可作为导航，但不能成为唯一事实源。

对 SkillForge 的直接启示：

- Compact summary 继续做主工作视图，不追求无损；
- 原始消息和 Tool output 必须保留可寻址；
- Search 返回小型候选，Read 才返回授权后的精确逻辑块；
- 衡量恢复率、引用正确性、无需要调用率和 schema/token overhead，而不是只衡量摘要质量。

来源：[arXiv 2608.21690](https://arxiv.org/abs/2608.21690)

### 2.2 DeepSeek Harness 固定版本证据

核验版本为官方 `deepseek-ai/deepseek-harness` tag `dsh-v0.1.2-alpha.4`、commit `4e84901e6471b79ec0338099867ebb4606d12bb5`。该项目是 developer preview，以下只描述固定 commit，不推断未来兼容性。

官方实现可证明：

- Session 是 append-only typed event log，模型 surface、UI 和 query 是派生视图；
- Compact 记录 shadowed range/seq 并替换模型 surface，但不删除 raw events；
- resume 载入同一 Session log 并从尾部 append，不是 rewind；
- fork 从稳定 completed-turn 前缀创建新 Session，源 Session 不变；
- UI history 用 opening fixed cut + seq boundary 分页；SQLite ranked search cursor 绑定 request fingerprint + corpus generation；
- current Session 的模型搜索上界截到发起 Tool step 之前，避免本 step 自匹配；
- 模型 History plugin 是 opt-in，Provider cursor 在工具内部消费，公开 Tool surface 本身不暴露分页 cursor。

官方公开实现不能证明：

- committed-history restore-in-place；
- restore 后 seq ABA 的解决方案或 durable history epoch；
- DeepSeek 的单一派生 FTS offset/generation 能直接解决 SkillForge message/summary/archive 多来源 no-skip/no-duplicate；
- 模型 History 输出符合 SkillForge 32K/40K wire gate；
- 默认 Agent 一定会在 Compact 后调用 History。

来源：

- [Session subsystem](https://github.com/deepseek-ai/deepseek-harness/blob/4e84901e6471b79ec0338099867ebb4606d12bb5/docs/subsystems/session.md)
- [Compaction subsystem](https://github.com/deepseek-ai/deepseek-harness/blob/4e84901e6471b79ec0338099867ebb4606d12bb5/docs/subsystems/compaction.md)
- [Session query tools](https://github.com/deepseek-ai/deepseek-harness/blob/4e84901e6471b79ec0338099867ebb4606d12bb5/packages/session-query/tool-session-query/README.md)
- [SQLite query backend](https://github.com/deepseek-ai/deepseek-harness/blob/4e84901e6471b79ec0338099867ebb4606d12bb5/packages/session-query/session-query-sqlite/README.md)
- [Session persistence](https://github.com/deepseek-ai/deepseek-harness/blob/4e84901e6471b79ec0338099867ebb4606d12bb5/packages/session/session-persistence/README.md)

SkillForge 采纳 raw fact + derived surface、pre-current-step cutoff、branch-new-session 的原则；不照搬 DeepSeek 的 opt-in/cursor-free 产品选择。用户要求所有 Agent 常驻 Search/Read 和模型可见精确分页，SkillForge 因此采用 system-resident pair、snapshot cutoffs、epoch 和 opaque continuation。

### 2.3 Codex 0.152 本地官方协议证据

本地安装的官方 `@openai/codex` 0.152.0 与 app-server generated schema 表明：

- CLI 把 `resume` 与 `fork` 分开；fork 返回新 thread；
- `thread/turns/list`、`thread/items/list` 使用 thread ID + opaque cursor；
- thread item/turn 有稳定 item/turn ID 和 rollout ordinal，不只靠可复用行号；
- fork 用 `beforeTurnId`/`lastTurnId` 冻结分叉点；
- `thread/revert` 在同一 thread 中切换到新的 reverted rollout lineage；分页位置必须属于当前 lineage。

这能支持一个架构原则：发生 same-thread revert 后，cursor/ref 必须绑定 timeline lineage，不能只用 thread/session ID + ordinal。它不能证明 Codex 对 stale cursor 的长期公开错误合同，也不能当作 SkillForge migration 设计。SkillForge 没有相同 rollout lineage，因此 r6 用 `historyEpoch + immutable row ID + selector/snapshot` 达到最小等价隔离。

本地证据：`codex-cli 0.152.0`、app-server v2 schema 中 `ThreadItemsList*`、`ThreadForkParams`、`ThreadRevertParams`，以及本地 official SQLite schema。该证据仅说明 0.152 实现，不是未来兼容保证。

## 3. SkillForge 当前代码现状（Batch 0 审计）

### 3.1 当前已经具备

- `t_session_message` 保存完整 Session 消息，`SessionMessageEntity.id` 是数据库行 identity，`seq_no` 提供 Session 内顺序；
- `t_session_summary` 保存 range summary、start/end、superseded relation 和 legacy `recovery_payload`；
- message 有 Compact 覆盖关联，`CompactionService` 已有 range summary/checkpoint 持久化路径；
- `t_session_compaction_checkpoint` 已支持 checkpoint list/get/branch/restore 基础路径；
- `t_session.context_runtime_json` 与 `ContextRuntimeSnapshot` 已保存 discovered Tool/Skill refs；
- `t_tool_result_archive` 能保存大 Tool result 并给当前模型 view 使用 preview/ref；
- Task 每轮从 Task 数据库重新生成 Reminder，不依赖 summary；
- `GetSessionMessages` 能读 recent tail，但不是 query/ref/archive History API。

这些是复用基础，不代表 r6 协议已实现。

### 3.2 当前缺口与已证实风险

最终计划审查对当前代码的只读核验确认：

1. 当前 `AgentLoopEngine` 会在最终 Session persistence 之前启动/broadcast Tool work；没有 intent durable ACK 和 execution generation claim boundary。
2. 当前 interactive answer 路径只持久 selected result；同一 assistant response 中的普通 sibling 不能形成完整原序 result vector。
3. running Session 的 queued user 目前先逐条 append DB，而 Engine 后续可能把多条合成一个 `Message.user`；这不能保证与 Tool pair 的持久顺序和 byte-identical shape。
4. 当前 archive service 在 read/model-view 路径使用 `REQUIRES_NEW` lazy write，主要按 toolUseId 关联，已有 archive row 的 `session_message_id` 常为 null；同 toolUseId/different payload 存在误认领风险。
5. 当前 OpenAI-compatible conversion 会在混合 TOOL_RESULT USER message 中丢弃非 Tool-result content，说明 provider materializer 不能仅凭 role 合并 queued USER。
6. 当前 migration head 是 V195；V196–V198、`history_epoch`、inbox、tool attempt、resolution audit、archive occurrence identity 都不存在。
7. 当前 datasource/Flyway 默认都使用 `postgres` role；不能证明“runtime role 无权 UPDATE/DELETE audit”。
8. 当前 range-model summary view 没有 r6 exact envelope/cue；legacy `recovery_payload` 被保存，但不应因此直接恢复生产注入。
9. 当前 checkpoint runtime snapshot 没有按 checkpoint 版本化；restore 可能保留未来 Tool/Skill state，branch 可能拿不到 checkpoint 当时 state。
10. 当前没有系统常驻的 `SessionHistorySearch/Read`、closed current-scope schema、block/event cursor、自指永久排除或 original-first evidence order。

因此本文不能写“只需开启现有工具”。首版是跨 Core/Server/Database/Dashboard 的 Full Pipeline change，其中 durable Tool pair 是 History 自身 Tool call 可安全落库和后续检索的前置条件。

### 3.3 既有验证证据的边界

需求调研初期曾运行 Compact/Recovery 聚焦测试，记录为 120 tests pass、0 failure/error/skipped；`SessionServiceDerivedContextIT` 的 11 个 PostgreSQL/Testcontainers 场景因无 Docker 被跳过。该结果只能证明修改前基线，没有验证 r6 目标，也不能作为 migration、两实例 fencing、角色权限、History E2E 或 restore audit 的完成证据。

## 4. 冻结目标设计（尚未实现）

### 4.1 History 与 Compact

- master-on 时 Search/Read 是所有 Agent 的 system-resident readonly pair，不依赖 Tool grant；master-off direct deny 并保留 legacy tool 配置；
- current user/session 来自 SkillContext/LoopContext，schema 无 sessionId/userId 且 closed；
- Search 至少一个 locator；Read 支持 refs、seq range、around、tail、archive 五个互斥 selector；
- cursor 无 HMAC，但绑定 current Session、historyEpoch、selectorHash、projectionVersion、multi-source cutoffs/watermarks；
- public message ref 使用 immutable `SessionMessageEntity.id` 并绑定 epoch，seq 只作为排序/返回值；
- 完整逻辑块先授权投影后分页；wire `<=32K`，两工具进入低信任边界；
- History 自己的 Tool pair 永久 block-level 排除，Compact 只保留 pair-preserving placeholder；
- ORIGINAL message/archive 排在 DERIVED_SUMMARY 前；
- exact envelope 包含 summaryId/start/end 和恢复 cue，后接 byte-unchanged summary；`ContextCompactTool` 只处理 pre-intent durable prefix；五条 Full source 全覆盖。

### 4.2 Durable Tool step 与 crash semantics

- V196：history epoch + checkpoint runtime snapshot；V197：loop/fence、write batch、inbox、attempt/generation、resolution audit/continuation；V198：archive occurrence/hash；无 grant migration；
- assistant TOOL_USE intent 在任何 future/dispatch/effect 前 durable ACK；随后 execution claim CAS；最后一次提交完整原序 result vector；
- immutable originLoopId/originFence 与 mutable execution owner/loop/fence/generation 分开；旧 generation 的 heartbeat/result/archive/broadcast 全部拒绝；
- 只有明确 read-only replayable 或真正传播 idempotency key 的 Tool 可恢复执行；mutating/unknown takeover 先 fence 再进入 `UNCERTAIN_PENDING_RESOLUTION`；
- owner/admin 的 `ACKNOWLEDGE_UNKNOWN_OUTCOME` 重验 assistant/hash/manifest，原子写 exact N 个 OUTCOME_UNKNOWN、RESOLVED_UNKNOWN、inbox disposition 和 append-only audit；
- CONTINUE 通过唯一 durable `SessionRunCoordinator` claim 继续；PREPARE_RESTORE 不调用 Provider，KEEP/DISCARD 全审计并在 inbox 空之前阻止 restore；
- queued USER 以 DB acceptance order 进入 ordered inbox，pair close 后才 materialize；Provider 只可合并普通 conversational USER。

### 4.3 Archive、runtime 与 timeline

- Archive identity 是原始 TOOL_RESULT occurrence + exact toolUseId + exact length-framed scalar payload hash；raw/archive 只产生一个 canonical candidate；
- write preparation 只接受 committed occurrence；History/RepeatableRead/model-view 是纯 read；restart 在 Provider 前重试 archive preparation，失败走有界 RAW_FALLBACK；
- runtime snapshot 是 content-free registry ref，不是恢复事实；null/corrupt checkpoint snapshot fail closed empty；
- branch 默认创建新 Session，不复制 inbox/attempt/audit/parent archive；
- restore 是明确确认的 same-Session destructive operation，拒绝 unresolved/nonempty inbox，保留 resolution audit，transactionally prune + epoch bump；resume/restart 不 bump epoch。

## 5. 外部方案与 SkillForge 选择对照

| 主题 | DeepSeek/Codex 可观察选择 | SkillForge 冻结选择 | 原因 |
| --- | --- | --- | --- |
| 事实源 | append-only event/rollout | 现有 message + summary + occurrence archive | 不重建第二份权威 log。 |
| Compact | raw 保留，surface 派生 | raw 保留，summary+tail+History | 与论文 locate→expand 一致。 |
| History visibility | DeepSeek opt-in | 所有 Agent system-resident pair | 用户要求，且用 S25/schema gate控制成本。 |
| Search continuation | DeepSeek model tool cursor-free | 模型可见 opaque cursor | 用户需要精确分页；snapshot+epoch 保证延续。 |
| Timeline identity | append-only seq / rollout lineage | historyEpoch + immutable entity ID | SkillForge 存在 restore/rewrite ABA。 |
| 回到过去 | fork/new session优先 | branch 默认；restore 显式破坏性 | 保留现有产品能力并隔离低频风险。 |
| Tool crash | 外部项目不能证明本地副作用原子性 | intent ACK + generation claim + audited unknown close | SkillForge 必须保护 tool_use/result 和副作用歧义。 |
| 大 Tool result | 项目各自 spill/query | occurrence-owned canonical archive | 当前 archive 需要修复 null FK/toolUseId 复用。 |

## 6. Batch 1 特别验收条件

### W-R6-1：single-coordinator continuation

Resolution result/audit 幂等不等于 Provider continuation 幂等。实现必须把 CONTINUE 的 post-action 持久绑定到 resolutionRequestId/resultBatchId/action，并由一个 Session run/fence claim 接管。两个 HTTP retry 即使都得到存量 resolution ACK，也只能得到一个被接受的 run claim 与 drain，不得并发重复调用 Provider，且只有一个响应/assistant intent 可成为持久 transcript。若 Provider 没有稳定 idempotency contract，post-dispatch/pre-persistence 崩溃窗口不能承诺物理 HTTP exactly-once，只能按既有 provider failure/fail-stop 策略处理。这个合同及 RED 在 Batch 1 冻结，后续 durability batch 实现；缺失时 Batch 1 不通过。

### W-R6-2：真实 PostgreSQL append-only enforcement

当前 app/Flyway 同为 `postgres`，所以文档声明本身不能形成保护。Batch 1 必须采用 migrator/runtime role 分离，runtime 对 resolution audit 仅 INSERT/SELECT、无 UPDATE/DELETE/TRUNCATE，并在 Testcontainers 中证明。Restore 和 attempt pruning 必须保留 audit；显式删除整个 Session 的 FK cascade 是唯一 retention exception，并必须单独授权和测试。

## 7. 评测与完成声明边界

主评测固定 A summary-only、B summary+tail+legacy recent、C summary+tail+History no cue、D envelope+summary+tail+History。R0/R1/R2 只做正交 evaluator-only comparison；首版 production Recovery 始终关闭。

上线门包括：D/A exact recovery `+>=20pp`、refs `>=98%`、leak 0、unnecessary History `<10%`、S25 quality drop `<=2pp`、schema overhead p95 `<=min(1500 tokens,5% context window)`、HISTORY wire `<=32K` 且真实 Engine 不触发 40K truncator、100K Search p95 `<=500ms`。

在完成 V196–V198、RED→GREEN、PostgreSQL two-role/并发验证、Provider golden、Dashboard browser checks、S1–S25 和 Full review 前，需求状态只能是“目标设计/实现中”，不能声称 History recovery、durable attempt 或 production rollout 已完成。
