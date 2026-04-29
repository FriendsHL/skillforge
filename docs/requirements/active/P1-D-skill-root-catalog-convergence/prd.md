# P1-D PRD

---
id: P1-D
status: prd-ready
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-29
updated: 2026-04-29
---

## 摘要

SkillForge 需要把 Skill 的物理 artifact、DB catalog 和运行时加载规则收口。完成后，系统只从 `system-skills/` 和 `skillforge-server/data/skills/` 加载 Skill；所有新增 runtime skill 必须写入统一 runtime root 并同步写入 `t_skill`；`t_skill` 保留为多用户、启停、绑定、统计、版本和审计的控制面。

## 目标

- 固定 Skill 的两个标准 root：
  - system root: `system-skills/`
  - runtime root: `skillforge-server/data/skills/`
- 统一 runtime skill 的新增路径，覆盖上传、SkillCreator、SkillHub、ClawHub。
- 保留并强化 `t_skill` 的 catalog/control-plane 职责。
- 启动加载时校准磁盘 artifact 与 `t_skill`。
- 处理同名冲突，确保 registry 中每个 name 只有一个有效 winner。
- 在 UI/API 中暴露 skill 的来源、状态、路径和冲突提示。

## 非目标

- 不删除 `t_skill`。
- 不把 `SKILL.md` 正文迁入 DB blob 存储。
- 不改变 SkillDefinition 调用语义，仍由 `SKILL.md` package 提供 prompt content。
- 不在本期强制把 agent skill binding 从 name 迁移到 skill id。
- 不重新设计 P1 A/B、Evolution 或 Draft 的产品流程。

## 用户流程

- 作为管理员，我希望在 Skills 页看到所有系统和用户/业务 skill 的统一列表，并知道它们来自哪里、是否启用、是否被遮蔽。
- 作为管理员，我希望上传或安装 skill 后，它一定出现在统一 runtime root，并在 `t_skill` 中有可管理记录。
- 作为管理员，我希望系统启动后自动发现并校准 skill artifact 与 DB，不会因为历史散落路径导致运行态不一致。
- 作为 agent 配置者，我希望 system skill 和 runtime skill 的绑定逻辑清楚：system 默认启用可关闭，runtime 必须显式绑定。
- 作为维护者，我希望同名冲突有确定规则，并能在日志或 UI 中看到提示。

## 功能需求

- 系统启动时扫描 `system-skills/`，注册 system skill，并 upsert `t_skill.is_system=true`。
- 系统启动时扫描 `skillforge-server/data/skills/`，解析 runtime skill，并与 `t_skill` reconcile。
- 所有 runtime skill 新增入口必须写入 `skillforge-server/data/skills/`。
- 所有 runtime skill 新增入口必须写入 `t_skill`。
- 新增 skill 流程必须先写盘并校验，再写 DB，DB commit 后注册 registry。
- DB 写失败时必须清理本次新建 artifact，避免新增 orphan。
- registry 注册失败时不得破坏已提交的 DB 与磁盘，后续启动或 rescan 应可恢复。
- 同名 system/runtime 冲突时，system skill 是 winner，runtime skill 标记为 shadowed。
- 同名 runtime 冲突时，按 `created_at` 最新确定 winner，并确保只有 winner `enabled=true`。
- Skills API 需要返回 status/source/path/shadowed 等治理字段，供 UI 展示。
- 提供手动 rescan 能力，用于无需重启地同步磁盘与 DB 状态。

## 验收标准

- [ ] `data/skills/` 和 `skillforge-server/skills/` 不再被运行时扫描。
- [ ] `skillforge.skills-dir` 解析为稳定的 `skillforge-server/data/skills/` 绝对路径，不受启动工作目录影响。
- [ ] 上传、SkillDraft approve、SkillHub、ClawHub 的新产物都落在 runtime root 下。
- [ ] 新增 runtime skill 后，磁盘有 artifact，`t_skill` 有记录，重启后能恢复注册。
- [ ] 修改磁盘 `SKILL.md` 后，启动或 rescan 能更新 DB 中可同步 metadata。
- [ ] 删除磁盘 artifact 后，启动或 rescan 标记 missing，并且不注册该 skill。
- [ ] system 同名 runtime skill 不成为 registry winner，并在 API/UI 中提示 shadowed。
- [ ] runtime 同名多 enabled 时，最新 `created_at` 保持 enabled，其余被禁用或标记 shadowed。
- [ ] Agent 未绑定 runtime skill 时不能调用该 skill。
- [ ] system skill 默认可用，仍支持按 agent disable。

## 依赖

- P1-C Skill Control Plane 已交付：
  - `t_skill.is_system`
  - `SessionSkillView`
  - `SystemSkillLoader`
  - `UserSkillLoader`
  - Skill Draft approve 写盘校验链路
- 现有 Flyway migration 管理 `t_skill` 和 `t_agent`。

## 验证预期

- 后端：新增 loader/reconcile/storage service 单测和 controller/service 集成测试。
- 前端：Skills 页面状态字段、shadowed 提示、rescan 结果展示测试。
- 浏览器：验证 Skills 页面能展示 system/runtime/source/status/path，且无明显布局溢出。
- 数据库：新增 migration 可重复应用，旧数据 `skill_path` 能迁移或标记。
