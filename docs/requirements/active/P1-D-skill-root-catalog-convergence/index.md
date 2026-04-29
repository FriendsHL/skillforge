# P1-D Skill Root 与 Catalog 收口

---
id: P1-D
mode: full
status: design-draft
priority: P1
risk: Full
created: 2026-04-29
updated: 2026-04-29
---

## 摘要

在 P1-C Skill Control Plane 基础上，进一步收口 Skill 的物理存放、启动加载、DB catalog 和冲突治理。目标是只保留 `system-skills/` 与 `skillforge-server/data/skills/` 两个标准 Skill root，并让 `t_skill` 作为用户/业务 Skill 的控制面和索引表。

## 阅读顺序

1. [MRD](mrd.md) - 用户原始诉求、背景和约束。
2. [PRD](prd.md) - 产品需求、验收标准和非目标。
3. [技术方案](tech-design.md) - 目录、加载、DB reconcile、冲突规则和迁移方案。

## 当前状态

技术方案草稿已形成，等待用户确认。确认后进入 Full Pipeline 实现计划。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | - |
