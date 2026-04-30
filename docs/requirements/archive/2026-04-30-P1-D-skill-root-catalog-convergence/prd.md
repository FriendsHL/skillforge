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

SkillForge 需要把 Skill 的物理 artifact、DB catalog 和运行时加载规则收口。完成后，系统只从 `system-skills/` 和 `${SKILLFORGE_HOME}/data/skills/` 加载 Skill；所有新增 runtime skill 必须写入统一 runtime root 并同步写入 `t_skill`；`t_skill` 保留为多用户、启停、绑定、统计、版本和审计的控制面。所有写盘 + 落 DB 通过单一 `SkillStorageService` 收口。

## 目标

- 固定 Skill 的两个标准 root：
  - **system root**: `system-skills/`
  - **runtime root**: `${SKILLFORGE_HOME}/data/skills/`(`SKILLFORGE_HOME` 环境变量;未设置时启动期 Java 代码自动向上找含 `pom.xml` 的项目根并将 anchor 设为 `<projectRoot>/skillforge-server`,使配置形式仍是相对路径但解析结果稳定不受启动 cwd 影响)
- 统一 runtime skill 的新增路径,通过 `SkillStorageService` 收口以下入口:
  - 管理端上传(`SkillService.uploadSkill`)
  - Agent 自生成(`SkillCreatorService`)
  - SkillDraft approve(`SkillDraftService.approveDraft`)
  - Evolution fork(`SkillEvolutionService`)
- **机制 B 入口**(SkillHub / GitHub / ClawHub 这些 user-facing skill 包):本期不在 server 端为它们新建 install service。它们通过 LLM 调用上述统一写盘 tool(SkillCreator / FileWrite + Storage)实现安装。
- 保留并强化 `t_skill` 的 catalog/control-plane 职责。
- 启动加载时校准磁盘 artifact 与 `t_skill`。
- 处理同名冲突,确保 registry 中每个 name 只有一个有效 winner。
- 在 UI/API 中暴露 skill 的来源、状态、路径和冲突提示。
- **删除 dead 的 ClawHub Java 链路**(`ClawHubInstallService` / `ClawHubTool` / `ClawHubClient` / `ClawHubModels` / `ClawHubProperties` / `ClawHubSafetyChecker`),它们已在 SkillForgeConfig 中不被注册,统一由 `system-skills/clawhub/` user-facing skill 取代。
- 处置历史散落 artifact:`skillforge-server/skills/grill-me/` 迁到 `system-skills/grill-me/`(从内容看是通用 system skill);两个 `data/skills/` 路径(项目根 + skillforge-server 子目录)的内容合并到统一 runtime root。

## 非目标

- 不删除 `t_skill`。
- 不把 `SKILL.md` 正文迁入 DB blob 存储。
- 不改变 SkillDefinition 调用语义,仍由 `SKILL.md` package 提供 prompt content。
- 不在本期强制把 agent skill binding 从 name 迁移到 skill id。
- 不重新设计 P1 A/B、Evolution 或 Draft 的产品流程。
- **不加定时扫描任务 / FileSystem WatchService**:绕过 server 直接往 runtime root 扔 skill 是 dev/运维行为,启动扫描 + 手动 Rescan API 已覆盖;真有 hands-off 需求作为 P2 follow-up。
- **不为 SkillHub / GitHub 新建 server 端 install service**(机制 A);保持机制 B(user-facing skill 包通过通用写盘工具安装)。

## 用户流程

- 作为管理员，我希望在 Skills 页看到所有系统和用户/业务 skill 的统一列表，并知道它们来自哪里、是否启用、是否被遮蔽。
- 作为管理员，我希望上传或安装 skill 后，它一定出现在统一 runtime root，并在 `t_skill` 中有可管理记录。
- 作为管理员，我希望系统启动后自动发现并校准 skill artifact 与 DB，不会因为历史散落路径导致运行态不一致。
- 作为 agent 配置者，我希望 system skill 和 runtime skill 的绑定逻辑清楚：system 默认启用可关闭，runtime 必须显式绑定。
- 作为维护者，我希望同名冲突有确定规则，并能在日志或 UI 中看到提示。

## 功能需求

### 路径与配置
- 启动期解析 `${SKILLFORGE_HOME}` 环境变量;未设置时 Java 代码向上查找最近的 `pom.xml` 作为项目根,将 anchor 设为 `<projectRoot>/skillforge-server` 并 log 一行 INFO。runtime root = `${SKILLFORGE_HOME}/data/skills`,system root = `${SKILLFORGE_HOME}/../system-skills`(或独立 `${SKILLFORGE_SYSTEM_SKILLS_DIR}` 配置)。
- 解析后路径必须存在或可创建;不可创建则启动 fail-fast。
- 启动期把解析结果(绝对路径)log 出来便于运维排查。

### 启动加载与 reconcile
- 系统启动时扫描 `system-skills/`,注册 system skill,并 upsert `t_skill.is_system=true`。
- 系统启动时扫描 runtime root,解析 runtime skill,并与 `t_skill` reconcile。
- reconcile 输出 RescanReport(created/updated/missing/invalid/shadowed/disabledDuplicates),写到启动 log。

### 写入路径(SkillStorageService 收口)
- 所有 server-side runtime skill 新增入口必须经 `SkillStorageService` 写入 runtime root。
- `SkillStorageService` 改造调用方:`SkillService.uploadSkill` / `SkillCreatorService` / `SkillDraftService.approveDraft` / `SkillEvolutionService` fork 写盘逻辑(共 4 个 server 入口)。
- 所有 runtime skill 写盘后必须同步写入 `t_skill` row。
- 新增 skill 流程必须**先写盘并校验,再写 DB,DB commit 后注册 registry**。
- DB 写失败时必须清理本次新建 artifact,避免新增 orphan。
- registry 注册失败时不得破坏已提交的 DB 与磁盘,后续启动或 rescan 应可恢复。
- **机制 B 安装**(SkillHub / GitHub / ClawHub user-facing skill 包):它们通过 LLM 调用上述写盘工具(`SkillCreator` 等)间接落地,本期不为它们新建独立 server 入口。

### 冲突裁决
- 同名 system/runtime 冲突时,system skill 是 winner,runtime skill 标记为 shadowed,`shadowed_by="system:<name>"`。
- 同名 runtime 冲突时,按 `created_at desc, id desc` 最新确定 winner,并确保只有 winner `enabled=true`。

### 治理与可观测
- Skills API 需要返回 status/source/path/shadowed 等治理字段,供 UI 展示。
- 提供手动 rescan API(`POST /api/skills/rescan`),用于无需重启地同步磁盘与 DB 状态;返回 RescanReport DTO。

### 数据 / 代码迁移(本期一次性)
- 把 `skillforge-server/skills/grill-me/` 迁到 `system-skills/grill-me/`(内容是通用 system skill,且当前不被任何 loader 扫描)。
- 把项目根 `data/skills/` 内容合并到统一 runtime root,处理双份(`1` 在两处都有的情况)。
- 删除 dead 的 ClawHub Java 链路(`com.skillforge.server.clawhub` 整个 package + application.yml 中 ClawHub 配置)。

## 验收标准

### 路径解析
- [ ] 不设 `SKILLFORGE_HOME` 时,启动期 Java 自动找到项目根并把 runtime root anchor 设为 `<projectRoot>/skillforge-server`,启动 log 显式打印解析后的绝对路径。
- [ ] 设了 `SKILLFORGE_HOME=/var/skillforge` 时,runtime root = `/var/skillforge/data/skills`(env var 优先于 auto-detect)。
- [ ] 不管启动 cwd 是项目根、`skillforge-server/`、还是其他位置,runtime root 解析结果都一致。
- [ ] 路径解析失败(`pom.xml` 找不到 + 没设 env var)时启动 fail-fast,不静默 fallback。

### 目录治理
- [ ] `data/skills/`(项目根)和 `skillforge-server/skills/` 不再被运行时扫描。
- [ ] `skillforge-server/skills/grill-me/` 已迁到 `system-skills/grill-me/`,启动后被 SystemSkillLoader 加载、t_skill 有 row、is_system=true。
- [ ] 双份 `data/skills` 内容合并完成,无重复 row。

### 写入入口
- [ ] 上传、SkillCreator 自生成、SkillDraft approve、Evolution fork 4 个 server 入口的新产物都落在 runtime root 下,且统一经 `SkillStorageService` 写入。
- [ ] 通过 `system-skills/skillhub/` / `system-skills/github/` / `system-skills/clawhub/` user-facing skill 安装的 skill 也落在 runtime root 下(它们通过调用统一写盘工具间接落地)。
- [ ] 新增 runtime skill 后,磁盘有 artifact,`t_skill` 有记录,重启后能恢复注册。

### Reconcile
- [ ] 修改磁盘 `SKILL.md` 后,启动或 rescan 能更新 DB 中可同步 metadata。
- [ ] 删除磁盘 artifact 后,启动或 rescan 标记 missing,并且不注册该 skill。
- [ ] 启动 reconcile 输出 RescanReport,内容写入 startup log。
- [ ] `POST /api/skills/rescan` 返回 RescanReport DTO,UI 触发后显示扫描结果。

### 冲突裁决
- [ ] system 同名 runtime skill 不成为 registry winner,并在 API/UI 中提示 shadowed。
- [ ] runtime 同名多 enabled 时,最新 `created_at` 保持 enabled,其余被禁用或标记 shadowed。

### Agent 绑定
- [ ] Agent 未绑定 runtime skill 时不能调用该 skill。
- [ ] system skill 默认可用,仍支持按 agent disable。

### ClawHub Java 链路清理
- [ ] `com.skillforge.server.clawhub` package 整个删除,无其他模块 import 它。
- [ ] application.yml 移除 `clawhub:` 配置块。
- [ ] `system-skills/clawhub/` user-facing skill 仍可用(通过通用写盘工具),取代 Java install 链路。
- [ ] `mvn compile` 通过,无 dead reference。

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
