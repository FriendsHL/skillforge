# EVAL-429-ROBUSTNESS — infra 失败摘出评测分母（泛化版）

> **创建**：2026-06-03（从 backlog 升 active）
> **状态**：决策已 ratify，待实施（Mid + Plan 细化 + 2 reviewer 对抗）
> **来源**：2026-06-02 mimo 429 + 2026-06-03 concern#2 evolve 活体再现（ark ConnectException 127.0.0.1:1082 + StreamResetException 致 AB eval scenario 失败混进 pass-rate）

## 痛点
A/B / eval 的 pass-rate 把**基础设施失败（infra）**当成 **agent 质量失败**算进分母 → evolve gate 拿被污染的分数比较 → 限流/网络抖动时段产不出可信结果。实测 concern#2 evolve run 的 +9.09pp/−9.09pp 因混入 infra 失败场景而不可信。

## 现状（Explore 2026-06-03）
- **重试**：provider 层有限重试（OpenAiProvider 对 ConnectException 重试 2 次；chatStream 流中断不重试 footgun#3）。**场景层无重试**。
- **分母污染**：场景 LLM 调用失败 → catch → 记 `ERROR`/score 0.0，**算进分母**。
- **4 个分母** in `AbEvalPipeline`：line 281 / 533 / 534 / 985，全 `passed / 总数.size()`。
- **gate**：`AgentEvolveAbEvalService.computeDeltas` / `computeSubsetDeltas` 逐场景算 target/regression delta，也要一致摘。
- **status 分类**：`EvalJudgeTool` 把 `ERROR`/`TIMEOUT` 当 infra/runtime 短路；`PASS`/`FAIL` 是 oracle 真质量判定。

## 已 ratify 决策（2026-06-03）
- **D1 摘哪些**：`ERROR` + `TIMEOUT` = infra/not-measured，摘出分母；`PASS`/`FAIL` 留（真质量）；`VETO` 若出现算质量失败、留。
- **D2 pairwise（两侧都摘）**：某场景只要 **candidate 或 baseline 任一侧** ERROR/TIMEOUT，**两侧都摘** → 两个率永远在"双侧都测到"的同一批场景上算，delta 严格可比。skipBaseline 模式下：候选侧 measured ∧ cached-baseline 该场景 measured 才计入。
- **D3 measured=0**：全场景 infra 失败 → 返回 **not-measured 哨兵（null）而非 0**，gate 当"测不出/不晋升"，不当 0% 全败。
- **D4 不静默**：log 摘了几个 infra 场景（哪几个 scenarioId）。

## 验收点
- AC-1：含 ERROR/TIMEOUT 场景的 A/B，pass-rate 分母 = 双侧都 measured 的场景数（pairwise），不含 infra 失败。
- AC-2：4 个分母 + computeDeltas/computeSubsetDeltas 一致；delta 在同一批 measured 场景上算。
- AC-3：measured=0 → not-measured 哨兵，gate 不晋升、不当回归。
- AC-4：现有全 PASS/FAIL（无 infra）的 A/B 行为不变（向后兼容）。
- AC-5：mvn 全绿 + 关键路径单测（含 infra 混入场景的分母正确性）。

## 范围
- **in**：AbEvalPipeline 4 分母 + AgentEvolveAbEvalService gate delta + not-measured 语义 + log。
- **out**：场景级自动重试（本期不做，先摘除；重试留后续）；provider 层重试逻辑不动；embedding 列 bug（顺手记，另修）。

## 文档
- 决策细节见本 index；实施 plan 见 pipeline 产出。
