# tech-design — AUTOEVOLVE-AGENT-LEVEL-BUNDLE

> 状态：draft，待 Plan 对抗 review 打硬 + 用户 ratify Phase 1。
> 关联代码（前置已交付，本包改/扩）：`AbEvalPipeline` / `GenerateCandidateTool` / `TriggerAbEvalTool` / `GetAbResultTool` / `RecordIterationTool` / `PromptImproverService` / `BehaviorRuleImproverService` / `BehaviorRuleAbEvalService` / evolve-orchestrator 种子(agent id 19)。

## 0. 模型回顾

- **bundle（改动包）= 各面版本指针元组** `{promptVersionId?, behaviorRuleVersionId?}`（null = 用 agent 现役版本）。
- **best = 一个 bundle**（初始全 null = 原版）+ best 整体分 + best 逐场景结果。
- **候选 = best bundle 上替换这轮要改的那几个面**（新版本建在 best 包对应面的版本上 = 顺延生成）。
- **A/B = 整 agent**：`applicator(agent, baselineBundle)` → baselineDef、`applicator(agent, candidateBundle)` → candidateDef，跑场景 → **一个 pass-rate**。顺延：缓存 best 分 + skipBaseline 只跑候选臂 + 比。

## 1. 数据模型（复用各面版本表 + 指针元组）

**不新建 agent-快照实体**。候选/best 的"内容"仍存在各面现有版本表：
- prompt → `t_prompt_version`（现有，`improveFromBasePrompt` 产出 candidate 版本）
- behavior_rule → `t_behavior_rule_version`（现有，improver 产出 candidate 版本）

**新增 1 张表 `t_agent_evolve_ab_run`**（整-agent A/B 的运行记录，evolve loop 读它）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | VARCHAR(36) PK | abRunId |
| `agent_id` | VARCHAR | 目标 agent（ownership guard） |
| `candidate_bundle_json` | TEXT | 候选包指针元组 `{"promptVersionId":..,"behaviorRuleVersionId":..}` |
| `baseline_bundle_json` | TEXT | 对照 best 包指针元组（null 指针=现役） |
| `dataset_version_id` | VARCHAR | 评测数据集版本 |
| `skip_baseline` | BOOLEAN | 顺延轮 true（只跑候选臂、复用缓存基准分） |
| `cached_baseline_rate` | DOUBLE | skip_baseline 时复用的 best 整体分（[0,100]） |
| `baseline_pass_rate` / `candidate_pass_rate` / `delta_pass_rate` | DOUBLE | 整体分 |
| `target_delta_pp` / `regression_delta_pp` | DOUBLE nullable | Phase 2 不退化保护用；Phase 1 留 null |
| `ab_scenario_results_json` | TEXT | 逐场景（候选侧 + 非 skip 时 baseline 侧） |
| `status` | VARCHAR(32) | PENDING/RUNNING/COMPLETED/FAILED/SUPERSEDED |
| `started_at` / `completed_at` | TIMESTAMPTZ | 审计（**无 created_at**，对齐 BehaviorRuleAbRunEntity 模板 + W6；started_at NOT NULL + @PrePersist 默认） |
| `prior_winner_ab_run_id` | VARCHAR(36) nullable | W1：上一轮赢家 ab_run 反引用（无硬 self-FK） |

> bundle 的"当前状态"由 orchestrator 在对话里顺延携带（跟现在携带 `currentBestVersionId` 同款），**不落独立 bundle 表** —— ab_run 行记录的 candidate/baseline bundle 即足够审计回溯。

## 2. 新建组件

### 2.1 BundleApplicator（包应用器）
`AgentDefinition apply(AgentEntity base, Bundle bundle)`：
- 克隆 base 的 `AgentDefinition`（JSON roundtrip 深拷，沿用 `BehaviorRuleAbEvalService.cloneDef` 模式）
- `bundle.promptVersionId` 非空 → load `t_prompt_version` 取 content，setSystemPrompt；否则保持现役
- `bundle.behaviorRuleVersionId` 非空 → load `t_behavior_rule_version` 取 rulesJson，`BehaviorRuleVersionToCustomRulesMapper.toCustomRules` → setBehaviorRules.customRules；否则保持现役
- **不变量**：未在 bundle 出现的面 = agent 现役（不动）。
- Phase 4 加 skill / 远期 tools·hook 的分支。

`Bundle` = record `{String promptVersionId, String behaviorRuleVersionId}`（V1 两面；加面时扩字段）。

### 2.2 AbEvalPipeline 加 def-based skipBaseline 方法
现状：`run(...)` 的 skipBaseline 耦合 prompt-version 入参；`runWithExplicitDefs(abRunId, scenarios, baselineDef, candidateDef)` 跑两臂、无缓存。
**新加** `List<AbScenarioResult> runWithDefs(abRunId, scenarios, baselineDef, candidateDef, Double cachedBaselineRate)`：
- `cachedBaselineRate != null` → **只跑 candidateDef 臂**，baseline 侧逐场景填 `BASELINE_CACHED` 哨兵 + 复用 cachedBaselineRate 当 baseline 整体分（沿用 prompt 路径 BUG-1 的 `BASELINE_CACHED_STATUS` 模式）。
- `cachedBaselineRate == null` → 跑两臂（= 现 `runWithExplicitDefs` 行为；可让后者委托新方法）。
- **判分语义跟 prompt 路径字节一致**（composite = 0.7·outcome + 0.3·efficiency，确定性 oracle 不走 LLM；judge rationale 方案 B 的 explain=true 透传）。

### 2.3 AgentEvolveAbEvalService（整-agent A/B 编排）
镜像 `BehaviorRuleAbEvalService` 结构（@Transactional + afterCommit defer 防 race + coordinatorExecutor）：
- `startAgentAb(candidateBundle, baselineBundle, agentId, datasetVersionId, cachedBaselineRate)`：建 `t_agent_evolve_ab_run` PENDING + afterCommit → `runAsync`
- `runAsync`：applicator 出 baselineDef/candidateDef → `abEvalPipeline.runWithDefs(...)` → 算整体分（+ Phase 2 target/general 分组分）→ 落库 + 广播 + INV-6 supersede 去重

### 2.4 evolve tool 扩 surface=agent
- `EvolveSurface` 加 `AGENT("agent")`
- `GenerateCandidateTool`：保持各面生成不变（产 prompt/rule 版本 id）；orchestrator 用它**各面分别生成**，自己组 bundle。**不在 tool 里组多面**（YAGNI + 复用现成单面生成）。
- `TriggerAbEvalTool` 加 `case AGENT`：入参 `candidateBundle`(json) + `baselineBundle`(json) + `cachedBaselineScore` → `agentEvolveAbEvalService.startAgentAb(...)`
- `GetAbResultTool` 加 `readAgent`：从 `t_agent_evolve_ab_run` 映 `{status, baselineScore, candidateScore, delta, deltaPassRate, wouldPromote, targetDeltaPp?, regressionDeltaPp?, perScenario}`

## 3. 复用组件（一行评测算力不重写）

| 复用 | 用途 |
|---|---|
| `AbEvalPipeline.runWithScenarios/Defs` | 整-agent 场景跑分（本来就整-agent） |
| `PromptImproverService.improveFromBasePrompt` | prompt 面顺延生成 |
| `BehaviorRuleImproverService`（**新加** `startImprovementFromBaseVersion`，见 §4.2） | behavior_rule 面顺延生成 |
| `BehaviorRuleVersionToCustomRulesMapper` | rulesJson → CustomRule（applicator 用） |
| 各面 promote service | Phase 3+ 采纳赢家包时各面分别 promote |
| opt-report 归因 | 决定 issue 落哪个面 + target 场景子集 |

## 4. 分期落地

### Phase 1 — 整-agent A/B 骨架（prompt-only 回归对照）
目标：新整-agent 通道能把**只含 prompt 的包**跑通，分数 == 现 prompt 爬坡（不退化）。
- 建 `t_agent_evolve_ab_run`（migration）+ Entity + Repository
- `Bundle` record + `BundleApplicator`（V1 两面，但本期只验 prompt 分支）
- `AbEvalPipeline.runWithDefs`（def-based skipBaseline）
- `AgentEvolveAbEvalService`
- `EvolveSurface.AGENT` + 三个 tool 的 agent 分支
- **验证**：手动用 prompt-only bundle 跑一轮整-agent A/B，分数对齐现 prompt A/B；mvn 全绿。
- **本期不改 orchestrator 种子**（手动/脚本触发验证）。

### Phase 2 — rules 进包 + 不退化保护
- behavior_rule 顺延生成入口 `startImprovementFromBaseVersion(eventId, agentId, baseVersionId, issue, ownerId[, editor])`（load baseVersionId 的 rulesJson 传给现有 `generateCandidateRulesFromAttribution`）
- BundleApplicator behavior_rule 分支启用 + 多面候选（prompt+rules）整-agent A/B
- **target/general 分组 gate**：场景分 target（issue 相关）+ general（其余），整体分上要求 target↑ 且 general 不退化（沿用 `BehaviorRuleAbEvalService` 的 role-aware/subset 思路，但作用在**整-agent 一个分**上）。target 子集判定见 §5 R2。

### Phase 3 — orchestrator 种子 v2 + 反思
- 种子重写：bundle 状态机（best bundle 顺延）、多面选择（按 opt-report issue 的 fixSurface 决定这轮动哪些面）、reflection（priorChange/priorEvalReport）、收尾汇总赢家包。
- reflection 入口：`generateCandidateRulesFromAttribution` 加 editor-aware overload（透传 priorChange/priorEvalReport，跟 prompt 对齐）。
- RecordIteration：candidateId 表达一个 bundle（存 bundle json）；轨迹图 candidateId/changeDesc 适配。

### Phase 4 — skill 进包
- `SkillDraftService.createDraftFromBaseDraft` + LLM 方法收 base 内容；BundleApplicator skill 分支（把 skill draft 应用到 AgentDefinition）。

## 5. 风险 / 待 Plan 打硬

- **R1 — AbEvalPipeline skipBaseline 解耦**：现 skipBaseline 耦合 prompt-version 入参。`runWithDefs` 抽出 def-based skipBaseline 必须跟 prompt 路径**判分字节一致**（持久化-engine shape invariant + BUG-1 cachedBaselineRate [0,100] 校验）。验证 `runWithExplicitDefs` 委托新方法后老 behavior_rule 路径不回归。
- **R2 — target 子集判定**：整-agent 候选的 target 场景子集怎么定？现 behavior_rule 用 rule_owner_agent role；prompt 路径没有 target/general 概念。候选项：opt-report issue 的 `exampleSessionIds` 派生 / issue→scenario 标签 / 沿用 role-aware split。**Phase 2 决策点**。
- **R3 — promote 包的原子性**：采纳赢家包 = 各面版本分别 promote。是否需要"全成功或全回滚"？人末尾定夺，Phase 3 决策。
- **R4 — orchestrator 种子 v2 复杂度**：bundle 状态机 + 多面选择是大 prompt 重写，Phase 3 单独 Full。
- **R5 — ownership/安全**：`t_agent_evolve_ab_run.agent_id` ownership guard（GetAbResult 现有模式）；candidate bundle 里的版本必须属于 targetAgentId（applicator load 时校验）。
- **R6 — 数据集 / 场景来源**：整-agent A/B 的 scenario 来源（dataset version）跟 prompt 路径一致；target/general 分组叠加在其上。

## 7. Plan 对抗 review 结论（2026-06-02，architect + java-design-reviewer 收敛）

> **总判**：架构方向(路 B / 指针元组 / 一个整体分)**sound 且贴合现有代码** —— `AbEvalPipeline` 本就整-agent、`cloneDef`+`inject` 证明 BundleApplicator 机械可行。失败点是**两处具体声明的精度缺口**，不是架构问题。下列修正在动 Phase 1 代码前已纳入设计。

### B1（blocker，两 reviewer 一致）—— "runWithDefs 判分跟 prompt 路径字节一致" 与 "runWithExplicitDefs 委托新方法" **自相矛盾**
现状代码有**两条不同判分链**：prompt 路径 `runWithScenarios` 走 `judge(scenario, run, true)`(explain=true) + 内部 `EvalJudgeOutput.isPass()` 计数；behavior_rule 路径 `runWithExplicitDefs` 走 `judge(scenario, bRun)`(无 explain) + caller `BehaviorRuleAbEvalService.passRateOf` 用 `oracleScore()>=0.5` 重算。两个 pass 谓词不可证等价。
**修正**：
- `runWithDefs` 加 `boolean explain` 形参；**agent 路径传 explain=true**(对齐 prompt 路径,含方案 B rationale)。
- **Phase 1 不把 `runWithExplicitDefs` 改成委托新方法**（避免给 promote-gated 的 behavior_rule 臂偷加 explain LLM 调用 → 回归）；`runWithDefs` 作为 sibling 新增，behavior_rule 路径字节不动。
- **AC-1 升级为数值 parity 测试**：同一 prompt-only 候选分别走「现 prompt A/B」和「新 agent 通道」在同一 dataset version 上，**断言 pass-rate 相等**（不是 mvn 绿 + 肉眼）。
- **澄清**：R1 原写 "skipBaseline 耦合 prompt-version" **过度**——skip 算术(`cachedBaselineRate != null` 判定 + baseline 侧填哨兵)已 def-agnostic；真正要抽的是 def-构造 + 持久化外壳。**persistence-shape-invariant 是误报**：`runWithDefs` 是纯 compute，返 `List<AbScenarioResult>`，不碰 `t_session_message` / `SessionService.rewriteMessages`。

### B2（blocker，java-design）—— `EvolveSurface.AGENT` 编译期打断 **4** 个穷尽 switch（设计漏数了 Promote）
`GenerateCandidateTool:246` / `TriggerAbEvalTool:265` / `GetAbResultTool:187` / **`PromoteCandidateTool:158`** 都是无 default 的穷尽 `switch` 表达式。加第 4 个枚举常量 → 四处全编译错。
**修正**：
- Phase 1 给**全 4 个 tool** 加 `case AGENT ->`：Generate/Trigger/GetAbResult 真实现；**Promote + RecordIteration 返回干净的「V1 不支持 agent promote」拒绝**（不是悄悄漏）。
- **解耦「Java 枚举常量」与「各 tool JSON-schema 对外 advertise 的 enum」**：Promote / RecordIteration 的 schema enum **暂不列 `agent`**（Phase 3 真做再开），防止有人 Phase 1 就 `PromoteCandidate surface=agent` 撞墙。
- **改 `EvolveSurface` javadoc**：AGENT 是 **evolve-routing-only 的 meta-surface**，**不是** `OptimizationEventEntity.SURFACE_*`（无 SURFACE_AGENT）——防下个读者以为 opt-event 能 `surface_type=agent` 接死路。

### 已纳入的 amendment（warning）
- **W1 — 缓存分↔bundle 元组一致性**（两 reviewer 一致，多面放大 BUG-1 原患）：best bundle 由 orchestrator 在对话里携带，多面=多指针易漂移；`cached_baseline_rate` 可能挂到一个**从没测过的元组**上。**修正**：每条完成的 agent ab_run 持久化 `candidate_bundle_json` + `candidate_pass_rate`；下一轮带 `skip_baseline=true` 时，`AgentEvolveAbEvalService` **断言传入的 `baseline_bundle_json` 结构等于上一个赢家的 `candidate_bundle_json`** 再信缓存分（存一条 prior-winner ab_run id 反引用）。
- **W2 — `runWithDefs` 返回 `List<AbScenarioResult>`（从第一天）**：Phase 2 的 target/general 分组要逐场景 partition；若 Phase 1 只返聚合分，Phase 2 要改核心方法签名。服务侧再聚合。
- **W3 — 抽 `cloneDef` 成共享 helper**（7 行、surface-agnostic、≥3 处用）；**behavior_rule 的 strip/inject 保持并行**（不强行让 with-vs-without 服务走 applicator，V1 不值）。
- **W4 — Phase 1 applicator 的 behavior_rule 分支**：非空 `behaviorRuleVersionId` **抛 `UnsupportedOperationException("behavior_rule bundle 未接入 Phase 1")`**，**不许悄悄跳过**（防 Phase 1.5 caller 传了被静默忽略 → 错 A/B）。`Bundle` 仍 2 字段(避免 record 加宽 churn) + 响亮 guard。
- **W5 — `AgentEvolveAbEvalService.runAsync` 保持薄**：抽出 `computeDeltas(perScenario, targetIds, regressionIds)` 纯函数(可测、对应 behavior 服务自己的 V2 TODO)；只移植 afterCommit-defer + supersede-dedup 骨架，不照抄 150 行 runAsync。
- **W6 — JPA**：注入 Spring 托管 `ObjectMapper` bean(非 `new`)；`t_agent_evolve_ab_run` 砍掉设计里多写的 `created_at`(对齐 `BehaviorRuleAbRunEntity` 模板只有 started/completed + `@PrePersist` 默认)，或显式 `@CreatedDate`+listener 二选一别半接；**identity-column-on-rewrite 不适用**(非 t_session_message rewrite 路径)。
- **W7 — bundle 指针 content-by-id 不变量**：applicator 按 id load 版本内容**无视 version status**(active/rejected/deprecated 都 load，best 顺延会引用非 active 版本)；**逐个指针**校验 `agentId == 目标 agent`；死指针 → 响亮 fail。
- **W8 — target 子集判定(R2)留 Phase 2 但提示**：现 behavior_rule 的 role-aware split 是 behavior_rule 专属，多面候选无单一 rule_owner_agent，**不能照搬 `AgentRoleResolver`**；Phase 2 要新定义 target 子集(opt-report issue 的 exampleSessionIds / issue→scenario 标签)。

## 8. Phase 2 设计决策（2026-06-02，用户 ratify R2 + 林定子点）

> Phase 1 已交付（commit a75894a）。Phase 2 = rules 进包 + 不退化保护。

### R2 resolution（用户 ratify）—— target 子集 = **按被进化 agent 的角色分**
- `target` = dataset version 里 `applicable_agent_roles` 匹配**被进化 agent 角色**的场景（复用 `AgentRoleResolver.resolveRole(agent)` + FLYWHEEL-AB-AGENT-AWARE-DATASET 的 role-aware 查询）。
- `general`（regression）= 该 dataset version 其余场景（general benchmark）。
- 语义：「提升这个 agent 自己的任务、别误伤通用任务」。**W8 的"多面无单一 owner"不成立** —— agent-level 候选的 owner 就是被进化的那个 agent。
- 角色无匹配场景 → 沿用 behavior_rule 的 regression-only fallback（全集当 general）。

### 子点① — skipBaseline 下的双标准基准（cached per-subset）
skipBaseline 只跑候选臂，但 target/regression delta 要两臂的 per-subset 分率。**解**：基准 per-subset 分率从**上一个赢家 ab_run 的 `ab_scenario_results_json`（候选臂逐场景）**按**同一 target/general id 集**重算，不重跑基准臂。
- target/general id 集给定 (dataset version + agent role) 是**确定的 → 跨轮稳定**，所以拿 best 的逐场景 partition 出 best_target / best_general 合法。
- W1 一致性 guard 仍要：cached 基准来自的赢家 bundle 必须等于传入的 baselineBundle。
- **首轮**（无 prior winner，cachedBaselineRate=null）→ runWithDefs 两臂都跑，per-subset 直接两臂算。

### 子点② — gate 语义 = vs-best 增量（非 vs-原始绝对）
爬坡跟 best 比，不是跟原始比：
- `targetDeltaPp = 候选_target_rate − best_target_rate`
- `regressionDeltaPp = 候选_general_rate − best_general_rate`
- `wouldPromote`（advisory）= `targetDeltaPp` 有意义为正 **且** `regressionDeltaPp ≥ REGRESSION_FLOOR`（vs best）。behavior_rule 一次性 promote 的 +10pp **绝对**阈值**不照搬**（那是 vs 原始）；hill-climb 是增量。
- **Phase 2 只算+暴露** targetDeltaPp/regressionDeltaPp/wouldPromote（写进 `t_agent_evolve_ab_run` 的 target_delta_pp/regression_delta_pp + GetAbResult.readAgent 返回）；**真 kept 决策在 orchestrator 种子 = Phase 3**。
- 展示 delta（轨迹图）仍用 global（candidate_global − baseline_global），跟 Phase 1 一致。

### Phase 2 review 留给 Phase 3 / backlog 的两点（2026-06-02 java-design-reviewer）
- **⚠️ Phase 3 必做 — vs-best regression floor 挡不住"慢腻蚀"**：子点② 的 `regressionDeltaPp ≥ −3`(vs best) 每轮都过、但 general 子集可能 5 轮累计 vs **原始**跌 −15pp。Phase 2 因 `wouldPromote` 只是 advisory、单轮无跨轮记忆,可接受；**但 Phase 3 orchestrator gate 必须在 vs-best floor 之上再加一条 vs-原始 general anchor**(记住原始 general 分,不许候选 general vs 原始跌破某阈值),否则爬坡会慢慢把通用任务啃掉。`REGRESSION_FLOOR_PP` 的 javadoc 已诚实标"vs 当前 best",Phase 3 实现者不要误当完整护栏。
- **backlog `AbstractDualCriteriaRunner` 抽取**：`BehaviorRuleAbEvalService` 自己的 javadoc 早写"等第二个 dual-criteria surface 出现就抽共享基类" —— 现在 `AgentEvolveAbEvalService` 就是第二个,抽取触发条件已满足。V1 并行实现仍正确,抽取留 backlog(Phase 4 skill 进包时若第三个出现再做)。

### Phase 2 交付清单
1. `BehaviorRuleImproverService.startImprovementFromBaseVersion(eventId, agentId, baseVersionId, issue, ownerId)`：load baseVersionId 的 rulesJson 传给现有 `generateCandidateRulesFromAttribution`（reflection editor overload 留 Phase 3）。
2. `BundleApplicator` rule 分支：**移除 Phase 1 的 UnsupportedOperationException**，改为 load behavior_rule version（content-by-id 无视 status，W7 逐指针 ownership 校验）→ `BehaviorRuleVersionToCustomRulesMapper.toCustomRules` → setBehaviorRules.customRules。
3. `AgentEvolveAbEvalService`：加 role-aware target/general split（注入 `AgentRoleResolver` + scenario role 查询）；computeDeltas 扩成算 target/general per-subset + 子点①的 cached-baseline-per-subset 重算；写 target_delta_pp/regression_delta_pp。
4. `GetAbResultTool.readAgent`：返回 targetDeltaPp/regressionDeltaPp + dual-criteria wouldPromote（vs-best 语义）。
5. `GenerateCandidateTool`：behavior_rule case 加 baseVersionId 路由（类似 prompt 的 basePromptVersionId）—— 让 orchestrator（Phase 3）能顺延生成 rule 候选。
6. 测试：多面 bundle（prompt+rules）整-agent A/B 出 target/regression；applicator rule 分支；startImprovementFromBaseVersion；cached-per-subset 基准重算；role fallback。
7. 验证：mvn 全绿 + 手动多面 bundle A/B 出双标准分（无 orchestrator，脚本/手动触发）。

## 9. Phase 3 设计决策（2026-06-02 用户 ratify）

> Phase 1+2 已交付（a75894a / 3585124）。Phase 3 = orchestrator 种子 v2 + 反思 —— 真正把多轮整-agent 爬坡跑起来 + 首次活体（Ark）。

### 决策（用户 ratify）
- **面选择 = 每轮可多面协同改**：orchestrator 让 editor 判一个 issue 要不要 prompt+rule 一起改 → 一个候选可同时动两面（candidate bundle 两个指针都换）。best 包跨轮顺延。
- **加 vs-原始 general anchor**（§8 设计债的解）：orchestrator 记住**第 1 轮原始 general 分**（首轮 A/B 两臂真跑，baseline general = 原始）；每个候选 gate = ① vs-best（target↑ 且 general 不跌破 vs best floor）**且** ② **general vs 原始 ≥ −ANCHOR**（绝对 anchor，挡慢腻蚀）。

### Phase 3 两条线
**A — 代码胶水（be-dev）**：
1. `BehaviorRuleImproverService.generateCandidateRulesFromAttribution` 加 **reflection editor overload**（收 `EvolveEditorContext` priorChange/priorEvalReport 透进 LLM prompt，对齐 prompt 面）；`startImprovementFromBaseVersion` 加 editor 重载。
2. `GenerateCandidateTool` behavior_rule case 透传 priorChange/priorEvalReport（现 prompt 有,behavior_rule Phase 2 只加了 baseVersionId）。
3. `RecordIterationTool`：candidateId 表达一个 bundle（存 bundle json / 或主指针 + bundle 旁字段）；surface=agent 这轮允许记账（Phase 1 是干净拒绝,Phase 3 放开 agent 记账）；轨迹图 candidateId 适配。

**B — orchestrator 种子 v2（林亲写 + 新 Vnnn migration 改 agent 19 system_prompt）**：bundle 状态机（currentBest bundle + global + target/general + **原始 general**）；每轮:选 issue → editor 判动哪些面 → 各面 GenerateCandidate(基于 best 包对应版本 + 反思) → 组 candidate bundle → TriggerAbEval(surface=agent, candidateBundle, baselineBundle=best, cachedBaselineScore=best global) → GetAbResult 拿 target/regression → **双 gate(vs-best + vs-原始 anchor)** → 赢则 best=candidate bundle → RecordIteration(surface=agent, bundle) → 反思组 priorEvalReport 反哺下轮 → 收尾汇总赢家 bundle,不 auto-promote。

### Phase 3 验证
mvn 全绿 + **活体多轮整-agent 爬坡(Ark,真 orchestrator 跑)** —— 整个功能第一次真跑起来。

### Phase 3 review 结论 + 恢复 TODO（2026-06-02 暂停点）
**状态**：线 A 代码 + V138 + 种子 v2(V139)**已写完 + 已 review**,但**未 commit、未活体验证**(活体 = Phase Final,留恢复时做)。mvn 2576/0/0(线 A,主会话亲验)。
- **seed-logic-trace（critical）**：**1 BLOCKER B1** + 其余全 clean(round 分支 / bundle 顺延组包 / 双 gate / originalGeneral 记一次 / 反思 anchor / 不 Promote / 所有 param+字段名都对)。
  - **B1（恢复必修,code 非 seed）**：seed 没给 `TriggerAbEval(surface=agent)` 传 `datasetVersionId`,而 `triggerAgent`(TriggerAbEvalTool:476-479)+ `startAgentAb`(AgentEvolveAbEvalService:130-132)硬要求它、无默认解析 → 活体 round1 就 validationError;且 子点① guard 要求跨轮**同一** datasetVersionId。**修法**：`startAgentAb` 在 datasetVersionId 省略时**从 agent 默认数据集解析**(re-inject `EvalDatasetService` + 镜像 `BehaviorRuleAbEvalService:165-167`;注意 Phase 2 把 EvalDatasetService 从该 service ctor 换出去了,要加回)→ 这样 seed 不传 datasetVersionId 是对的、且同 agent 默认跨轮稳定自动满足 子点①。**修完种子无需改**。
- **java-reviewer(线 A)PASS + 2 warning**:**W1**(恢复修)`BehaviorRuleImproverService` 内层 5/6-arg overload 上的 `@Transactional(REQUIRES_NEW)` 是 self-invocation no-op(当前 caller 走 proxy 没事,未来 intra-bean 调才炸)→ 从内层 overload 删掉(外层 entry-point 已保证 REQUIRES_NEW);**W2** `yield r.promptVersionId()` 装 rule version id(Phase 2 既有,misleading,backlog)。
- **database-reviewer(V138+V139)PASS + 1 warning(已修:V139 加 updated_at=NOW())**。
- **nit**:V138 无 0-100 CHECK(全表惯例如此,不新增 gap);V139 supersedes 注释漏 V132(doc-only)。

**恢复步骤**:① B1 code 修(startAgentAb 默认解析 datasetVersionId)+ W1 删内层 REQUIRES_NEW + 各自测试 → mvn 全绿 ② apply V138+V139(重启 server)③ **活体多轮整-agent 爬坡(Ark)** —— 触发 evolve run、看 orchestrator 真跑 bundle 爬坡、出真实轨迹 ④ docs + commit Phase 3。

## 10. Phase 4 设计决策（2026-06-02 用户 ratify，2-subagent 源码对比后定）

> Phase 1-3 已交付(a75894a/3585124/51c0ad0,活体跑通)。Phase 4 = skill 进包。

### 关键事实（2 subagent 读源码确认）
- **agent loop 跑 skill 不读磁盘**:按名字从内存 `SkillRegistry` 取 `SkillDefinition`,直接返回内存 `promptContent`(`AgentLoopEngine:2722/2810`,`SkillRegistry` 是 ConcurrentHashMap)。**磁盘渲染非运行时必需**。
- **现状已 2/3 是 A**:`SkillCreatorService.renderTransientCandidateSkill`(skillPath=null 纯内存)+ `SimulatorTrialOrchestrator`(user-sim)。只有老 `SkillAbEvalService` 是 B(落盘),且落盘纯因自己 `buildSkillDefinition` 磁盘优先(R3 补丁自缚),非引擎需要。

### 决策（用户 ratify）—— 统一走 A（内存),scope = agent-level 先做 + 抽共享构建
- **A**:从持久 `SkillDraftEntity` 在内存建 `SkillDefinition`(skillPath=null),注入 eval sandbox registry,**无 per-A/B 落盘 transient、零清理**。
- **草稿永持久**(bundle 的 skillDraftId 指针),**赢家采纳时**走现成 `approveDraft` 渲染 SKILL.md 落盘 + 建永久 skill(`SkillDraftService:1004`,A/B 机制无关)→ 输了不留盘、赢了正常落盘。
- **scope**:Phase 4 只做 agent-level skill 走 A + **抽唯一的 draft→SkillDefinition 构建函数**(agent-level + 现有 2 个 A 面共用,解 DRY + 保真漂移);**老 SkillAbEvalService 迁 A = 紧跟 backlog**(promote gate/V64/dogfood 历史,单独迁更稳)。

### Phase 4 交付清单
1. `Bundle` record 加第 3 指针 `skillDraftId`(3-field;applicator/W1 结构等价用到,扩字段)。
2. **抽共享 `SkillDefinitionFromDraft` 构建器**(draft triggers/requiredTools/promptHint → 内存 SkillDefinition,allowedTools 镜像 requiredTools 防 tool-gate 拒);agent-level 用它,标注现有 2 个 A 面后续收敛到它(backlog)。
3. `SkillDraftService.createDraftFromBaseDraft(eventId, baseDraftId, desc, ownerId[, editor])` —— 顺延生成(load base draft 内容喂 LLM,镜像 behavior_rule `startImprovementFromBaseVersion`)+ reflection editor 重载。
4. `BundleApplicator` skill 分支:load draft(content-by-id,W7 ownership)→ 共享构建器建 SkillDefinition → 名字加进 `def.getSkillIds()` → **把候选 SkillDefinition 带出**(applicator 返回值/sidecar)给 A/B 注入。
5. **`AbEvalPipeline.runWithDefs` 加 `List<SkillDefinition> extraSkills` 重载**(向后兼容,旧签名 delegate List.of());`runSingleScenario` 用 `buildSandboxRegistryWithSkills(...)` 注入。`AgentEvolveAbEvalService` 把 bundle 的候选 skill 定义传进去。(AbEvalPipeline **不在红灯核心清单**,但多 surface 共享 seam → Full review。)
6. `GenerateCandidateTool` skill case:baseDraftId 路由 + 反思透传(镜像 behavior_rule)。
7. orchestrator **种子 v3(V140)**:加 skill 面(surface=skill issue → GenerateCandidate(surface=skill, baseDraftId=best.skillDraftId)→ 组 3-指针 bundle)。
8. 测试 + **活体**:多面候选含 skill 的整-agent A/B 出分。

### Phase 4 风险
- **保真**:内存建 body vs 生产渲染 body 非字节一致 + A/B 不验 artifact → 共享构建器缓解(同一函数),验证留 approveDraft。
- **runWithDefs 签名**:多 surface 共享,用重载保向后兼容。
- target/general split:role-aware 已通用,skill 候选直接复用。

## 6. 不做（V1 边界）
- skill / tools / hook 进包（Phase 4 / 远期）
- 按面归因消融（2^N 臂，永不在爬坡里做）
- 多 surface 在 orchestrator 里的复杂选择策略优化（Phase 3 先简单按 issue.fixSurface）
- 自动 promote（始终人末尾定夺）
