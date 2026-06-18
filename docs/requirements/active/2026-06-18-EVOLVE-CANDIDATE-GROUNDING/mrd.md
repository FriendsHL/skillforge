# MRD — EVOLVE-CANDIDATE-GROUNDING（Phase 2）

## 用户诉求

> 「按照顺序来 EVOLVE-JUDGE-GROUNDING Phase 2 做这个吧」+「不用我选了你来决策吧。决策过程可以搞个 subagent review 下。除非一定需要我来决策。」（2026-06-18）

用户授权我决策 Phase 2 设计，要求决策过程经 subagent 对抗 review，仅在"一定需要用户"时上报。

## 背景（live 证据）

Phase 1（配对判据）交付后实跑 evolve run cba0878f：两轮均正确拒，但 **0 赢家依旧**，且新判据给出清晰信号——候选**主动制造回归**：iter1 net -1（改进3/回归4）、iter2 net -7（改进1/回归8）。判据已可靠，候选质量成为瓶颈，与 [现状快照](../../../references/autoevolving-capability-stage-2026-06-17.md) 记录的「瓶颈移到候选 grounding」吻合。

## 决策过程（按用户要求 subagent review）

- 我初定：方案 A「grounding-in-context」（喂失败详情 + 盖 targetScenarioId），defer B（fan-out）。
- architect agent 对抗 review → **ENDORSE-WITH-CHANGES**，关键修正：
  - 纯 A 上限低——live net -7 是"整面编辑爆炸半径"，瞄得准 ≠ 刷子窄；grounding 让 leaf 瞄准但不让它改得更窄。
  - 漏了最高性价比缝：`ReconcilePrediction` + `perScenarioFlips.rationale` 已算但从没穿回 leaf（闭环测量、开环学习）。
  - 必须叠加 **C-seam（最小/加性编辑）** 直击爆炸半径；A+C 明显强于纯 A。
  - prediction 需对已知失败 id 集 ground，否则是"假 grounding"（confidence 算空分母）。
  - tool payload 必须 capped（防 token 膨胀稀释 leaf 推理）。
- 据此修订为 **A + C-seam + 4 改动**。

## 需用户决策的（architect 标"human-owned"）

- **H1 退出标准**（已 ratify 2026-06-18）：≥3 轮干净 run 仍 0 赢家且净回归未明显降 → 升级。
- **H2 升级方向**（先打底，非本需求）：升级时重开"整-agent bundle"设计（per-scenario 子打分/限编辑范围），**非反射上 B**（B 未验证为解，fan-out 仍整面编辑仍各自炸）。重开旧设计是用户决定。

## 约束

- 不改"一轮一 bundle"基数 + A/B/keep 记账；不重开 bundle 设计；无 schema 迁移（free-schema sidecar + V-migration 仅 prompt 内容 UPDATE）。
