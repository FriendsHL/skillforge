---
id: SKILL-IMPORT-BATCH
mode: mid
status: done
priority: P1
risk: Mid
created: 2026-05-01
updated: 2026-05-01
delivered: 2026-05-01
---

# SKILL-IMPORT-BATCH 扫描 marketplace whitelist batch import

## 摘要

SKILL-IMPORT 是 agent on-demand 设计：每装一个 skill 都得 agent 显式调 ImportSkill。结果是用户 `~/.openclaw/workspace/skills/` 历史装好的 3 个 skill（pre-SKILL-IMPORT 装的）现在 SkillForge 看不到。本期补一个 batch rescan：dashboard 一个按钮 / 后端一个 endpoint，扫描 `allowedSourceRoots` 下的所有子目录，每个含 SKILL.md 的批量调既有 ImportSkill 流程注册到 catalog。

**本期只做后端 + API 类型定义，前端按钮 UI 设计后续单独 agent 做**。

## 当前状态

**done，2026-05-01 交付**。Mid Pipeline 走完：Phase 1 Backend Dev → Phase 2 双 Reviewer 对抗 1 轮（Sonnet）→ Phase 3 Judge Opus 重判（NEEDS_FIX：1 维持 blocker = source whitelist 缺失 + DECIDE-1 主会话决策 auth pattern 选 A）→ Phase 3.5 Dev 一次性 fix MUST-1/2 + DECIDE-1(A) PRD 文档对齐 → Phase Final 主会话独立 mvn test + spot-check 通过 → 归档。详见 [delivery-index.md 2026-05-01 行](../../../delivery-index.md)。

## 阅读顺序

1. [PRD](prd.md) — F1-F3 + AC + 非目标
2. [技术方案](tech-design.md) — 实现拆分 + 关键决策

## 链接

| 文档 | 链接 |
| --- | --- |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 关联需求 | [SKILL-IMPORT (2026-05-01)](../../archive/2026-05-01-SKILL-IMPORT-third-party-marketplace/index.md) —— 提供 SkillImportService.importSkill() / SkillImportProperties.allowedSourceRoots / ImportResult |
