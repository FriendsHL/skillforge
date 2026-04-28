# P1 PRD

---
id: P1
status: done
owner: youren
priority: P0
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-16
updated: 2026-04-29
---

## 摘要

实现 Self-Improve Pipeline，包括 eval scenarios、scenario runner、judge、attribution、prompt versions、A/B eval 和 promotion。

## 目标

- 支持 scenario-based eval。
- 支持失败归因。
- 支持 prompt 改进候选和 A/B 测试。
- 支持自动晋升和回滚。
- 支持前端查看 eval/improve 状态。

## 非目标

- 不在本期实现所有未来 benchmark。
- 不让自动改进绕过 Goodhart 防护。

## 功能需求

- EvalRun / EvalSession entity。
- ScenarioRunnerSkill。
- EvalJudgeSkill。
- AttributionEngine。
- PromptVersionEntity。
- PromptImproverService。
- AbEvalPipeline。
- 前端 Eval 页面和 ImprovePromptButton。

## 验收标准

- [x] Seed scenarios 可运行。
- [x] Eval run 可追踪。
- [x] A/B 改进有阈值和晋升逻辑。
- [x] Goodhart 防护生效。

## 验证预期

- 后端 eval/orchestrator tests。
- 前端状态展示检查。
