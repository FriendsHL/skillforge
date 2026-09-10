# 交付计划 — Session History Recovery

> 模式：Full。计划覆盖 `AgentLoopEngine`、`ChatService`、`SessionService`、`CompactionService`、Tool 注册、Dashboard、V196–V198 与 PostgreSQL 并发/权限。
> 状态：`BATCH0_ACCEPTED`；Batch 1 foundation 已释放。任何协议修改都会使 gate 失效并返回 Batch 0。
> 实现纪律：每个行为先得到符合预期原因的 RED，再做最小 GREEN；不 commit、不 push，除非用户明确批准。

## 1. 硬门和冻结范围

Batch 0 必须使 `index.md`、`mrd.md`、`prd.md`、`tech-design.md`、`delivery-plan.md`、`research-report.md` 与冻结 r6 一致。以下任一情况都使 gate 失败：

- 仍使用旧版模糊不确定状态，而不是可审计的 `UNCERTAIN_PENDING_RESOLUTION` unresolved barrier；
- 仍使用单一 loop fence 而没有 execution generation claim CAS；
- 允许 execution-before-intent、partial Tool result、silent inbox deletion 或 archive preparation 前继续 Provider；
- History 依赖 Agent grant、schema 包含 session/user identity，或另建 Tool grant migration；
- 仍把 Recovery Attachment 当成首版生产能力；
- 未冻结 W-R6-1 single-coordinator continuation 或 W-R6-2 PostgreSQL append-only audit enforcement。

独立 Full spec reviewer 必须完成逐项映射并输出 `BATCH0_ACCEPTED_CANDIDATE`；root/Judge 吸收 warning、冻结最终文档 hash、确认未提前开始代码，并在仓库验收记录中写入 `BATCH0_ACCEPTED` 后，才能执行 migration、entity、test 或 production-code 变更。后续若修改合同，自动退回 Batch 0。

## 2. Batch 1 — Foundation、V196–V198 与 codec

### RED 顺序

1. 六个配置名/default/bounds；effective envelope 必须是 master && envelope；
2. `PromptSourceType.HISTORY`、`STORED_DATA` 和 Search/Read 低信任分类；
3. Search/Read schema 无 identity、`additionalProperties:false`，direct raw-map validator 拒绝未知/nested/sessionId/userId；
4. Search initial locator、selector/cursor conflict；Read 五个 selector arm 和所有 partial/mixed invalid cases；
5. master-on 所有 Agent 都得到 Search+Read、legacy hidden；master-off direct deny、legacy 维持原配置；
6. clean DB 与 populated V195 DB 的 V196→V198 upgrade；
7. attempt state CHECK、one-unresolved partial unique、immutable origin/mutable execution columns、generation/fence constraints；
8. inbox FK/index、message batch partial unique、epoch/runtime defaults、archive occurrence constraints；
9. row-store master fail-closed、`PersistedMessageCodec` serialize→deserialize→serialize byte stability；
10. resolution audit 在 attempt pruning/restore 后存在，runtime role 的 UPDATE/DELETE/TRUNCATE 失败；
11. durable post-action continuation shape 能绑定 resolutionRequestId/resultBatchId/action 和一个 Session run claim。

### GREEN

- V196：`history_epoch` + checkpoint `runtime_snapshot_json`；
- V197：Session run/lease/restore state、message write batch、inbox、attempt、generation claim、resolution audit、durable post-action continuation；
- V198：archive occurrence/hash identity；
- Entity/repository/properties/YAML/trust/codec/History schemas；
- 不创建 V197 Tool grant migration，不批量修改 Agent `tool_ids`。

### Batch 1 强制验收条件

#### W-R6-1：single coordinator continuation

`CONTINUE_CURRENT_TIMELINE` resolution transaction 只创建一个 durable PENDING post-action。`SessionRunCoordinator` 用 Session lock、loop/fence 和稳定 claimRequestId 唯一 claim；HTTP duplicate/ACK-loss 只能取回同一 ACK，不能各自并发 drain/provider。验收必须证明一个 resolutionRequestId/resultBatchId/action 对应一个被接受的 run claim、一次被接受的 inbox drain 和一个被接受的逻辑 transcript continuation；crash 只能由 winning fence 接管。Provider 没有幂等协议时，不承诺 post-dispatch/pre-persistence 崩溃窗口中的物理 HTTP exactly-once，只允许一个响应/assistant intent 成为持久 transcript，并按既有 provider failure/fail-stop 策略处理歧义。

#### W-R6-2：PostgreSQL append-only audit

- Flyway owner/migrator 和 runtime role 分离；runtime 不是 table owner；
- runtime 对 audit 只有 INSERT/SELECT 及 sequence 所需权限，显式无 UPDATE/DELETE/TRUNCATE；
- attempt ID 是无 FK scalar；restore/attempt pruning 保留 audit；
- 显式删除整个 Session 导致 FK cascade 是唯一 retention exception，并单独做授权/DB 测试；
- Testcontainers 以两个真实角色验证；当前 application/Flyway 都用 `postgres` 的默认环境不满足验收。

两项均有 executable contract、migration constraint 和 PostgreSQL RED/GREEN 证据后，Batch 1 才可 accepted。

## 3. Batch 2 — Intent-before-execution、claim CAS 与 result close

### RED 硬顺序

1. intent DB failure：无 `toolStarted`、future、dispatch、side effect、durable broadcast；
2. intent 持久 exact assistant/manifest/pre-intent frontier/immutable origin，retry 同 ID 幂等；
3. execution claim failure：零 dispatch；
4. 初始 claim ACK loss：同 `claimRequestId+owner+loop+fence` 返回同 ACK，generation 只增一次；
5. lease 未过期时不同 owner 被拒绝；过期后 safe `INTENT_COMMITTED`/`EXECUTING` G→G+1；
6. unsafe takeover 先 bump generation/fence 再进入 `UNCERTAIN_PENDING_RESOLUTION`；旧 heartbeat/result/archive/broadcast 全拒绝；
7. parallel result 完成顺序任意，但一次按 manifest ordinal 提交完整 N-vector；
8. result commit ACK loss 返回同 ACK、不重执行；DB outage 不得 next Provider/Compact/inbox/broadcast；
9. `RESULTS_COMMITTED` restart 先重试 occurrence archive preparation；三次失败记录 `RAW_FALLBACK` 并走 exact raw budgeter，不重执行 Tool；
10. timeout/cancel 关闭全部 calls，late completion 无效；mixed replay safety 继承 unsafe；corrupt hash/manifest/partial batch fail closed；
11. final reconciliation 只能校验 ACK rows/补普通 terminal assistant，不得 wholesale rewrite。

### GREEN

Core `engine/durability/*`、`LoopContext`、`AgentLoopEngine`；Server ordered writer、attempt service、JPA sink、generation-fenced coordinator/recovery、Chat/Session wiring，以及 `PersistedBlockOccurrence` 和 `OccurrenceArchivePreparation` 调用/恢复接口。

## 4. Batch 3 — Interactive full-vector、ordered inbox 与 unknown resolution

### RED

- `[normal,ask]`、`[ask,normal]`、`[normal,confirmation,normal]`、多个 interactive：回答前所有 sibling 执行次数为零；
- control+assistant intent+WAITING_USER atomic；answer claim 使用同一 generation CAS；
- answer/deny/timeout/cancel/supersede 按原 ordinal 一次写 selected result + 全部 `ABORTED_FOR_INTERACTIVE_CONTROL`；
- approved mutation crash 进入 `UNCERTAIN_PENDING_RESOLUTION`；double answer 幂等；control 不进入 Provider；
- queued USER 在 parallel tool 前/中/后都不拆 pair，多条仍为独立 raw row；DB acceptance order 而非 executor/HTTP arrival order为 canonical；
- crash-before/after inbox drain，Claude/OpenAI-compatible/DeepSeek golden 保证 conversational USER 只出现一次，且不跨 TOOL_RESULT/control/attachment/summary/hidden context 合并。

Unknown-outcome RED：

- owner 和显式 admin 成功；cross-owner/unprivileged/stale epoch-generation-fence 非枚举式失败；
- normal result path 不能做 unknown transition；重读 assistant/hash/manifest 后原子写 exact N 个 `OUTCOME_UNKNOWN`、RESOLVED_UNKNOWN、audit 和 dispositions；
- resolution double-click/ACK-loss 返回同 ACK；第二 request、late old executor、corrupt manifest 拒绝；
- CONTINUE 只产生一个被接受的 durable continuation claim，archive/fallback→drain→Provider 不并发重复；只有一个响应/assistant intent 可成为持久 transcript；无 provider 幂等协议时不以物理 HTTP 次数作为跨崩溃 oracle；
- PREPARE_RESTORE 不调用 Provider、不 drain，审计每个 KEEP/DISCARD；KEEP/非空 inbox 继续阻止 restore，后续 discard 仍幂等/审计；
- crash-before commit 不改变 unresolved barrier；crash-after commit 恢复同 result/audit/post-action；restore 保留 audit。

### GREEN

`InteractiveStepPlanner`、control/answer service、interactive DTO、`UnknownOutcomeResolutionService`/controller/audit writer、inbox disposition service、provider-only USER materializer。必须在 Batch 2 handoff 后顺序修改共享 Engine/ChatService 文件。

## 5. Batch 4 — Compact、envelope、epoch 与 checkpoint

### RED

- normal Compact 不跨 active/unresolved attempt；
- `ContextCompactTool` 只 compact pre-intent durable prefix，排除当前 assistant/result；
- `user-manual`、`agent-tool`、`engine-hard`、`engine-preemptive`、`post-overflow` 五条 Full source；
- >20 acknowledged tail、连续两次 reload/live Compact、合法 envelope + raw prior summary 才 strip；
- History pair-preserving placeholder；
- Phase 3 在 append/attempt/inbox/restore/fence change 后 stale；
- range Compact 不 bump epoch；destructive restore/rewrite epoch +1 且 rollback 原子；
- branch/restore/runtime snapshot；restore 拒绝 unresolved/KEEP/nonempty inbox并保留 resolution audit。

### GREEN

`FullCompactStrategy`、compact projection/policy、`CompactionService`、envelope/runtime codec、shared destructive primitive、checkpoint branch/restore。Batch 2/3 均为前置硬门。

## 6. Batch 5 — History core、cursor/ref/projection/order

### RED

- system current scope、closed direct dispatch、所有 Agent visibility/legacy compatibility；
- Search 至少一个 locator；Read refs/range/around/tail/archive 五 arm 与互斥；
- block/event cursor 不可混解，malformed/scope/selector/epoch/projection/content 错误准确；
- unsigned exact-token pagination、current intent/result cutoff、后续 History block 永久排除、guessed derived ref denied；
- immutable message ID + epoch，restore-shorter 后 seq 回长 ABA；
- RepeatableRead cutoffs、summary as-of、multi-source no-skip/no-duplicate、current step append 后 cursor 仍有效；
- deterministic authorization projection、version/hash/redaction、Unicode；
- `ORIGINAL` before `DERIVED_SUMMARY`，包括 `limit=1`；
- legacy `messages_json` fail closed；真实 AgentLoopEngine HISTORY wrapper `<=32K` 且未被 40K truncator 截断。

### GREEN

`server/session/history/*`、owner-scoped repository queries、system dispatch、legacy adapter、prompt/cue/wire。Cursor/ref 实现先于 ABA integration tests。

## 7. Batch 6 — Canonical occurrence archive

### RED

- length-framed exact identity 区分 JSON whitespace/key order、null/false/errorType；encoding failure 拒绝；
- committed occurrence carrier 从不产生 null message ID；History RR/model-view read 无 archive write；
- raw/archive 在 snapshot pages 中只有一个 candidate；same ID/same payload 用 archive，same ID/different payload 保留新 raw；
- orphan/unclaimed/ambiguous legacy raw fallback；offline exact-match backfill；
- raw ref 后归档返回 canonical redirect；已打开 cursor 不切换 representation；
- model-view 与 History 共用 resolver；branch/restore/orphan、自指 archive、concurrent insert、100K 性能。

### GREEN

`ArchivePayloadIdentityHasher`、`CanonicalToolResultOccurrenceResolver`、`OccurrenceArchivePreparation`、archive service/repository/History adapter。V198 和 Batch 2 carrier 均为前置。

## 8. Batch 7 — Dashboard

### RED

- wrapper 在 entity decode 前拒绝 raw nested tag、invalid entity、wrong/duplicate wrapper、trailing bytes；合法 escaped tag 只作为文本；decode 一次；closed DTO/oversize fallback；
- live/replay History card 同一 decoder；
- intent/closed/`UNCERTAIN_PENDING_RESOLUTION`/`RESOLVED_UNKNOWN` 状态；
- owner/admin permission rendering、明确“可能已成功/禁止自动重试”、CONTINUE/PREPARE inbox disposition、double-click/ACK-loss 和 stale/cross-owner无泄漏；
- branch/restore confirmation 浏览器 DOM/text 测试。

### GREEN

History output decoder/DTO guard/result card、unknown-outcome warning/confirmation/action card、API client，以及 `ChatWindow.tsx`/`Chat.tsx`。无 Dashboard flag。

## 9. Batch 8 — S1–S25、A-D、R0-R2 与灰度

固定 A/B/C/D：

```text
A: summary-only
B: current summary + tail + legacy recent/GetSessionMessages
C: summary + tail + system Search/Read, no cue
D: envelope/cue + byte-unchanged summary + tail + system Search/Read
```

R0/R1/R2 是正交 evaluator-only attachment variants；首版不增加生产 Recovery marker/hook。报告固定 model/provider/context/prompt/fixture/projection version。

退出门：

- S1–S25 全部 deterministic；S11/S12 及 unknown-outcome 做浏览器验证；
- D/A exact recovery `+>=20pp`，同时报告 C-A/D-C；refs `>=98%`；leak `=0`；unnecessary `<10%`；
- S25 quality drop `<=2pp`，schema overhead p95 `<=min(1500 tokens, 5% context window)`；
- real Engine wire `<=32K` 且不触发 40K truncation；100K Search p95 `<=500ms`；
- attempt crash/recovery、role enforcement、restore ABA/audit、archive fallback、rollback drill 有 fresh evidence。

## 10. Worker ownership与安全 waves

| Worker | 独占范围 |
| --- | --- |
| ROOT-SPEC | Batch 0 六文档同步、映射、独立审查和 Judge gate。 |
| W-FOUNDATION | V196–V198、entity/repository、properties/YAML、trust、codec、PostgreSQL roles。 |
| W-DURABILITY | core durability、LoopContext/AgentLoopEngine；attempt/writer/sink/coordinator/lease、ChatService/SessionService；resolution 和 continuation claim；occurrence carrier/call/recovery seam。 |
| W-INTERACTIVE | durability handoff 后的 planner/control/answer/inbox disposition；共享 Engine 文件只顺序改。 |
| W-COMPACT | durability+interactive 后的 FullCompactStrategy、CompactionService、envelope/checkpoint/runtime/placeholder。 |
| W-HISTORY | History package/tools/schema/validator/cursor/ref/projection/order/wire/prompt 和划分后的 repository query。 |
| W-ARCHIVE | exact hasher、archive preparation implementation/backfill、canonical resolver/service/repository；不与 W-DURABILITY 同时编辑 SessionService/writer。 |
| W-FRONTEND | Dashboard decoder、unknown-outcome cards/API/浏览器测试。 |
| W-EVAL | fixture/runner/report/perf/runbook；不写生产 Recovery。 |

Waves：0 文档 gate；1 foundation/schema；2 durability 独占 red-light files；3 interactive→Compact 顺序 handoff；4 History/Archive 按 repository 分区并行、Frontend 可并行；5 eval/integration/reviewer/Judge。最多三个 implementation worker 加 root。

## 11. 验证命令

聚焦后全量 fresh run：

```bash
mvn -pl skillforge-core -Dtest='*AgentLoopEngine*,*Durability*,*ToolAttempt*,*ExecutionClaim*,*FullCompact*,*History*' test
mvn -pl skillforge-server -am -DskipTests compile
mvn -pl skillforge-server -am -Dtest='*ToolAttempt*,*ExecutionClaim*,*UnknownOutcomeResolution*,*ResolutionAudit*,*OrderedMessageWriter*,*Interactive*,*SessionHistory*,*Compaction*,*Checkpoint*,*ToolResultArchive*,*ChatService*' test
mvn -pl skillforge-server -am verify
npm --prefix skillforge-dashboard test -- --run
npm --prefix skillforge-dashboard run build
```

PostgreSQL/Testcontainers 必须覆盖 partial indexes、RepeatableRead、DB-time lease、两个 execution owner、runtime/migrator roles、claim/resolution ACK loss、attempt crash cuts、Flyway V195→V198、restore ABA/audit、archive conflict/restart。安全测试覆盖 owner/admin、cross-owner non-enumeration、closed DTO、stale epoch/generation/fence、reason hash 和 audit 无正文。Provider golden 覆盖 Claude/OpenAI-compatible/DeepSeek。

## 12. 灰度、停止和回滚

1. Batch0 accepted 后部署 V196–V198，所有 flags false；完成 row-store 校验/回填；
2. 仅 internal canary 开 master，使 system History + two-phase writer/inbox 一起生效；envelope/index/recovery eval 仍关闭；
3. 观察 intent latency、unresolved age、generation/fence conflict、stale-event reject、resolution idempotency、archive retry/RAW_FALLBACK、inbox age、History self-exclusion/wire；
4. canary 稳定后开 envelope；index 只有 parity 后启用；Recovery 始终 evaluator-only。

任何原始消息变化、Tool pair 破坏、跨用户泄漏、错误自动重放、restore audit 丢失、duplicate Provider continuation 都立即停止。

回滚先关 envelope，再关 master，停止新 loop。旧 binary 启动前必须用新 binary 关闭所有 `INTENT_COMMITTED`、`EXECUTING`、`WAITING_USER`、`UNCERTAIN_PENDING_RESOLUTION`，完成 CONTINUE 或审计式 PREPARE_RESTORE，清空 inbox。Resolution audit 必须导出/保留。不得 drop migration、decrement epoch、reset generation/fence，或回到 execution-before-intent/wholesale reconciliation。
