# P1-D 技术方案

---
id: P1-D
status: design-draft
prd: ./prd.md
risk: Full
created: 2026-04-29
updated: 2026-04-29
---

## TL;DR

保留 P1-C 的 Skill Control Plane，但把 runtime skill 的物理 root 固定为 `skillforge-server/data/skills/`，并把所有新增入口收口到统一 storage service。启动时扫描 `system-skills/` 和 runtime root，再与 `t_skill` 做 reconcile。磁盘 artifact 是内容真相，`t_skill` 是 catalog/control-plane，负责 owner、enabled、source、created_at、usage、版本、风险、shadowed 等状态。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| 保留 `t_skill` | 多用户、启停、绑定、统计、版本、风险审计和 UI 稳定 ID 都需要 DB catalog | 删除表只扫目录，会丢失管理能力 |
| 磁盘 artifact 是内容真相 | 避免 DB 中的正文与 `SKILL.md` 双写不一致 | 将完整 `SKILL.md` 存 DB blob，复杂且不符合 package 模型 |
| runtime root 固定为 `skillforge-server/data/skills/` | 避免 `./data/skills` 随启动目录变化产生双目录 | 继续使用相对 `./data/skills`，会复现当前分裂问题 |
| system skill 继续使用 `system-skills/` | 保持代码随附 skill 的现有发布模式 | 将 system skill 也搬进 runtime root，会混淆代码资产和用户资产 |
| runtime 同名采用 A + D | 用户已确认：`created_at` 最新优先，且只允许一个 enabled | semver 最大优先，当前数据缺 semver 不可靠 |
| 启动扫描后 reconcile DB | 防止人工修改 `SKILL.md` 后 DB metadata 过期 | 只信 DB `skill_path`，会漏掉合法新增 artifact |

## 架构

### 目录边界

```text
system-skills/
  <name>/SKILL.md

skillforge-server/data/skills/
  upload/{ownerId}/{uuid}/SKILL.md
  extracted/{ownerId}/{uuid}/SKILL.md
  skillhub/{slug}/{version}/SKILL.md
  clawhub/{slug}/{version}/SKILL.md
  filesystem/{ownerId-or-local}/{uuid-or-name}/SKILL.md
```

`data/skills/` 与 `skillforge-server/skills/` 不再作为运行时扫描目录。现有内容通过迁移脚本或人工迁移进入 runtime root。

### 运行时对象

- `SkillDefinition`: 从 `SKILL.md` package 解析出的运行态定义。
- `SkillRegistry`: 只注册冲突裁决后的 winner。
- `t_skill`: catalog/control-plane，保存状态和索引。
- `SessionSkillView`: 每个会话的可用 skill 快照，继续作为 agent 授权边界。

### 加载与授权分层

加载进 registry 只表示系统知道该 skill 的有效 winner。Agent 能否使用 runtime skill，仍由 agent 绑定配置决定。system skill 默认可用，可被 `disabledSystemSkills` 关闭。

## 后端改动

### 配置

- 将 `skillforge.skills-dir` 固定解析为 `skillforge-server/data/skills/`。
- 避免裸 `./data/skills` 随工作目录变化。
- 保留 `skillforge.system-skills-dir: system-skills`。

### 新增 SkillStorageService

统一负责 runtime skill artifact 目标路径生成和写入事务外准备：

- `upload/{ownerId}/{uuid}`
- `extracted/{ownerId}/{uuid}`
- `skillhub/{slug}/{version}`
- `clawhub/{slug}/{version}`

改造调用方：

- `SkillService.uploadSkill`
- `SkillDraftService.approveDraft`
- SkillHub 安装入口
- ClawHub 安装入口或清理旧 Java ClawHub 安装链路
- Skill evolution fork 写盘逻辑

### 新增 SkillCatalogReconciler

职责：

- 扫描 root。
- 调用 `SkillPackageLoader.loadFromDirectory`。
- 计算 content hash。
- 与 `t_skill` 对齐。
- 输出 rescan report。

建议识别顺序：

1. 以 canonical `skill_path` 匹配既有 row。
2. path 未命中时，以 `(is_system, owner_id bucket, name, source)` 做兼容匹配。
3. 都未命中时新增 row。

### 启动加载

启动流程：

1. `SystemSkillLoader` 扫 `system-skills/`。
2. system row upsert，`is_system=true`。
3. runtime loader 扫 `skillforge-server/data/skills/`。
4. runtime row reconcile，`is_system=false`。
5. 冲突裁决。
6. registry 注册 winners。
7. 记录 missing、invalid、shadowed、disabled duplicate。

### 新增 Rescan API

建议新增：

```http
POST /api/skills/rescan
```

返回：

```json
{
  "created": 1,
  "updated": 2,
  "missing": 1,
  "invalid": 0,
  "shadowed": 3,
  "disabledDuplicates": 2
}
```

### 冲突裁决

- system 与 runtime 同名：
  - system 注册为 winner。
  - runtime 不注册。
  - runtime row 标记 `artifact_status=shadowed`，`shadowed_by=system:<name>`。
- runtime 同名：
  - 按 `created_at` desc，`id` desc 作为 tie-breaker。
  - 最新 row 为 winner。
  - winner 保持或设置 `enabled=true`。
  - 非 winner 设置 `enabled=false` 或 `artifact_status=shadowed`。
  - registry 只注册 winner。

## 前端改动

Skills 页面增加 catalog 治理字段：

- type: `system` / `runtime`
- source
- enabled
- artifactStatus
- skillPath
- shadowedBy
- lastScannedAt

交互：

- shadowed skill 展示 warning。
- missing/invalid skill 展示不可用状态。
- system skill 禁止删除。
- 增加 Rescan 操作，展示扫描报告。

## 数据模型 / Migration

保留现有 `t_skill`。建议新增字段：

```sql
ALTER TABLE t_skill ADD COLUMN content_hash VARCHAR(128);
ALTER TABLE t_skill ADD COLUMN last_scanned_at TIMESTAMP;
ALTER TABLE t_skill ADD COLUMN artifact_status VARCHAR(32) NOT NULL DEFAULT 'active';
ALTER TABLE t_skill ADD COLUMN shadowed_by VARCHAR(255);
```

`artifact_status` 建议值：

- `active`
- `missing`
- `invalid`
- `shadowed`

迁移注意：

- 已有 `skill_path` 指向 `data/skills/` 的 row 需要迁移到 `skillforge-server/data/skills/`，或在首次 rescan 中标记并提示。
- Flyway 已应用 migration 不回改，新增 `V33__skill_root_catalog_convergence.sql`。

## 错误处理 / 安全

- 写盘成功但校验失败：删除新建目录，不写 DB。
- 写 DB 失败：删除新建目录，不注册 registry。
- DB commit 成功但 registry 注册失败：保留 DB 与磁盘，记录 error，下一次启动或 rescan 恢复。
- 缺失 artifact：标 missing，不注册。
- 非法 package：标 invalid，不注册。
- path 必须位于允许 root 下，防止 `skill_path` 指向任意系统路径。
- 日志不得输出完整用户私密 `SKILL.md` 内容，只输出 path、name、error summary。

## 实施计划

- [ ] 增加 requirement review 后的实现计划。
- [ ] 新增 `SkillStorageService`，统一 runtime artifact 路径。
- [ ] 新增 `SkillCatalogReconciler` 和 rescan report DTO。
- [ ] 改造启动 loader，扫描 system root 与 runtime root。
- [ ] 改造上传、draft approve、marketplace install、evolution fork 的写入路径。
- [ ] 增加 migration 字段。
- [ ] 增加 conflict/shadowed 裁决。
- [ ] 更新 Skills API 和前端列表。
- [ ] 编写本地迁移脚本或手册。
- [ ] 清理或明确停用旧 ClawHub Java Tool 链路。

## 测试计划

- [ ] `SkillStorageServiceTest`: 不同 source 生成稳定 runtime root 路径。
- [ ] `SkillCatalogReconcilerTest`: DB 缺失时 insert，hash 变化时 update，磁盘缺失时 missing，非法包 invalid。
- [ ] `SkillConflictResolutionTest`: system 同名优先，runtime 同名最新优先，非 winner disabled/shadowed。
- [ ] `SystemSkillLoaderTest`: system skill upsert 到 `t_skill`。
- [ ] `UserSkillLoaderTest`: 只扫描 runtime root，不扫描废弃目录。
- [ ] `AgentLoopEngineSkillViewFailSecureTest`: 未绑定 runtime skill 仍 not allowed。
- [ ] `SkillControllerTest`: rescan 返回统计，Skills API 返回 status/source/path/shadowed。
- [ ] 前端测试：Skills 页面展示 shadowed/missing/invalid 状态，Rescan action 触发并展示结果。
- [ ] 手工验证：启动服务后 logs 中不再出现 `data/skills` 与 `skillforge-server/data/skills` 双写，`skillforge-server/skills` 不被扫描。

## 风险

- 旧数据迁移误伤 `skill_path`。
  - 缓解：先输出 dry-run report，再执行迁移。
- agent 绑定仍按 name，runtime 同名会造成歧义。
  - 缓解：本期强制同名 runtime 只有一个 enabled；后续单独评估 skill id binding。
- 自动导入 DB 缺失 artifact 可能把临时文件变成可管理 skill。
  - 缓解：未决问题中要求确认默认 enabled/disabled 策略；实现可先导入为 disabled。
- ClawHub 旧 Java Tool 与 system skill 并存造成双路径。
  - 缓解：本期明确删除、停用或隐藏旧 Java Tool 链路。

## 评审记录

待 Full Pipeline plan/review 阶段补充。
