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

## 分期 & 顺序（D5/D6 已 ratify 2026-06-03）

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
