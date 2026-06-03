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

### G1（最高 ROI）— 候选 grounding 接可复现失败场景
现状：候选从 opt-report **session issue** 出（主观、不可复现），A/B 拿**无关固定场景**量 → 脱节。
设计：
- issue 携带 **failing eval scenario ids**（候选要修的具体可复现失败）。来源（D2）：(a) 直接取 agent 在 benchmark 上 baseline FAIL 的场景；(b) 把 top session 失败转成可复现 eval 场景（更难）；先做 (a)。
- `GenerateCandidate` 输入加 `targetScenarioIds`；候选-gen LLM prompt 注入这些场景的**输入 + 期望 + agent 当前失败表现**（trace re-hydrate，G2）。
- A/B 的 target 子集 = 这些场景 → "issue→候选→A/B" 闭合到同一套可复现用例。

### G2 — trace re-hydrate
现状：出候选 LLM 只看一行 suggestion（`PromptImproverService` attribution block），trace 链断 4 跳。
设计：候选-gen 时把 `targetScenarioIds` 的场景内容 + agent 失败 trace 摘要喂回 LLM（不只 suggestion）。

### G3 — predicted_impact
设计：`GenerateCandidate` 产出 + 候选元数据加 `predictedImpact: {flipToPass:[ids], riskToFail:[ids]}`；A/B 后拿真实 perScenario 翻转**对账**（反思 `priorEvalReport` 扩展成 prediction-vs-actual）；预测错 = 质量信号（喂下一轮 / 降信心）。

### G4 — report 丰富 facets + MULTIPLE TIMES（Claude Code /insights）
现状：5 surface enum + 一行 suggestion；无重复加权。
设计：
- annotation/issue schema 加更丰富 typed facets（D3，按我们数据量裁剪几维：outcome / friction / surface / **recurrence**）；`suggestion` 放开成 free-form `rootCause` + `proposedFix`（机器解析的 envelope 跟 insight payload 解耦）。
- **MULTIPLE TIMES**：aggregator 跨 session/场景做语义重复识别，**反复出现的 issue 优先 + 高置信**（vs 一次性噪声）。直接治"挑错靶子"（实测挑了一次性 tool-budget、漏 9 个反复失败）。

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

## 分期 & 顺序（D5）

两种顺序：
- **A（用户原话顺序）**：P1 闭环先 → P2 对靶 → P3 benchmark。先把"能落地"做出来，哪怕提升还不够对靶。
- **B（先让提升真）**：P2 对靶先 → P1 闭环 → P3。先让赢家是真提升，再给采纳按钮（否则一键采纳一堆平手/噪声候选意义不大）。

**林倾向 B 的轻量版**：P2 的 **G1（scenario-grounding）+ G4(MULTIPLE TIMES) 先做**（让提升真且对靶），紧接 **P1 闭环**（让真提升能落地），P3 benchmark 贯穿验证。但用户原话是 P1 先 —— **留 D5 ratify**。

## 已知关联 backlog（并入本包或引用）
- 旧 skill A/B 迁 A（统一，已 patch baseline bug `c227d2a`）
- opt-report aggregator 撞 10 步 loop 上限（report 质量降）
- `column "embedding" does not exist`（MemoryConsolidator 去重）
- 凭证健壮性（启动前校验 + 失败 fallback 模型）
