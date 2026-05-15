# EVAL-DYNAMIC-USER-SIM 动态用户模拟 + 三因子 promote gate

---
id: EVAL-DYNAMIC-USER-SIM
mode: full
status: design-draft
priority: P2
risk: High
created: 2026-05-16
updated: 2026-05-16
---

## 摘要

V5 飞轮升级：把"A/B 评测通过 = 改进有效"从"static held-out scenario 上更好"升级为"动态多轮 user simulator + 生产数据回流 + process-level judge 三因子全过 = 真有效"。

V4 已完成 OptimizableSurface<V> 抽象 + AbstractAbEvalRunner Template Method + 三 surface (skill / prompt / behavior_rule) 接飞轮。V5 在此基础上：

1. **增强 SessionScenarioExtractorService** —— 抽出业务语义 6 字段 (businessGoal / successCriteria / userPersona / userConstraints / failureSignals / expectedOutcome)
2. **新建 UserSimulatorAgent** —— system agent 走 V1/V2/V3 同款 pattern (owner_id=1 + is_public=TRUE)；按 goal+persona 动态生成下一轮用户输入跟 candidate agent 多轮对话
3. **新建 ProcessLevelJudge** —— 看完整 transcript 评分 (任务完成 / 约束满足 / 重复解释 / 工具调用 / 轮数 / latency)
4. **三因子 promote gate** —— A/B 通过 ✓ + canary 通过 ✓ + dynamic sim 通过 ✓ = 真有效，新加 `auto_promote_after_dynamic_sim` 阶段性 config 默认 false

## 范围裁剪

V5 Phase 2/3 单独成包，Full 档：

- 不动 V4 OptimizableSurface 接口形态 (扩展，不修改)
- 不破 V2 `auto_promote_after_ab` 现有 ratify (`auto_promote_after_dynamic_sim` 独立 config)
- 不引入 tau-bench (4 ratify decision #4)
- 不污染生产数据 (`t_session.origin='user_sim'` 隔离)
- 不动核心 7+1 文件 (Iron Law)

V5 Phase 1 (`SKILL-AB-MULTITURN-FIX`) 已交付 (2026-05-13 commit `6a78dd5` + 2026-05-16 commit `ca6a58d` archive)。

## 阅读顺序

1. [MRD](mrd.md) — 业务痛点 + V4 完成后剩余 gap + 用户场景
2. [PRD](prd.md) — Phase 2/3 范围 + 4 已 ratify 决策 + 验收点 + 非目标
3. [技术方案](tech-design.md) — 6 字段抽取 / UserSimulatorAgent / ProcessLevelJudge / 三因子 gate / Flyway V84+ / 实施计划

## 当前状态

需求包 draft，**等用户 review + 批准 Plan phase 启动**。

4 ratify 决策已 2026-05-16 与 user pre-ratify (写入 `prd.md` §已 ratify 决策)。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 整体方案 | [PROD-OPTIMIZATION-FLYWHEEL §V5](../../../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md) |
| Phase 1 归档 | [SKILL-AB-MULTITURN-FIX](../../archive/2026-05-13-SKILL-AB-MULTITURN-FIX/) |
