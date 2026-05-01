---
id: SKILL-IMPORT
type: tech-design
status: delivered
created: 2026-05-01
updated: 2026-05-01
delivered: 2026-05-01
---

# SKILL-IMPORT 技术方案

> 对应 [PRD](prd.md)。Mid 档：单后端模块 + 改 3 个系统 skill 的 SKILL.md，纯代码 + 文档改动，**无 schema migration**。
>
> **Delivered 2026-05-01 注**：
> - Phase 3 Judge r1 NEEDS_FIX 一次性 fix 完成：MUST-1（F2 orphan log.warn 漏，PRD line 40 明文要求）、MUST-2（warning 升 blocker：并发 retry 是 Mockito 假阳性 + tech-design 明文要求 `insertIgnoreConflict` 模式没沿用，dev 改用 `INSERT ... ON CONFLICT (COALESCE(owner_id, -1), name) DO NOTHING` native query 与 V31 索引精度一致）、MUST-3（warning 升 must / one-liner：`row.setName` 用 `meta.slug()` 消除 frontmatter rename 重复行 corner case）
> - **D6 落地**：`SkillCatalogReconciler.hashSkillMd(Path)` 加 public 方法，复用内部 `findSkillMd` + `readBytes` + `sha256` 私有 static helper；`SkillImportServiceTest.importSkill_contentHashMatchesReconcilerAlgorithm` 守 hash 算法漂移
> - **D8 shared path 确认**：clawhub/skillhub/github 路径不带 ownerId（沿用 P1-D），跨用户共享磁盘 artifact，per-user 隔离仅在 t_skill row 层面。reconciler orphan 检测全表 scan 兜得住
> - **附加发现（follow-up）**：`uq_t_skill_owner_name` 索引只保 `(owner_id, name)` 不含 source，与 PRD F2 4 元组身份假设漂移。dev 防御代码 fail-loud（`IllegalStateException`），不是 silent overwrite。用户 2026-05-01 决策：选方向 (a) 改错误信息 + 专属 exception，等真遇到重名场景再做。详见 [`/tmp/nits-followup-skill-import.md`](/tmp/nits-followup-skill-import.md) 末段
> - **Pipeline 实际走法**：Mid 1 轮 review 对抗（双 Sonnet 独立挑），Judge Opus NEEDS_FIX 一次 fix，主会话目检 + mvn test 通过后归档。0 残留 blocker

## Pipeline 档位说明

走 **Mid Pipeline**（默认档）：

| 红灯检查 | 命中？ | 备注 |
|---|---|---|
| 触碰核心文件清单 | 否 | 只 call `SkillStorageService.allocate()`，不改其内部逻辑；不动 AgentLoopEngine / ChatService / CompactionService / LlmProvider |
| 多不变量协议 | 否 | tool_use ↔ tool_result 配对、lock/unlock 等都不涉及 |
| 新 entity / schema | 否 | V33/V38 已留 `source` / `skill_path` / `content_hash` / `artifact_status` / `last_scanned_at` 字段 |
| 跨 3+ 模块 | 否 | 主要 `skillforge-server`，加 3 个 `system-skills/*/SKILL.md` 文档 |
| brief >800 字 | 否 | spec 已收敛 |

→ Mid 1 轮对抗 review + Judge + Phase Final，不开 Plan phase。

## 整体架构

```
                    [agent calls Tool]
                            │
                            ↓
              ┌─────────────────────────────────┐
              │  ImportSkillTool (新, server)   │
              │  implements core.skill.Tool      │
              └────────────┬────────────────────┘
                            │ delegates
                            ↓
              ┌─────────────────────────────────┐
              │  SkillImportService (新, server) │
              │   1. validate sourcePath         │
              │   2. parse SKILL.md + _meta.json │
              │   3. allocate target path        │ ←── SkillStorageService (P1-D, 不改)
              │   4. copy files                  │
              │   5. compute content_hash        │ ←── 沿用 SkillCatalogReconciler 算法
              │   6. UPSERT t_skill row          │ ←── SkillRepository (不改)
              │   7. register to SkillRegistry   │ ←── SkillRegistry (不改)
              └─────────────────────────────────┘
                            │ uses config
                            ↓
              ┌─────────────────────────────────┐
              │  SkillImportProperties (新)      │
              │   allowedSourceRoots: List<Path> │
              └─────────────────────────────────┘
```

## 关键决策

| # | 决策 | 替代方案 | 选定理由 |
|---|---|---|---|
| D1 | Tool 放在 `skillforge-server/src/.../skill/ImportSkillTool.java` | 放 `skillforge-tools` module | Tool 依赖 `SkillImportService` / `SkillRepository` / `SkillRegistry` / `SkillStorageService`，全在 server。`skillforge-tools` 是无状态系统级 tool（Bash/FileRead/Glob 等） |
| D2 | 业务逻辑抽到独立 `SkillImportService` | 全塞进 Tool 里 | Tool 应只做参数解析 + 调 service。Service 可被未来的 dashboard `/api/skills/import` REST 复用，而 Tool 不能（Tool 跑在 agent loop 里，REST 跑在 controller 里） |
| D3 | 同 slug 直接 UPDATE，不报错 | 报错让 user 选 | 用户已拍板：覆盖。简化 agent 流程，不引入 conflict 协议 |
| D4 | 旧版本目录变 orphan，log.warn 不删 | 自动删旧目录 | P1-D 同样的 orphan 处理风格（避免误删 user 在 inspect 的目录）。删可走 `refactor-cleaner` agent 或 P1-D follow-up |
| D5 | 白名单走 `application.yml` + `@ConfigurationProperties` | 硬编码 list / DB 配置 | 配置驱动 + 重启生效（不需要热加载） |
| D6 | content_hash = sha256(SKILL.md 字节)，**与 `SkillCatalogReconciler` 共用算法**。在 reconciler 加 `public String hashSkillMd(Path skillDir)` 暴露其内部已有的 `sha256(readBytes(skillMd))` 逻辑 | 复制一份 / 抽到 utility class | reconciler 启动期会基于 hash 判断是否要 update t_skill row。import service 写入的 hash 必须与 reconciler 算法 byte-for-byte 一致，否则下次 reconciler 跑会误判 hash 漂移触发 update 重写。注意：hash 范围是 **SKILL.md 单文件**，不含目录其他文件（与 reconciler 当前行为对齐） |
| D7 | source enum 复用 `SkillSource.{CLAWHUB,GITHUB,SKILLHUB,FILESYSTEM}` | 加新 enum 项 | P1-D 已留好。Tool 入参 string → 用 `SkillSource.valueOf(input.toUpperCase().replace('-','_'))` 反序列化（处理 wireName） |
| D8 | AllocationContext 三家 marketplace（clawhub/skillhub/github）path 不带 ownerId，**disk artifact 跨用户共享**，per-user 隔离只在 `t_skill.owner_id` row 层面 | 改 SkillStorageService 加 ownerId 路径段 | (a) 改 SkillStorageService 触动 P1-D 核心文件，红灯升 Full;(b) SkillForge 当前单用户假设（P12-PRE 待决策多用户边界），共享路径不引入实际问题；(c) 副作用见下方 "shared path 副作用" |

## 实现拆分

### 模块 A — 配置 properties（F4）

**新文件**：`skillforge-server/.../skill/SkillImportProperties.java`

```java
@ConfigurationProperties(prefix = "skillforge.skill-import")
public class SkillImportProperties {
    /** Whitelisted source roots (one of these must be a prefix of sourcePath). */
    private List<String> allowedSourceRoots = List.of();
    // getter + setter
}
```

注册到 `@EnableConfigurationProperties(SkillImportProperties.class)` 的 config（可加在 `SkillForgeConfig` 或新建 `SkillImportConfig`）。

`application.yml` 默认值：

```yaml
skillforge:
  skill-import:
    allowed-source-roots:
      - "~/.openclaw/workspace/skills"
```

启动时把每条 root 用 `Paths.get(s.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize()` 解析成绝对路径，存到 properties bean 中（或 service 内做）。

### 模块 B — `SkillImportService`（F1 + F2）

**新文件**：`skillforge-server/.../skill/SkillImportService.java`

构造器注入：`SkillImportProperties`、`SkillStorageService`、`SkillRepository`、`SkillRegistry`、`SkillPackageLoader`、`SkillCatalogReconciler`（拿 hash 算法用）。

**前置改动**：在 `SkillCatalogReconciler` 加一个 public 入口暴露已有 hash 算法（D6）：

```java
/** P1-D 算法暴露给 SKILL-IMPORT：sha256 of SKILL.md bytes. */
public String hashSkillMd(Path skillDir) {
    Path skillMd = findSkillMd(skillDir);
    if (skillMd == null) {
        throw new IllegalArgumentException("SKILL.md not found in " + skillDir);
    }
    return sha256(readBytes(skillMd));
}
```

`findSkillMd` / `sha256` / `readBytes` 已是 `private static`，新方法只是复用。**不改原 reconciler 行为**，仅暴露 API。

公开方法：

```java
@Transactional
public ImportResult importSkill(Path sourcePath, SkillSource source, Long ownerId) {
    // 1. 校验白名单
    validateSourcePath(sourcePath);

    // 2. 校验 SKILL.md 存在 + 解析
    Path skillMd = sourcePath.resolve("SKILL.md");
    if (!Files.isRegularFile(skillMd)) {
        throw new IllegalArgumentException("SKILL.md not found in " + sourcePath);
    }
    SkillDefinition def = packageLoader.loadFromDirectory(sourcePath);

    // 3. _meta.json 兜底拿 slug / version
    SkillMeta meta = readMetaJsonOrFallback(sourcePath, def);

    // 4. allocate target
    AllocationContext ctx = buildAllocationContext(source, ownerId, meta);
    Path target = storageService.allocate(source, ctx);

    // 5. recursive cp
    storageService.ensureDirectories(target);
    copyDirectoryReplacing(sourcePath, target);

    // 6. content_hash — sha256 of SKILL.md (与 reconciler 一致，见 D6)
    String hash = reconciler.hashSkillMd(target);

    // 7. upsert t_skill
    boolean conflictResolved = upsertSkillRow(meta, source, target, hash, ownerId);

    // 8. register to registry
    def.setSystem(false);
    def.setOwnerId(String.valueOf(ownerId));
    skillRegistry.registerSkillDefinition(def);

    return new ImportResult(savedRow.getId(), savedRow.getName(), target.toString(),
                            source.wireName(), conflictResolved);
}

private void validateSourcePath(Path sourcePath) {
    Path normalized = sourcePath.toAbsolutePath().normalize();
    boolean allowed = properties.resolvedAllowedRoots().stream()
        .anyMatch(root -> normalized.startsWith(root));
    if (!allowed) {
        throw new IllegalArgumentException(
            "sourcePath not in allowed roots: " + normalized);
    }
}

private boolean upsertSkillRow(SkillMeta meta, SkillSource source, Path target,
                                String hash, Long ownerId) {
    Optional<SkillEntity> existing = skillRepository
        .findByOwnerIdAndNameAndSourceAndIsSystem(ownerId, meta.name(), source.wireName(), false);
    if (existing.isPresent()) {
        SkillEntity row = existing.get();
        row.setSkillPath(target.toString());
        row.setContentHash(hash);
        row.setLastScannedAt(Instant.now());
        row.setArtifactStatus("active");
        // description / triggers 也按新 SKILL.md 覆盖
        skillRepository.save(row);
        return true;
    }
    SkillEntity row = new SkillEntity();
    row.setName(meta.name());
    row.setOwnerId(ownerId);
    row.setSystem(false);
    row.setSource(source.wireName());
    row.setSkillPath(target.toString());
    row.setContentHash(hash);
    row.setLastScannedAt(Instant.now());
    row.setArtifactStatus("active");
    row.setEnabled(true);
    skillRepository.save(row);
    return false;
}
```

**Edge cases**：

- `_meta.json` 缺失：fallback 用 `SKILL.md` frontmatter 的 `name` 当 slug，version 留空（`forClawhub("tool-call-retry", "")` 时路径是 `clawhub/tool-call-retry/`，`SkillStorageService.requireSafeSegment` 拒绝空 string —— 所以 version 缺失时必须 fallback 到 `"unknown"` 或 `"latest"`，这一点要在 `buildAllocationContext` 里兜底）
- 旧 row 的 skill_path 指向旧版本目录（cp 后变 orphan）：service 不删，下次 reconciler 跑时会 log.warn
- 校验时 sourcePath 是 symlink：先 `toRealPath()` 解析，避免符号链接绕过白名单
- 并发 import 同 slug：`@Transactional` + UNIQUE 约束 `uq_t_skill_owner_name` 兜底，第二个并发会撞 unique，service 捕获 + retry 一次（lookup → save → catch → re-lookup → update），沿用 P1-D `ToolResultArchiveService` 的 `lookup → insertIgnoreConflict → re-lookup` 三步幂等模式

### shared path 副作用（D8 取舍后果）

clawhub / skillhub / github 三家的 `AllocationContext` 不带 ownerId，target path 是 slug（或 repo+ref）层级共享。后果：

- 同一 slug 跨用户共享 disk artifact（user A 和 user B 都 import `tool-call-retry@1.0.0` → 同一 `data/skills/clawhub/tool-call-retry/1.0.0/SKILL.md`，两条 t_skill row 都指向它）
- 当 user A 升级到 1.0.1：A 的 row.skill_path 改到 1.0.1/，但 1.0.0/ 仍可能被 user B 的 row 引用 → **不能简单当 orphan 删**
- 当前 reconciler orphan 检测（`UserSkillLoader.scanOrphanDirs`）已经是基于"任何 t_skill 行的 skill_path 等于此目录"的全局判断（`findByIsSystemFalse()` 全表 scan），所以**多用户共享场景下天然安全**，已经 OK
- 单用户场景（SkillForge 当前默认）：升级即 orphan，符合 P1-D follow-up 的"3 个磁盘 orphan"现象

**结论**：本期不改 SkillStorageService 加 owner 路径段（避免触动 P1-D 核心文件升 Full），共享路径在多用户场景下也由现有 reconciler orphan 逻辑兜住。但记一条 follow-up：P12-PRE 多用户/权限模型 design doc 完成后，再决定是否给 marketplace source 加 ownerId 路径段。

### 模块 C — `ImportSkillTool`（F1）

**新文件**：`skillforge-server/.../skill/ImportSkillTool.java`

```java
public class ImportSkillTool implements Tool {

    private final SkillImportService importService;

    public ImportSkillTool(SkillImportService importService) {
        this.importService = importService;
    }

    @Override
    public String getName() { return "ImportSkill"; }

    @Override
    public String getDescription() {
        return "把第三方 marketplace（ClawHub / GitHub / SkillHub）已经装好的 skill 注册到 SkillForge。"
             + "适用场景：你刚刚用 `npx clawhub install <slug>` / `gh repo clone <repo>` / "
             + "`npx @skill-hub/cli install <slug>` 把 skill 装到外部目录，"
             + "调本工具把它复制到 SkillForge 的 runtime root + 写入 t_skill 表 + 注册到 SkillRegistry，"
             + "之后 dashboard 能看到、后续 agent turn 能调用。"
             + "不调用本工具，第三方 CLI 装的 skill SkillForge 不可见。";
    }

    @Override
    public ToolSchema getToolSchema() {
        return ToolSchema.object()
            .addProperty("sourcePath", ToolSchema.string()
                .description("已装好的 skill 目录绝对路径（包含 SKILL.md），"
                           + "例如 ~/.openclaw/workspace/skills/tool-call-retry"))
            .addProperty("source", ToolSchema.string()
                .description("Skill 来源：clawhub | github | skillhub | filesystem"))
            .required("sourcePath", "source")
            .build();
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        String sourcePathStr = (String) input.get("sourcePath");
        String sourceStr = (String) input.get("source");
        Long ownerId = context.getUserId();

        Path sourcePath = Path.of(sourcePathStr.replaceFirst("^~",
            System.getProperty("user.home")));
        SkillSource source = SkillSource.valueOf(sourceStr.toUpperCase().replace('-','_'));

        ImportResult result = importService.importSkill(sourcePath, source, ownerId);
        return SkillResult.ok(toJson(result));
    }
}
```

注册到 `SkillForgeConfig#skillRegistry()` bean（与现有 BashTool / FileReadTool 等同一处注册）。

### 模块 D — 三个 system skill SKILL.md 文档（F3）

改三个文件：

- `system-skills/clawhub/SKILL.md`
- `system-skills/github/SKILL.md`
- `system-skills/skillhub/SKILL.md`

每个文件在 install 章节后追加（PRD F3 已给 ClawHub 范本，github/skillhub 类似，slug 改 repo path / skillhub workspace 路径）。

> 注：这是 system skill 的 prompt 内容，会作为 `Skill("clawhub")` tool result 灌给 LLM。LLM 看到这段后会在装完调用 `ImportSkill` Tool。

### 模块 E — Repository 方法

`SkillRepository` 加方法（如果还没）：

```java
Optional<SkillEntity> findByOwnerIdAndNameAndSourceAndIsSystem(
    Long ownerId, String name, String source, boolean isSystem);
```

## 测试覆盖

| 测试类 | 覆盖 AC | 关键 case |
|---|---|---|
| `SkillImportServiceTest` (unit, Mockito) | AC-1, AC-2, AC-3, AC-4, AC-5 | 新建 / 同名覆盖 / 升级版本 orphan / 白名单拒绝 / 缺 SKILL.md 报错 |
| `ImportSkillToolTest` (unit) | AC-1 | tool 入参解析 → service.importSkill 被调用 with 正确 args |
| `SkillImportPropertiesTest` (unit) | AC-8 | yaml 解析 + `~` 展开 + 多 root |
| `SkillImportIT` (集成, 真 file IO + 真 PG via embedded) | AC-1, AC-2, AC-3 | 端到端 cp + 落库 + register 验证 |
| `SkillPackageLoaderTest` 既有 | AC-7 回归 | 系统 skill 加新段后仍能 parse frontmatter |

## 风险点

- **content_hash 算法漂移**：如果 `SkillImportService` 算的 hash 跟 `SkillCatalogReconciler` 不一致，下次 reconciler 启动跑会把 row 当 changed → 触发 update 路径。**缓解**：复用 reconciler 算法（D6），加 `EngineHashMatchesReconcilerHashTest` 守卫。
- **path traversal**：sourcePath 即使在白名单内，也要 `toRealPath()` 解析符号链接，避免 `~/.openclaw/workspace/skills/../../../etc/...` 绕过。**缓解**：`validateSourcePath` 用 `toRealPath` + `startsWith` 双重检查。
- **并发 import**：两个 agent loop 同时 import 同 slug → unique 约束兜底 + retry。
- **agent 不主动调用**：F3 改 system skill SKILL.md 是行为引导，但 LLM 不一定每次都遵守。**缓解**：description 写直白 + tool description 也明示。后续观察用户反馈，若仍漏可在 ClawHub system skill 里加自动检测（例如装完后让 agent 用 ask_user 确认是否注册）。

## 不在本期范围

- dashboard import 按钮 / `/api/skills/import` REST endpoint（非目标）
- 自动跑 `npx clawhub install` 等装包命令（非目标）
- 跨用户 / 公开 skill import（owner_id 当前用户绑死）
- zip / tar 包源（必须已解压目录）
- 白名单热加载

## Phase Final 验证清单

- [ ] `mvn -pl skillforge-server -am test -Dtest=SkillImport*` 全绿
- [ ] dashboard 实测：登录 → agent 跑 `npx clawhub install <slug>` + `ImportSkill` → Skills 页能看到新行 + skillPath 在 `data/skills/clawhub/<slug>/<version>/`
- [ ] 三个 system skill SKILL.md frontmatter 仍能 parse（`SystemSkillLoader` 启动 log "Registered system skill" 6 个全在）
- [ ] 重启 server 后新 row 仍在（持久化 OK）+ reconciler 不会因为 hash 漂移误判 update
- [ ] `delivery-index.md` 加交付行 + `todo.md` 队列行移到"最近完成" + 需求包 `requirements/active/` → `requirements/archive/<yyyy-MM-dd>-SKILL-IMPORT-<slug>/`
