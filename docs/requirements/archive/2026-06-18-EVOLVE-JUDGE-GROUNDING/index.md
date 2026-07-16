# EVOLVE-JUDGE-GROUNDING — 自进化判定与候选对靶优化

> 创建：2026-06-17（从 backlog 升 active）
> 状态：**done / archived；Phase 1 已交付**（2026-06-18，commit `5be19db9`，见 [delivery-index](../../../delivery-index.md)）。**Phase 2 = [EVOLVE-CANDIDATE-GROUNDING](../2026-06-18-EVOLVE-CANDIDATE-GROUNDING/index.md)**（独立包并已交付）。Phase 3+ 若启动应另立需求，不再让本包保持 active。
> 模式：Full（触碰 judge "赢家判定"正确性 + evolve-loop workflow；属核心测量层）
> 来源：对照 Anthropic《A Harness for Every Task》《Introducing Dynamic Workflows》自治多 agent 经验（对抗证伪 / pairwise 判定 / self-preferential bias）+ 自进化现状快照瓶颈（0 赢家）。

## 摘要

治 evolve loop「**0 真赢家、绝对打分噪声大、候选不对靶**」的瓶颈。分阶段：

- **Phase 1（本需求主体）— 配对 / comparative 判定**：把 `decideKeep` 从"绝对 weightedScore 差值"改为"**配对逐场景胜负**判定"（候选 vs 基线在同一组场景，用已有的 `perScenarioFlips` 做符号检验 / McNemar 式 net-wins + 显著性闸）。**无 schema 改动**——配对数据已持久化。
- **Phase 2+（roadmap，本需求只列不做）**：候选 per-badcase grounding（候选携带因果链）/ 对抗 refuter / held-out gate 制度化。

## 为什么先做 Phase 1

绝对加权分在 n=18~36 下噪声大，±5pp 难分真假——这是 0 赢家"测不出提升"那一面的直接病因。而**配对数据（同场景候选 vs 基线的逐题胜负）已经在 `GetAbResultTool.perScenarioFlips` 里算好了**，只是没被判定逻辑用上。改造面最小、ROI 最高、blog 明确背书"比较式判断比绝对打分更可靠"。

## 阅读顺序

1. [prd.md](prd.md) — 目标 / 非目标 / 验收点（实现前必读）
2. [tech-design.md](tech-design.md) — 最小改造面 + 2 方案 tradeoff + 推荐 + 风险 + 测试/冒烟（design-draft，待 PRD ratify 后定稿）
3. [mrd.md](mrd.md) — 用户原始诉求 + blog 经验来源（意图清楚可跳过）

## 下一步

PRD/tech-design 已 ratify（Q1 配对为主+绝对分降 advisory / Q2 minNetWins 先松后紧）→ **按 Full pipeline 实现 Phase 1**（触碰核心测量层，对抗 review 含 java-reviewer + 显式审 evolve-loop workflow INV-1~3 不变量）。

## 关联

- 现状：[autoevolving 能力现状快照](../../../references/autoevolving-capability-stage-2026-06-17.md) B/C
- 强相关：[AUTOEVOLVE-CLOSE-LOOP](../../active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/index.md) 阶段B（EVOLVE-BADCASE-SENSITIVITY，尺子敏感度）—— **本需求管“判定方法”，阶段B 管“尺子刻度”，互补，协调排期**
- 归属：[AUTOEVOLVING-MASTER](../../active/2026-05-28-AUTOEVOLVING-MASTER/index.md) V3（falsification + predicted_impact 的前置子集）
- 姊妹方向：[WF-CONCURRENT-PIPELINE](../../backlog/WF-CONCURRENT-PIPELINE/index.md)（Phase 2 的多阶段 fan-out 链路受益于它）
