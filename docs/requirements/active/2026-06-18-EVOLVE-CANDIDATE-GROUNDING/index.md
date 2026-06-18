# EVOLVE-CANDIDATE-GROUNDING — 候选 per-badcase grounding（EVOLVE-JUDGE-GROUNDING Phase 2）

> 创建：2026-06-18
> 状态：**Phase 2 已交付**（2026-06-18，commit `775fe4df`，LIVE 冒烟 PASS）。后续按退出标准跨 ≥3 轮观察净回归/赢家
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

## 阅读顺序

1. [prd.md](prd.md) — 目标/非目标/验收/退出标准/冒烟
2. [tech-design.md](tech-design.md) — 4 缝的 file:line + C-seam + 不变量 + 风险 + 测试
3. [mrd.md](mrd.md) — 用户诉求 + architect review verdict（意图清楚可跳）

## 关联

- 现状：[autoevolving 能力现状快照](../../../references/autoevolving-capability-stage-2026-06-17.md)
- 前身：[EVOLVE-JUDGE-GROUNDING Phase 1](../2026-06-17-EVOLVE-JUDGE-GROUNDING/index.md)
- 归属：[AUTOEVOLVING-MASTER](../2026-05-28-AUTOEVOLVING-MASTER/index.md) V3（候选质量是 outer-loop 收敛前提）
