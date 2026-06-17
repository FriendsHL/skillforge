# PRD — EVOLVE-JUDGE-GROUNDING（Phase 1：配对 / comparative 判定）

> 状态：prd-ratified（2026-06-18 用户拍 Q1/Q2，见文末「已定决策」）
> 范围：本 PRD 只定义 **Phase 1**（配对判定）。Phase 2+（grounding / refuter / held-out）见 index roadmap，单独 PRD。

## 目标

让 evolve loop 的 `decideKeep`「赢家判定」从**绝对 weightedScore 差值**升级为**配对逐场景胜负判定**，在小样本（n=18~36）下更可靠地区分"真提升"与"噪声"，缓解 0 赢家里"测不出提升"那一面。

## 非目标（Phase 1 不做）

- 不做候选 per-badcase grounding（Phase 2）。
- 不做对抗 refuter agent（Phase 2）。
- 不做 held-out / blind-oracle 强制 gate（Phase 2）。
- 不做多候选锦标赛 bracket（候选生成当前是单候选/轮，bracket 留 Phase 3）。
- 不改 A/B 跑法、不改 schema、不改 `weightedScore` 公式本身（它仍用于轨迹展示 / advisory）。

## 工作流（改动后 decideKeep 的判定）

候选 vs 当前 best 在**同一组场景**跑 A/B（现状已如此）→ `GetAbResultTool` 已产出每场景配对结果。判定改为：

1. 取配对的不一致对：`improvedTotal`（基线挂、候选过）与 `regressedTotal`（基线过、候选挂）。
2. **comparative 判据**：候选胜出当且仅当 net-wins（`improvedTotal − regressedTotal`）为正，**且**通过显著性/margin 闸（具体检验见 tech-design，候选方案：符号检验 p 值 / 最小 net-wins 数）。
3. 保留现有两道闸：F3 `minMeasuredN`（样本不足→inconclusive）、F6 vs-original 锚（general 不被磨穿）。
4. `weightedScore` 降级为**轨迹展示 + advisory**（不再是 keep 主判据），但仍记录。

## 功能需求

- FR1：`decideKeep` 用配对 net-wins + 显著性闸做 keep 决策（替换绝对 weightedScore 差值为主判据）。
- FR2：配对数据来源复用 `GetAbResultTool.perScenarioFlips`（已持久化的 `abScenarioResultsJson`），不新增 schema。
- FR3：判定结果 `{keep, reason}` 的 reason 必须打印配对证据（improved/regressed/net + 检验结论），保证轨迹可解释。
- FR4：显著性/margin 阈值走 `EvolveThresholdProperties`（配置化，JSR-303 校验），不硬编码。
- FR5：两道既有闸（minMeasuredN / anchor erosion）保持不变、继续生效。

## 验收点

- AC1：同场景配对下，候选 net-wins 为正且达显著阈 → keep；net-wins ≤0 或不显著 → 不 keep（reason 带配对证据）。
- AC2：在 run 45a25dba 那类"绝对分平手但有真实逐题反转"的回放上，新判据能给出跟旧绝对分判据**不同且更合理**的结论（用历史 `abScenarioResultsJson` 回放验证）。
- AC3：minMeasuredN / anchor erosion 两道闸仍拦得住（回归测试）。
- AC4：阈值改配置即生效，无需改代码。
- AC5：`weightedScore` 仍出现在轨迹/RecordIteration（不丢展示）。
- AC6（冒烟，部署后 qa-reviewer 执行）：真起一轮 evolve（或回放一条历史 run）→ 观察 `decideKeep` reason 输出配对证据 + keep/reject 决策合理；贴 raw 日志为证。

## 冒烟用例（设计时预写，部署后执行）

1. **配对胜出**：构造/回放 improved=5 regressed=1 的 run → 期望 keep，reason 含 `net=+4` + 显著。
2. **配对平手/不显著**：improved=2 regressed=2 → 期望不 keep，reason 含 `net=0 不显著`。
3. **绝对分涨但配对回归**：weightedScore 微涨但 regressed>improved → 期望不 keep（这正是旧判据会误 keep 的反例）。
4. **早失败检测**：A/B 未完成 / measuredN 不足 → inconclusive，跟功能 bug 区分开。

## 已定决策（2026-06-18 用户 ratify）

- **Q1 → 配对为主，绝对分降 advisory**：keep 主判据 = 配对 net-wins；`weightedScore` 仍记轨迹/RecordIteration 但不再是判据。（理由：绝对分噪声正是 0 赢家"测不出提升"病因，留它当闸只会继续 0 赢家。）
- **Q2 → 最小 net-wins 数（先松后紧）**：显著性闸用可配置 `minNetWins`（先取松，如 2，按历史回放校准），符号检验 p 值作**可选**更严闸、默认关。（理由：n=18~36、不一致对常个位数，纯 p<0.05 极难达，会继续 0 赢家。）
