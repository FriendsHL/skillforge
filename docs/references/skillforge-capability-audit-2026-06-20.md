# SkillForge 全维度能力现状审计 — 2026-06-20 快照

> 类型：dated snapshot（会随交付推进过期）。触发：全维度能力现状审计。**代码是唯一权威，本审计经源码逐一查证**（path:line 引用均为绝对路径）；**交付事实唯一来源仍是 [delivery-index.md](../delivery-index.md)**，本文是横切整合视图，方便一眼看清"八个能力维度现在各到哪一步"。

## 1. 一句话总览

SkillForge 当前整体成熟度：这是一个**高度成熟的生产级 Java/Spring Agent 平台** —— 8 个能力维度中，**Agent Loop / Context 压缩 / Memory / Multi-Agent / Eval 五项基本完备**（✅ 或接近 ✅），**Self-Evolution 与 Safety 各有一处明确缺口，Skill 系统缺自动归档 curator**。核心能力（loop 防失控、双层压缩 + archive offload、memory 全生命周期、SubAgent 树 + Team 网状、A/B eval pipeline）都已落地并带持久化与崩溃恢复。

瓶颈集中在两处：
- **自进化闭环的自主性** —— autoEvolving loop 空转 0 真赢家（见 [autoevolving-capability-stage-2026-06-17.md](./autoevolving-capability-stage-2026-06-17.md)） + DREAMING 无自动批准；
- **对抗不可信内容的纵深防御** —— 无 taint / quarantine 污点追踪。

## 2. 8 维能力表

| 维度 | 状态 | 关键文件证据（path:line） | 现状一句话 | 缺口 |
|---|:--:|---|---|---|
| **1 Agent Loop 引擎** | 🟡 | `AgentLoopEngine.java:622,641-648,768-772` / `LoopContext.java:172,428-451` / `CancellationRegistry.java:16-38` | turn 循环 / 取消 / 时长预算 / no-progress 四项完备 | input token 预算默认 opt-in（软）；detectWaste 启发式偏弱 |
| **2 Context 压缩** | ✅ | `LightCompactStrategy.java:84-146` / `FullCompactStrategy.java:178-237` / `ToolResultArchiveService.java:40-326` / `ToolResultRequestBudgeter.java:33-262` | 三策略 + ratio 三档触发 + DB archive offload + 请求期临时裁剪全到位 | 引擎层无字节一致 roundtrip 运行时 guard（靠规则/review/测试模板） |
| **3 Memory** | ✅ | `MemoryConsolidator.java:157-203` / `MemoryEntity.java:52-65` / `MemoryProposalEntity.java:36-40` | 四级生命周期 + TTL 自动归档 + 容量降级 + pgvector 去重 + 5-turn 提醒全实现 | 无实质缺口 |
| **4 Multi-Agent** | ✅ | `SubAgentTool.java:39-310` / `SubAgentRegistry.java:89-114` / `TeamCreateTool.java:92-154` / `TeamSendTool.java:27-283` | SubAgent 树（深≤3/父并发≤5）+ Team 网状（深≤2/总≤20）+ 持久化邮箱 + 顺序投递 | 无实质缺口 |
| **5 Self-Evolution** | 🟡 | `EvolveController.java:133,438` / `MemoryProposalService.java:112-118` / `evolve-loop.workflow.js:70-108,467-495` | 类2 人审采纳 ✅；类1 DREAMING 执行管线接通但无自动批准；类3 无 per-goal budget；类4 框架全在但空转 0 真赢家 | 类1 无自动批准；类3 无 per-goal token budget；类4 无对靶候选 + 相对判定（in progress） |
| **6 Eval** | ✅ | `AbEvalPipeline.java:75,127-129` / `BehaviorRulePromotionService.java:98` / `GetAbResultTool.java:411-424` | A/B pipeline + oracle 机器判分 ≥40 + infra 失败摘出分母 + 双准则自动晋升全到位 | 判定尺子敏感度问题（与维度5 类4 同源），非 pipeline 缺失 |
| **7 Safety** | 🟡 | `SafetySkillHook.java:28-204` / `ToolApprovalRegistry.java:13-53` / `SkillSecurityScanner.java:20-259` / `McpServerService.java:~145-200` | 命令黑名单/路径穿越/三档审批/skill 扫描/SSRF/XML 沙箱/防失控全到位 | 无 taint/quarantine 自动降权（不消费"读过外部内容"信号） |
| **8 Skill 系统** | 🟡 | `SystemSkillLoader.java:32-107` / `SurfaceRegistry.java:32-79` / `SkillEntity.java:76-83` | 两层加载 + SurfaceRegistry + usageCount/successCount 统计齐全 | 无低使用 skill curator（usageCount 有数据无消费） |

速览：**Loop 🟡 · Context ✅ · Memory ✅ · Multi-Agent ✅ · Self-Evolution 🟡 · Eval ✅ · Safety 🟡 · Skill 🟡**。

---

### 维度1 Agent Loop 引擎 — 🟡 半截

- **Turn loop + max-iteration cap: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/engine/LoopContext.java:172`（maxLoops default 25）；`AgentLoopEngine.java:622` `while (loopCount < maxLoops)`；hard stop `AgentLoopEngine.java:1413`；per-agent override `LoopContext.java:405-408`。
- **Time / wall-clock budget: ✅** —— `AgentLoopEngine.java:641-648`，maxDurationMs default 30min（1800000ms），per-agent `config.max_duration_seconds`，hard break `status=duration_exceeded`。
- **Cancellation: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/engine/CancellationRegistry.java:16-38` `cancel(sessionId)` → `LoopContext.requestCancel()`（`LoopContext.java:176-181` 置 `cancelRequested` + `streamCanceller.run()`）；pre-iteration 检查 `AgentLoopEngine.java:626` 与 post-LLM 检查 `AgentLoopEngine.java:1019`；stream cancel 注册 `AgentLoopEngine.java:938-943`。
- **No-progress detection: ✅** —— `LoopContext.java:428-451` `isNoProgress()`：`recordToolOutcome()` 跟踪 callHash（tool + input 哈希）；同 callHash ≥ 3 且 outcome 一致 → no-progress；用于 `AgentLoopEngine.java:768-772`（追加 warning）+ per-tool ≥ 8 次 warning `AgentLoopEngine.java:776`。
- **Token budget（input）: 🟡** —— `AgentLoopEngine.java:611-638`，maxInputTokens default 500K 但 **OPT-IN**（`enforce_max_input_tokens`，default false，注释 "P9-2: max_input_tokens 改为 opt-in"）。默认是软约束 → 长任务可超预算，仅 telemetry，无 hard stop。
- **Waste detection: 🟡** —— `AgentLoopEngine.java:698,760` `detectWaste(messages)` 被调用并触发一次压缩 + warning，但启发式/阈值定义不清晰（不明确算 iteration-level guard）。

**现状一句话**：turn 循环 / 取消 / 时长预算 / no-progress 四项完备，但 input token 预算默认 opt-in（软）、waste 检测定义偏弱。

**缺口**：① input token 预算默认不强制（建议 default-on 或文档化）；② detectWaste 启发式 / 阈值不清晰。

### 维度2 Context 压缩 — ✅ 已有（一处不变量靠纪律）

- **Light 压缩: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/compact/LightCompactStrategy.java:84-146`，4 规则按序早停：truncate-large-tool-output（>10KB → head4K + tail2K，line108-116）/ dedup-consecutive-tools（118-127）/ fold-failed-retries（3+ 连续 error → 1 + summary，129-137）/ drop-empty-assistant-narration（<80 char，139-143）；`PROTECTION_WINDOW=5`（line52）保护最后 5 条；幂等 marker（line78）；`TARGET_RECLAIM_RATIO=0.20` 早停（line81）。
- **Full 压缩（LLM）: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/compact/FullCompactStrategy.java:178-237`，3-phase（boundary 检测纯 Java 在锁内 / LLM 调用在锁外 / finalize）；`YOUNG_GEN_KEEP=20`（line46）；`findSafeBoundary()` 不切断 tool_use ↔ tool_result（line244-288）；10-section 结构化 summary 模板（line79-116）；超大窗口 map-reduce chunk（line374-411）；`SUMMARY_INPUT_RESERVE_TOKENS=5500`（line70）。
- **SessionMemory 0-LLM 压缩: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/compact/SessionMemoryCompactStrategy.java:27-84`，用预渲染 memory summary 代替 LLM 调用，3 道检查不过则 fallback 到 `FullCompactStrategy`。
- **触发阈值: ✅** —— `AgentLoopEngine.java:689-750`，soft ratio > 0.60（或 waste）→ Light（B1）；hard ratio > 0.80 且 B1 已跑 → Full（B2）；preemptive ratio > 0.85 在 LLM 调用前（line864）；per-provider 覆盖 `llmProvider.getCompactThresholds()`（line689）。
- **persistence-shape 不变量: 🟡** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/service/CompactionService.java` 与 `AgentLoopEngine.java:425-435` 把不变量写为设计注释（"userMessageBlock 同一 Message 引用 → 请求历史 byte-identical"，"不可 mutate user message 的 text ContentBlock"），但**审计读到的引擎侧代码层无显式 roundtrip 测试 / 运行时 guard，靠 compactor 纪律**。注：CLAUDE.md 列了 `persistence-shape-invariant.md` 规则 + compact-reviewer 内嵌 8 条不变量 + `java.md` footgun #4/#5，即不变量靠"规则 + review + 测试模板"层面强保障，只是引擎主循环里没有内联运行时断言。
- **tool-result offload / archive: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/service/ToolResultArchiveService.java:40-326`，单条 user message 的 tool_result 聚合 > 200K chars → 把最大块写 `t_tool_result_archive` 表（`ToolResultArchiveEntity`），`UNIQUE(session_id,tool_use_id)` + `ON CONFLICT DO NOTHING` 幂等（233-234），`applyArchive()` 用 archive_id + 2KB preview 替换（75-91,193-199），可凭 archive_id 取回。
- **请求期临时裁剪: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/engine/ToolResultRequestBudgeter.java:33-262`，LLM 调用前 ephemeral 裁剪（不持久化），聚合 > 200K → 最大块裁到 2048 chars，deep-copy 不动原始，压缩后重新预算（`AgentLoopEngine.java:879`）。

**现状一句话**：Light/Full/SessionMemory 三策略 + ratio 三档触发（0.60/0.80/0.85）+ DB archive offload + 请求期临时裁剪全到位；persistence-shape 不变量靠规则 / review / 测试模板保障，引擎主循环无内联运行时断言。

**缺口**：引擎层无字节一致 roundtrip 运行时 guard（目前靠 compact-reviewer + 测试模板 + 规则纪律）。

### 维度3 Memory — ✅ 已有（强项）

- **生命周期 ACTIVE→STALE→ARCHIVED→物理删除: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/entity/MemoryEntity.java:52-53` status（default ACTIVE），archivedAt（56-57），archivedReason（64-65：expired_ttl / capacity_demote / dedup_merge_with_<id>）。
- **MemoryConsolidator: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/memory/MemoryConsolidator.java:157-203` `consolidate()` 三段：Phase0 `deduplicateByEmbedding()`（line164）/ Phase1 评分 + 状态迁移 ACTIVE → STALE（staleAfterDays）→ ARCHIVED（archiveAfterDays）（166-195）/ Phase2 `enforceCapacity()` 降级溢出到 STALE（196）；`isExpiredArchived()` 删除 ARCHIVED 超 deleteAfterDays（90 天默认）（375-381）；`ConsolidationResult` 记录 dedupArchived / ttlArchived / staleTransitioned / capacityDemoted / expiredDeleted / activeAfter（39-60）。
- **字段: ✅** —— `MemoryEntity.java` recallCount（43）/ lastRecalledAt（45）/ importance（72，high/medium/low）/ lastScore（76）。
- **MemoryAgeSource 5-turn 提醒: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/reminder/MemoryAgeSource.java:36` intervalTurns（default 5），`debounceElapsed()`（59），仅 `staleCount()` > 0 才提醒（85）。
- **pgvector: ✅** —— `MemoryConsolidator.java:66-76` embeddingColumnAvailable 探测缓存；`deduplicateByEmbedding()`（218-314）用 `VectorUtils.cosineSimilarity`，原生 SQL pgvector `<=>` 操作符（line232 `(embedding <=> CAST(:embedding AS vector)) AS distance`），cosineMergeThreshold（283），败者归档 dedup_merge_with_<winnerId>（321）。
- **MemoryProposal 人审门: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/entity/MemoryProposalEntity.java:36-40` status（proposed/approved/rejected/auto_archived/stale）type（dedup/reflection/optimize/contradiction）；`MemoryProposalService.approve()`（68-127）悲观写锁；`autoArchiveStale()` 扫超 7 天提案 → auto_archived（223-237）。

**现状一句话**：四级生命周期 + 年龄 TTL 自动归档 + 容量降级 + pgvector 余弦去重 + 5-turn stale 提醒全实现，记忆归档清理是已落地强项。

**缺口**：无实质缺口。

### 维度4 Multi-Agent — ✅ 已有

- **SubAgent 树（父 → 子异步派发，结果自动回投）: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/tool/SubAgentTool.java:39-310`（dispatch / continue / terminate / list；chatAsync 异步派发返回 runId，line297）；`SubAgentRunEntity.java:22-47`（runId / parentSessionId / childSessionId / status RUNNING/COMPLETED/FAILED/CANCELLED/TERMINATED）；`/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/subagent/SubAgentRegistry.java:89-114` `registerRun()` `MAX_DEPTH=3` `MAX_ACTIVE_CHILDREN_PER_PARENT=5`；`onSessionLoopFinished()`（181-218）子完成 → `SubAgentPendingResultEntity` → `maybeResumeParent()` 自动回投空闲父。
- **Team 网状（多 agent 并行，handle 寻址）: ✅** —— `CollabRunEntity.java:14-48`（collabRunId / leaderSessionId / status，maxDepth=2，maxTotalAgents=20）；`/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/tool/TeamCreateTool.java:92-154`（TeamCreate 派生成员，AgentRoster 按 handle 注册）；`CollabRunService.java:93-206` `spawnMember()`。
- **agent 间通信: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/tool/TeamSendTool.java:27-283`（send by handle / broadcast leader-only / send to parent；邻接策略 = 父/子/兄弟，240-264；信封格式 `[AgentMessage type=PEER_MESSAGE from=<handle>...]` 277-282）；`SubAgentRegistry.enqueueForSession()`（338-367，messageId 去重 + seqNo 排序）+ `maybeResumeSession()`（373-399，按 seqNo 顺序投递 + 唤醒）。消息走 `SubAgentPendingResultEntity` 持久化，支持崩溃恢复。

**现状一句话**：两套拓扑都生产级 —— SubAgent 树（深 ≤ 3 / 父并发 ≤ 5）父子异步 + 自动回投；Team 网状（深 ≤ 2 / 总 ≤ 20）handle 寻址 + 单播/广播 + 持久化邮箱 + 顺序投递。

**缺口**：无实质缺口。

### 维度5 Self-Evolution — 分 4 类判（🟡）

- **类2 `/evolve` + adopt（meta 人审）: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/evolve/EvolveController.java:133` `@PostMapping("/agents/{agentId}/run")` + `:438` `@PostMapping("/runs/{evolveRunId}/adopt")`；adopt 强制 `userId ≠ SYSTEM_USER_ID` 人工 gate（444）；`AgentBundleAdoptionService.adopt()` 各面独立事务防半失败（101-113）。
- **类1 DREAMING（runtime 提炼）: 🟡** —— **纠正一处 pre-investigated 怀疑**：approve → 执行管线其实是接通的。`/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/service/MemoryProposalService.java:112-118` `approve()` 的 switch 直接调 `applyDedup` / `applyReflection` / `applyOptimize` / `applyContradiction`，这些方法真改 `t_memory`（`memoryRepository.save`），然后 line120 才置 APPROVED —— 不是只翻 status。runtime 侧 `LlmMemorySynthesisScheduler.java:44` `@Scheduled(cron="0 30 4 * * *")` 每日 04:30 提炼（`bypassGate=false`，强制走 gate）。**仍判 🟡 的真正原因**：没有 auto-approve —— `approve()` 唯一调用方是 `AdminMemoryLlmSynthesisController.java:272`（人工 admin 端点），DREAMING 提炼出提案后必须人审批准才落地，没有自主闭环（no autonomous runtime self-evolution loop）。
- **类3 budget 内嵌（per-goal token_budget）: ❌** —— 全代码无按 goal 维度的 token_budget（entity / config / service 都没有）；仅有整体 agent-loop 级预算（维度1 那个）。无 goal_token_budget 概念、无按目标聚合费用、无单目标早停。
- **类4 autoEvolving loop（算法优化）: 🟡** —— 框架全在但空转 0 真赢家。DSL 引擎 `/Users/youren/myspace/skillforge/skillforge-server/src/main/resources/workflows/evolve-loop.workflow.js` 有 6 原语（phase line215 / agent 296 / tool 327,357 / log / humanApprove 517，P1 暂硬编 autoApprove=true 未触达）+ hill-climb（carry-forward best 467-480 / noImprove 重置 481 / 停止条件 486-495）；`decideKeep()`（70-108）配对 net-wins 主判据 + F3 最小测量 + F6 vs-original 锚；`GetAbResultTool.java:75` `PASS_COMPOSITE_THRESHOLD=40.0` 绝对分；weightedScore = 0.6·general + 0.4·harvest（`GetAbResultTool.java:411-424` `computeWeightedScore`，子集缺则重归一化）。瓶颈（见 [autoevolving-capability-stage-2026-06-17.md](./autoevolving-capability-stage-2026-06-17.md)）：候选不对靶 + 绝对打分 self-preferential → 0 真赢家；EVOLVE-JUDGE-GROUNDING（active）正在治。

**现状一句话**：4 类自进化 —— 类2 人审采纳 ✅ 完整，类1 DREAMING runtime 提炼 + 执行管线已接通但无自动批准（半截），类3 per-goal budget 完全没有，类4 算法框架全在但空转 0 真赢家。

**缺口**：类1 缺自动批准闭环（目前全人审）；类3 缺 per-goal token budget；类4 缺对靶候选 + 相对/对抗判定（in progress）。

### 维度6 Eval — ✅ 已有

- **A/B Eval Pipeline: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/improve/AbEvalPipeline.java:75` `PASS_COMPOSITE_THRESHOLD=40.0`（oracle 机器判分阈值），`isPass()`（84-94），`pairwiseMeasured()`（127-129）baseline + candidate 同轮都非基建错误才计入（infra 失败摘出分母）。
- **Prompt A/B: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/improve/PromptEvalService.java:24-28` 委托 `PromptImproverService.runEvalSetInternal()`。
- **behavior oracle + 自动晋升: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/improve/BehaviorRulePromotionService.java:98` `isDualCriteriaSatisfied()`（双准则：targetDeltaPp + regressionDeltaPp）；另有 `PromptPromotionService` / `SkillAbEvalService`。
- **weightedScore / decideKeep: ✅** —— `GetAbResultTool.java:411-424` `computeWeightedScore = (wG·generalRate + wH·harvestRate)/(wG+wH)`，子集缺则 drop-out 重归一化；comparativeVerdict `netWins = improvedTotal − regressedTotal ≥ minNetWins` + 可选 two-sided sign test（520-547）；JS 侧 `decideKeep`（`evolve-loop.workflow.js:70-108`）。

**现状一句话**：A/B eval pipeline（候选 vs 原始跨场景 + oracle 机器判分 ≥ 40 + infra 失败摘出分母）+ behavior oracle 双准则自动晋升 + weightedScore/decideKeep 全到位。

**缺口**：判定是绝对加权分（self-preferential 风险）、无 held-out 强制 gate、无 pairwise 显著性强制（EVOLVE-JUDGE-GROUNDING 在治），这与维度5 类4 同源，属测量尺子敏感度而非 pipeline 缺失。

### 维度7 Safety — 🟡 半截（主体 ✅，缺 taint 追踪）

- **SafetySkillHook（命令黑名单 / 路径穿越）: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/engine/SafetySkillHook.java:28-204` + `DangerousCommandChecker.java:17-84`（`rm -rf /` / sudo / mkfs / dd / fork bomb / shutdown 正则；Write/Edit 路径保护 `/etc/` `~/.ssh/`；Read 敏感文件 id_rsa；在 beforeSkillExecute 拦截）。
- **ToolApprovalRegistry + Pre-tool-use 权限 hook（allow/ask/deny 三档）: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/engine/confirm/ToolApprovalRegistry.java:13-53`（一次性 token issue/consume）+ `Decision.java:14-35`（APPROVED/DENIED/TIMEOUT）+ `ConfirmationPrompter.java`（SPI 阻塞 prompt）+ `PendingConfirmationRegistry.java:15-100`（latch + 决策状态，HTTP / 飞书 WS 回调唤醒）。
- **SkillSecurityScanner: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/security/skill/SkillSecurityScanner.java:20-259`（skill 包导入扫 skill.md / _meta.json / package.json，9 项规则 pipe-to-shell / secret exfil / destructive / prompt injection / encoded payload / secret literal / symlink / file-too-large，返回 ALLOW / ALLOW_WITH_WARNINGS / BLOCK + HIGH/MEDIUM/LOW）。
- **MCP SSRF guard: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/mcp/service/McpServerService.java`（~145-200 `isDisallowedAddress`：拒 loopback / any-local / link-local 含 169.254.169.254 元数据端点 / site-local 10·172.16-31·192.168 / multicast / IPv6 ULA fc00::/7 / CGN 100.64/10，处理 IPv4-mapped IPv6 `::ffff:169.254.169.254`）；test `McpServerServiceTest` BUG-33 验证。
- **规则 XML 沙箱防注入: ✅** —— `/Users/youren/myspace/skillforge/skillforge-core/src/main/java/com/skillforge/core/context/SystemPromptBuilder.java:18-20` DANGEROUS_TAGS + 174-219 `appendCustomRuleGroup` / `sanitizeCustomRule`（自定义 behavior rule 用 `<user-configured-guidelines>` 标签沙盒，过滤 `<system|assistant|user|tool_use|tool_result|function|instructions>` → `[filtered]`，限 500 字符）。
- **防失控: ✅**（见维度1 no-progress / 时长 / 取消）。
- **关键缺口 taint/quarantine 追踪: ❌** —— `ToolApprovalRegistry` 判 allow/ask/deny 时**不吃**"agent 是否刚读过不可信外部内容"信号（`ToolApprovalRegistry.java:34-45` 纯 token + sessionId 匹配）；全代码无 `TaintTracker` / `QuarantineRegistry` / `UntrustMarker` 类；只有 prompt 工程层注释提"untrusted user data"（`MemorySynthesisLlmPromptBuilder` 等）警告 LLM，不是运行时污点信号。即"读不可信内容后自动降权（quarantine / 污点追踪）"缺失。

**现状一句话**：命令黑名单 / 路径穿越 / 三档审批 / skill 扫描 / SSRF / XML 沙箱 / 防失控全到位，唯独缺"读不可信内容 → 自动降权"的污点追踪。

**缺口**：无 taint / quarantine 自动降权（`ToolApprovalRegistry` 不消费"读过外部内容"信号）。

### 维度8 Skill 系统 — 🟡 半截（缺低使用 curator）

- **system-skills vs user-skills 两层: ✅** —— `/Users/youren/myspace/skillforge/system-skills/` 目录（browser / clawhub / github / grill-me / skill-creator / skillhub 等内置）；`SystemSkillLoader.java:32-107`（启动扫 system-skills/，is_system=true owner_id=NULL，同名覆盖用户 skill，system skill 不可 DELETE 403）；`UserSkillLoader.java:34-82`（加载 `t_skill` is_system=false 且 enabled=true）。
- **SurfaceRegistry: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/improve/surface/SurfaceRegistry.java:32-79`（Spring 注入所有 `OptimizableSurface<?>`，按 surfaceType 分发 skill / prompt / behavior_rule）。
- **SkillEntity usageCount / successCount: ✅** —— `/Users/youren/myspace/skillforge/skillforge-server/src/main/java/com/skillforge/server/entity/SkillEntity.java:76-83`（usageCount / successCount / failureCount，init 0）；`SkillRepository.incrementStats()` 原子更新（喂 evolution 信号，V14）。
- **curator / 归档: ❌** —— 无 `SkillConsolidator` / `SkillCurator` / `SkillArchiv*` 任何文件（grep 0 命中）；usageCount 被统计但从不被消费做自动归档；`SkillService.deleteSkill()` 仅人工删除 REST 端点，无自动驱逐；对比 `MemoryConsolidator`（60 天冷 → ARCHIVED → delete）skill 无等价自动生命周期。即"低使用自动归档 skill 的 curator"缺失（确认 pre-investigated 怀疑成立）。

**现状一句话**：两层 skill 加载 + SurfaceRegistry + usageCount/successCount 统计齐全，但缺一个按使用率自动归档低频 skill 的 curator（类比 `MemoryConsolidator` 之于 memory）。

**缺口**：无低使用 skill curator（usageCount 有数据但无消费它做归档/弃用的调度器）。

---

## 3. 纠正 wiki 假设

下列 takeaway 散落在 wiki（`/Users/youren/myspace/research-docs/research/agent-harness-wiki/` 各 harness/eval 页 frontmatter 的 `skillforge_takeaway` 字段）里，可能被写成"SkillForge 需求/缺口"，但代码证明"SkillForge 早就有 / 不适用"。主索引 `index.md`，SkillForge 自身页 `harness/skillforge.md`。本审计的作用是给"SkillForge 已有"打证据钉子，避免后续写成假需求。

1. **Memory 归档清理** —— 任何"建议 SkillForge 加 memory TTL / 归档 / 容量驱逐 / 去重"都已落地：`MemoryConsolidator` 四段（dedup / TTL-archive / capacity-demote / expired-delete）+ ACTIVE→STALE→ARCHIVED→物理删除全生命周期 + pgvector 余弦去重 + 5-turn stale 提醒。证据 `MemoryConsolidator.java:157-203` / `MemoryEntity.java:52-65`。→ **不要再列成需求。**
2. **Tool-approval / 危险动作确认** —— 任何"建议 SkillForge 加危险动作人工确认 / allow-ask-deny 权限门"都已有：`ToolApprovalRegistry` + Pre-tool-use 三档（APPROVED/DENIED/TIMEOUT）+ HTTP / 飞书回调唤醒。证据 `ToolApprovalRegistry.java:13-53` / `Decision.java:14-35` / `PendingConfirmationRegistry.java:15-100`。→ **不要再列成需求。**
3. **命令黑名单 / 路径穿越 / SSRF / XML 注入沙箱** —— 都已有（`SafetySkillHook` / `DangerousCommandChecker` / `McpServerService` SSRF / `SystemPromptBuilder` XML 沙箱），别当缺口。
4. **DREAMING approve → 执行管线** —— 之前怀疑"批准后执行未装"，代码证明已接通（`MemoryProposalService.approve()` switch 直接 `apply*` 改 `t_memory`）。真正缺的只是"自动批准"（无 auto-approve），别误写成"执行管线缺失"。
5. **SubAgent 树 + Team 网状 + agent 间通信** —— 都已生产级（`SubAgentTool` / `TeamCreateTool` / `TeamSendTool` + 持久化邮箱），别列成需求。
6. **A/B eval pipeline + 机器判分 oracle + 自动晋升** —— 已有（`AbEvalPipeline` / `BehaviorRulePromotionService`），别列成需求；eval 真正的待治项是"判定尺子"（绝对打分 / 对靶），不是 pipeline 本身。

## 4. 值得注意的现状（观察）

- **真正的 4 个明确缺口**（全平台仅此 4 处，按 ROI / 影响排）：
  1. Self-Evolution 类4 autoEvolving loop 空转 0 真赢家（EVOLVE-JUDGE-GROUNDING active 在治，**测量尺子问题不是框架问题**）；
  2. Safety taint / quarantine 污点追踪缺失（读不可信内容后不自动降权）；
  3. Skill 低使用 curator 缺失（usageCount 有数据没人消费）；
  4. Self-Evolution 类3 per-goal token budget 完全没有 + 类1 DREAMING 无自动批准。
- **一个值得注意的不对称**：Memory 有完整自动 curator（`MemoryConsolidator`），Skill 却没有 —— 同样有 usageCount/successCount 信号，同样的归档模式可复用，这是最"低垂果实"的缺口（把 `MemoryConsolidator` 的 TTL / 容量模式套到 `SkillEntity`）。
- **persistence-shape 不变量**是"靠规则 + review + 测试模板"而非"引擎内联运行时断言"保障 —— 在 compact 重灾区（已知 footgun）这是**有意识的防御纵深**（CLAUDE.md 列了 `persistence-shape-invariant.md` 规则 + compact-reviewer 8 不变量 + `java.md` footgun #4/#5），不是疏漏，但若要再加固可考虑引擎层 roundtrip 断言。
- **input token 预算默认 opt-in** 是有意为之（P9-2 注释），但对"防失控成本"而言，长任务默认不强制 token 上限是一个值得 review 的取舍。
