# P12 定时任务

---
id: P12
mode: full
status: prd-ready
priority: P1
risk: Full
created: 2026-04-28
updated: 2026-04-28
---

## 摘要

为 SkillForge 增加 user 型定时任务，让用户可以按 cron 或一次性时间触发 Agent prompt。

## 阅读顺序

1. [MRD](mrd.md)
2. [PRD](prd.md)
3. [技术方案](tech-design.md)

## 当前状态

[Sprint 4 前置决策（P12-PRE）](../../archive/2026-05-04-P12-PRE-preflight-decisions/index.md) 2026-05-04 已闭环：Cost Dashboard = 复用 `pages/ModelUsage`；PG 备份和多用户/权限模型均"暂不实现，accept current risk"。可进入设计评审。首版范围已收窄为 user tasks；system jobs 和高级可靠性进入 V2。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
