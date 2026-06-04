# tech-design — AUTOEVOLVE-CLOSE-LOOP

> 状态：draft，待 ratify + 分期 Plan 对抗 review。
> 关联代码：`EvolveController` / `GenerateCandidateTool` / `PromoteCandidateTool` / opt-report workflow + aggregator seed / `PromptImproverService` `BehaviorRuleImproverService` `SkillDraftService` 的 promote 路径 / `AbEvalPipeline` / dashboard `EvolveTrajectoryPanel`。

---

## Phase 1 — 闭环采纳（adopt flow）

### 现状（证据）
- `EvolveController` 只有 `POST /agents/{id}/run`（无 promote/adopt）。
- `PromoteCandidateTool` 对 `surface=agent` 明确拒绝（"promote each surface 分别来"）。
- 各面已有 promote 服务：prompt→`PromptPromotionService.evaluateAndPromote`（gate 15pp + 24h cooldown 等）；behavior_rule→`BehaviorRulePromotionService.promoteManual`；skill draft→`SkillDraftService.approveDraft`（渲染 SKILL.md 落盘 + 建永久 SkillEntity）。
- dashboard `EvolveTrajectoryPanel` 只展示轨迹，无采纳按钮。

### 设计
1. **新 `AgentBundleAdoptionService`**（编排各面 promote）：
   - 输入 `evolveRunId` + 选中的赢家 bundle `{promptVersionId?, behaviorRuleVersionId?, skillDraftId?}` + 触发用户。
   - 对每个非空指针调对应面的**现成 promote service**（注意：evolve 的 gate 是 vs-best/整体分，跟单面 promote service 自己的 gate 阈值不同 → 采纳走"人已决策"路径，**绕过单面 gate 的自动阈值、但保留权限/cooldown/原子写**；或加一个 `adopt(force-by-human)` 入口）。
   - **D1 原子性**：推荐"尽力 + 逐面状态"——各面 promote 独立事务，返回 `{prompt: ok, rule: ok, skill: failed(reason)}`；半成不回滚但响亮报（各面本就独立可重试）。可选全回滚留 D1 ratify。
   - skill 面：`approveDraft` 把赢家 draft 渲染落盘 + 建永久 skill（draft 一直在 DB，采纳时才落盘——Phase 4 设计已定）。
2. **REST**：`POST /api/evolve/runs/{evolveRunId}/adopt`，body = 选中 bundle，权限 guard（非 system user），返回逐面结果。
3. **前端 `EvolveTrajectoryPanel`**：选中一条赢家 iteration → 展示该 bundle 各面 **diff**（prompt 版本 vs 现役 diff / rule 列表 diff / skill draft 内容）→ **Approve & Adopt 按钮** → `Modal.confirm` → 调 adopt 端点 → 逐面结果 toast。**对齐 Claude Code `/insights` "建议而非改、决策权 100% 用户"**。

### 风险
- evolve 整体 gate vs 单面 promote gate 不一致 → 采纳用"人已决策"force 路径，但保留权限/cooldown/审计。
- 各面 promote 的副作用（active 版本切换 / canary）—— 复用现成路径，不新造。

### ✅ 已 ratify（2026-06-03，含 Explore 锚点）— Mid 档，整包单审批

**用户拍定 UX**：**一张卡（各面 diff + 整体提升）+ 一个 Approve&Adopt 按钮 → 整包生效**，不逐面审批。结果反馈逐面显示（prompt✓/rule✓/skill✓）但不要用户操作。

**各面 promote 路径（human 就是 gate，绕自动门槛）**：
- **prompt**：`PromptPromotionService.evaluateAndPromote` **只有自动 gate（15pp+cooldown），无 force 路径** → **必须新增 `promoteByHuman(promptVersionId, agentId, userId)`**（复用现成原子写 `:89-124`：deprecate old → activate new → update agent，跳 delta/cooldown，保幂等+审计）。← P1 唯一核心新代码。
- **behavior_rule**：直接调底层 `BehaviorRulePromotionService.promote(v)`（已 public `:147`，绕 dual-criteria，只做 atomic 状态写）。
- **skill**：`SkillDraftService.approveDraft(draftId, userId, forceCreate=true)`（绕 high-similarity gate；handle `SkillNameConflictException` 逐面报错）。

**赢家 bundle 取法**：存在 `t_flywheel_run_step.step_output_json.candidateBundle{promptVersionId?,behaviorRuleVersionId?,skillDraftId?}` + `kept` —— 取最后一条 `kept=true` iteration。**`EvolveIterationDto` 现未暴露 candidateBundle → 要扩 DTO + `EvolveReadService.parseIterationStep` 解析**（无新表）。null 面=现役原版不动。

**新 `AgentBundleAdoptionService`** 编排三面，best-effort + 逐面结果 `{prompt:ok, rule:ok, skill:failed(reason)}`（D1：半成不回滚、响亮报，各面独立可重试）。

**REST**：`POST /api/evolve/runs/{evolveRunId}/adopt`，body = bundle 指针，权限 guard。

**FE**：新 `EvolveAdoptCard`（赢家 bundle 各面 diff + 一个按钮 + Modal.confirm），复用 `SkillMdDiff.tsx` 做文本 diff；`api/evolve.ts` 扩 `EvolveIteration` 加 candidateBundle + `adoptEvolveBundle`（footgun#6 契约 BE DTO↔FE type 同步）。

**档位 Mid**：无新表/无 migration（不加 bundle 级采纳审计表——各面 promote 已各自审计；P3 north-star 真要 bundle 级追踪时再加，那时本就动 schema）。跨 BE(improve+evolve)+FE。

**已知**：干净 evolve 重跑（2026-06-03）两轮候选都 kept=false（无赢家）→ **P1 采纳按钮现在会空转**（没真赢家可采）；让它有东西可采靠 **bad-case 收割**（A/B 接真失败场景，下一期）。P1 先把闭环基础设施搭好。

---

## Phase 2 — 对靶改进（on-target，依据 AHE + Claude Code /insights）

### ⚠️ 优先级重排（2026-06-03，经验测试后）

跑通 G4 后用 **agent 1（Design）opt-report 活体经验测试**，发现**报告完整性是比候选 grounding 更上游的瓶颈**（详见 G5）。用户拍板：**先做报告完整性（G5），P2-b 候选 grounding（G1+G2）延后**（plan R1 已存 `/tmp/plan-p2b-grounding-r1-DEFERRED.md`，待 G5 ship 后恢复）。

**经验证据（agent 1 opt-report runId 2c0d2992，2026-06-03）**：报告产出 5 条 issue（质量不差，引用真实 session id + 工具计数：git 子目录 pathspec / 工具过度使用 token 膨胀 / CSS 反复修 / 调试超时 / 模糊需求大改），但：
- **细粒度前置模式被揉进粗桶**：Edit 相关失败被并进 issue 2"工具过度使用/token 膨胀"，没被单独拎成"Edit 失败因没先 Read"这类 actionable 前置条件 issue。
- **recurrence 全 =1**：agent 1 无失败 cluster（annotator 关 13 天 + 其 session 没聚类，cluster 表只有 agent 9/7/8/2）→ G4 加权对 agent 1 完全哑火，报告纯靠 aggregator 单遍读 20 session 撑起。
- 根因：**单遍 LLM 读一个窗口 → 挑最响模式、把细粒度跨 session 前置模式折叠**；加上 recurrence 信号缺 cluster 时哑掉。

### G5（新，最高优先，**Mid 档**）— 报告完整性 / holistic error-span 分析。形态 X 两段式，已 ratify 2026-06-03。

**目标**：让报告把跨 session 的细粒度模式**单独拎出来**（而非折叠进粗桶），使 orchestrator 选问题时不漏。orchestrator 从报告挑问题出候选——报告漏/折叠了，下游候选 grounding（G1/G2）再好也救不回。

**Explore 现状锚点（2026-06-03）**：
- opt-report.workflow.js 4 phase（Load/Annotate fanout via `parallel()`/Aggregate/Approve）；aggregator 是**单遍 LLM 读一个 batch**（V141 prompt STEP6），易把细粒度前置模式折叠。
- error span 在 `t_llm_span`（kind=tool / error TEXT / error_type / iteration_index / started_at）；`LlmSpanRepository.findBySessionIdInOrderByStartedAtAsc` 能按时间序还原某 session 完整工具调用链；`findBySessionIdInOrderByStartedAtAsc(Collection)` 支持批量。
- **数据实锤折叠**：agent 1 近 30 天 100 条 error span，含 `Grep "Path is not a directory…/src"`×49(11 session) + `Edit "old_string not found in file"`×44(7 session)，4 元组聚类把它们揉进粗桶。
- 量级：agent 1 仅 100 条 error span，单 LLM 可承受；高频 agent 7/9 才需 map-reduce。现成 `parallel()` 可复用。

**形态 X 两段式（ratify）**：opt-report.workflow.js 在 Aggregate 前插一个 `holistic-error-span-analyzer` agent phase：
- **段 1 症状归类**：新只读工具（如 `LoadErrorSpanBatch(agentId, windowDays)`，查 `t_llm_span` kind=tool & error IS NOT NULL）→ 按 (工具 + 错误签名) 跨 session 分组计数。
- **段 2 根因诊断**：对 top-N 症状组，各拉 2-3 个代表 session 的**完整有序工具调用序列 + 任务**（可能复用 `GetTrace`，或加第二个 compact 工具——Dev 评估）→ agent 看序列推前置根因。
- 产出 `preconditionIssues` → **JS stringify 注入 aggregator 的 Aggregate user message**（路径 a，不改 aggregator 工具白名单）。
- 跟 G4 互补：G4 cluster 给"量/复现次数"（确定性），holistic 给"actionable 前置根因深度"。

**纪律（盲测完整性）**：holistic agent 的 prompt **写成完全通用**（"按工具+错误模式跨 session 归类、看代表序列推前置根因"），**绝不写具体修法**——让它自己从代表序列里推因。

**Mid 范围**：1-2 个只读工具 + 1 新 agent（V142 种 prompt）+ 改 opt-report.workflow.js + aggregator 注入 + 测试。无新表/无 DDL/单模块/无前端 → Mid。review 含 database-reviewer（新 SELECT）；若 reviewer 发现架构硬伤当场升 Full。配套：让 annotator 真跑补 cluster（recurrence 才不哑，已重开）。

### G1 + G2 = P2-b 子期（候选 grounding + 证据 re-hydrate）【延后，待 G5 后恢复】。路线已 ratify 2026-06-03：A + C-lite，跳过 B。

**现状锚点（Explore 2026-06-03）**：
- `GenerateCandidateTool`（`tool/evolve/GenerateCandidateTool.java`）输入只有 `surface / issue（自由文本）/ targetAgentId`；候选-gen LLM（`PromptImproverService.generateCandidatePromptFromAttribution`，约 1241-1260）只看到「一行 issue 文字 + agent 当前 prompt + memory + 可选反思块」，**无任何场景 input/expected/失败证据**。`OptReportIssueDto:96-100` 已预留 `targetScenarioIds` 扩展注释（未实现）。
- A/B target 子集机制：`AgentEvolveAbEvalService.resolveRoleSplit`（约 395-433）**只按 agent role**（`applicable_agent_roles` JSONB）划 target/general，**不支持显式 scenario id 列表**。`computeSubsetDeltas`（约 506-534）的 `targetIds`/`generalIds` Set 是注入点。`TriggerAbEvalTool` 已有 `evalScenarioIds` 参数但未贯通到 split。
- **benchmark 表现数据已存在**：`t_eval_task_item` 每场景记 `status / composite_score / quality_score / efficiency_score / latency_score / cost_score / loop_count / tool_call_count / latency_ms / agent_final_output / judge_rationale`。composite = 质量+效率(权重 0.20)+成本+延迟多维加权（`EvalScoreFormula`）。`t_eval_task`（COMPLETED run）按 agent 存。**现有数据**：agent 1（7 FAIL + 2 TIMEOUT）、agent 3（2 FAIL + 4 TIMEOUT）有 baseline run（2026-05-04，偏旧，要新鲜可重跑）；agent 9 无 benchmark run（系统 agent，benchmark 不适用）。

**G1 设计 — grounding target = 表现最差场景（不只 FAIL）**：
- target 子集 = 该 agent 最近 COMPLETED benchmark run 里 **composite 分最低的 bottom-K 场景**。这一刀同时网住：(a) 答错的（status=FAIL，质量分低）+ (b) **答对但低效**（status=PASS 但 loop_count 高 / latency 高 / efficiency_score 低 → composite 被拖低）。**用 composite 排序统一两类**，不单看 status=FAIL（否则漏掉"完成但轮太多/太慢"这类真优化点）。infra 失败（TIMEOUT）不计入（P3 的"infra 摘出"前置，P2-b 先排除 TIMEOUT/ERROR）。
- 新只读工具（如 `GetUnderperformingScenarios(agentId, k)`）：查最近 COMPLETED `t_eval_task` + `t_eval_task_item`，排除 TIMEOUT/ERROR，按 composite ASC 取 bottom-K，返回 scenarioId + 摘要。
- `GenerateCandidateTool` 加可选 `targetScenarioIds`；orchestrator 把 bottom-K id 透传。
- A/B target 子集 = 这些 id → 扩展 `AgentEvolveAbEvalService` 支持"显式 id 列表作 target"分支（不止 role）；A/B 量 composite delta（含效率维度）→ 砍轮数/提速的候选即使 pass-rate 已满也能涨出 composite。
- **跳过路 B（LLM 语义匹配 issue→场景）**：当前 bottom-K 才几个，全挂当 target 即可；多一层匹配是当前数据量下过度设计，FAIL/低效场景数涨了再议。

**G2 设计 — C-lite 证据注入**：
- 候选-gen LLM prompt 注入 bottom-K 场景的证据块（top-K 限量防 context 过载），按场景类型给信号：
  - FAIL → `task`（EvalScenario）+ `oracleExpected` + `agent_final_output` + `judge_rationale`（为什么判失败）。
  - PASS 但低效 → `task` + "完成但 loop_count=N / latency_ms=X / efficiency_score=Y"（让 LLM 知道要优化效率，不只纠错）。
- 素材全在 `t_eval_task_item` + `EvalScenarioEntity`，**无需新 schema**。
- **不做**完整 tool-call trace re-hydrate（要 join sessionId/trace 表，侵入 improver SRP 边界）→ 留 G3/后续。
- 三处 improver（prompt/`BehaviorRuleImproverService`/skill）证据块对等改动。

**demo agent = agent 1**（有 7 FAIL + 低效场景，bottom-K 信号足）。

### G3 — predicted_impact（P2-b 之后）
设计：`GenerateCandidate` 产出 + 候选元数据加 `predictedImpact: {flipToPass:[ids], riskToFail:[ids]}`；A/B 后拿真实 perScenario 翻转**对账**（反思 `priorEvalReport` 扩展成 prediction-vs-actual）；预测错 = 质量信号（喂下一轮 / 降信心）。

### G4 — report 丰富 facets + MULTIPLE TIMES（Claude Code /insights）= P2-a 首个 Full 子期

**现状锚点（Explore 2026-06-03）**：
- aggregator = `opt-report-aggregator` system agent，prompt 在 DB（`V128__seed_workflow_demo_agents.sql`），纯 LLM 在单 session 内读 raw annotation 归因，**无确定性聚合**。
- 10 步 loop 上限在 `DefaultWorkflowAgentInvoker.java:54`（`DEFAULT_MAX_LOOPS=10`）+ schema 重试 → session 批量大触顶 `max_loops_reached`。
- annotation = `t_session_annotation`（V74）只有 type/value/source/confidence/reasoning；issue = `OptReportIssueDto` + workflow `SUMMARY_SCHEMA` 只有一行 `suggestion`。
- **现成但被浪费的雏形**：`SessionPatternClusterService` 已按 `(outcome, suspect_surface, top_failing_tool, agent_id)` 4-tuple bucket + `member_count` 计数（门槛 ≥3）写 `t_session_pattern`，**但 aggregator 走 `LoadSessionBatchTool` 直接读 raw annotation 行，不读这张 pattern 表**。
- 候选侧 `GenerateCandidateTool` 只消费一个自由文本 `issue` 字段透传进 `PromptImproverService`。

**设计（D3/recurrence 路线已 ratify 2026-06-03）**：
- **recurrence = 混合路线**：复用 `SessionPatternClusterService` 的 4-tuple bucket + `member_count` 当**确定性 recurrence 骨架**接进 aggregator（aggregator 改读 pattern cluster 而非纯 raw annotation → 顺带缓解 10 步 loop：聚合不再全靠 LLM step）；LLM aggregator 在确定性骨架之上做**语义合并**（抓措辞不同但同义的 issue）。recurrence_count 来自 cluster member_count + LLM 合并后的跨 cluster 计数。
- **facets = 加 friction + recurrence**（outcome/surface 已有）：annotation/issue schema 加 `friction`（失败模式分类，裁成 ~6 enum，不照搬 CC 12）+ `recurrence`（重复计数/加权）；issue 侧 `suggestion` 拆成 `rootCause` + `proposedFix`。
- **MULTIPLE TIMES 效果**：反复出现的 issue 高置信 + 优先排序（vs 一次性噪声）。直接治"挑错靶子"（实测挑了一次性 tool-budget、漏 9 个反复失败）。

**红灯**：触碰核心 evolve loop（opt-report aggregator / annotation schema / issue schema）+ Flyway migration（schema 边界）→ **Full 档**。

### 影响
- 触碰核心 evolve loop（GenerateCandidate / improver / opt-report aggregator / 种子）→ 分子期，各 Full。

---

## Phase 3 — benchmark 验证

### 设计
- **north-star metric**：固定 held-out benchmark（现有 36 场景：GAIA/tau-bench/AgentBench/dogfood，D4 可 dogfood 扩）上的 agent 质量 pass-rate。
- **趋势**：每次采纳后跑一次 benchmark，记 `agent × benchmark_version × 分 × 时间`；dashboard 看**采纳累积是否真涨**。
- **infra 失败摘出**（用户确认要做）：`surface=other`（凭证/429/超时）的 scenario/ session 失败**不计入 agent 质量分母**（重试/排除/标 not-measured）；治污染 + 留 actionable 名额。这是 `EVAL-429-ROBUSTNESS` backlog 的泛化（→ 所有 infra 失败）。
- **基线对照**：每个 agent 记"原始 baseline 分"作不动对照，量 evolve 累积净提升。

---

## Phase BC — bad-case 收割（实现 D2(b)，**最高优先 unlock**，Full）

> 状态：tech-design 完成，spike 已 de-risk（见 `proposal-badcase-and-g3.md` Spike 结果：Edit stale 42/42 + Grep 53/53 可重建）。**用户 2026-06-04 授权直接驱动（"不用我决策了你搞吧"）**——本节即设计文档，自审后走 Full pipeline。
> 一句话：把真失败 session 转成隔离、可复现、多轮的 eval 场景，让 A/B 量在真失败上 → evolve 出真赢家 → P1 有东西可采 + read-before-edit 盲测可验证。

### 现状锚点（取证 file:line）
- eval 沙箱 = `SandboxSkillRegistryFactory:24-29`：new 空 `SkillRegistry`，**只注册** Sandboxed 版 Read/Write/Grep/Glob，**无 Edit / 无 Bash**。每场景 temp 目录 `$tmpdir/eval/{runId}/{scenarioId}`，跑完 `cleanupSandbox` 递归删。
- fixture 注入现成：`AbEvalPipeline.runSingleScenario:968` 把 `scenario.setup.files`（Map<path,content>）写进沙箱；task 里 `/tmp/eval/` 前缀替换成 sandboxRoot（`:965`）。**但 `EvalScenarioEntity` 无字段持久化 setup.files**——benchmark 场景的 fixture 来自磁盘 JSON，DB 场景（session_derived）无 fixture。
- oracle：`EvalJudgeTool` 仅 exact_match/contains/regex/llm_judge；composite=0.7*outcome+0.3*efficiency，pass≥40（`AbEvalPipeline:74`）。tool 调用错误已被 `ScenarioRunResult.applyToolCallSignals:139` 抓（遍历 `LoopResult.toolCalls`，记 skillExecutionFailed）。
- `FileEditTool`（tools 模块）：name="Edit"，required file_path/old_string/new_string，错误 "old_string not found in file"（:108）；`Path.of(filePath)` 直接用绝对路径（无沙箱）。
- 失败数据全在 `t_llm_span`（kind=tool / name / input_summary=完整 JSON args / output_summary=Read 内容 ~40KB / error / error_type）。
- A/B target split：`AgentEvolveAbEvalService.resolveRoleSplit` 只按 agent role，**不支持显式 scenario id 列表**（P2-b G1 延后项，本期需恢复）。

### 五个组件设计

**① SandboxedFileEditTool（新，eval 沙箱补 Edit）**
镜像 `FileEditTool` 的 edit 语义（读文件 → old_string 唯一匹配替换 new_string → 同样的 "old_string not found" / "not unique" / "does not exist" 错误），加 `SandboxedFileWriteTool` 同款 `Path.of(fp).normalize().startsWith(sandboxRoot)` 越界拒。注册进 `SandboxSkillRegistryFactory.buildSandboxRegistry`。**这是复现 Edit 失败的前提**（沙箱没 Edit 工具就没法重演）。

**② fixture 持久化（schema，→ Full）**
- migration `V143__add_eval_scenario_fixture.sql`：`t_eval_scenario` 加列 `fixture_files_json JSONB`（Map<相对路径, 内容>，nullable）。
- `EvalScenarioEntity` 加 `Map<String,String> fixtureFiles`（`@Column JSONB`，参考现有 `ruleTriggerHints`/`applicableAgentRoles` 的 JSONB List 写法）。
- `AbEvalPipeline.runSingleScenario` 写 fixture 时：现有从 `scenario.setup.files` 读 → 扩展为"DB 场景优先用 `fixtureFiles`，无则回落 setup.files"。**对账注意**：内容实测全 <40KB，单 JSONB 列够；多文件场景就是 map 多条目。

**③ 行为型 oracle（新 oracleType，无新列）**
- 新 `oracleType='tool_error_absence'`；判据 JSON 存进现有 `oracleExpected` TEXT：
  ```json
  {"tool":"Edit","errorSignature":"old_string not found","passWhen":"no_match","rounds":5}
  ```
- `EvalJudgeTool` 加分支：扫这次 run 的 `LoopResult.toolCalls`（已有 name + isSuccess + error），命中 (name==tool && error 含 errorSignature) → 该轮"复发"。passWhen=no_match → 无复发 outcomeScore=100 / 有复发=0。**不调 LLM judge**（便宜、确定）。
- 需把 toolCalls 透进 EvalJudgeTool 的判定上下文（现 applyToolCallSignals 已持有，接线即可）。

**④ 多轮复发率（core harness，→ Full）**
- `oracleExpected.rounds=N`（默认 5）。当场景 oracleType=tool_error_absence 且 rounds>1：`AbEvalPipeline` 把该场景跑 N 轮 → 复发率 = 命中轮数/N → outcomeScore=(1−复发率)*100；efficiency 取 N 轮均值；composite 沿用 0.7/0.3。
- A/B：baseline 复发率 vs candidate 复发率 → composite delta。砍复发=涨分。
- 成本意识：N×运行；默认 N=5，target 子集只挂收割场景控量。

**⑤ BadCaseHarvestService（新，重建场景）**
- 只读 span 重建，写一条 `EvalScenarioEntity`：
  - `task` = session 首条 user prompt（同 `SkillCreatorService:750` 先例）。
  - 失败 span（Edit stale / Grep notdir）→ 取 file_path → 同 session 该 path **前一次成功 Read/Write/Edit 的内容**（Read.output_summary / Write.content / Edit.new_string）当 fixture；Grep notdir 内容可空（建出该文件即复现）。
  - **路径 rebase**：剥 repo-root 前缀（`/Users/youren/myspace/skillforge/` → 相对路径）写进 `fixtureFiles`；task 里该前缀改写成 `/tmp/eval/`（复用现成替换）。
  - oracle = tool_error_absence（tool+errorSignature 来自失败 span）。
  - `sourceType=session_derived` / `purpose=regression` / `sourceRef=session:{id}` / `status=draft`。
- 产出 status=draft → 人审 activate 后才进 A/B（Iron-Law 人 gate；activate 走简单 endpoint/UI）。
- **target split**：恢复 P2-b G1 的"显式 scenario id 列表作 target"（扩 `AgentEvolveAbEvalService.resolveRoleSplit` / `computeSubsetDeltas` 注入点；`TriggerAbEvalTool.evalScenarioIds` 贯通到 split）。

### 实现里程碑（Full pipeline 内分两段）
- **BC-M1（闭环打通证明，盲测安全）**：①SandboxedFileEditTool + ②fixture 列 + ③行为 oracle（单轮）+ ⑤harvest（Edit 类，手动选 1 个真 bad case）→ 生成场景 → **跑 baseline 证明失败复现 + oracle 正确判 FAIL**。oracle 的 **PASS 分支用合成无错运行测**（fixture 内容含 old_string → 普通 agent 成功 → 验 oracle 返回 PASS），**不手搓任何修复 candidate**。**真正的 FAIL→PASS 翻转留给 evolve loop 自己发现**（这就是盲测验证的 payoff，我不能代劳写出答案）。
- **BC-M2（鲁棒 + 集成）**：④多轮复发率 + Grep 类 + ⑤target split + orchestrator 接线（evolve 自动收割/对靶）+ dashboard 展示收割场景。

### 测试计划
- `SandboxedFileEditToolTest`：越界拒 / old_string 替换 / not-found / not-unique（镜像 FileEditTool 错误）。
- `EvalJudgeToolBehavioralOracleTest`：命中/未命中签名 → outcome 0/100；多轮复发率映射。
- `BadCaseHarvestServiceTest`：Edit stale span → 重建出 task+fixture+oracle；路径 rebase 正确；无 prior content 时跳过/降级。
- `AbEvalPipeline` fixture 列回落测试（DB fixtureFiles 优先于 setup.files）。
- IT：harvest 真 span（用测试 fixture span）→ activate → A/B 单轮 → 断言 baseline FAIL / 注入修复 candidate PASS。
- **migration**：`V143` 跑 + 回滚验证（database-reviewer）。

### 验收点
- [ ] eval 沙箱能执行 Edit 并复现 "old_string not found"（SandboxedFileEditTool）。
- [ ] 一个真 Edit bad case 被重建成 session_derived 场景（fixture + 行为 oracle，路径 rebase 正确，真文件零触碰）。
- [ ] BC-M1：baseline 在该场景 FAIL（错签名复发）+ oracle 正确判 FAIL；oracle PASS 分支经合成无错运行验证（**不手搓修复 candidate**）。
- [ ] BC-M2：多轮复发率口径 + 显式 id target split 生效；evolve 能把收割场景当 target 量出 target_delta。
- [ ] **盲测闭环**：收割逻辑/oracle/prompt **全程无 "Edit 前先 Read" 字样**，让 evolve loop 自己发现该修法能让收割场景 FAIL→PASS。

### 盲测纪律（贯穿，最高约束）
harvest service / 行为 oracle / 任何 prompt / 本设计文档 **只描述通用动作**（"复现失败签名、量复发率、修法使签名消失"）。**绝不在任何持久产物里写 "先 Read 再 Edit" 这类具体修法**。BC-M1 的手搓 candidate 仅用于本地验证整链通不通，**不入库、不进 prompt、不写文档**。

### 风险
- **Edit 失败的非确定性**：eval temp=0 较确定，但 agent 仍可能偶发先读；正是多轮复发率要捕捉的（M1 单轮先证存在性，M2 多轮给稳健率）。
- **沙箱 Edit 与生产 Edit 行为漂移**：SandboxedFileEditTool 必须逐字镜像 FileEditTool 的匹配/错误语义，否则复现的不是同一失败 → 测试逐条对齐（**已用 `SandboxedFileEditToolErrorParityTest` 把"错误串逐字一致"变 CI 守卫**）。
- **pool 小（~23 session）**：M1 只需 1 个；M2 验证够用；随 session 增长。
- **多轮成本**：N=5 + target 子集限量；orchestrator 接线时注意别全量场景多轮。

### BC-M1 交付状态（Full pipeline 完成，待 commit）
4 组件全实现 + 20 测试绿（SandboxedFileEditTool 6 / Parity 3 / 行为 oracle 4 / harvest 4 / fixture 回落 3）。3 reviewer（java + database + design）全 PASS、0 blocker；warning 一轮 fix（path-traversal 防护 / 跨工具错误串等价测试 / isDeterministicOracle 谓词 / 3 limitation javadoc / repoRoot 卫生）。

**诚实边界**：harvest 的"真 bad case 重建"由**代表性 span 单测**覆盖（4 case：Read 重建 / Write 重建 / 无内容跳过 / 非失败 span 跳过）；**未做活体跑真 DB session 出场景**——M1 按 scope 不含触发 endpoint（harvest=service 方法 + draft 人 gate，activate/触发留 M2）。"真文件零触碰"已结构性坐实：harvest 纯 DB 读 + 写 draft 行，无任何文件系统写；沙箱写 fixture 已加 path-traversal 防护。

### BC-M2 范围 + 本期遗留 backlog
**BC-M2 拆两子期**（M2 全做太大，按"先把测量做对、再接进 evolve"切）：

#### BC-M2a — oracle 测量正确性（本期，Mid）
1. **oracle do-nothing soundness 真修**（design-W3，**最高优先**）：当前行为 oracle 只判"错误签名缺席"——啥都不干/不调 Edit 也 PASS → evolve 把它当 target 时"回归成不编辑"被记成进步。
   - **oracle v2（结果型，盲测安全）**：pass = **(目标工具被成功调用过 = 任务有 engagement) AND (错误签名未复发)**。
     - 啥都不干（目标工具没调）→ 不 pass ✓ 关闭 gap
     - 调了目标工具但出错签名 → 不 pass ✓（失败）
     - 调了目标工具且无错签名 → pass ✓（成功完成那次编辑）
   - **仍是结果型**：只说"在该文件上无错完成一次目标工具操作"，**不说 HOW**（修法仍留给 loop）。**盲测安全**。
   - **考虑 path-scope**：engagement 应限定在收割失败的那个 file_path（防 agent 去改别的 trivial 文件刷过）。dev + reviewer 定要不要 path-scope。
2. **多轮复发率**：`oracleExpected.rounds=N`（默认 5）；AbEvalPipeline 对行为 oracle 场景跑 N 轮 → 复发率 = 命中轮数/N → outcomeScore=(1−率)*100；efficiency 取 N 轮均值；composite 沿用 0.7/0.3。
   - **⚠️ temp=0 caveat（诚实）**：eval 强制 temp=0，N 轮可能近确定性 → 复发率退化成 0/1（不平滑）。多轮的价值在于**捕捉跨轮残余非确定性**（provider 采样 / 工具顺序）；确定性时退化成 0/1 也仍正确（只是不平滑）。值得做（用户 D-2 明确要复发率口径），但别期待平滑率。
   - 范围：AbEvalPipeline 多轮执行 + 聚合（核心 A/B 执行语义，bug 会静默污染所有 evolve 信号 → review 重点）。
   - 档位 **Mid**（无 migration，rounds 在 oracleExpected JSON；AbEvalPipeline 非"核心文件清单"项）；reviewer 若发现多轮聚合架构硬伤 → 升 Full。java-reviewer + java-design-reviewer（oracle soundness 是设计判断）。

   **✅ BC-M2a 已交付（Mid，commit 待填）**：oracle v2（engagement + path-scope + 无错签名）+ 多轮复发率（rounds 默认 5，缺省退化单轮）+ 抽 `BehavioralOracleCriteria` record 单点解析（消除判据 JSON 跨模块两处解析）。java + java-design reviewer **全 PASS 0 blocker**（含盲测结构性审查通过——oracle v2 仍结果型）。warning 一轮修（删 DEFAULT_BEHAVIORAL_ROUNDS 死常量 + BehavioralOracleCriteria record + infra 轮 efficiency 也归 0 闭合 inflation + representative 注释）。验证：全模块 **2694/0/0** BUILD SUCCESS。**temp=0 caveat 仍在**（多轮率可能退化 0/1，已 javadoc 标注，不加扰动）。

#### BC-M2b — 接进 evolve 端到端（下一期，Full）

> **2026-06-04 用户重定调 + 并入 G3**（待 ratify）。读代码取证后两点变更触发重定调，见下「重定调缘由」。

##### 重定调缘由（读代码取证 2026-06-04）
1. **SandboxedGrepTool 复现不了目标错**：沙箱 Grep 对 path=文件用 `Files.isRegularFile()` **直接搜文件、不报错**；生产 `GrepTool.java:86` 才对非目录抛 `"Path is not a directory"`。→ Grep 收割前提是给沙箱 Grep 做 **parity**（镜像生产），跟 BC-M1 给 Edit 做 parity 同模式。
2. **BC-M2a 的 path-scoped engagement 对 Grep 不可满足**：oracle v2 的"engagement=目标工具在 filePath 上成功调用"对 Edit 干净，但 Grep 的对的修法**根本不再碰那个文件** → "在 filePath 上成功调 Grep"永远满足不了 → oracle 永远 PASS 不了。

##### 用户重定调：错题本去工具特殊化 + 两套用例分工
- **一条通用 pipeline**：任何工具（Edit / Grep / 以后）的报错都走同一条「报错 → 进错题本 → A/B」，不按工具分叉判分。
- **两套用例各管一头**：**benchmark** 看分数（能力守门）；**错题本** 看"还犯不犯那个错"（靶向）。赢家 = 错题本那个错没了 **且** benchmark 分没掉。

##### 设计变更（本期 BC-M2b 实做）
1. **通用多工具收割**：`BadCaseHarvestService` 从 Edit 专用泛化为「按 (工具, 路径输入字段, 错误签名集) 适配」驱动。Edit：路径字段=`file_path` / 签名集=`KNOWN_EDIT_SIGNATURES`；Grep：路径字段=`path` / 签名=`"Path is not a directory"`，fixture 内容可空（建出该文件即复现）。加新工具=加一行适配。
2. **SandboxedGrepTool parity**：镜像 `GrepTool.java:86` —— path 非目录 → `"Path is not a directory: <path>"`；加 `SandboxedGrepToolErrorParityTest`（逐字对齐生产错误串，CI 守卫）。⚠️ **回归核查**：现有 benchmark 场景无给 Grep 传文件路径者（dev 验证，否则改语义会破存量场景）。
3. **oracle 通用化（动 BC-M2a 已交付语义）**：`tool_error_absence` 判分由 BC-M2a 的「engagement=目标工具在 filePath 上成功调用 AND 错误签名缺席」改为：
   - **PASS = (本轮 agent 至少调用过 1 次任意工具，即非 no-op/拒答) AND (错误签名在 filePath 上未复发)**
   - **去掉** path-scoped engagement（对 Grep 不可满足）；`filePath` 仍用于**限定错误签名匹配范围**（只有发生在该路径的错才算复发），不再当 PASS 闸门
   - 防"假进步"主力交给 **benchmark 子集同跑**（do-nothing → benchmark 掉分 → 非赢家）；oracle 内只留通用 do-nothing/拒答兜底（≥1 工具调用）
   - **盲测安全**：仍是结果型，只说"没再犯那个错 + 真干了活"，不说 HOW
   - **design-reviewer 专项**：确认放通用化后「do-nothing 假进步」洞由 benchmark 子集兜得住（两套用例必须同跑，见 #4）；这是 BC-M2a 反例的回归点，重点审
4. **两套用例同跑**：harvest-targeted A/B 运行**必须同时覆盖** benchmark(general) + 错题本(target) 两子集。`computeSubsetDeltas` 已分别算 `targetDeltaPp` / `regressionDeltaPp`；赢家判据 = `targetDelta>0 AND regressionDelta≥0`。orchestrator 接线确保 dataset version 含 benchmark 场景，**不可 target-only 跑**（否则 do-nothing 守门失效）。
5. **显式 scenario-id target split**（恢复 P2-b G1）：扩 `AgentEvolveAbEvalService.resolveRoleSplit` 支持显式 id 列表作 target；`TriggerAbEvalTool.evalScenarioIds` 贯通到 split。
6. **activate 人 gate**：草稿→active endpoint + UI 按钮（写 `reviewedAt`）。
7. **orchestrator 接线**：evolve 自动收割（ERROR 聚类 → 代表 session → harvest draft）+ 自动拿 **active** 收割场景当 target。**盲测扫描**：接线代码/prompt 不得出现任何具体修法字样。
8. **dashboard**：收割场景列表（draft 待启用 / active）+ 启用按钮。

##### G3 — predicted_impact（用户 2026-06-04 要求并入本期）
每轮候选带**可证伪预测**，A/B 后对账：
- 候选 schema 加 `prediction: {targetProblem, flipToPass:[scenarioId], riskToFail:[scenarioId]}`
  - `targetProblem` 命名**问题**（"Edit old_string not found 类复发"），**不是修法** —— 盲测安全
  - `flipToPass` / `riskToFail` 引用真实 `scenarioId`（错题本提供，所以排错题本之后）
- **对账纯函数** `reconcile(prediction, abResult)`：逐场景比预测翻转 vs 实际翻转 → `{hits, misses, surprises, confidence}`
  - 预测准（flipToPass 真 fail→pass）→ 高信心
  - 预测说翻 Y 但 Y 没翻 → 修法不对 / 归因错 → 降信心 + 下一轮换思路
- **反思接线**：对账结果喂回 orchestrator 下一轮 reasoning + confidence
- **UI**：迭代行展示「预测 vs 实际翻转」
- **盲测安全**：预测只描述问题与场景 id，从不描述修法；对账是通用纯函数，不编码任何 problem→fix 映射

##### 收割现场重建机制 + 归因位置（2026-06-04 澄清）
**Q1 — 错题怎么加进错题本（确定性重放，非模型生成）**：
- 错题的三件套全是**真实回放**，不过模型：任务=代表 session 的**原始 user prompt 原样**（仅把仓库前缀 → `/tmp/eval/`）；现场文件=失败操作前**那次成功 Read 的 `output_summary`**（全文，≤40KB）回填成 fixture；判分=失败 span 的**真实错误签名**。
- **为何不模型生成任务**：① 忠实复现（真任务才复现真错）；② **盲测安全**（模型总结易顺嘴写出修法 → 泄 held-out；确定性重放不知修法泄不了）。模型只在上游归类/起名（命名问题非修法）。
- **沙箱搭现场**：`AbEvalPipeline.runSingleScenario` 建临时沙箱根 → fixture map 按相对路径写成真文件 → 任务里 `/tmp/eval/` 改写成沙箱根 → agent 看到文件真实存在、内容/路径对。
- **"运行成功" ≠ Edit 成功**：baseline 重放目标是**复现错**；重放是新跑 agent 自己生成操作，会不会再犯靠**多轮复发率**量。fixture 只负责还原现场。
- **真文件零触碰**：纯读 span + 写 draft；重放全在临时沙箱（Sandboxed 工具越界拒 + 跑完递归删）。
- **边界（best-effort，不造假题）**：内容截断 / 依赖未 Read 的他文件 / 只有 Edit-new_string 无全文 → **重建不全就跳过这条**（`priorContent==null` return empty）。

**Q2 — `topIssues` 与归因位置**：归因 + surface 映射在 `RunWorkflow(opt-report)` 那个 workflow **内部**就做完了；`GetOptReport` 只是只读工具，把已算完的 `summary_json` 读出来（`GetOptReportTool:229` 复用 `OptReportSummaryParser`）。`topIssues[]` 每条已带 `suspectSurface / fixSurface / effectiveSurface / convertible / rootCause / recurrence`。编排器**不重做归因**，只挑 convertible 的迭代。**已知有损**（5-enum 消化丢信号 = 4 跳 trace 断）由 P2-a(G4) 富化字段 + **本期错题本（直接喂可复现真场景当 target，绕开消化）** 双治。

##### 分段交付（本会话内 3 个 Full review 子轮，各自 commit、等用户批准）
- **子轮 1（测量底座）**：通用多工具收割（Edit+Grep）+ Grep parity + oracle 通用化 + 显式 target split + 两套用例 deltas。Review 重点：oracle 语义变更（design-reviewer 盯 do-nothing 回归）+ 盲测扫描。
- **子轮 2（接进 evolve）**：activate endpoint/UI + orchestrator 接线 + dashboard。Review 重点：orchestrator 盲测扫描 + 两套用例同跑保证。
- **子轮 3（G3 预测对账）**：候选 prediction 字段 + reconcile 纯函数 + 反思接线 + UI。Review 重点：盲测（预测不含修法）+ 对账纯函数正确性。

##### 验收点（修订）
- [ ] 任意工具报错走同一收割路径（Edit + Grep 实证；加第三工具只需一行适配）
- [ ] SandboxedGrepTool 复现 `"Path is not a directory"`（parity test 逐字对齐生产）
- [ ] oracle 通用化后：no-op 候选不 PASS（≥1 工具调用兜底 + benchmark 掉分双保险）；真修好 → 错题本 fail→pass 翻转可量
- [ ] 两套用例同跑：赢家 = `targetDelta>0 AND benchmark regressionDelta≥0`
- [ ] 显式 scenario-id target split 生效
- [ ] activate 人 gate（draft→active）endpoint + UI
- [ ] orchestrator 自动收割 + 自动对靶；盲测扫描全产物无修法字样
- [ ] G3：候选带 prediction；A/B 后对账输出 hits/misses/surprises + confidence；反思喂回下一轮；预测全程不含修法
- [ ] **盲测闭环**：read-before-edit（及 Grep 等价修法）不在任何产物，让 loop 自己发现

##### 做完 BC-M2b：evolve 自转（找真失败 → 出错题 → 考新旧 agent → 选真赢家 + 带预测对账）→ P1 采纳有东西可采 → 盲测 loop 内可验。

**本期遗留 backlog（小，非 M2 主线）**：
- `extractFirstUserPrompt` 与 `SkillCreatorService` 重复 → 抽 `SessionMessageJson` util 共用（碰 scope-out 文件，专门 refactor 或 M2 顺带）。
- `output_summary` 截断标记检测（db-W2）：源内容超列上限静默截断 → 残缺 fixture。当前 pool 实测无截断；M2 加截断标记检测则跳过（需先确认 `LlmSpanPersistenceService` 截断标记格式）。

---

## 分期 & 顺序（D5/D6 已 ratify 2026-06-03；2026-06-04 bad-case 升最高优先）

**总顺序（D5）**：P2 对靶先 → P1 闭环采纳 → P3 benchmark 贯穿。理由：先让赢家是真提升，再给采纳按钮（否则一键采纳一堆平手/噪声候选意义不大）。

**P2 内部切分（D6）**：拆两个 Full 子期——
- **P2-a = G4（先做）**：report 对靶。aggregator 跨 session/场景做语义重复识别 → MULTIPLE TIMES 重复加权（反复出现=高置信=优先）+ annotation/issue schema 加 recurrence/outcome/friction typed facets。直接治"挑错靶子"。
- **P2-b = G1+G2**：候选 grounding。issue 携带 benchmark baseline FAIL 场景 ids（D2 (a)）+ 候选-gen LLM 注入这些场景的输入/期望/失败 trace（re-hydrate）+ A/B target 子集 = 这些场景 → "issue→候选→A/B" 闭合到同一套可复现用例。
- **G3 predicted_impact**：排 P2-b 之后。

**D2 已 ratify**：grounding 来源先用 (a) benchmark baseline FAIL 场景；(b) session 失败转可复现场景留后续。

## 已知关联 backlog（并入本包或引用）
- 旧 skill A/B 迁 A（统一，已 patch baseline bug `c227d2a`）
- opt-report aggregator 撞 10 步 loop 上限（report 质量降）
- `column "embedding" does not exist`（MemoryConsolidator 去重）
- 凭证健壮性（启动前校验 + 失败 fallback 模型）
