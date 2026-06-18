# Tech Design — EVOLVE-JUDGE-GROUNDING（Phase 1：配对判定）

> 状态：design-approved（2026-06-18 PRD ratify，Q1/Q2 已定，可实现）
> 触碰：`evolve-loop.workflow.js`（核心 workflow）+ `GetAbResultTool.java` + `EvolveThresholdProperties.java`。属核心测量层 → Full pipeline。
>
> **已定（来自 PRD Q1/Q2）**：方案 A（配对为主判据 + 绝对分降 advisory）；显著性闸 = 可配 `minNetWins`（先松后紧，默认暂定 2）+ 符号检验可选默认关。下文方案 B/C 仅留作记录。

## 勘察结论（现状代码）

- **A/B 已是同场景配对**：候选 vs best 在同一组 `evalScenarioIds` 上跑，逐场景结果持久化在 `AgentEvolveAbRunEntity.abScenarioResultsJson`。
- **配对胜负已算好**：`GetAbResultTool.computePerScenarioFlips(run)` 返回
  `perScenarioFlips: {regressed[], improved[], regressedTotal, improvedTotal}`
  —— `improvedTotal`=基线挂候选过、`regressedTotal`=基线过候选挂（正是 McNemar 的两个不一致对 b/c）。
- **判定没用上配对**：`decideKeep(res, best, originalGeneral)`（evolve-loop.workflow.js:64）当前
  `keep ⟺ res.weightedScore > compareBase + minImprovePp`，外加两道闸（minMeasuredN / anchorErosionFloorPp）。配对数据完全没进判据。

→ **改造面极小**：判定逻辑（JS）+ 可选的服务端配对结论字段，**无 schema、无 A/B 跑法改动**。

## 方案对比

### 方案 A（推荐）— 配对为主判据，绝对分降 advisory，显著性走「最小 net-wins + 可选符号检验」

- `decideKeep` 主判据改为：`netWins = improvedTotal − regressedTotal`；`keep ⟺ netWins ≥ minNetWins`（配置）`&& 通过可选符号检验`。
- `weightedScore` 仍由 `GetAbResultTool` 算并写轨迹/RecordIteration，但只作 advisory（reason 里一并打印）。
- 两道既有闸（minMeasuredN / anchor erosion）**前置保留**，先过闸再比配对。
- 显著性：先用 `minNetWins`（默认小，如 2~3，配置化），符号检验 p 值作为**可选**更严闸（默认关，避免小样本过严继续 0 赢家）。

**优点**：最小改动、用已有数据、可解释（净胜几题）、小样本稳健、阈值先松后紧可调。
**缺点**：min net-wins 是启发式不是严格统计；需回放校准默认值。

### 方案 B — 双判据取严（绝对分 AND 配对都过）

- 现 `weightedScore` 判据 + 配对判据都满足才 keep。
**优点**：更保守。**缺点**：本来就 0 赢家，再"取严"几乎必然继续 0 赢家 —— 跟目标相悖。**不推荐**。

### 方案 C — 纯符号检验 p<0.05

- 严格统计。**缺点**：n=18~36、不一致对可能个位数，p<0.05 极难达 → 继续 0 赢家。**不推荐作默认**（保留为方案 A 的可选闸）。

## 推荐

**方案 A**。判据落在 `decideKeep`（JS），配对结论可选地由 `GetAbResultTool` 预计算成 `comparativeVerdict {netWins, significant}` 字段透传（避免 JS 重算），阈值入 `EvolveThresholdProperties`。

## 实现拆分

1. `EvolveThresholdProperties`：加 `minNetWins`（默认待回放定，暂定 2）+ `pairwiseSignTest`（boolean 默认 false）+ `pairwiseAlpha`（默认 0.05，仅当 signTest 开）。JSR-303 校验。
2. `GetAbResultTool`：（可选）加 `comparativeVerdict` 字段，从 `perScenarioFlips` 的 totals 算 netWins + （若开）符号检验；`thresholdsEcho` 带上新阈值。不动 `computeWeightedScore`。
3. `evolve-loop.workflow.js` `decideKeep`：主判据改 netWins/comparativeVerdict；保留 minMeasuredN / anchor 两道闸前置；reason 打印 `improved/regressed/net + 判据`；`weightedScore` 仍记 advisory。
4. 测试见下。

## 关键不变量 / 风险

- **INV-1 闸顺序**：minMeasuredN（样本不足→inconclusive）必须在配对判据**之前**，否则会对噪声样本下配对结论（n 太小净胜无意义）。
- **INV-2 first-candidate**：iter1 无 best 基线时的"首个有信号候选立为 best"语义要保留（配对此时无对照→沿用现逻辑）。
- **INV-3 advisory 不丢**：`weightedScore` / `wouldPromote` 继续写 RecordIteration + 轨迹，FE 轨迹面板不回归。
- **风险**：`minNetWins` 默认值定太松→噪声 keep、太严→继续 0 赢家。**缓解**：用历史 `abScenarioResultsJson` 回放多条 run 校准默认值（AC2），阈值配置化先松后紧。
- **风险**：`improvedTotal/regressedTotal` 在 skip-run（沿用前轮 winner 候选侧）语义下的口径 —— 必须跟 `putAgentMeasurement` 的 measured 口径一致（勘察见 GetAbResultTool 注释），测试覆盖 skip-run 路径。

## 测试计划

- 单测（`GetAbResultToolTest` 扩）：comparativeVerdict 计算 —— improved>regressed 显著 / 平手 / regressed 多 / totals 截断时用 total 字段 / null 子集。
- 单测（新 `decideKeep` 若可在 JS 测框架内跑，或抽成可测纯函数）：4 个 PRD 冒烟用例对应的判定分支 + 两道闸前置 + first-candidate。
- 回放（AC2）：取 run 45a25dba 等历史 `abScenarioResultsJson`，喂新判据，断言结论与旧绝对分判据的差异点合理。
- 冒烟（部署后 qa-reviewer，按 prd 4 条）：真起/回放一轮，贴 `decideKeep` reason raw 日志。

## 与 pipeline 的关系

触碰核心测量层 + evolve-loop workflow → Full。对抗 review：java-reviewer（GetAbResultTool / EvolveThresholdProperties）；evolve-loop.workflow.js 改动显式审 INV-1/2/3。无 schema 改动故不必 database-reviewer；不涉认证/SQL/外部输入故不必 security-reviewer。
