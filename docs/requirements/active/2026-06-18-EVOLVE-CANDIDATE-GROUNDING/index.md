# EVOLVE-CANDIDATE-GROUNDING — 候选 per-badcase grounding（EVOLVE-JUDGE-GROUNDING Phase 2）

> 创建：2026-06-18
> 状态：**Phase 2 已交付**（2026-06-18，commit `775fe4df`）。**观察 1/≥3（2026-06-22，run `bbe8a4dd`）：候选仍负优化**（target 80%→40% = −40pp，0 赢家），且候选是**大段通用重写、非最小对靶 → C-seam(#4) 未生效**；**倾向升级 Phase 3 / H2**。详见下方「观察记录」。
> 模式：Full（触碰核心 evolve-loop.workflow.js + 候选生成工具 + V-migration prompt；属核心测量/进化层）
> 前身：[EVOLVE-JUDGE-GROUNDING](../2026-06-17-EVOLVE-JUDGE-GROUNDING/index.md) Phase 1（配对判据，已交付）的 Phase 2。

## 摘要

Phase 1 把"判据噪声"治好了（配对 net-wins），但 **0 赢家依旧**——live run cba0878f 实证候选**主动制造回归**（iter2 改进 1 / 回归 8 / net -7）。根因（勘察确认）：候选从 opt-report 聚合线索生成，**真实失败场景详情（errorSignature/task/rationale）从没喂进候选生成**，leaf 蒙眼改整面 → 改一处崩八处。

**决策（我定 + architect review 修订）= 方案 A「grounding-in-context」+ C-seam「最小 delta 编辑」**，不做侵入式 fan-out（B）。

architect review 关键修正：纯 A（喂失败详情让 leaf 瞄准）**上限低**——live 的 net -7 是"整面编辑爆炸半径",瞄得准 ≠ 刷子窄。故必须叠加 C-seam（最小/加性编辑收窄爆炸半径）+ 接上一条被丢弃的高价值反馈回路。

## 4 个改动（详见 tech-design）

1. **接回被丢弃的反馈回路**（最高性价比）：`ReconcilePrediction` 结果 + `perScenarioFlips.regressed[].rationale` 已算但从没穿回下一轮 leaf history（现只塞 bare 场景名）→ 穿回去。
2. **喂 capped per-badcase 失败详情**：扩 `ListActiveHarvestedScenarios` 返投影截断（errorSignature + 一行 task + extractionRationale，**不含** fixtures/全文）→ 进 leaf prompt，V151 改"先诊断后改"。
3. **prediction 对已知失败 id 集 ground**（非瞎猜 id）+ 候选盖 targetScenarioId 因果链。
4. **C-seam：最小 delta / 加性编辑约束**（GenerateCandidate 优先追加靶向 rule 而非重写、prompt 限 diff）——直击爆炸半径。

## 不做（边界）

- 不做 B（per-badcase fan-out / 改"一轮一 bundle"基数 + A/B/keep 记账）—— review 论证 B 未验证为解（fan-out 仍是整面编辑、仍各自炸）。
- 不重开"整-agent 一个 bundle 一个分"设计（AUTOEVOLVE-AGENT-LEVEL-BUNDLE）—— 见 H2。

## 退出标准（2026-06-18 用户 ratify）

A+C 上线后，用 Phase 1 判据作尺，agent 3 跨 **≥3 轮干净 evolve run**：若中位 net-wins 仍 ≤0（0 赢家）**且**净回归没比 live 基线（-1/-7）明显降 → 判 A+C 不够，**升级**。

**H2（升级方向，非本需求）**：升级时**重开 bundle 设计**（per-scenario 子打分 / 限定编辑范围），**不是**反射式上 B。重开旧设计是用户决定。

## 观察记录（退出标准 ≥3 轮）

### 观察 1/≥3 — 2026-06-22，run `bbe8a4dd`（agent 3，maxIter=1）

**先肯定：管道/收尾层已修好。** 这次 run 干净跑完：opt-report（5 issue）→ GenerateCandidate → A/B（5 target + 45 regression = 50 场景跑完）→ ReconcilePrediction → RecordIteration → workflow 收尾，~7min，**没像历史(06-05)那样卡 RUNNING**（那批 active/RUNNING 孤儿是旧代码遗留）。

**但候选仍是负优化，0 赢家**（A/B run `1b71ca1e`）：

| 指标 | baseline | candidate | 差 |
|---|---|---|---|
| **target（5 个该修的 badcase）** | **80%** | **40%** | **−40pp** ❌ |
| regression（45） | — | — | +4.4pp |
| 总体 | 74% | 74% | 0 |

翻挂的 2 个 target（oracle 理由实证）：
- **Open Source Eval Platform Review**（多步调研报告）：baseline 70 PASS → candidate **0 FAIL**，理由"agent 根本没完成任务、只说自己缺资源" = **直接摆烂没产出**。
- **badcase-grep-81f62bbc**（多轮工具操作 behavioral oracle）：57 PASS → **30 FAIL**（干净完成 2/5 → 0/5）。

**根因（关键）**：本轮 issue 是"token 效率"类。候选生成器（evolve-candidate-gen）的"修法"是**糊上一整段通用强约束**：「输出严格限长 / 超 10 轮压缩丢细节 / 单任务搜索最多 15 轮」（候选 `12524c53`，prompt 448→669 字，还带"冗余冗余"错字）。这些**通用紧箍咒把多步任务憋死** → 在自己的靶场景上自爆。

**这直接证伪了 Phase 2 的 C-seam(#4)假设**：候选**不是**"最小/加性/对靶编辑",而是**大段通用重写**。说明：
- C-seam（GenerateCandidate 优先追加靶向 rule、限 diff）**没真生效** —— 候选仍在做整面宽泛改写。
- 候选生成**对回归无感知** —— 不知道这些全局约束会打挂 target 场景（grounding #2/#3 喂了失败详情，但没喂"这些能力必须保住")。

**结论（1/≥3，但机制极清晰）**：0 赢家的根**不在判据、不在管道**，在**候选生成质量**——拿任务完成度换 token 效率的越界改动。倾向不必凑满 3 轮即可判 **Phase 2 (A+C) 不够，需升级**。

### Phase 3 / H2 候选修复方向（待立项）

1. **候选生成强制"最小 + 对靶 + 回归感知"**：evolve-candidate-gen prompt 硬约束「只做针对该 badcase 失败行为的最小编辑，**禁止加可能影响其它任务的通用全局约束/紧箍咒**」；并把 **target 场景摘要喂给候选生成**（"这些能力必须保住，改坏即废"）。
2. **质疑 issue 适配性**：opt-report 产"token 效率"这类**宽泛 issue** 是否适合用"加 prompt 规则"来修 —— 宽泛 issue → 宽泛修 → 高回归风险。可能要在 opt-report→候选 之间过滤/降权这类 issue。
3. （承接 H2）若上述仍不够，再考虑重开 bundle 设计（per-scenario 子打分 / 限定编辑范围）。

> 复现路径：`POST /api/evolve/agents/3/run?maxIter=1` → 看 `t_agent_evolve_ab_run` 的 `candidate_target_rate` vs `baseline_target_rate` + `ab_scenario_results_json` 里 subset=target 的 baseline/candidate.status 翻挂。

## 阅读顺序

1. [prd.md](prd.md) — 目标/非目标/验收/退出标准/冒烟
2. [tech-design.md](tech-design.md) — 4 缝的 file:line + C-seam + 不变量 + 风险 + 测试
3. [mrd.md](mrd.md) — 用户诉求 + architect review verdict（意图清楚可跳）

## 关联

- 现状：[autoevolving 能力现状快照](../../../references/autoevolving-capability-stage-2026-06-17.md)
- 前身：[EVOLVE-JUDGE-GROUNDING Phase 1](../2026-06-17-EVOLVE-JUDGE-GROUNDING/index.md)
- 归属：[AUTOEVOLVING-MASTER](../2026-05-28-AUTOEVOLVING-MASTER/index.md) V3（候选质量是 outer-loop 收敛前提）
