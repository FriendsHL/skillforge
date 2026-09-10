# PRD — Session History 检索、Compact 导航与可审计恢复

## 1. 产品原则

1. **原始证据优先**：持久化 message block 与通过 occurrence 校验的 archive 是事实源；summary 是有损导航投影。
2. **按需物化**：先 Search 返回小型 locator，再 Read 精确正文；不默认重放整段会话。
3. **当前 Session 固定**：Harness 从可信上下文注入身份；模型既不能传 `sessionId/userId`，也不能用 cursor 获得权限。
4. **非破坏优先**：事实遗漏先用 History；只有用户明确选择旧时间点时才 branch/restore checkpoint。
5. **执行先留痕**：Tool intent 在副作用前持久化；完整结果向量持久化后才可继续 Provider、queued user 或 Compact。
6. **未知就停**：崩溃后无法证明 Tool 结果时，不猜成功/失败、不自动重试；先 fencing，再由授权用户审计闭合。
7. **状态各归其位**：Task、工作区、Tool/Skill Registry、Session history 和 checkpoint 各自从权威来源恢复。
8. **历史是低信任数据**：历史内容不得提升 authority，不得通过伪造标签改变 Tool 权限或当前目标。
9. **先证明收益**：A/B/C/D 与 S1–S25 必须证明恢复收益，并用 S25 约束无意义调用和 schema 开销。

## 2. 正常模型工作视图

Full Compact 后，模型默认看到：

```text
确定性 compact-checkpoint envelope
+ 当前 active summary 原始正文
+ summary 未覆盖的已持久化尾部消息
+ 当前动态上下文（Task Reminder、授权 Tool 等）
```

尾部是 Compact 策略保留的未覆盖区间，不是固定“最近 20 条”。当前轮每个完整 Tool step 和 queued USER 都必须先形成
持久化边界，Compact 只处理已 ACK 的安全前缀，因此恢复正确性不依赖进程内尚未落库的数据。

如果工作视图已足够，Agent 不调用 History。只有精确事实缺失、任务连续性断裂或用户指出偏移时，才执行 Search→Read。

## 3. Compact checkpoint envelope

### FR-1 确定性、非自闭合 envelope

每个 active range summary 在进入模型视图时使用以下精确平台格式：

```text
<compact-checkpoint schema="1" summary-id="42" start-seq="0" end-seq="183">
If exact prior facts are missing, task continuity breaks, or the user reports drift, use SessionHistorySearch to locate evidence, then SessionHistoryRead to inspect it before acting.
</compact-checkpoint>
{raw summary_text begins here, byte-unchanged}
```

- attribute 顺序、恢复 cue 与换行均为常量；不得使用自闭合标签。
- `summary-id/start-seq/end-seq` 来自 active summary 持久化记录，不由 LLM 生成。
- envelope 是模型视图的确定性派生数据，不创建真实消息；summary 原始正文逐字不变，role 不变。
- parser 只接受精确、受界的平台格式；用户伪造或畸形 lookalike 按普通历史事实处理。
- 连续 Compact 只剥离正文与上一份原始 summary 完全相等的合法平台 envelope；最终只保留一个 cue 与正确范围。

## 4. History 系统工具

### FR-2 系统常驻边界与 closed input

`SessionHistorySearch` 与 `SessionHistoryRead` 是一个不可拆分的系统工具对：

- `skillforge.session-history.enabled=false`：两者均不暴露，direct execution 也拒绝；旧 `GetSessionMessages` 沿用现有 Agent 配置。
- master-on：所有现有和未来 Agent 都能看见并调用两者，不受 `tool_ids`、`required_tool_ids`、custom config 或 deferred-tool 状态影响；旧工具不再与其同时暴露。
- 不新增 per-Agent enablement、seed/grant migration、`search-enabled` 或 partial-pair fallback。
- `GetSessionMessages` 只保留 compatibility/API adapter，可内部转调 `SessionHistoryRead(tail=N)`，不得与 Search/Read 模型共显。
- `CurrentSessionHistoryScope` 只由可信 `SkillContext/LoopContext` 构造；模型 JSON Schema 没有 `sessionId/userId` 且 `additionalProperties:false`。
- execution-side validator 在 repository 访问前拒绝未知/嵌套未知字段、类型越界，以及所有大小写/拼写变体的身份字段。
- SessionList/Session 域负责跨 Session 发现与选择；History 永远只读当前 Session。

### FR-3 `SessionHistorySearch`

Search 用于定位，不返回大段正文。允许字段仅为：

```json
{
  "query": "可选关键词",
  "seqFrom": 0,
  "seqTo": 500,
  "roles": ["USER", "ASSISTANT"],
  "kinds": ["TEXT", "TOOL_USE", "TOOL_RESULT", "SUMMARY"],
  "toolName": "FileRead",
  "toolUseId": "toolu_xxx",
  "compacted": "ANY",
  "summaryState": "ANY",
  "limit": 8,
  "cursor": "opaque-token"
}
```

规则：

- 首次调用必须提供至少一个真正 locator/filter；只有 `limit` 无效。
- `seqFrom<=seqTo`，roles/kinds 是 closed enum，所有 bounds 在 repository 访问前校验。
- continuation 必须带回与初始请求 byte-equivalent canonical selector；不得增加或修改过滤器。
- 当前 Engine 调用的 snapshot cutoff 上限是 Tool attempt 持久化的 pre-intent frontier，不能使用 intent 写入后的 DB max。
- Search 永久排除当前与过往 History Search/Read 的 TOOL_USE 及配对 TOOL_RESULT，也排除由它们产生的 archive。
- 搜索 raw message blocks、canonical archives 与 active/superseded summaries；raw/archive 是 `ORIGINAL`，summary 是 `DERIVED_SUMMARY`。
- 默认全局排序先 `ORIGINAL`、再 `DERIVED_SUMMARY`；原始证据内部以最新 logical seq 优先。`limit=1` 也不得让 summary 压过原文。

### FR-4 `SessionHistoryRead`

Read 精确展开 Search 命中或一个显式范围。每个初始请求恰好选择一个互斥 selector arm：

1. `refs`：1..50 个 message/summary/archive refs，去掉后续重复项后保留调用方顺序；
2. `seqFrom + seqTo`：两者必填，按 persisted event 升序返回；
3. `aroundSeq + before + after`：中心与非负窗口，最终升序返回；
4. `tail`：读取 frozen cutoff 下最后 1..50 个 eligible events，再升序返回；
5. `archiveRef + offset + maxChars`：一个授权 archive/block 的分页读取。

`cursor` 不是第六个 selector，必须与创建它的完整 selector/budget 一起回传。`refs+tail`、半个 seq range、
`archiveRef+refs`、around+range、cursor+修改后的 selector 均在 repository 访问前返回结构化 validation error。

稳定 ref 格式为：

```text
msg:e{historyEpoch}:id{sessionMessageId}:block{blockIndex}
summary:e{historyEpoch}:id{sessionSummaryId}
archive:e{historyEpoch}:id{archiveId}
```

Seq 只是排序元数据，不是 identity。Restore 在同一事务内把 `history_epoch` 加一；旧 epoch ref 返回 `HISTORY_STALE`，
当前 epoch 下未知或越权 ref 返回不泄漏存在性的 `INVALID_REF`。

事件 selector 默认最多 20、硬上限 50；响应 projected text 默认 8,000、硬上限 20,000 字符。授权投影先对完整逻辑块
执行，再按 Unicode code point 分页，禁止按页单独脱敏。Block 与 Event 使用不同 cursor discriminant，不能互相解码。

### FR-5 unsigned exact-token cursor

- Cursor v1 是 unsigned、opaque、canonical JSON/base64url，最大 4096；首版不使用 HMAC。
- 正确的冻结快照、无跳页、无重复只对服务端发出且调用方原样回传的 token 承诺；修改 token 是无效输入。
- 每次 continuation 仍重新校验 owner、服务端当前 Session、history epoch、canonical selector hash、projection version、
  snapshot cutoffs/source watermarks 和完整授权内容 hash。
- Cursor 不授权。Malformed/extra/impossible 返回 `INVALID_CURSOR`；selector 变化返回 `CURSOR_SELECTOR_MISMATCH`；epoch
  变化返回 `HISTORY_STALE`；projection/content 变化分别返回 `HISTORY_PROJECTION_CHANGED/HISTORY_CONTENT_CHANGED`。

### FR-6 使用纪律、projection 与低信任 wire

Agent 只在以下情况使用 History：精确 ID/路径/数字/命令/Tool 参数或输出缺失；summary 有缺口；当前权威状态冲突；
用户指出偏移；或准备声称旧事实不可用。Search 命中只是候选，影响操作/结论/用户纠正时必须 Read 原始证据。

授权投影允许 USER/ASSISTANT text、普通 TOOL_USE input、普通 TOOL_RESULT content/error、summary 与 canonical archive；排除
隐藏 prompt、reasoning、runtime/control/internal metadata 与所有 History-derived blocks。JSON object/array 做确定性递归密钥
脱敏，plain text/scalar 做受界 credential 脱敏。内部 payload hash 不进入 DTO、error、log 或 cursor。

完整 DTO 使用 Spring `ObjectMapper` 序列化后，再进入真实 `LowTrustContextBoundary` wrapper。最终 provider wire 必须
`<=32000` 字符，并低于现有 40000 字符 truncator；Dashboard 只做 decode-once、closed DTO、React text 渲染。

## 5. 两阶段持久 Tool attempt

### FR-7 intent-before-effect 与原序闭合

每个 provider assistant response 中的 Tool step 使用同一个 durable attempt：

```text
NONE --intent tx/ACK--> INTENT_COMMITTED --execution claim CAS--> EXECUTING
  --complete ordered result tx/ACK--> RESULTS_COMMITTED
```

- assistant TOOL_USE intent 与 ordered manifest 必须在 `toolStarted`、future 创建、confirmation 注册、direct dispatch、
  `ContextCompactTool`、网络/文件/进程操作或其他副作用之前提交并 ACK。
- Intent/claim DB 失败时执行数与 durable broadcast 都为 0。
- intent 保存 immutable origin loop/fence、assistant hash、pre-intent frontier 与 Tool manifest；execution owner/fence/generation
  通过独立 claim CAS 取得。
- 普通 attempt 只有在一个事务中写入与 manifest 数量完全相同、toolUseId 相同、provider ordinal 相同的全部 results 后才闭合。
- intent/result retry 使用稳定 ID。已有且 byte-identical 的完整记录返回同一 ACK；partial/mismatch 视为 integrity error。
- 结果 ACK 前不得 append candidate result 到 durable raw view，不得下一次 LLM、drain inbox、live Compact 或最终对账修补。
- 最终 reconciliation 只验证已 ACK rows，并可追加尚未持久化的 terminal plain assistant；不得 DELETE+INSERT 或填补半个 attempt。

### FR-8 execution generation、replay 与 hard crash

服务端给 Tool descriptor 定义 `READ_ONLY_REPLAYABLE`、`IDEMPOTENT_KEYED`、`MUTATING`、`UNKNOWN`，默认 UNKNOWN；混合
attempt 继承最不安全类别。只有前两类且 keyed adapter 真实传播 `(stepId,toolUseId)` 稳定幂等键时，才允许自动 replay。

所有初始、interactive answer 与 takeover claim 使用同一个 Session+attempt 锁定 CAS：

- 相同 `claimRequestId+owner+loop+fence` 的 ACK-loss retry 返回原 ACK，generation 只增加一次；
- 不同 owner 在 DB-time lease 到期前拒绝；到期后 replayable attempt 先把 generation/fence 从 G 推进到 G+1，再重放同一步；
- unknown/mutating takeover 先原子推进 generation/fence 使旧执行者失效，再进入 `UNCERTAIN_PENDING_RESOLUTION`；
- heartbeat、tool-finished、result、archive preparation 与 broadcast 都携带 winning execution identity；旧 generation 全部拒绝。

WAITING_USER 在重启后只重建 durable control，不调用 Tool/provider。`RESULTS_COMMITTED` 重启先确保 occurrence archive 已准备；
最多三次幂等失败后记录 `RAW_FALLBACK`，通过现有请求预算使用 exact raw，绝不重跑 Tool。

### FR-9 interactive preflight 与 ordered inbox

`InteractiveStepPlanner` 在任何 intent/Tool/confirmation 副作用前纯预检整个 assistant response：

- 若存在 ask/confirmation，选 provider ordinal 最早的一项作为唯一 control；所有 sibling 均不执行。
- assistant intent、`WAITING_USER` attempt 与 control 原子持久化；control 不进入 provider view。
- answer/approve/deny/timeout/cancel/supersede 使用同一 generation claim；被选项产生对应 result，所有 sibling 产生
  `ABORTED_FOR_INTERACTIVE_CONTROL`，完整 N-vector 原序闭合后才能继续。
- 批准后的 mutating confirmation 若硬崩溃，按 `UNCERTAIN_PENDING_RESOLUTION` 处理，不能自动再次批准。

运行中的新 USER input 写入按 Session lock 接受、按 DB `id` 排序的 durable inbox，不直接插进 open intent/result pair。
闭合后 drain 把每条 USER 作为独立、byte-identical message row 写入并删除对应 inbox row。Provider 只能临时合并相邻的普通
conversation USER；TOOL_RESULT 虽然 role=USER，但永远不是可合并的 conversation USER。Control、attachment、summary、
hidden context 也都是 merge barrier。DB commit 顺序是 canonical acceptance order，提交前 UI 只能显示 `scheduled`。

### FR-10 unknown-outcome 审计闭合

`UNCERTAIN_PENDING_RESOLUTION` 是阻止新 Provider、Compact、inbox drain 与 restore 的 unresolved barrier，不是终态。
唯一合法 transition：

```text
UNCERTAIN_PENDING_RESOLUTION
  --ACKNOWLEDGE_UNKNOWN_OUTCOME-->
RESOLVED_UNKNOWN
```

API：`POST /api/sessions/{sessionId}/tool-attempts/{attemptId}/resolve-unknown`。Closed body 只含
`resolutionRequestId, expectedHistoryEpoch, expectedExecutionGeneration, expectedExecutionFence, action, reason,
inboxDispositions`；actor 只来自认证。这里的 path `sessionId` 属于经过认证的 Dashboard/API 命令，不是 LLM History
Tool Schema；它不能改变 History 的当前 Session 固定边界。Session owner 或具有显式 `session:resolve-unknown` 权限的 admin 可调用。

事务必须：重新校验 immutable assistant/hash/manifest 与 generation/fence；覆盖锁内每个 inbox item；按原 ordinal/toolUseId
写入恰好 N 个 `is_error=true,error_type=OUTCOME_UNKNOWN` results；写 `RESOLVED_UNKNOWN`、disposition 与 append-only audit。
Fixed result 文本必须明确“操作可能已经成功，不得自动重试，应检查外部状态或取得新用户决定”。同一
`resolutionRequestId` ACK-loss/double-click 返回同一结果；另一个请求不能写第二组 vector/audit。

Post-action：

- `CONTINUE_CURRENT_TIMELINE`：所有 inbox item 都是 KEEP；result ACK/broadcast 后先 archive prepare/raw fallback，再通过
  唯一 durable Session run claim drain/provider 一次。
- `PREPARE_RESTORE`：设置 `restore_preparing=true`，不调用 provider、不 drain；每个 item 显式 KEEP 或
  `DISCARD_FOR_RESTORE`。Discard 只在 audit 保留 inbox ID、exact message hash、actor、reason hash 与 action，不复制正文。
  KEEP/非空 inbox 继续阻止 restore；后续 discard 也走独立幂等审计命令。

Resolution audit 在 restore/prune 后仍存在；普通 `commitToolResults` 没有权限把 uncertainty 伪装为正常 close。

## 6. Canonical archive 与证据唯一性

### FR-11 occurrence-owned archive

Archive identity 绑定 `(current Session, persisted messageId, blockIndex, exact toolUseId, canonicalPayloadHashVersion)`。
内部 v1 hash 对 exact scalar bytes 做 length framing，区分 JSON 空白/键序、null、false 与 errorType；hash match 后仍做 exact
scalar equality。相同 toolUseId、不同 payload 永远不能复用旧 archive。

Raw result rows 先提交，`ResultAck` 再携带非空 `PersistedBlockOccurrence` 给 archive preparation。History/model projection 只读，
不得在 RepeatableRead snapshot 中懒写 archive。Legacy null-occurrence archive 只有离线 backfill 在唯一 exact match 时才能 claim；
否则 invisible，raw 继续权威。

共享 canonical resolver 保证同一 snapshot 下：有效 occurrence archive 只返回 archive；无效/孤儿/冲突只返回 raw；
History-derived occurrence 两者都不返回。若 Search 曾发 raw ref、后续独立 Read 发现已经 canonical archive，则只返回无正文
`HISTORY_REF_REDIRECT`；已打开的 cursor 不在中途切 representation。

## 7. Checkpoint、runtime 与 Recovery

### FR-12 branch/restore 与 `ContextRuntimeSnapshot`

- `resume`/restart 在同一时间线继续 append，不改变 epoch；branch 建新 Session，是默认时间旅行；restore 原地破坏性重写。
- Branch 复制 checkpoint 前已 ACK 的 raw messages/summary/runtime，不复制 parent inbox、attempt runtime/audit 或 archive。
- Restore 必须明确 UI 确认，并拒绝 active claim、任何 unresolved/WAITING attempt、`UNCERTAIN_PENDING_RESOLUTION`、
  `restore_preparing` 未闭合状态或非空 inbox。
- 合法 restore 在共享事务中 prune 未来 timeline/runtime，保留 resolution audit，`history_epoch` 恰好 +1；失败整体回滚。
- `ContextRuntimeSnapshot` 仅保存 Tool/Skill ID/hash/ref。Resume 重载当前值；branch 在 child 中解析 checkpoint snapshot；restore
  覆盖当前值；老 checkpoint 为 null/corrupt 时清空，不能保留恢复点之后的 future runtime。
- `ContextCompactTool` 只能 Compact 自己 intent 之前的 durable frontier，不能把当前 durable assistant 或未闭合 result 总结掉。
- 普通 Full/Light Compact 在任何 unresolved attempt 上拒绝/no-op；Compact DB Phase 3 必须重新校验
  epoch、frontier、attempt、fence 与 inbox。这里的 Phase 3 是单次 Compact 的提交阶段，不是旧交付阶段顺序。

### FR-13 Production Recovery deferred

生产事实恢复基线是 envelope + summary + tail + History。首版不实现 `RecoveryManifest/Attachment`、pending/consumed marker、
provider-attempt one-shot hook 或旧 file-head payload。R0（无 attachment）、R1（引用型）、R2（sanitized legacy）只存在于
evaluator；通过 R1/R2 不会自动开启生产 Recovery。Task 与当前文件分别从 TaskList/数据库和 FileRead/工作区恢复。

## 8. S1–S25 场景与验收矩阵

| ID | 场景 | 预期路径 | 必须验证 |
| --- | --- | --- | --- |
| S1 | Compact 后 summary+tail 已足够 | 直接继续 | 结果正确，不调用 History |
| S2 | 旧 USER 事实被 summary 遗漏 | Search→Read | 引用 original USER ref/seq |
| S3 | 用户后来修正旧事实 | Search→Read 多命中 | ORIGINAL 内采用最新有效事实 |
| S4 | 精确 UUID、版本、数字、路径 | Search→Read | 字节/字符精确，不从 summary 猜 |
| S5 | 旧 Tool 参数 | toolName/toolUseId Search→Read | intent-before-effect，返回正确 TOOL_USE block |
| S6 | 普通旧 Tool 结果 | Search→Read | 返回正确原序配对 TOOL_RESULT，不跨 open attempt |
| S7 | 超大 archive 中部 | Search→archive Read 分页 | occurrence 正确、Unicode 分页、重组 hash 一致、无 raw duplicate |
| S8 | 事实分散在多个位置 | ordered refs Read 后当前计算 | refs 去重保序，不新增 FactRecover |
| S9 | 事件先后顺序问题 | seq range/around/tail Read | frozen cutoff 下稳定升序、event cursor 不跳页 |
| S10 | 用户指出偏移但不要求回滚 | History | 除永久排除的 History audit step 外，不改原时间线/epoch |
| S11 | 用户明确从旧点另开路线 | checkpoint branch | 源不变；child raw/summary/runtime 正确；不复制 inbox/attempt/parent archive |
| S12 | 用户明确原地恢复 | audited prepare + checkpoint restore | UI 确认、空 inbox/no unresolved gate、audit 保留、epoch +1、旧 ref/cursor stale |
| S13 | 浏览器刷新/服务硬重启 | fenced recovery | WAITING_USER 只重建 control；safe attempt 同 step G+1；unsafe attempt 进入 uncertainty；不生成替代 Tool call |
| S14 | 老 checkpoint/崩溃切点 | runtime clear + idempotent recovery | null/corrupt runtime 清空；intent/claim/result/resolution ACK-loss 均只形成一份持久结果和一个被接受的逻辑 continuation |
| S15 | Compact 后仍需当前文件 | FileRead；评测 R0/R1/R2 | 生产不注入旧 file head，当前内容来自工作区 |
| S16 | 未完成 Task | TaskList/Task Reminder | 不以 summary/History/Recovery 覆盖 Task 状态 |
| S17 | summary 与 raw 冲突 | Search→Read | ORIGINAL 先于 DERIVED_SUMMARY，暴露冲突并引用最新 raw seq |
| S18 | 历史没有目标事实 | exhaustive Search 无命中 | 明确 unknown，不伪造 |
| S19 | 旧 Tool 结果含 prompt injection | Search→Read | low trust，仅作为数据；History-derived blocks 不可再次检索 |
| S20 | 身份注入/越权/其他 Session | server scope/closed validator | schema 无身份字段；越权非枚举；cursor 不授权 |
| S21 | 当前 History step 自匹配 | pre-intent cutoff + permanent exclusion | 当前 intent/result 与先前 History wrapper 均不命中；step 闭合后原 cursor 仍有效 |
| S22 | emoji/非 BMP/中文 | Search/Read/wire | code-point safe，中文精确命中，wrapper 未截断 JSON |
| S23 | 连续两次 Compact/superseded summary | deterministic envelope + as-of state | 单 cue、原 summary body 不变、active/superseded/range 正确 |
| S24 | 五类 Full Compact 与并行/等待 Tool | user-manual/agent-tool/engine-hard/engine-preemptive/post-overflow | ContextCompact 只看 pre-intent prefix；并行结果原序；interactive siblings 零执行；open attempt 禁止 Compact |
| S25 | 无需恢复的普通短 row-store Session | 无 Compact/History | no-call；质量下降 <=2pp；schema overhead p95 达门 |

## 9. Hard-crash、等待与恢复的强制验收

以下是 S5/S6/S12–S14/S24 的强制子矩阵，不可仅用 happy-path 单元测试替代：

| 切点 | 必须结果 |
| --- | --- |
| intent transaction 前/中失败 | 无 Tool future、无 `toolStarted`、无副作用、无 durable broadcast |
| intent commit 后、dispatch 前硬崩溃 | 同一个 durable attempt 可恢复；unsafe takeover 先 fence 再进入 uncertainty，provider 不生成替代 Tool call |
| execution claim ACK 丢失 | 同一 request 返回同一 ACK，generation 只加一次 |
| 不同实例在 lease 过期前/后 claim | 过期前拒绝；过期后 G→G+1；旧 heartbeat/result/archive/broadcast 全拒绝 |
| 并行 Tool 乱序完成 | durable result 仍按原 manifest ordinal 一次提交完整 N-vector |
| 外部副作用成功、result tx 失败 | 活实例仅重试同 batch；崩溃后 unsafe 进入 uncertainty，不自动重放 |
| result commit/unknown resolution commit 后 ACK 丢失 | 恢复同一 batch/audit；不二次执行 Tool、不接受重复 transcript continuation |
| WAITING_USER 期间重启 | 只重建 control；未获 answer/approve 前 Tool/provider 调用数为 0 |
| approval 后 mutating Tool 中途崩溃 | 转 uncertainty；不自动批准、取消或重试 |
| uncertainty CONTINUE double-click/进程崩溃 | 唯一被接受的 durable run claim 保证无并发重复 drain/provider，且只有一个响应/assistant intent 可进入持久 transcript；没有 provider 幂等协议时不承诺物理 HTTP exactly-once |
| uncertainty PREPARE_RESTORE | provider/drain 为 0；每个 inbox KEEP/DISCARD 有 audit；只有 inbox 空后 restore |
| USER 在 open attempt 期间到达 | durable inbox 保存；provider raw view 永不在 intent/result 中间插入它 |
| inbox drain 前/后崩溃 | accepted USER 恰好一次；DB raw 保持每条独立、byte-identical |
| ContextCompactTool 正在执行 | summary 结束不超过 pre-intent frontier；当前 assistant intent 保持未覆盖 |
| restore 与 cursor/attempt 竞争 | open attempt 阻止 restore；合法 restore epoch +1，使旧 cursor/ref stale |

## 10. 数据、安全与协议不变量

1. Intent/result 与 queued USER 的持久 JSON 必须与 Engine 同逻辑 Message byte-shape 一致；reasoning、trace、metadata、message type 不丢失。
2. `tool_use/tool_result` 数量、ID 与 provider ordinal 完整配对；普通失败/timeout/cancel 也闭合全部结果。
3. History repository/projection path read-only，不得懒写 archive、Memory、Task 或 checkpoint；通用两阶段 writer 仍会把本次
   History TOOL_USE/TOOL_RESULT 作为普通 conversation audit 持久化，但这些 blocks 永久不成为 History evidence。
4. History own blocks 永久排除；同一 raw/archive occurrence 只返回一个 canonical candidate。
5. Summary role 与原始 summary body 不变；envelope/parser、covered range 与 active/superseded 状态一致。
6. Restore/branch 保留或明确清理 trace/control/answer/message type 等 identity/association columns，不以 seq 充当 identity。
7. Cursor 与 ref 每次都重新校验当前 owner/Session/epoch；错误不得泄漏跨用户或跨 Session 是否存在。
8. Resolution actor 来自认证，reason 只保存 length-framed hash；audit 不包含 raw inbox body 或 raw Tool payload。
9. Unknown outcome audit 对 restore/prune 是只追加且不可变；全 Session 删除是否作为唯一保留例外必须在 Batch 1 明确。
10. 不允许旧二进制在存在 `INTENT_COMMITTED/EXECUTING/WAITING_USER/UNCERTAIN_PENDING_RESOLUTION` 时接管 Session。

## 11. 评测与上线门

固定相同 fixture：

- A：summary-only；
- B：当前 summary + tail + legacy recent/GetSessionMessages；
- C：summary + tail + system Search/Read，无 cue；
- D：deterministic envelope/cue + 原始 summary + tail + system Search/Read。

R0/R1/R2 是 evaluator-only 正交变量，报告必须固定 model/provider/context/prompt/fixture/projection version，拒绝混合比较。

上线 gate：

- D-A exact recovery `>=20pp`，同时报告 C-A、D-C；locator/ref correctness `>=98%`；leakage=0。
- 不必要 History 调用率 `<10%`；S25 quality drop `<=2pp`。
- schema overhead p95 `<=min(1500 tokens, context window 5%)`。
- 真实 HISTORY wrapper `<=32K` 且低于 40K truncator；100K search p95 `<=500ms`。
- S1–S25 均有确定性自动化；S11/S12/unknown resolution 有真实 Dashboard browser checks。
- PostgreSQL/Testcontainers 覆盖 partial index、RepeatableRead、DB-time lease、双实例 generation/fence、audit immutability、
  Flyway V195→V198、restore ABA/audit survival 与 archive conflict。
- 生产 Recovery 始终缺席；R1/R2 评测通过也不能在本需求中开启它。

## 12. Batch 0 与两项实施接受条件

### Batch 0 hard gate

六份需求文档必须同步到本 PRD 与 r6 方案，独立 Full reviewer 只记录 `BATCH0_ACCEPTED_CANDIDATE`；root/Judge 吸收 warning、
冻结最终文档 hash、确认没有提前开始代码，并在仓库验收记录中写入 `BATCH0_ACCEPTED` 后，才能释放 Batch 1。任何后续协议修改都会使 gate 失效。Gate 之前不得创建 V196–V198、entity、生产测试或业务实现。

### W-R6-1：CONTINUE 只能接受一个逻辑 continuation

幂等 resolution ACK 不能让两个 HTTP retry 各自调用 provider。实现必须把
`resolutionRequestId/resultBatchId/action` 绑定到唯一 durable `SessionRunCoordinator` claim 或等价的持久 post-action claim。
双击、ACK-loss、commit 后崩溃测试必须断言只有一个 run claim 与 inbox drain 被接受，且只有一个 Provider 响应/assistant intent
进入持久 transcript。若 Provider 提供并实际传播稳定 idempotency key，可额外断言物理请求 exactly-once；否则 post-dispatch/
pre-persistence 崩溃窗口遵循既有 provider failure/fail-stop 策略，不能把物理 HTTP 次数等同于本地 CAS 保证。

### W-R6-2：audit 只追加策略必须使用真实 PostgreSQL 角色验证

Batch 1 必须冻结并测试实际策略：分离 Flyway owner 与受限 runtime role，或部署角色下真正生效的 trigger/privilege 设计。
同时明确“显式删除整个 Session”是否是唯一 retention exception；restore 永远不能 UPDATE/DELETE resolution audit。仅在默认
`postgres` role 下写应用层约定不算通过。

## 13. Feature flags 与灰度边界

首版仅允许：

```yaml
skillforge.session-history.enabled: false
skillforge.session-history.checkpoint-envelope-enabled: false
skillforge.session-history.search-index-enabled: false
skillforge.session-history.max-provider-wire-chars: 32000
skillforge.session-history.recovery-eval-enabled: false
skillforge.compact.recovery.enabled: false
```

没有 cursor-HMAC、dashboard、search-visible 或 per-Agent History flag。Master-on 必须同时具备 row read/write、attempt writer 与
inbox；`search-index-enabled=false` 使用 authoritative bounded row scan，不隐藏工具。Effective envelope 是 master 与
checkpoint-envelope 两个 flag 同时开启。仅有 legacy `messages_json` 且未完成 verified backfill 的 Session fail closed。
Migrations 先 flags-off 部署，内部 canary 再同时开启系统工具与两阶段 writer。Rollback 先停新 loop，使用新二进制闭合/
审计所有 unresolved state 和 inbox，绝不回退到 execution-before-intent 或 wholesale reconciliation。
