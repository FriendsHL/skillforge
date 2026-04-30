# P1-D Skill Root 与 Catalog 收口

---
id: P1-D
mode: full
status: done
priority: P1
risk: Full
created: 2026-04-29
updated: 2026-04-30
delivered: 2026-04-30
---

## 摘要

在 P1-C Skill Control Plane 基础上,进一步收口 Skill 的物理存放、启动加载、DB catalog 和冲突治理。目标是只保留 `system-skills/` 与 `${SKILLFORGE_HOME}/data/skills/` 两个标准 Skill root,并让 `t_skill` 作为用户/业务 Skill 的控制面和索引表。所有 server 端写盘 + 落 DB 通过单一 `SkillStorageService` 收口。

**2026-04-30 spec 重写**(基于现状勘查 + 用户对齐):
- 确认 ClawHub Java 链路已 dead → 本期顺手删
- 路径方案选 SKILLFORGE_HOME env var,默认 Java 启动期找项目根作为 anchor(配置形式相对、解析结果稳定)
- SkillHub / GitHub 不加 server 端 install service,保持 user-facing skill 包(机制 B)
- SkillDraft approve / Evolution fork 纳入治理(它们也写 runtime root)
- grill-me 迁到 `system-skills/`(内容是通用 system skill)
- out-of-band 写盘只用 startup + 手动 Rescan(不加定时任务,P2 follow-up)

## 阅读顺序

1. [MRD](mrd.md) - 用户原始诉求、背景和约束。
2. [PRD](prd.md) - 产品需求、验收标准和非目标。
3. [技术方案](tech-design.md) - 目录、加载、DB reconcile、冲突规则和迁移方案。

## 当前状态

**done,2026-04-30 交付**。Full Pipeline 走完:Plan(Planner+Reviewer+Judge,1 轮 NEEDS_FIX 一次 fix)→ Phase 2 Dev(Backend Dev T0-T8+T10 + Frontend Dev T9 并行)→ Phase 3 Review(2 轮:r1 NEEDS_FIX 2 blocker → r1 fix → r2 双 PASS → Judge r2 PASS)→ Phase Final(主会话独立 mvn test + dashboard build)→ 归档。详见 [delivery-index.md 2026-04-30 行](../../../delivery-index.md)。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | - |
