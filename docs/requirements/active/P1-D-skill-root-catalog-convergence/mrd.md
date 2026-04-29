# P1-D MRD

---
id: P1-D
status: mrd
source: user
created: 2026-04-29
updated: 2026-04-29
---

## 用户诉求

用户希望在 P1-C Skill Control Plane 已完成的基础上，继续把 Skill 的存放和加载收口：

- system skill 保持在现有 `system-skills/` 目录。
- 用户或业务 Skill，包括管理端上传、SkillCreator 创建、SkillHub 安装、ClawHub 安装，都统一放在 `skillforge-server/data/skills/`。
- 运行时加载系统中的两个 Skill 文件夹：`system-skills/` 和 `skillforge-server/data/skills/`。
- `t_skill` 表不能删除，因为它支撑多用户、启停、归属、统计、绑定、版本和审计。
- 每次新增 runtime skill 后都必须写磁盘 artifact，也必须写 `t_skill`。
- 如果 skill name 重复，system skill 优先；runtime 同名 skill 采用“最新优先 + 只允许一个 enabled”的规则。
- 用户希望相关需求被正式写入文档，便于后续实施。

## 背景

当前仓库存在多个 Skill 物理位置：

- `system-skills/`
- `data/skills/`
- `skillforge-server/data/skills/`
- `skillforge-server/skills/`

其中 `skillforge-server/skills/grill-me/SKILL.md` 看起来是生成过程或人工操作散落出的 artifact，但当前加载逻辑不会默认扫描该目录。`data/skills/` 和 `skillforge-server/data/skills/` 又会因为服务启动目录不同而分裂，导致同类 runtime skill 落到不同位置。

P1-C 已经完成 Skill Control Plane 的大部分核心能力，包括 `is_system`、`SessionSkillView`、`SystemSkillLoader`、`UserSkillLoader` 和 Skill Draft 生成链路。但 runtime skill 的物理 root 仍未完全固定，写入路径也分散在多个 service 中，后续需要统一。

## 期望结果

- 只有两个标准 Skill root。
- 用户/业务/runtime skill 统一归入 `skillforge-server/data/skills/`。
- `t_skill` 明确作为 catalog/control-plane，而不是内容真相。
- `SKILL.md`、references、scripts 以磁盘 artifact 为内容真相。
- 新增 skill 的写入流程统一：写盘、校验、写 DB、commit 后注册。
- 启动或手动 rescan 能校准磁盘与 DB 的不一致。
- UI 能看清 skill 的 system/runtime、source、enabled、path、status 和 shadowed 状态。

## 约束

- 不删除 `t_skill`。
- 不改变 system skill 的现有目录。
- runtime skill 只认 `skillforge-server/data/skills/`。
- `data/skills/` 与 `skillforge-server/skills/` 后续不再作为运行时扫描目录。
- 同名规则采用用户确认的 A + D：
  - runtime 同名按 `created_at` 最新优先。
  - runtime 同名只允许一个 `enabled=true`。
- system skill 同名永远优先于 runtime skill。
- Agent 最终能使用哪些 skill，仍由 agent 绑定配置和 `SessionSkillView` 决定。

## 未决问题

- [ ] `skillforge-server/data/skills/` 下扫描到合法 `SKILL.md` 但 DB 没记录时，默认自动导入为 enabled，还是导入为 disabled 并提示用户确认。
- [ ] 现有 `skillforge-server/skills/grill-me` 是否迁入 runtime root 并补 `t_skill`，还是作为临时产物删除。
- [ ] runtime skill 的 agent 绑定是否继续使用 name，还是另起后续需求迁移到 `skill_id` 绑定。
