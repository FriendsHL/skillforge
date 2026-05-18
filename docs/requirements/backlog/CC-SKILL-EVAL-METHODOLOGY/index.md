---
id: CC-SKILL-EVAL-METHODOLOGY
status: backlog
priority: P3
mode: tbd
risk: tbd
created: 2026-05-18
---

# CC-SKILL-EVAL-METHODOLOGY — Claude Code skill 评测方法论借鉴

## User Request

2026-05-18: Claude Code 有一套 skill 评测方法论值得借鉴。本次只记录这个需求（token 紧张），后续单独 session 看怎么融入 SkillForge。

## Sources

- **方法论文档**: https://agentskills.io/skill-creation/evaluating-skills
- **实现 reference**: https://github.com/anthropics/skills/tree/main/skills/skill-creator
  - 评测实现就在这个 `skill-creator` skill 里面（cc 自己用 skill 做 skill 评测，meta dogfood）

## Why 借鉴

SkillForge 当前 skill 评测体系（EVAL-V2 + SKILL-CANARY-ROLLOUT V2 + MULTI-SURFACE-FLYWHEEL V4 + EVAL-DYNAMIC-USER-SIM V5）走的是：
- EvalScenario（静态 held-out scenarios + V5 动态 user simulator）
- SkillAbEvalService 做 baseline vs candidate A/B 对比
- 4 维评分 (quality / efficiency / latency / cost)

cc `skill-creator` 的 skill 评测方法论可能有差异化思路（待调研）：
- single skill 质量评测（不是 A/B）？
- skill 创作过程中 inline evaluator？
- 跟我们 SessionAbEvalService 5-hook template 对比

## Next steps（后续 separate session）

1. 读 https://agentskills.io/skill-creation/evaluating-skills 方法论 + design intent
2. 读 github anthropics/skills/skill-creator 实施代码 + prompt + example
3. 跟现有 EVAL-V2 / SKILL-CANARY-ROLLOUT / MULTI-SURFACE-FLYWHEEL / EVAL-DYNAMIC-USER-SIM 对比 — 找重叠点 + 差异化点
4. 决定融入 path（3 选 1 候选）：
   - **A. 扩 EvalScenario**：把 cc 方法论的评测维度加进 EvalScenario schema（businessGoal/successCriteria 等已有，可能要加 skill-creation-specific 字段）
   - **B. 扩 SkillAbEvalService template hook**：加 cc 风格的 single-skill quality check 作为 AbstractAbEvalRunner 新 hook
   - **C. 独立新 surface**：新 `/api/skill-quality` endpoint + 独立 `SkillQualityEvaluator` service（如果方法论跟 A/B 完全正交）
5. 拍板后开 mrd / prd / tech-design 三件套，正式启动需求

## 关联 SkillForge 现状

- EVAL-V2 (2026-05-07 archive): M0-M6 全闭环 evaluator framework
- SKILL-CANARY-ROLLOUT (2026-05-15 archive): skill A/B + canary 灰度
- MULTI-SURFACE-FLYWHEEL (2026-05-15 archive): OptimizableSurface 抽象 + 3 surface 共骨架
- EVAL-DYNAMIC-USER-SIM (2026-05-16 archive): UserSimulator agent + multi-turn 评测

## MVP 不做（本 backlog item）

- 不本 session 实施 — 仅记录 + 链接 + 后续 separate session 调研
