# Tech Design — EVOLVE-CANDIDATE-GROUNDING（Phase 2）

> 状态：design-approved（含一次代码勘察 + architect 对抗 review ENDORSE-WITH-CHANGES）
> 触碰：`evolve-loop.workflow.js`（核心）+ `ListActiveHarvestedScenariosTool` + `GenerateCandidateTool` + `V*__...sql`（leaf prompt UPDATE）+ `RecordIterationTool` sidecar。Full pipeline。

## 勘察结论（current-state，file:line）

- 候选生成是**单 leaf**：`evolve-loop.workflow.js:274` `agent(candPrompt, {agentSlug:'evolve-candidate-gen', schema:CAND_SCHEMA})`；prompt 构造 `:260-273`，看 `allIssues`（聚合线索）+ currentBest + history，自主选面出**一个 bundle**。
- **失败详情没进生成**：`ListActiveHarvestedScenarios`（`:235`）只把 `scenarioIds` 喂 A/B 测量侧（`:328-330`），**从不进 candPrompt**。tool 只返 `{id,name,sourceRef}`（`ListActiveHarvestedScenariosTool.java:124-128`）。
- **反馈回路开环**：`ReconcilePrediction`（`:349-356`）+ `perScenarioFlips.regressed[].rationale`（`GetAbResultTool.java:442-507`）算了但 `history.push`（`:406-417`）只塞 bare 场景名，rationale + reconciliation 丢弃。
- **失败详情数据已存在**：`EvalScenarioEntity.oracleExpected`={tool,errorSignature,filePath,rounds} + `task` + `extractionRationale`（`BadCaseHarvestService.java:272-283`）。
- **因果链字段现成但留空**：`PredictionDto.flipToPass/riskToFail`（场景 id 列）存在，但 V151 prompt（`:96-99`）叫 leaf 留空。`RecordIteration` 写 free-schema `step_output_json`（`RecordIterationTool.java:204-231`）→ 加 targetScenarioId **无迁移**。
- `GenerateCandidate` 已收 free `issue` 串 + 未用的 reflection 输入（`GenerateCandidateTool.java:148-152,197-210`）。

## 方案（A + C-seam，architect review 修订后）

> 核心认知：live net -7 = **整面编辑爆炸半径**。A（grounding）让 leaf 瞄准但不收窄刷子 → 必叠 C-seam。

### 改动 1 — 接回反馈回路（FR1，最高性价比，~1 行级）
`evolve-loop.workflow.js:~409` `history.push` 增 `reconciliation`（`:349-356` 已算）+ `perScenarioFlips.regressed[].rationale`（现只取 name）。leaf prompt 增"上轮为何回归"段。

### 改动 2 — 喂 capped 失败详情（FR2）
- 扩 `ListActiveHarvestedScenariosTool` 返**投影**：`{id, name, errorSignature, taskSummary(一行截断), extractionRationale}`，**排除** fixtureFiles/全文 task，**总量硬上限**（防 token 膨胀稀释推理——architect 重点）。
- `evolve-loop.workflow.js` 把该投影喂入 candPrompt（新增段）。
- V-migration（新 `V*__...sql`）改 `evolve-candidate-gen` system prompt：**先逐条诊断喂入失败、再提编辑**（替代 V151 的纯通用自选）。

### 改动 3 — prediction grounding + 因果链（FR3）
- candPrompt 提供**已知失败场景 id 集**（取自 ListActiveHarvestedScenarios）；V-migration prompt 改为要求 `flipToPass/riskToFail` 填真实 id（取消 V151 的"留空"指示）。
- 候选盖 `targetScenarioId`（leaf 输出 + RecordIteration sidecar，free-schema 无迁移）。

### 改动 4 — C-seam 最小/加性编辑（FR4，直击爆炸半径）
- `GenerateCandidate`（或其改 prompt）约束：behavior_rule 优先**追加一条靶向规则**而非整体重写；prompt 编辑**限 diff 规模**。可在 GenerateCandidate 的 surface-specific 指令 + V-migration 落。

## 关键不变量 / 风险

- **INV-1 不改基数/记账**：仍"一轮一 bundle"、一个整体 A/B 分、Phase 1 配对 keep 不动（architect 确认这是保持 Mid-tier 风险的前提）。
- **INV-2 payload cap 必须硬**：失败详情投影超上限会稀释 leaf 推理——architect 标为"silently degrade"风险，**实现必须截断 + 测试断言上限**。
- **INV-3 prediction 对已知 id 集 ground**：否则 leaf 瞎猜 id → confidence 空分母 → "假 grounding"。必须喂真实失败 id 集。
- **INV-4 leaf 仍可能不遵从**：prompt nudge 非硬约束，A 的效果上限受 leaf 合规度限——预期"回归变少"而非"必出赢家"，不可过度承诺（写进退出标准）。
- **风险（H2）**：若 A+C 仍 0 赢家，根因可能就是"整-agent bundle/整面编辑"设计本身——升级须重开 bundle 设计（用户决定），**不是**反射上 B（B 仍整面编辑、仍各自炸）。

## 测试计划

- 单测：`ListActiveHarvestedScenariosTool` 投影 + cap（含超限截断）；`RecordIterationTool` targetScenarioId sidecar；GenerateCandidate 最小-delta 约束（若可单测）。
- workflow 测试（`EvolveLoopWorkflowTest` 经真引擎）：history 携带 reconciliation+rationale；candPrompt 含失败详情（stub harvested 投影）；prediction 填真实 id；payload cap。
- 冒烟：部署后真 evolve run，按 PRD 5 用例贴 raw 证据。

## pipeline

触碰核心 evolve-loop.workflow.js + 新 V-migration → Full。对抗 review：java-reviewer（tools + migration）+ code-reviewer（workflow JS + grounding 逻辑 + 测试完整性，防再现 Phase 1 的 echo chamber）。无 schema 迁移故不必 database-reviewer；不涉认证/SQL 注入/外部输入故不必 security-reviewer。
