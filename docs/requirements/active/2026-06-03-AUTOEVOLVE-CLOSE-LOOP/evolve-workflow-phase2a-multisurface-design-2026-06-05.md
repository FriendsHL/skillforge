# AUTOEVOLVE Evolve Loop Phase 2a 设计:多面 bundle 支持

> 状态:设计提案(architect 产出,未写实现,**待 user 拍 2 个决策**)。日期 2026-06-05。
> 关联:Phase 1 `evolve-workflow-rearchitecture-design-2026-06-05.md`(commit a692ff21)。
> 目标:候选叶子从产单 prompt 面候选,升级为产**跨面 bundle**(prompt + behavior_rule + skill 任意几个),功能对齐 orchestrator 路。
> 范围铁律:**A/B 引擎 / 整体分 / ReconcilePrediction 对账 / carry-forward 数据模型一律不动**(orchestrator 路已跑多面)。本 Phase 只改"候选叶子怎么装配 bundle" + "workflow.js 怎么透传 bundle"。

## 0. 命门:Phase 2a 零 Java 引擎改动
Phase 1 的 A/B 已走 `surface=agent` 单指针 bundle `{promptVersionId}`。整条 A/B→整体分→Reconcile→carry-forward 在引擎侧本就 **bundle-native**(`AgentEvolveAbEvalService.startAgentAb` 吃完整 `Bundle(promptVersionId, behaviorRuleVersionId, skillDraftId)`;`TriggerAbEval.parseBundle` 解析三字段;`RecordIteration` 有 candidateBundle sidecar;`GetCandidateDiff` 每面各取一次)。**Phase 2a = (1) 候选叶子多调几次 GenerateCandidate、(2) workflow.js 多面装配 + 多面 diff、(3) CAND_SCHEMA 换形状。Java 引擎/service/entity 零改**(除可选 read 层 FE 兼容)。

## 1. 决策 A — 面选择:推荐 **hybrid**(fixSurface 白名单 + 叶子白名单内可减面)
- **A1 纯确定性**:JS 从 issue 的 `fixSurface` 算白名单,叶子必须改满白名单所有面。确定但可能强行改不需要改的面。
- **A2 叶子自由判断**:把"选哪几个面"塞回 LLM,违背 Phase 1"机械决策不给 agent"铁律。
- **推荐 hybrid**:JS 用 `fixSurface`(单值或数组)算 `allowedSurfaces` 白名单(确定性,复用 opt-report 归因);叶子**只能在白名单子集内**决定实际改哪几个面(可减面,不能越界)。退化:无 fixSurface → 白名单退回 `['prompt']`(等价 Phase 1)。回退:fixSurface 归因差就放宽白名单算法到 `fixSurface ∪ suspectSurface ∪ 全面`(改一行 JS)。

## 2. 决策 B — bundle 装配(确定):叶子按面多次调 GenerateCandidate
`GenerateCandidateTool` 拒 `surface=agent`,按 prompt/skill/behavior_rule 单面产候选并持久化返回真 id。叶子对选中的每面调一次 GenerateCandidate(传该面 hill-climb base 指针:prompt→basePromptVersionId / behavior_rule→baseVersionId / skill→baseDraftId),拿回各面 candidateId,JS 组装 `candidateBundle = {promptVersionId?, behaviorRuleVersionId?, skillDraftId?}`(未改的面省略=用 active)。叶子不能伪造 id,只转发+按面归位。

## 3. CAND_SCHEMA 多面形状
```js
required: ['candidateBundle', 'surfaces', 'changeDesc']
candidateBundle: { promptVersionId?, behaviorRuleVersionId?, skillDraftId? }  // 真 id 三件套
surfaces: ['prompt'|'behavior_rule'|'skill', ...]  // 实际改的面(⊆ allowedSurfaces, 镜像 bundle 非空 key)
changeDesc: string                                  // 跨面综述
prediction: object (可选, G3 对账, 形状不变)
```
JS 侧 `normalizeSurfaces` 以 candidateBundle 非空指针为权威(防声明不一致)。

## 4. workflow.js 节点变更
- 删 `SURFACE='prompt'` 常量;加 `resolveAllowedSurfaces` / `normalizeSurfaces` / `bundlePointer` / `primaryPointer` helper。
- `selectAndRank` 去"prompt 独宠"。
- **carry-forward `best`**:单指针 → 整 bundle `{bundle:{三件套}, score}`;各面 base 指针从 `best.bundle` 取(各面独立爬坡,bundle=各面当前最优组合)。
- 候选叶子 prompt 传 `allowedSurfaces` + `baseBundle`。
- **多面 semanticDelta**:对每个 changedSurface 各调一次 `GetCandidateDiff` → `semanticDeltas` 数组(`{surface, before, after, diff}`)。
- `TriggerAbEval(surface=agent, candidateBundle=整三件套, baselineBundle=best.bundle, cachedBaselineScore=best.score)`。
- GetAbResult 轮询 / ReconcilePrediction / decideKeep:不改。
- `RecordIteration`:surface='agent'(enum 已含)、candidateId=primaryPointer(满足 required)、candidateBundle=整三件套、semanticDelta=数组(Java putJsonSidecar 原样存数组,零改)。

## 5. 决策 B' — semanticDelta 存储:推荐**复用现有 `semanticDelta` key 存数组**(Java 零改)
RecordIteration 的 `semanticDelta` sidecar 是 free-schema(putJsonSidecar 对数组也原样存)。复用现有 key 存数组 → **Java RecordIterationTool 零改**,代价是 FE 渲染加 `Array.isArray()` 分支(老 run object=单面、新 run array=多面)。备选新增 `semanticDeltas` 复数 key 语义清晰但要改 Java + DTO。**推荐复用**。

## 6. 候选叶子 system_prompt 多面化(新 seed V150)
输入加 `allowedSurfaces` + `baseBundle`;在白名单子集内决定改哪几面;每面一次 GenerateCandidate(传该面 base 指针);组装 candidateBundle + surfaces + changeDesc + prediction;不越白名单、不伪造 id、不拼 surface=agent。registry 不动(仍只放 GenerateCandidate)。

## 7. 改动清单
- **改**:`evolve-loop.workflow.js`(主) + `evolve-candidate-gen` seed prompt(V150)。
- **改(纯 read/FE)**:`EvolveReadService.getRunDetail`/`EvolveRunDetailDto` 透传 candidateBundle 三件套 + semanticDelta 可能数组;FE 加 `Array.isArray` 分支。
- **不动**:A/B 引擎 / 整体分 / Reconcile / Bundle record / GenerateCandidate+三 improver service / GetCandidateDiff Java / RecordIterationTool Java / 数据模型(无 migration except V150 seed)/ 灰度(orchestrator 默认不变)。

## 8. 风险
- **R1 semanticDelta object→array**:FE `Array.isArray` 兼容老 run(低-中)。
- **R2 面选择 A1 vs hybrid**:§1,推荐 hybrid,回退明确(需 user 拍)。
- **R3 budget**:A/B budget 按触发次数计不按面计,多面仍是一次 agent A/B 触发,**不额外烧 A/B budget**;多面多调 GenerateCandidate(候选生成成本,无 cap,prompt 引导优先单面)。
- **R4 各面独立 carry-forward**:bundle=各面当前最优组合,引擎 W1 guard 天然兜 baseline=上轮赢家 bundle(需 user 确认语义)。
- **R5 surfaces 声明与 bundle 不一致**:normalizeSurfaces 以 bundle 非空为权威,记 warn 不 fail。
- **不是风险**:多面 A/B/对账 orchestrator 已用;id 不可伪造;recursion guard 不变。

## 9. 独立验证
给带多面 fixSurface(如 `["prompt","behavior_rule"]`)的 reportId 跑 1-2 iter,断言:candidateBundle ≥2 非空指针且各 id 真行可查;semanticDelta 是数组各面有 diff;AgentEvolveAbRun bundle 三件套一致;RecordIteration surface='agent';对照 orchestrator 行为等价;单面 fixSurface 退化为单指针(无回归)。

## 10. 边界
**含**:多面 bundle 装配 / CAND_SCHEMA / workflow.js bundle 透传 + carry-forward bundle + 多面 semanticDelta / read-FE 数组兼容。
**不含(推后)**:退役 orchestrator(2b);issue 选择 agent 叶子;产物文件化;humanApprove resume;per-面级 changeDesc。
**灰度**:orchestrator 默认不变。

**两个待 user 拍**:① 面选择 hybrid(推荐)vs 纯 A1;② semanticDelta 复用 key 存数组(推荐)vs 新增复数 key。
