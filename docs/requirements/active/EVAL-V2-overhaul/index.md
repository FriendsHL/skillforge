# EVAL-V2 评测系统改造

---
id: EVAL-V2
mode: full
status: prd-draft
priority: P1
risk: Full
created: 2026-05-04
updated: 2026-05-04
milestones: M0 (browser + judge 显式) → M1 (progress streaming) → M2 (多轮 case) → M3 (对标 langfuse/opik/coze)
---

## 摘要

2026-05-04 audit：当前 SkillForge eval 系统**链路完整但体验远落后**。后端 `EvalJudgeTool` / `AbEvalPipeline` / `EvalOrchestrator` 已经能算 compositeScore / attribution / judgeRationale，但前端：

- **看不到当前有哪些 case**（`pages/Eval.tsx` 的 Scenarios tab 仅展示某次 run 内的 results，不是独立 dataset browser）
- **多轮对话 case 装不进数据模型**（`EvalScenarioEntity.task` 单 String，`oracleExpected` 单 String，无 `conversation_turns` 字段）
- **跑 eval 时看不到进度**（触发返回 `{evalRunId, status:RUNNING}` 后只能 poll `passedScenarios` 计数，无 SSE/WS push）
- **跑完看不到分数 / 建议**（链路在但显示位置不直观；缺"调分析 agent 看 session 给意见"入口）
- **UI 对比 langfuse / opik / coze 罗盘缺**：dataset 一等公民页面 / runs side-by-side compare / annotation queue / case → trace 链接

把 eval 从"基础堪用"提升到"对标 langfuse/opik 的可信工具"。

## 阅读顺序

1. [MRD](mrd.md) — 用户原始痛点 + 对标平台特性摘要
2. [PRD](prd.md) — M0-M3 范围 / 验收点 / 决策点
3. [技术方案](tech-design.md) — schema / API / UI 实现路径

## 范围决策

**MVP（M0 + M1）—— 优先做**：
- M0 Scenarios browser + Eval 详情显式化（解痛点 1 + 4，~1 天）
- M1 Progress streaming（解痛点 3，~0.5 天）

**V2（M2 + M3）—— 排后**：
- M2 多轮 conversation case 模型扩展（解痛点 2，~1-2 天，需要 schema migration）
- M3 UI 对标 langfuse/opik/coze（解痛点 5，~3-5 天，dataset browser / compare / annotation / case-trace linking）

**不在范围**：
- P14 tau-bench 接入（暂缓表保留）
- 权限 / 多用户 dataset 共享（依赖 P12-PRE 多用户决策定型）

## 当前状态

- prd-draft：5 痛点 audit 结果固化在 PRD §3；范围拆分清晰
- 待 ratify：3 个决策点（见 PRD §6）
- tech-design：草稿状态，等 PRD ratify 后细化

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 关联：评测方法论 | [`docs/references/design-eval-methodology.md`](../../../references/design-eval-methodology.md) |
| 关联：P1 Self-Improve（已交付）| [archive](../../archive/2026-04-20-P1-self-improve-pipeline/) |

## 关联暂缓项（不在本期）

- **P14** tau-bench 类真实业务评测 — 等真实 benchmark 需求
- **P3-2/P3-4** — 依赖 P14
- **P15-3/P15-6** Analyzer MVP eval run 落库 — 部分可纳入 M0 Eval 详情显式化，多数延后
