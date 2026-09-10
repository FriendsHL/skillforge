# 技术设计 — Session History、Durable Tool Attempt 与 Compact Recovery

> 状态：`BATCH0_ACCEPTED` 的冻结目标设计；本文描述待实现协议，不代表当前代码已经具备这些能力。
> 设计基线：`/tmp/session-history-plan-r6.md`，SHA-256 `29f87c8d288ed130bbe3e06ab4f003c9b1fd65588053819b54ff1d611338acbf`。
> 最终计划审查：`PASS_WITH_WARNINGS`；W-R6-1、W-R6-2 是 Batch 1 的强制验收条件。

## 1. 目标边界

首个生产版本同时交付以下闭环：

1. active summary 前的确定性 checkpoint envelope、原始 summary 正文和未覆盖尾部；
2. 所有当前及未来 Agent 的系统常驻只读工具 `SessionHistorySearch`、`SessionHistoryRead`；
3. 当前 Session 的服务端可信 scope、`history_epoch`、不可变 ref、snapshot cursor、授权投影和低信任 wire；
4. Tool step 的 intent-before-effect 两阶段持久化、execution generation/fence、ordered inbox 和 crash fail-stop；
5. raw TOOL_RESULT occurrence 与 occurrence-owned archive 的唯一 canonical evidence；
6. checkpoint runtime snapshot、默认 branch-new-session 和显式 destructive restore-in-place；
7. A/B/C/D 评测和正交的 R0/R1/R2 evaluator-only variants。

不在首版生产范围内：RecoveryManifest/Attachment 注入、provider one-shot recovery、向量检索、跨 Session History、第三个 `FactRecover` 工具。`resume`/restart 只重载同一时间线；`branch` 创建新 Session；`restore` 才会破坏性改写同一 Session。

唯一配置项为：

```yaml
skillforge.session-history.enabled: false
skillforge.session-history.checkpoint-envelope-enabled: false
skillforge.session-history.search-index-enabled: false
skillforge.session-history.max-provider-wire-chars: 32000
skillforge.session-history.recovery-eval-enabled: false
skillforge.compact.recovery.enabled: false
```

不存在 `search-enabled`、`dashboard-enabled`、cursor HMAC、per-Agent History grant 或 Tool grant migration。Envelope 的 effective 条件必须是 master enabled 与 envelope enabled 同时为 true。Master-off 保持旧 `GetSessionMessages` 配置语义；master-on 要求 row store、durable attempt writer 和 inbox 都可用，legacy-only `messages_json` Session 在完成校验回填前 fail closed。

## 2. 总体架构

```text
trusted SkillContext/LoopContext
             |
             +--> system-resident Search/Read --> authorized projection --> <=32K HISTORY wrapper
             |              |                         |
             |              +--> message/summary ----+
             |              +--> canonical archive --+
             |
LLM response | assistant TOOL_USE candidate
             v
      intent transaction + durable ACK
             |
      execution-generation claim CAS
             |
      tool execution / interactive control
             |
      complete ordered TOOL_RESULT transaction + durable ACK
             |
      occurrence archive preparation
             |
      ordered inbox drain --> Compact/next provider call

checkpoint --> runtime snapshot --> branch(new Session) / restore(same Session + epoch bump)
```

Core 通过不依赖 JPA 的 `ConversationDurabilitySink` 使用这些能力；Server 通过 `JpaConversationDurabilitySink`、`SessionOrderedMessageWriter`、`SessionToolAttemptService`、`SessionRunCoordinator` 和共享 `PersistedMessageCodec` 实现。所有多表写入放在 public Service transaction 中；任何 Session/attempt lock 都不得跨 Tool、Provider 或其他外部 I/O。

## 3. 系统常驻 History 能力

### 3.1 注册、兼容与可信 scope

- master-on：Search/Read 作为一个不可拆分的系统工具对所有 Agent 可见，不依赖 `tool_ids`、`required_tool_ids`、custom Agent 或 deferred-tool 状态；
- master-off：两工具都不可见且 direct dispatch 被拒绝；
- master-on 时旧 `GetSessionMessages` 不与新工具共同对模型暴露；旧 Bean/API 可内部转调 `read(tail=N)` 维持兼容；
- `SessionList` 继续负责 Session 发现，跨 Session 访问不进入 History 域；
- 不修改现有或未来 Agent 的 grant 数据，不增加 V197 grant migration。

`CurrentSessionHistoryScope` 只能由 `SkillContext.userId/sessionId`、`LoopContext` 和当前 durable attempt 构造。Search/Read 的 LLM JSON schema 不含 `sessionId`、`userId`，使用 `additionalProperties:false`。执行侧 `HistoryToolInputValidator` 在 repository 访问前再次按 closed allowlist 校验 raw map，拒绝未知/嵌套字段以及所有 identity 拼写变体。缺失可信 scope、owner 不符或越权 direct dispatch 均返回不泄漏资源存在性的错误。

### 3.2 Search 输入

允许字段仅为：

```text
query, seqFrom, seqTo, roles, kinds, toolName, toolUseId,
compacted, summaryState, limit, cursor
```

首次请求必须至少提供一个实际 locator/filter，不能只给 `limit`。`seqFrom <= seqTo`；role/kind 是有界 closed enum；continuation 必须重复同一个 canonical selector，不能增删或修改过滤器。

Search 在 owner-scoped PostgreSQL `REPEATABLE_READ` snapshot 中捕获 `maxMessageId/maxMessageSeq`、`maxSummaryId`、`maxArchiveRowId`，并受当前 attempt 的 pre-intent frontier 限制。多来源分页使用各来源的完整 keyset watermark、exhausted/buffer/scan-budget 状态；单页扫描和总扫描均有界。Summary active/superseded 判断固定在 `maxSummaryId as-of`。

### 3.3 Read selector union

Read 恰好选择一个 selector arm：

1. `refs`：1..50 个 message/summary/archive ref，按调用方顺序返回，后续重复 ref 去重；
2. `seqFrom + seqTo`：两者同时提供，按持久事件顺序升序读取；
3. `aroundSeq + before + after`：读取中心前后有界窗口并升序返回；
4. `tail`：按 snapshot cutoff 选最后 1..50 个 eligible event，再升序返回；
5. `archiveRef + offset + maxChars`：单个 archive/block 的精确分页。

`cursor` 不是第六个 arm，只能与生成它的原 selector 一起使用。`refs+tail`、半个 seq range、archive 与 refs 混用、around 与 range 混用、cursor 下修改 selector/budget，均在查询前返回结构化 validation error。Event 默认 20、硬上限 50；单响应投影正文默认 8,000、硬上限 20,000 字符。

### 3.4 Timeline identity、ref 与 cursor

`history_epoch` 是同一 Session 时间线的 durable generation：普通 append、intent/result commit、inbox drain、range Compact、resume/restart 不递增；任何 destructive same-Session rewrite/restore 在最低层共享事务中递增一次。Branch 获得新 Session scope，不修改 parent epoch。

```text
msg:e{epoch}:id{SessionMessageEntity.id}:block{blockIndex}
summary:e{epoch}:id{SessionSummaryEntity.id}
archive:e{epoch}:id{archiveId}
```

`seqNo` 只用于排序和返回，不是 public identity。Read 每次重新校验 owner、服务端 current Session、epoch 和 immutable occurrence。旧 epoch 返回 `HISTORY_STALE`；当前 scope 下未知/越权 ref 返回非枚举式 `INVALID_REF`。Restore 后 seq 重新增长形成的 ABA 必须由 epoch + immutable ID 拦截。

Cursor v1 是无 HMAC 的 opaque/versioned canonical JSON + base64url。正确性只承诺服务端原样返回的 token；cursor 不是授权凭据。每次使用仍校验 current Session scope、`historyEpoch`、`selectorHash`、`projectionVersion`、snapshot cutoffs 和 source watermarks。编码长度上限 4096。

- `BlockReadCursorV1`：archive 或单个 oversized block，绑定 canonical target、完整授权正文 hash、Unicode code-point offset；
- `EventReadCursorV1`：refs/range/around/tail，绑定 ordered-event manifest hash、event/block/character position 和 keyset watermark；
- 两种 discriminant 不能互相解码；malformed、scope、selector、epoch、projection、content change 各有明确错误；
- 当前 Tool step 完成后新 append 位于 snapshot cutoffs 之外，不会让原 cursor 失效；restore 会使旧 cursor `HISTORY_STALE`。

### 3.5 授权投影、低信任 wire 与证据顺序

对完整逻辑块先执行确定性的 `HistoryAuthorizedProjection.VERSION`，再按 Unicode code point 分页。投影允许授权 USER/ASSISTANT text、普通 TOOL_USE input、普通 TOOL_RESULT content/error、summary 和 canonical archive；排除 hidden system/developer prompt、reasoning、runtime/control/internal metadata。结构化 JSON 递归做 secret-key redaction；plain text/JSON scalar 只做有界 credential redaction。只返回 `authorizedContentHash`，raw/canonical storage hash 不进入 DTO、cursor、error 或日志。

Search/Read 输出使用 `PromptSourceType.HISTORY -> STORED_DATA`，并由 `ToolResultTrustClassifier` 将两工具置于低信任边界。完整 DTO 经 Spring `ObjectMapper` 序列化并包裹真实 `LowTrustContextBoundary` 后必须 `<=32000` 字符，低于 `ToolResultTruncator` 的 40K 上限。

默认证据顺序为：

```text
ORIGINAL(message/archive) before DERIVED_SUMMARY,
then logicalSeq DESC, createdAt DESC, sourceOrder, stableRef ASC
```

因此 `limit=1` 也不能让 summary 覆盖原始证据；原始证据冲突时采用较新的 seq，并向模型暴露冲突。

### 3.6 永久自指排除

History 自己的 TOOL_USE/TOOL_RESULT 是派生检索痕迹，不是历史候选：

- Search 在 block 枚举前移除 Search/Read tool-use 和 exact toolUseId 配对 result；
- 猜测这些 block ref 的 Read 返回 `INVALID_REF`；
- archive resolver 在 candidacy 前执行相同规则；
- Full Compact 的 summarizer projection 用 pair-preserving placeholder 替换两侧输入/正文，保留 toolUseId/name 和普通 sibling block；
- current-step pre-intent cutoff 与永久排除是两道独立防线。

## 4. 两阶段 Durable Tool Attempt

### 4.1 迁移冻结

当前 migration head 是 V195。首版只使用 V196–V198：

- **V196**：`t_session.history_epoch BIGINT NOT NULL DEFAULT 0`；checkpoint 增加 nullable `runtime_snapshot_json`；
- **V197**：Session loop/fence/lease/`restore_preparing`，message write batch identity，ordered inbox，tool attempt、execution generation/claim lease、resolution audit，以及 W-R6-1 的 durable continuation claim shape；
- **V198**：archive occurrence/hash identity、约束和索引；
- 不存在 Tool grant migration。

V197 的核心字段：

- Session：`active_loop_id`、`loop_fence`、`loop_owner_instance_id`、`loop_lease_until`、`restore_preparing`；
- Message：`write_batch_id`、`write_batch_ordinal`，部分唯一 `(session_id,write_batch_id,write_batch_ordinal)`；
- Inbox：`id BIGSERIAL`、唯一 `inbox_id`、Session FK、user、exact `message_json`、`created_at`，索引 `(session_id,id)`；
- Attempt immutable origin：`step_id`、`history_epoch`、`origin_loop_id`、`origin_fence`、assistant immutable ID/hash、pre-intent frontier、ordered manifest；
- Attempt mutable execution：`execution_loop_id/fence/owner/generation`、`claim_request_id`、claim/lease time、state、result batch/generation/fence、archive preparation state/count；
- 每个 Session 最多一个 `INTENT_COMMITTED|EXECUTING|WAITING_USER|UNCERTAIN_PENDING_RESOLUTION` attempt；
- Resolution audit：唯一 `resolution_request_id`、Session、scalar attempt ID（不对 prunable attempt 建 FK）、step/epoch/generation/fence、server-derived actor、time、length-framed reason hash、action、inbox disposition、result batch、outcome state；不保存 reason、inbox body 或 Tool payload正文。

W-R6-1 的 continuation 采用 attempt 上的 durable fields：`post_action_state`、`post_action_resolution_request_id`、`post_action_result_batch_id`、`post_action_kind`、`post_action_claim_request_id`、`post_action_loop_id/fence`。`CONTINUE_CURRENT_TIMELINE` resolution transaction 将其置为 PENDING；只有 `SessionRunCoordinator` 能用 Session lock 和 loop/fence CAS 转成 CLAIMED。相同 claim request 返回同一 ACK；其他 HTTP retry 只能读取状态，不能自行 drain/provider。CLAIMED 后的 crash 由同一 run-fence recovery 接管；provider/next durable intent handoff 后转 COMPLETED。PREPARE_RESTORE 不创建 continuation claim。

### 4.2 Intent transaction

在任何 `toolStarted`、future、direct dispatch、confirmation registration、`ContextCompactTool`、网络/文件/进程副作用之前：

1. lock 并重新授权 Session owner、loop/fence/owner/lease；
2. 验证 epoch、expected frontier 且不存在 unresolved attempt；
3. 预分配稳定 stepId 和 writeBatchId；
4. 写入 exact assistant message 与 attempt，记录 immutable origin、pre-intent frontier、ordered manifest，execution generation 初值 0；
5. 重新 decode 持久行并返回 immutable row/seq/content ACK。

相同 ID 的重试只有在 byte-identical 且完整时返回同一 ACK；partial/mismatch 是 corruption。Engine 用 ACK object 替换 candidate object，随后才允许 durable intent broadcast。Intent transaction 失败意味着零 execution、零 future、零 durable broadcast。

### 4.3 Execution claim CAS

初始、interactive answer 和 takeover 共用一个 transactionally locked CAS：

1. caller 预分配稳定 `claimRequestId`，identity 来自 server instance；
2. Session 后 attempt 的固定 lock order，使用 DB time 校验 lease、epoch、owner 和 expected state/generation；
3. 初始 claim 将状态改为 EXECUTING，写 execution loop/fence/owner，generation 只增加一次；
4. 相同 `claimRequestId+owner+loop+fence` 的 ACK-loss retry 返回原 ACK，不增加 generation；
5. 不同 owner 只有在 DB-time lease 过期且 replay safety 通过后才可 G→G+1；已 EXECUTING 的 safe attempt 也必须显式 CAS；
6. unsafe takeover 先增加 generation/fence 使旧 executor 失效，再在同一个 CAS 中写 `UNCERTAIN_PENDING_RESOLUTION`；
7. heartbeat、tool event、idempotency binding、result batch、archive preparation 全部绑定 attempt+step+generation+executionFence+owner；旧 generation 不得 broadcast、archive 或关闭 attempt。

ReplaySafety 仅由服务端 descriptor/adapter 决定：`READ_ONLY_REPLAYABLE`、真实下游去重的 `IDEMPOTENT_KEYED`、`MUTATING`、默认 `UNKNOWN`。混合 attempt 继承最不安全分类；模型声明无效。

### 4.4 Result transaction 与 archive-before-continuation

普通 attempt 只允许 `EXECUTING -> RESULTS_COMMITTED`：

1. 一次提交 manifest 中全部 N 个 result，toolUseId 和 ordinal 与 assistant intent 完全一致；
2. 校验当前 epoch、attempt/step、winning generation/fence/owner、assistant hash/manifest；
3. 同一 transaction 写完整有序 result batch 并关闭 attempt；
4. 相同 batch 的 ACK-loss retry 返回 byte-identical ACK；partial/mismatch fail closed。

Result ACK 后才可把 ACK objects 加回 Engine、broadcast、准备 archive、drain inbox、Compact 或调用下一个 LLM。Restart 看见 `RESULTS_COMMITTED` 时必须先对 winning occurrence 调用 `OccurrenceArchivePreparation.ensurePrepared`，最多三次有界幂等重试；持续失败记录 `RAW_FALLBACK`，只把 exact raw result 交给现有 request budgeter，然后才可继续。此路径绝不重跑 Tool。

### 4.5 Interactive full-vector

`InteractiveStepPlanner` 在 intent commit 和任何 side effect 前遍历整条 assistant response：

- 若含 ask/confirmation，选择 provider ordinal 最早的一项作为唯一 control；所有 sibling，包括前置普通 Tool、History、Compact 和其他 interactive，均执行零次；
- atomically 写 assistant intent、WAITING_USER attempt 和 provider-filtered control；
- 回答/approve/deny/timeout/cancel/supersede 先走同一个 generation claim CAS；
- selected call 产生所选结果，每个 sibling 产生 `ABORTED_FOR_INTERACTIVE_CONTROL`；
- 按原 provider ordinal 一次提交完整 N-result vector 后才可继续。

Approved mutating confirmation 在 effect 后 crash，按普通 mutating attempt 进入 `UNCERTAIN_PENDING_RESOLUTION`，不能自动重放。

### 4.6 Ordered queued-user inbox

API executor submission 只表示 `scheduled`。Canonical order 是取得 Session lock 并 commit 的 DB acceptance order，不宣称 HTTP arrival order：

- idle：claim loop/fence，写 exact USER row，commit 后启动 Provider；
- live：写入独立 exact `Message.user` JSON inbox row，不能插入 TOOL_USE/RESULT pair；
- expired lease：先写 inbox，再调度 fenced recovery；
- drain 仅在不存在 unresolved attempt、最后 pair durable closed 后按 inbox `id` 执行；每条 USER 作为独立 contiguous raw row写入并删除对应 inbox row。

Provider materializer 只可临时合并相邻的普通 conversational USER。TOOL_RESULT、control、attachment、summary、hidden context、reasoning、内部 metadata 都是硬边界。Claude、OpenAI-compatible、DeepSeek golden 必须证明 queued text 恰好出现一次且不破坏 Tool pair。

Final reconciliation 只校验 ACK rows，或通过 ordered writer 补一个未 ACK 的普通 terminal assistant；不得 DELETE+INSERT、重编号、补 partial attempt、bump epoch 或把 in-memory Tool outcome 当成 durable。

## 5. Unknown outcome 的审计消歧

状态机核心为：

```text
INTENT_COMMITTED / EXECUTING
        | unsafe takeover, generation/fence invalidated first
        v
UNCERTAIN_PENDING_RESOLUTION
        | ACKNOWLEDGE_UNKNOWN_OUTCOME
        v
RESOLVED_UNKNOWN
```

`UNCERTAIN_PENDING_RESOLUTION` 对自动执行是终点，但仍是 uniqueness/admission/Compact/provider/restore 的 unresolved barrier。只有专用命令可以离开该状态；普通 result commit 不得伪装此转换。

```text
POST /api/sessions/{sessionId}/tool-attempts/{attemptId}/resolve-unknown
{resolutionRequestId, expectedHistoryEpoch, expectedExecutionGeneration,
 expectedExecutionFence, action, reason, inboxDispositions}
```

- actor 只来自 authentication；授权为 Session owner 或显式 `session:resolve-unknown` admin；
- closed request、bounded reason；audit 只保存 reason hash；跨 owner/stale 返回非枚举式错误；
- transaction lock Session + attempt，重读 immutable assistant/hash/manifest；
- 按原 toolUseId/ordinal 写恰好 N 个 `is_error=true,error_type=OUTCOME_UNKNOWN` result；固定正文明确“操作可能已成功，禁止自动重试”；
- result vector、RESOLVED_UNKNOWN、inbox disposition、resolution audit 和 continuation/restore action 在一个 transaction 中；
- 同一个 resolutionRequestId 在 double-click 或 ACK loss 后返回同一 ACK；第二个 request、late executor、stale epoch/generation/fence 全部拒绝。

Post-action：

- `CONTINUE_CURRENT_TIMELINE`：全部 inbox 必须 `KEEP_FOR_CONTINUE`；resolution transaction 只创建一个 durable PENDING continuation，随后由 `SessionRunCoordinator` 唯一 claim，按 result ACK/broadcast → archive preparation/raw fallback → inbox drain → provider 的顺序推进；duplicate/ACK-loss 不得产生并发重复 drain/provider，且只有 winning claim 的一个响应/assistant intent 可进入持久 transcript；
- `PREPARE_RESTORE`：设置 `restore_preparing=true`，不调用 Provider、不 drain；每个 inbox item 显式 KEEP 或 DISCARD，discard 保留 ID/hash/actor/reason/action audit。KEEP 继续阻止 restore；后续 `prepare-restore-inbox` 命令使用相同授权、CAS、idempotency 和 append-only audit，清空后 restore 才合法。

Dashboard 必须显示 tool name/input、“可能已成功”、禁止自动重试、当前 inbox 和 action 后果，并要求明确确认。Generic cancel 不能映射到此 API。

### 5.1 W-R6-1 — Batch 1 验收条件

Batch 1 必须把上述 durable continuation fields/constraints、`SessionRunCoordinator` claim contract 和 RED fixture 冻结为可执行接口。验收 oracle 不只是“一条 audit/一个 result batch”，还必须证明：两个 HTTP retry 都收到同一个 resolution ACK 时，只有一个 run claim 与 inbox drain 被接受、没有并发重复 Provider continuation，且只有一个 Provider 响应/assistant intent 可进入持久 transcript；crash 后只能由 winning loop/fence 接管。Provider 未提供并传播稳定 idempotency key 时，本地 CAS 不承诺 post-dispatch/pre-persistence 窗口的物理 HTTP exactly-once，该窗口使用既有 provider failure/fail-stop 策略。

### 5.2 W-R6-2 — Batch 1 验收条件

Append-only audit 采用明确的 PostgreSQL 角色分离：

- Flyway/DDL 使用 owner/migrator role；应用运行时使用非 owner `skillforge_app` 等价 role；
- runtime role 只获得 audit table/sequence 的 INSERT、SELECT 所需权限，显式撤销 UPDATE、DELETE、TRUNCATE；
- restore、attempt pruning 和普通业务写均不得更新/删除 audit；attempt ID 为无 FK scalar，避免 pruning 触发 audit mutation；
- `session_id ON DELETE CASCADE` 只允许在显式删除整个 Session 时作为唯一 retention exception，必须经过现有 Session-delete 授权和独立测试；
- 默认应用/Flyway 都使用 `postgres` 的现状不能作为验收配置。Testcontainers 必须创建 migrator/runtime 两个角色，证明 runtime direct update/delete/truncate 失败、restore 保留 audit、whole-Session delete 是唯一例外。

未同时满足 W-R6-1/W-R6-2，不得标记 Batch 1 accepted，也不得开启 master flag。

## 6. Canonical TOOL_RESULT archive

V198 移除 `(session_id,tool_use_id)` 的唯一语义，加入 `session_message_id`、`block_index`、`canonical_payload_hash CHAR(64)`、`payload_hash_version`；部分唯一 `(session_id,session_message_id,block_index)`，查询索引 `(session_id,tool_use_id,canonical_payload_hash)`。

`ArchivePayloadIdentityHasher` v1 对 exact persisted logical scalar bytes 做 domain-separated length framing：固定字段顺序，每项为 field tag、null marker、unsigned 64-bit byte length、exact UTF-8 bytes；boolean 有独立 scalar bytes。字段为 exact toolUseId、exact content string、isError 和 nullable errorType。禁止 JSON parse/key-sort/whitespace normalization；hash 命中后仍做 exact scalar equality。Encoding failure 拒绝 archive creation。

写路径：

1. ordered writer commit raw result，返回含 messageId/seq/block/resultBatch 的 `PersistedBlockOccurrence`；
2. ACK 后的 `OccurrenceArchivePreparation` 只接受 committed carrier，在有界 write transaction 中重新校验 occurrence 并准备 archive；
3. History/model projection 是纯读，不从 read-only/RepeatableRead path 调用 `REQUIRES_NEW` lazy writer；
4. legacy null-message-ID archive 只有 offline backfill 在唯一 exact match 时才认领，否则 raw occurrence 保持 canonical。

`CanonicalToolResultOccurrenceResolver` 同时服务 model-view 和 History：snapshot 内有效 archive 时只返回 archive candidate；无 archive、orphan/hash mismatch/ambiguous legacy 时只返回 raw；History-derived occurrence 两者都不返回。Internal dedup key 为 `(messageId,blockIndex,toolUseId,canonicalPayloadHash)`。同 toolUseId 不同 payload 绝不复用旧 archive。Raw ref 后来被归档时，新 Read 返回无正文 `HISTORY_REF_REDIRECT`；已有 cursor 不在中途切换 representation。

## 7. Compact、envelope 与 pre-intent frontier

Normal Full/Light Compact 不得跨 `INTENT_COMMITTED`、`EXECUTING`、`WAITING_USER` 或 `UNCERTAIN_PENDING_RESOLUTION`。`ContextCompactTool` 是唯一在自身 open attempt 中 Compact 的路径，只能处理 intent 记录的 `preIntentMaxMessageId/preIntentMaxSeq` 之前 durable prefix；当前 assistant intent 和其 result 都不进入 compact 输入。Phase 3 重新校验 step/fence/prefix，可用 `(stepId,preIntentFrontier)` 作为幂等 key。

模型视图中的 exact envelope 为：

```text
<compact-checkpoint schema="1" summary-id="42" start-seq="0" end-seq="183">
If exact prior facts are missing, task continuity breaks, or the user reports drift, use SessionHistorySearch to locate evidence, then SessionHistoryRead to inspect it before acting.
</compact-checkpoint>
{raw summary_text begins here, byte-unchanged}
```

Renderer/parser 固定 attribute order、cue 和 LF。`FullCompactStrategy.stripLeadingPriorSummary` 只有在合法 envelope body 或 raw prior summary 与持久 summary byte-equal 时才 strip；用户伪造/损坏 lookalike 保留为事实。`rangeModelSummarySafeReconcile`、`containsCompactSummary` 使用同一 parser。连续两次 reload/live Compact 只保留一个 cue、正确 range 和 untouched raw tail。

五条 Full source 固定为 `user-manual`、`agent-tool`、`engine-hard`、`engine-preemptive`、`post-overflow`；`engine-soft` 是 Light。Compaction Phase 1 捕获 epoch/message/seq/content-provenance hash/loop/fence/open-attempt token，Phase 3 在 Session lock 下全量重验；并发 append、attempt transition、inbox drain、restore 或 fence change 使旧计算失效。

## 8. Runtime checkpoint、branch、restore 与 Recovery

`ContextRuntimeSnapshot` 只保存 registry-resolvable 的 Tool/Skill control refs，不保存对话事实。Checkpoint 的 `runtime_snapshot_json`：

- resume/restart 加载当前 Session runtime；
- branch 将 checkpoint runtime 解析并在 child 当前 Registry/授权下重建；
- restore 用 checkpoint runtime 覆盖当前 runtime；
- null/corrupt legacy snapshot fail closed 为 empty，不携带未来能力状态。

Branch 只复制 acknowledged raw message/summary/runtime 到新 Session；不复制 inbox、scheduled input、attempt runtime、resolution audit 或 parent archive。Restore 需要明确 UI 确认，拒绝 active claim、所有 unresolved attempt 或非空 inbox；合法后在一个 transaction 中 prune future message/summary/checkpoint/control/runtime/obsolete attempt，保留全部 resolution audit，epoch +1，并清除 `restore_preparing`。Restore 不自动重放 Agent turn。

`skillforge.compact.recovery.enabled=false` 和 Java default 均保持关闭。R0 无 Attachment、R1 reference-only、R2 sanitized legacy 只存在于 evaluator；首版不写 Recovery marker、pending/consumed state 或 Provider hook。

## 9. Dashboard 与安全边界

Dashboard 只解析固定 HISTORY wrapper：entity decode 前拒绝 raw nested `<`/`>`、unknown/invalid entity、重复/错误 wrapper 和 trailing bytes；五种 XML entity 只 decode 一次；`JSON.parse` 到 `unknown` 后通过 closed DTO guard，只用 React text render。合法 escaped tag 作为文字显示，禁止 DOM parser、HTML insertion 和 persistence meta 扩展。

安全检查：

- History query/ref/cursor/offset/limit 和 resolution request 全部 closed/bounded；SQL/JPQL 参数化；
- 当前 user/session/actor 均来自 auth/runtime，不信任模型或 client body；
- owner/admin 权限每次重新校验，跨 owner 错误不泄漏存在性；
- 日志不记录 query 正文、raw hash、Session transcript、reason、inbox body、Tool payload 或 secret；
- History 是只读能力，不修改 Memory、Task、checkpoint 或 timeline；其自身 Tool step 的正常 intent/result 持久化属于通用 conversation writer，而不是 History service 副作用。

## 10. 评测设计与上线门

固定四个主 arm：

- A：summary-only；
- B：current summary + tail + legacy recent/GetSessionMessages；
- C：summary + tail + system Search/Read，无 cue；
- D：envelope/cue + byte-unchanged summary + tail + system Search/Read。

R0/R1/R2 是与 A-D 正交的 evaluator-only attachment variants。报告固定 model/provider/context/prompt/fixture/projection version，拒绝混合比较。

门槛：D 相对 A exact recovery `+>=20pp`，同时报告 C-A、D-C；ref correctness `>=98%`；leakage `=0`；unnecessary History `<10%`；S25 quality drop `<=2pp`；schema overhead p95 `<=min(1500 tokens, context window 5%)`；完整 HISTORY wire `<=32000` 且真实 Engine 路径不被 40K truncator 截断；100K Search p95 `<=500ms`。

S25 是普通短 row-store Session：无 Compact、无缺失事实、不得调用 History，并验证配置模型质量和 schema token overhead。R1/R2 达标也不会自动启用生产 Recovery。

## 11. 验证与回滚原则

实现按 RED→GREEN：先验证 intent/claim/result/resolution ACK loss、多实例 generation/fence、interactive complete vector、ordered inbox、current History self-exclusion、archive orphan/canonical redirect、连续 Compact、restore ABA/audit survival，再实现最小 GREEN。PostgreSQL-specific 约束、角色、DB time、RepeatableRead 和 partial unique 必须用 Testcontainers；provider wire 用 Claude/OpenAI-compatible/DeepSeek golden；S11/S12 和 unknown-outcome confirmation 必须做浏览器 DOM/text 验证。

回滚先关 envelope，再关 master，并停止新 loop。旧 binary 运行前必须用新 binary 处理完 `INTENT_COMMITTED`、`EXECUTING`、`WAITING_USER`、`UNCERTAIN_PENDING_RESOLUTION`，完成 CONTINUE 或审计式 PREPARE_RESTORE inbox disposition，确认没有 unresolved/inbox 后才能降级。不得 drop migration、decrement epoch、reset generation/fence、删除 resolution audit，或恢复 execution-before-intent/final wholesale reconciliation。
