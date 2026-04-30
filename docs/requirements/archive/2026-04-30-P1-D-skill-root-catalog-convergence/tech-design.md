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

保留 P1-C 的 Skill Control Plane,把 runtime skill 物理 root 锚定到 `${SKILLFORGE_HOME}/data/skills/`(env var 优先,未设置时启动期 Java 代码自动找项目根作为 anchor)。所有 server 端写入入口收口到统一 `SkillStorageService`。启动时扫描 `system-skills/` 和 runtime root,再与 `t_skill` 做 reconcile。磁盘 artifact 是内容真相,`t_skill` 是 catalog/control-plane,负责 owner、enabled、source、created_at、usage、版本、风险、shadowed 等状态。本期顺手删 dead 的 ClawHub Java 链路。

## 关键决策

| 决策 | 理由 | 替代方案 |
| --- | --- | --- |
| 保留 `t_skill` | 多用户、启停、绑定、统计、版本、风险审计和 UI 稳定 ID 都需要 DB catalog | 删除表只扫目录,会丢失管理能力 |
| 磁盘 artifact 是内容真相 | 避免 DB 中的正文与 `SKILL.md` 双写不一致 | 将完整 `SKILL.md` 存 DB blob,复杂且不符合 package 模型 |
| **runtime root 用 `${SKILLFORGE_HOME}/data/skills/`(env 优先)+ Java 启动期 fallback 找项目根作为 anchor** | 用户要求"配置形式相对、解析结果稳定";env var 部署友好,fallback 让 dev 零配置;不依赖启动 cwd | (a) 写死绝对路径(部署不可移植);(b) 纯相对 `./data/skills`(当前 bug 根因);(c) `user.home`(桌面感不符合 server 部署) |
| system skill 继续使用 `system-skills/` | 保持代码随附 skill 的现有发布模式 | 将 system skill 也搬进 runtime root,会混淆代码资产和用户资产 |
| runtime 同名采用 created_at desc + 只允许一个 enabled | 用户已确认:`created_at` 最新优先 | semver 最大优先,当前数据缺 semver 不可靠 |
| 启动扫描后 reconcile DB | 防止人工修改 `SKILL.md` 后 DB metadata 过期 | 只信 DB `skill_path`,会漏掉合法新增 artifact |
| **`SkillHub` / `GitHub` 不加 server 端 install service(机制 B)** | 这些是 user-facing skill 包,LLM 通过通用写盘工具自描述安装,server 不需要专属逻辑 | 机制 A:为它们各自加 Java install service(增加 surface,违反"上层多样、底层统一") |
| **本期顺手删 ClawHub Java 链路**(`com.skillforge.server.clawhub` 整 package) | 勘查发现 `ClawHubTool` 已在 SkillForgeConfig:230 注释停止注册,`ClawHubInstallService` 是无人 inject 的 dead bean,删除影响接近 0;system-skills/clawhub/ user-facing skill 已接管 | (a) 仅 deprecated 留单独 task(留 dead code 在 codebase);(b) 不动(治理不彻底) |
| **`grill-me` 迁到 `system-skills/grill-me/`** | 内容是通用 system skill(让 LLM 质询用户 plan/design),不依赖具体用户/业务,适合 system 性质 | 留 runtime 需要 owner/enabled 决策且语义不符 |
| **out-of-band 写盘只用 startup + 手动 Rescan,不加定时任务/WatchService** | 频率低(dev/运维行为),手动 Rescan 已覆盖,定时任务有 IO 噪声且需要 hash 跳过逻辑 | (a) 加 @Scheduled 定时扫(P2 follow-up);(b) WatchService(跨平台坑) |

## 架构

### 目录边界

```text
<projectRoot>/system-skills/
  <name>/SKILL.md             # 代码自带 system skill(browser/clawhub/github/skill-creator/skillhub/grill-me)

${SKILLFORGE_HOME}/data/skills/   # SKILLFORGE_HOME 默认 = <projectRoot>/skillforge-server (Java 启动期解析)
  upload/{ownerId}/{uuid}/SKILL.md       # 管理端上传
  skill-creator/{ownerId}/{uuid}/SKILL.md  # SkillCreatorService 自生成
  draft-approve/{ownerId}/{uuid}/SKILL.md  # SkillDraftService approve(P1 self-improve)
  evolution-fork/{ownerId}/{parentSkillId}/{uuid}/SKILL.md  # SkillEvolutionService fork
  skillhub/{slug}/{version}/SKILL.md     # user-facing skillhub skill 通过 SkillCreator 落地
  clawhub/{slug}/{version}/SKILL.md      # user-facing clawhub skill 通过 SkillCreator 落地
  github/{owner}-{repo}/{ref}/SKILL.md   # user-facing github skill 通过 SkillCreator 落地
  filesystem/{ownerId-or-local}/{uuid-or-name}/SKILL.md  # 直接文件系统导入
```

**不再扫描的目录**(本期处置):
- `data/skills/`(项目根,旧相对路径产物) — 内容合并到 runtime root
- `skillforge-server/skills/grill-me/` — 迁到 `system-skills/grill-me/`(内容是通用 system skill)

### 运行时对象

- `SkillDefinition`: 从 `SKILL.md` package 解析出的运行态定义。
- `SkillRegistry`: 只注册冲突裁决后的 winner。
- `t_skill`: catalog/control-plane，保存状态和索引。
- `SessionSkillView`: 每个会话的可用 skill 快照，继续作为 agent 授权边界。

### 加载与授权分层

加载进 registry 只表示系统知道该 skill 的有效 winner。Agent 能否使用 runtime skill，仍由 agent 绑定配置决定。system skill 默认可用，可被 `disabledSystemSkills` 关闭。

## 后端改动

### 配置

新增 `SkillForgeHomeResolver`(server 启动期 helper):

```java
@Component
public class SkillForgeHomeResolver {
    public Path resolve() {
        // 1. SKILLFORGE_HOME env var 优先(部署期约定)
        String env = System.getenv("SKILLFORGE_HOME");
        if (env != null && !env.isBlank()) return Path.of(env).toAbsolutePath().normalize();
        // 2. 启动期向上找含 pom.xml 的项目根,fallback 到 <projectRoot>/skillforge-server
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null) {
            if (Files.exists(cur.resolve("pom.xml")) && Files.isDirectory(cur.resolve("skillforge-server"))) {
                return cur.resolve("skillforge-server");
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("Cannot resolve SKILLFORGE_HOME — set env var or run from project root");
    }
}
```

application.yml 改为:
```yaml
skillforge:
  home: ${SKILLFORGE_HOME:#{T(com.skillforge.server.skill.SkillForgeHomeResolver).staticResolve()}}
  skills-dir: ${skillforge.home}/data/skills
  system-skills-dir: ${skillforge.home}/../system-skills  # 项目根下 system-skills/
```

启动期把 `${skillforge.home}` 和 `${skillforge.skills-dir}` 解析后绝对路径 log 出来便于排查。

### 新增 SkillStorageService

统一负责 runtime skill artifact 目标路径生成和写入事务外准备:

```java
public Path allocate(SkillSource source, AllocationContext ctx) {
    // 根据 source 类型生成路径
    return switch (source) {
        case UPLOAD -> root.resolve("upload").resolve(ctx.ownerId).resolve(ctx.uuid);
        case SKILL_CREATOR -> root.resolve("skill-creator").resolve(ctx.ownerId).resolve(ctx.uuid);
        case DRAFT_APPROVE -> root.resolve("draft-approve").resolve(ctx.ownerId).resolve(ctx.uuid);
        case EVOLUTION_FORK -> root.resolve("evolution-fork").resolve(ctx.ownerId)
                                   .resolve(ctx.parentSkillId).resolve(ctx.uuid);
        case SKILLHUB -> root.resolve("skillhub").resolve(ctx.slug).resolve(ctx.version);
        case CLAWHUB -> root.resolve("clawhub").resolve(ctx.slug).resolve(ctx.version);
        case GITHUB -> root.resolve("github").resolve(ctx.repoSlug).resolve(ctx.ref);
        case FILESYSTEM -> root.resolve("filesystem").resolve(ctx.ownerId).resolve(ctx.uuid);
    };
}
```

改造调用方(共 4 个 server-side 入口):
- `SkillService.uploadSkill` → source=UPLOAD
- `SkillCreatorService` → source=SKILL_CREATOR
- `SkillDraftService.approveDraft` → source=DRAFT_APPROVE
- `SkillEvolutionService` fork 逻辑 → source=EVOLUTION_FORK

不直接改造的(机制 B,通过上面 4 个入口间接落地):
- `system-skills/skillhub/SKILL.md` 自描述,LLM 调 `SkillCreator` tool 写盘 → source=SKILLHUB(SkillCreator 内部根据 caller hint 选 source)
- `system-skills/github/SKILL.md` 同上 → source=GITHUB
- `system-skills/clawhub/SKILL.md` 同上 → source=CLAWHUB

ClawHub Java 链路本期顺手删除(见下面"代码清理"章节)。

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
3. runtime loader 扫 `${SKILLFORGE_HOME}/data/skills/`(解析后的绝对路径)。
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

保留现有 `t_skill`。建议新增字段(用 `Instant` 类型,符合 java.md footgun #2):

```sql
-- V33__skill_root_catalog_convergence.sql
ALTER TABLE t_skill ADD COLUMN content_hash VARCHAR(128);
ALTER TABLE t_skill ADD COLUMN last_scanned_at TIMESTAMP;  -- JPA 端映射为 Instant
ALTER TABLE t_skill ADD COLUMN artifact_status VARCHAR(32) NOT NULL DEFAULT 'active';
ALTER TABLE t_skill ADD COLUMN shadowed_by VARCHAR(255);
```

`artifact_status` 取值:`active` / `missing` / `invalid` / `shadowed`

迁移策略:
- 已有 `skill_path` 指向旧路径(`./data/skills/...`、`data/skills/...`、`skillforge-server/data/skills/...`)的 row,首次 reconcile 时按以下规则处理:
  - 路径文件存在 → 计算其相对于 runtime root 的相对位置,如不在 root 下,**移动文件 + 更新 skill_path**(由 reconciler 输出 dry-run report,确认后实际 move)
  - 路径文件不存在 → `artifact_status=missing`
- Flyway 已应用 migration 不回改,V33 是下一个空位(V32 已用)。

### 一次性数据 / 文件迁移(本期收尾)

按以下顺序执行(可手动或写迁移脚本):

1. **grill-me 迁系统**:
   ```bash
   git mv skillforge-server/skills/grill-me system-skills/grill-me
   ```
   启动后 SystemSkillLoader 会发现并 upsert `t_skill.is_system=true`。

2. **双 data/skills 合并**:
   - 列出项目根 `data/skills/` 内容(勘查发现:`1`, `clawhub`)
   - 列出 `skillforge-server/data/skills/` 内容(勘查发现:`1`)
   - 同名(`1`)按内容比对:相同 → 删项目根的副本;不同 → 取 created_at 最新作为 winner,另一个移到 `archive/{date}/` 备份(或直接删,看用户决定)
   - 项目根 `data/skills/clawhub/` 移到 `${SKILLFORGE_HOME}/data/skills/filesystem/legacy/clawhub/`(或类似归类)

3. **ClawHub Java 链路删除**:见下面"代码清理"章节。

### 代码清理:删除 ClawHub Java 链路

勘查事实(2026-04-30):
- `SkillForgeConfig.java:230` 注释 "ClawHubTool 已迁移为 system-skills/clawhub/ 文件化 Skill,不再需要 Java bean 注册" — `ClawHubTool` 已不在 SkillRegistry
- `ClawHubInstallService` 是 `@Service` Spring bean,但 grep 不到任何 inject 它的地方 — dead bean
- 没有 controller endpoint 引用 ClawHub Java
- `ClawHubProperties.java:15` 注释"ClawHubTool 仍然注册"是 stale comment

删除清单:
- `skillforge-server/src/main/java/com/skillforge/server/clawhub/` 整个 package(6 个文件)
- `skillforge-server/src/main/java/com/skillforge/server/tool/ClawHubTool.java`
- `application.yml` 里 ClawHub 配置块
- 任何引用上述类的 import / `@ConditionalOnProperty("clawhub.enabled")` 等

验证:`mvn clean compile` 通过即视为安全。

## 错误处理 / 安全

- 写盘成功但校验失败：删除新建目录，不写 DB。
- 写 DB 失败：删除新建目录，不注册 registry。
- DB commit 成功但 registry 注册失败：保留 DB 与磁盘，记录 error，下一次启动或 rescan 恢复。
- 缺失 artifact：标 missing，不注册。
- 非法 package：标 invalid，不注册。
- path 必须位于允许 root 下，防止 `skill_path` 指向任意系统路径。
- 日志不得输出完整用户私密 `SKILL.md` 内容，只输出 path、name、error summary。

## 实施计划

按 Full Pipeline 流程,Plan 阶段进一步细化拆分。当前粗拆:

- [ ] 新增 `SkillForgeHomeResolver`,改造 `application.yml`、`SkillForgeConfig` 注入路径
- [ ] 新增 `SkillStorageService`,统一 runtime artifact 路径
- [ ] 新增 `SkillCatalogReconciler` 和 `RescanReport` DTO
- [ ] 改造启动 loader(`SystemSkillLoader` / `UserSkillLoader`),扫描 system root 与 runtime root
- [ ] 改造 4 个 server 入口写入路径:`SkillService.uploadSkill` / `SkillCreatorService` / `SkillDraftService.approveDraft` / `SkillEvolutionService` fork
- [ ] V33 migration 增加 4 字段
- [ ] 实现 conflict/shadowed 裁决(system vs runtime / runtime vs runtime)
- [ ] 新增 `POST /api/skills/rescan` API
- [ ] 更新 `SkillController` 暴露 catalog 治理字段
- [ ] 前端 Skills 页面加 governance 字段 + Rescan 按钮 + shadowed/missing/invalid 状态展示
- [ ] **一次性迁移**:grill-me 迁 system / 双 data/skills 合并(可写迁移脚本或手册)
- [ ] **代码清理**:删除 `com.skillforge.server.clawhub` package + ClawHubTool + application.yml ClawHub 配置
- [ ] 启动 fail-fast 校验:解析后 path 必须存在或可创建,否则 startup 报错

## 测试计划

- [ ] `SkillForgeHomeResolverTest`: env var 优先;未设时 fallback 找项目根;两者都没 → 报错。
- [ ] `SkillStorageServiceTest`: 8 种 SkillSource 各自生成稳定路径;路径都在 runtime root 之下;owner/uuid/slug/version 拼接正确。
- [ ] `SkillCatalogReconcilerTest`: DB 缺失时 insert,hash 变化时 update,磁盘缺失时 missing,非法包 invalid;输出 RescanReport 字段正确。
- [ ] `SkillConflictResolutionTest`: system 同名优先,runtime 同名最新优先,非 winner disabled/shadowed,shadowed_by 字段正确。
- [ ] `SystemSkillLoaderTest`: system skill upsert 到 `t_skill`,is_system=true。
- [ ] `UserSkillLoaderTest`: 只扫描 runtime root,不扫描废弃目录(`data/skills/` 项目根、`skillforge-server/skills/`)。
- [ ] `AgentLoopEngineSkillViewFailSecureTest`: 未绑定 runtime skill 仍 not allowed。
- [ ] `SkillControllerTest`: `POST /api/skills/rescan` 返回 RescanReport;Skills API 返回 status/source/path/shadowed。
- [ ] 前端测试: Skills 页面展示 shadowed/missing/invalid 状态,Rescan action 触发并展示结果。
- [ ] **回归**:确认 ClawHub Java 链路删除不破坏任何引用 — `mvn clean compile` 通过 + 启动 SkillRegistry 中无 ClawHubTool。
- [ ] **手工验证**(Phase Final):
  - 启动服务后 logs 中显示解析后的 `${SKILLFORGE_HOME}` 绝对路径
  - 不同 cwd(项目根 / skillforge-server/ / 任意其他)启动得到一致 runtime root
  - 设 `SKILLFORGE_HOME=/tmp/skillforge` 启动后 runtime 写入 `/tmp/skillforge/data/skills/`
  - `data/skills/` 项目根、`skillforge-server/skills/` 不再被扫描(grill-me 迁完后此目录无内容)
  - dashboard Skills 页面 Rescan 按钮工作,展示 RescanReport
  - system-skills/clawhub/ user-facing skill 仍能从 LLM 安装(机制 B 链路完整)

## 风险

- **旧数据迁移误伤 `skill_path`**
  - 缓解:reconciler 提供 dry-run mode,先输出 report;实际 move 前 require 用户确认或 explicit flag
- **agent 绑定仍按 name,runtime 同名会造成歧义**
  - 缓解:本期强制同名 runtime 只有一个 enabled;后续单独评估 skill id binding(本期非目标)
- **自动导入 DB 缺失 artifact 可能把临时文件变成可管理 skill**
  - 缓解:reconciler 新增 row 时默认 `enabled=false`(用户可在 UI 显式启用);防误伤
- **ClawHub Java 链路删除可能破坏未发现的引用**
  - 缓解:删除前 `mvn dependency:tree` + grep 全量;Phase Final `mvn compile` + 启动 + system-skills/clawhub/ user-facing 流程实测都过才视为完成
- **路径解析 fallback 找 `pom.xml` 在多 module 项目可能找错**(找到 skillforge-server/pom.xml 而不是项目根 pom.xml)
  - 缓解:`SkillForgeHomeResolver` 同时校验 `pom.xml` 存在 + `skillforge-server/` 目录存在(双重 anchor);测试覆盖多 module fixture
- **`grill-me` 迁系统后 owner_id 处理**
  - 缓解:system skill upsert 时 owner_id 设 NULL(P1-C 已支持),is_system=true 标识

## 评审记录

待 Full Pipeline plan/review 阶段补充。
