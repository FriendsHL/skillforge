# SKILL-AB-MULTITURN-FIX Skill A/B 多轮评测修复

---
id: SKILL-AB-MULTITURN-FIX
mode: mid
status: delivered
priority: P1
risk: Mid
created: 2026-05-13
updated: 2026-05-16
delivered_commit: 6a78dd5
gap_fill_commit: ca6a58d
---

## 摘要

本包只修一个现存缺陷：`SkillAbEvalService` 在 skill A/B 评测中遇到 multi-turn scenario 时，目前会打印 EVAL-V2 M2 R5 warning 并降级执行 single-turn。本包让 skill A/B 复用现有多轮 runner / multi-turn judge 能力，真正执行 `conversationTurns`，从而让 skill evolution 的 candidate 比较覆盖真实多轮流程。

## Backlog 对齐

- 本包 supersedes / refines `EVAL-DYNAMIC-USER-SIM` 中的 Phase 1：先修现存 skill A/B multi-turn fallback。
- `EVAL-DYNAMIC-USER-SIM` 后续仍保留在 backlog，用于 session 业务场景抽取、LLM user simulator、process-level judge 等 Full 级能力。
- 本包关联 EVAL-V2 M2 R5 遗留 warning：`SkillAbEvalService` 当前明确写有“不支持 multi-turn，fallback single-turn”的日志路径。

## 范围裁剪

Phase 1 单独成包，按 Mid 推进：

- 不做 schema migration。
- 不新增 REST endpoint。
- 不改 dashboard 主流程。
- 不改 `EvalScoreFormula` 权重。
- 不接 `AbEvalPipeline` prompt A/B。
- 不做动态用户模拟。

Phase 2/3 不在本包 ratify。等 Phase 1 实测后，再把它们合并到 `EVAL-DYNAMIC-USER-SIM` 独立开 Full 包。

## 阅读顺序

1. [MRD](mrd.md) - 业务痛点和本次裁剪后的目标。
2. [PRD](prd.md) - Phase 1-only 产品范围、验收和非目标。
3. [技术方案](tech-design.md) - 现状证据、runner 复用决策、实施计划。

## 当前状态

方案草案已按 review 意见瘦身。开工前必须先完成 Phase 1.0 证伪步骤：稳定重现当前 fallback warning、引用源码行号、写红测试，再进入修复。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | - |
