# EVOLVE-JUDGE-GROUNDING — 自进化测量与候选对靶优化（pairwise judge + 对抗证伪 + 候选 grounding）

> 创建：2026-06-17
> 状态：backlog（未排期；跟 AUTOEVOLVE-CLOSE-LOOP 阶段B + AUTOEVOLVING V3 falsification 合流，**不重复造**）
> 模式：Full（触碰 judge/A-B 测量层 + 候选生成；影响"赢家判定"正确性）
> 来源：参考 Anthropic《A Harness for Every Task》《Introducing Dynamic Workflows》里关于自治多 agent 的**使用规范/经验**（对抗证伪、pairwise 判定、self-preferential bias、何时不该用）。

## 用户请求

「自进化的 loop 可以参考他们的一些使用规范或者经验，我们该如何优化？」

## 背景（当前瓶颈，见现状快照 B/C）

- run 45a25dba：测量层修好了但 **0 赢家**，瓶颈移到**候选 grounding 到真实目标失败**。
- judge 当前是**绝对加权分**（`0.6·general + 0.4·harvest`），无 pairwise 对比、无统计显著性、无 held-out 强制 gate，且是"证明候选更好"方向（self-preferential 风险）。
- 候选从 opt-report session issue 出，跟 agent 真实的 9 个可复现失败**脱节** → 候选不对靶。

CC blog 的相关经验（可直接参考）：
- **比较式判断比绝对打分更可靠**（"sorting" 用例明确点名 pairwise/锦标赛）。
- **对抗式核验 / Popper 证伪**：每个产出由独立 agent 去**反驳**，迭代到收敛——从结构上压制 self-preferential bias。
- **根因调查**：从互不相交证据起多个假设、各自面对 verifier+refuter——映射到"候选必须对靶具体失败"。
- **何时不该用 workflow**：不是每个任务都值得，先问"真的需要更多算力吗"——提醒我们别给小改动套重流程。

## 问题（三点）

1. **judge 绝对打分**：小样本（n=18~36）下绝对分噪声大，难分"真提升"与"噪声"。
2. **self-preferential bias**：judge 倾向证明候选更好，而非主动找它引入的回归。
3. **候选不对靶**：候选不绑定到具体可复现失败 → 0 赢家根因。

## 提议方案（待 design 细化，按 ROI 排）

1. **pairwise / 锦标赛判定**（最高 ROI、blog 背书）：争议候选改为"候选 vs 原始"两两对比判定，替代/补充绝对加权分。
2. **候选 per-badcase grounding**：每个 badcase 各起 agent 诊断 → 针对该失败生成候选，候选**携带因果链**（针对哪条 badcase 的什么失败），否则不进 A/B。
3. **对抗证伪**：采纳前额外起 refuter 证明"候选 NOT better / 引入回归"，多数证伪则毙。
4. **held-out / blind-oracle 制度化**：把现有"盲测纪律"升为 winner 判定的硬 gate（过 held-out 才算赢），防 Goodhart/过拟合 eval 集。

## 验收点（草拟）

- 争议赢家判定走 pairwise（候选 vs 原始）而非单点绝对分。
- 候选记录里能查到"针对哪条 badcase 的什么失败"的因果链。
- 采纳前有 refuter pass；通过 held-out gate 才算赢家。
- 在 run 45a25dba 那类场景上，能区分"真没提升"与"度量没抓住提升"。

## 开放问题 / 协调

- **跟 CLOSE-LOOP 阶段B（EVOLVE-BADCASE-SENSITIVITY）边界**：阶段B 管"尺子敏感度让加权分爬得动"，本包管"判定方法（pairwise/证伪）+ 候选对靶"。两者互补，**需在升 active 时合并排期，避免重复**。
- pairwise 的成本（更多 LLM 调用）；"争议"阈值怎么定。
- 跟 AUTOEVOLVING V3 的 falsification + predicted_impact 如何对齐（本包可视为 V3 的前置/子集）。

## 关联

- 现状：[autoevolving 能力现状快照](../../../references/autoevolving-capability-stage-2026-06-17.md) B/C/D
- 强相关：[AUTOEVOLVE-CLOSE-LOOP](../../active/2026-06-03-AUTOEVOLVE-CLOSE-LOOP/index.md) 阶段B / G3 / P3
- 归属：[AUTOEVOLVING-MASTER](../../active/2026-05-28-AUTOEVOLVING-MASTER/index.md) V3（falsification + predicted_impact）
- 姊妹方向：[WF-CONCURRENT-PIPELINE](../WF-CONCURRENT-PIPELINE/index.md)（承载本包的多阶段 fan-out 链路）
