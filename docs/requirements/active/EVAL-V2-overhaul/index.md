# EVAL-V2 评测系统改造

---
id: EVAL-V2
mode: full
status: done
priority: P1
risk: Full
created: 2026-05-04
updated: 2026-05-07
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

## 范围（v2，按完整闭环重组）

**Phase 1 — 闭环基础（P0，必做）**：
- M2 多轮对话 case 模型（~1-2 天 Full）
- M3a 任务模型重构（task/item 双表 + rename t_eval_run + origin 字段，~4-5 天 Full **红灯**）
- M3b Dataset UI 完整化（含 Gap A，~0.5 天 Mid）
- M3c 归因闭环（含 Gap B + 独立关联表 t_eval_analysis_session，~2 天 Mid）

**Phase 2 — 体验对标（P1）**：
- M3d Compare runs side-by-side
- M3e Case → trace 跳转
- M3f Annotation queue
- M3g Dataset versioning

**Phase 3 — 高级能力（P2，按需）**：
- M4 Score 多维度
- M5 Trace 数据回流 dataset
- M6 Apply suggestion 接 PromptImprover

**不在范围**：
- Online evaluation 自动 sample / Built-in Metrics / Custom Metrics / Test Suites / CI 触发 / API 上传外部 dataset
- P14 tau-bench / SWE-bench 接入
- 权限 / 多用户 dataset 共享（依赖 P12-PRE）

## 当前状态

- **prd v2 ratified（2026-05-05）**：完整闭环重写 / 7 milestones (M2 + M3a-g + M4-6) / D1-D14 决策全 ratify
- M0+M1 MVP **已交付**（commit `47b331c`）
- Q1+Q2+Q3 follow-up **已交付**（commit `3554569`）
- M2 多轮对话 case **已交付**（commit `5608cd0`）
- M3a-b1 origin 字段 / 5 过滤 / 3 spawn 复制 **已交付**（commit `cd69350`）
- M3a-b2 task/item 双写 + migration 收尾 **已交付**（commit `2d10edf`）
- M3b Dataset UI 完整化 **已交付**（commit `b349f6f`）
- M3c 归因闭环 **已交付**（commit `2d10edf`，三种 analyze 入口 + `t_eval_analysis_session` + `AnalyzeEvalTask` 写回通道）
- M3d Compare runs **已交付**（commit `2d10edf`）
- M3e Case → trace **已交付**（commit `2d10edf`）
- M3f Annotation queue **已交付**（commit `2d10edf`，队列 / 持久化 / pending→applied，并接上“从 corrected expected 创建新版本”）
- M3g Dataset versioning **已交付**（commit `2d10edf`，scenario lineage + version selector/history drawer）
- M5 Trace 数据回流 dataset **已交付**（commit `2d10edf`）
- M4 Score 多维度 **已交付**（commit `99ad06f`）
- M6 Apply suggestion 接 PromptImprover **已交付**（commit `99ad06f`）
- M5+ Smart trace/session dataset drafts **已交付**（commit `a06bd3d`，trace/session 智能候选筛选 + 批量创建 draft + approve 前置流）
- **M 系列（M4 + M5 + M6）已完成；smart trace import 增强已推送**
- 下一步：**归档 EVAL-V2 需求包**

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
