---
id: SKILL-IMPORT
mode: mid
status: done
priority: P1
risk: Mid
created: 2026-05-01
updated: 2026-05-01
delivered: 2026-05-01
---

# SKILL-IMPORT 第三方 marketplace 装包导入 SkillForge

## 摘要

补齐 P1-D 留下的 import 缺口：当 agent 用 `npx clawhub install` / `gh repo clone` / `npx @skill-hub/cli install` 这类第三方 CLI 把 skill 装到外部目录后，agent 调用新增的 `ImportSkill` Tool 把那个目录的 skill 注册到 SkillForge —— `cp` 到 SkillForge runtime root + 写 `t_skill` + 注册到 `SkillRegistry`，让 dashboard 可见、agent 后续 turn 可用。

## 背景

P1-D 在 `SkillSource` enum 里预留了 `CLAWHUB / GITHUB / SKILLHUB / FILESYSTEM` 四档目标路径分配（`SkillStorageService.allocate()`），但**没有任何上层 service 调用过它们**。结果：agent 看到 ClawHub system skill 文档里写"Install: `npx clawhub install <slug>`"就直接照做，skill 装到 `~/.openclaw/workspace/skills/` —— 跟 SkillForge `t_skill` 完全脱节，dashboard 看不见，agent 下次 turn 也调不到。

bug 现场：session `f73cba8f-4a01-466d-bb24-e9de7cd69438` 装了 `tool-call-retry`，落到 `~/.openclaw/workspace/skills/tool-call-retry/`，SkillForge 的 `t_skill` 表 0 user 行，`data/skills/` 空目录。详见 [delivery-index 现场分析](#)（待写入）。

## 当前状态

**done，2026-05-01 交付**。Mid Pipeline 走完：Phase 1 Backend Dev → Phase 2 双 Reviewer 对抗 1 轮（Sonnet）→ Phase 3 Judge Opus 重判（NEEDS_FIX：1 维持 blocker + 1 warning 升 blocker + 1 warning 升 must / one-liner）→ Phase 3.5 Dev 一次性 fix MUST-1/2/3 → Phase Final 主会话独立 mvn test + spot-check 通过 → 归档。详见 [delivery-index.md 2026-05-01 行](../../../delivery-index.md)。

## 阅读顺序

1. [PRD](prd.md) — 功能需求 / 验收点 / 非目标。
2. [技术方案](tech-design.md) — 模块划分、配置、Tool / Service 实现要点、edge cases。

## 链接

| 文档 | 链接 |
| --- | --- |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 关联需求 | [P1-D Skill Root 与 Catalog 收口](../../archive/2026-04-30-P1-D-skill-root-catalog-convergence/index.md)（提供 `SkillSource` enum + `SkillStorageService.allocate` + reconciler 基础设施） |
| 现场 session | `f73cba8f-4a01-466d-bb24-e9de7cd69438`（agent 用 `npx clawhub install` 装 `tool-call-retry`，落到 ClawHub workspace 而非 SkillForge）|
