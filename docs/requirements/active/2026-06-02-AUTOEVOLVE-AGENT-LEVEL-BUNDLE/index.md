# AUTOEVOLVE-AGENT-LEVEL-BUNDLE — agent 整体爬坡(跨面改动包 / 一个整体分)

> **创建**：2026-06-02
> **状态**：**Phase 1 已交付**（2026-06-02，Full pipeline，双 reviewer PASS，mvn 2554/0/0 + AC-1 parity + V137 真活）。Phase 2-4 待排。详见 [delivery-index](../../../delivery-index.md) 首行。
> **父需求包**：[AUTOEVOLVE-AGENT-FLYWHEEL](../2026-05-31-AUTOEVOLVE-AGENT-FLYWHEEL/)（本包是其 evolve loop 的架构升级）
> **前置已交付**（见 [delivery-index](../../../delivery-index.md)）：BUG-1 winner-carry-forward(prompt 单面) + reflection + judge rationale(方案 B) + Volcengine Ark provider

## 30 秒摘要

把 AUTOEVOLVE 的 evolve loop 从「**按 surface 各自爬坡**」升级到「**agent 整体爬坡**」：

- **候选 = 跨面改动包**：一轮可同时改 prompt + behavior_rule（以后 tools/skills/hook）
- **A/B = 整 agent，一个整体分**：把改动包贴到 agent 跑评测场景，量「整个 agent 改完变好没」，**一个 pass-rate**，跟当前 best 比
- **best = 各面版本指针的一个包** `{promptVersionId, behaviorRuleVersionId, …}`，初始全空=现役原版

## 为什么(路 B vs 路 A)

底座 `AbEvalPipeline` **本来就是整-agent 跑分**（收两个完整 `AgentDefinition`）。三个 surface 专用 A/B 只是在拼 baselineDef/candidateDef 时**各换一个面**。所以「prompt+rules 一起改」天然就是 candidateDef 上同时换两样 → **一次 A/B 一个分**。

- **路 A（按面各自爬，已否决）**：复用现成 surface A/B，但各面单独赢的改动**拼起来从没整体验证过**，可能打架；best 是个没整体验证过的补丁集。
- **路 B（本包）**：爬的是 agent 本身；best 永远是一个**完整评测过的自洽 agent 状态**；以后加 tools/hook 只是包里多一种指针 + 一个面应用逻辑，A/B 和爬坡循环不用再改。

## 已 ratify 的决策（2026-06-02 用户）

| 决策 | 选定 |
|---|---|
| 架构方向 | **路 B**：agent 整体爬坡 / 候选=跨面改动包 / 一个整体分 |
| 打几个分 | **一个**（整-agent pass-rate）；按面归因要消融 2^N 臂，爬坡不做 |
| V1 范围 | **prompt + behavior_rule**（都有现成生成路径）；skill = Phase 4；tools/hook 远期 |
| 包持久化 | **复用各面版本表 + 指针元组**（不新建 agent-快照实体），复用现成 promote 路径 |
| 不退化保护 | behavior_rule 的 target/general 双标准**降级成「套在那一个整体分上的场景分组 gate」**，对任何改动适用 |

## 分期

1. **Phase 1 — 整-agent A/B 骨架** ✅ **已交付 2026-06-02**：包应用器 + TriggerAbEval/GetAbResult(surface=agent) + skipBaseline 顺延。**先用「只含 prompt 的包」验证** = 把现在的 prompt 爬坡用新整-agent 通道重跑（回归对照，确保不退化）。
2. **Phase 2 — rules 进包** ✅ **已交付 2026-06-02**：多面候选（prompt+rules 一起改）+ target/general 不退化保护套在整体分上。
3. **Phase 3 — orchestrator 种子 v2** + 反思 + 收尾汇总。
4. **Phase 4 — skill 进包**（skill 的「应用到 AgentDefinition」+ draft 顺延）。
5. **(远期)** tools / hook 各自先建面再进包。

## 文档

- [`prd.md`](prd.md) — 范围 / 验收点 / 决策
- [`tech-design.md`](tech-design.md) — 组件改动（复用 vs 新建）/ 风险 / 分期落地
