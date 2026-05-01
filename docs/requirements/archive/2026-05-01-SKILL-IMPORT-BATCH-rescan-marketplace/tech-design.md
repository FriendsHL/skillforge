---
id: SKILL-IMPORT-BATCH
type: tech-design
status: delivered
created: 2026-05-01
updated: 2026-05-01
delivered: 2026-05-01
---

# SKILL-IMPORT-BATCH 技术方案

> 对应 [PRD](prd.md)。Mid 档：单后端模块改动 + 1 个 typescript API 类型导出，**无 schema migration**，**不改前端 UI**。
>
> **Delivered 2026-05-01 注**：
> - Phase 3 Judge r1 NEEDS_FIX 一次性 fix 完成：MUST-1（marketplace source whitelist 缺失，加 `MARKETPLACE_SOURCES = EnumSet.of(CLAWHUB, GITHUB, SKILLHUB, FILESYSTEM)`，valueOf 后 contains 校验，非 marketplace source 返 400 + 区分错误消息），MUST-2（IT 用 Mockito mock 替换 null SkillConflictResolver，零风险防御）
> - **DECIDE-1(A) 主会话决策**：PRD F1 假设 codebase 用 cookie-session auth context 与现实不符 —— SkillController 现状是 `AuthInterceptor` 拦 Bearer token + controller 接受 `userId` query param 当 ownerId（uploadSkill / forkSkill / 等全 controller 同款）。dev mirror 既有 pattern，主会话拍板选 A（接受现状 + 修 PRD F1/F4/AC-7/AC-8 描述对齐 codebase）+ 加 follow-up 到 P12-PRE "auth model upgrade" scope（多用户/权限决策定型后统一改成从 token extract userId）
> - **测试 design 修正**：dev self-check 发现 brief 给的"`skill-hub` → SKILLHUB"测试用例有逻辑 bug —— `SKILLHUB` 是单词无下划线，`replace('-','_')` 得 `SKILL_HUB` valueOf 失败。dev 改用 `SkillHub` 大小写测试 + dash 输入由 `internalSource_returns400`（skill-creator / draft-approve / evolution-fork）覆盖
> - **Pipeline 实际走法**：Mid 1 轮 review 对抗（双 Sonnet 独立挑），Judge Opus NEEDS_FIX 一次 fix + 1 主会话决策项，主会话目检 + mvn test 通过 → Phase Final live curl 暴露第二个 production bug → 主会话直接修（extraction，见下条）→ 归档
> - **Phase Final live-test 暴露生产 bug + extraction 修复（D9）**：主会话 live curl AC-1 时 3 个 skill 全 `failed: InvalidDataAccessApiUsageException: Executing an update/delete query`。根因是 Spring 经典 self-invocation footgun —— `batchImportFromMarketplace` (no `@Transactional`) 调 `this.importSkill` (`@Transactional`) 在同一类内 → 跳过 Spring proxy → `@Transactional` 注解失效 → `@Modifying` native-INSERT 没事务 → 抛错。Mockito unit test + 跳过的 IT (无 Docker) 都没抓到（**P9-2 / SKILL-IMPORT MUST-2 同款"测试假阳性"footgun**）。**修法（D9）**：抽出 `SkillBatchImporter` orchestrator class（新 file），inject `SkillImportService` 通过 Spring proxy 调 `importSkill` —— `@Transactional` 自然生效。`SkillController` 改注入 `SkillBatchImporter`。测试：`SkillImportServiceBatchTest` rename `SkillBatchImporterTest` 用 `new SkillBatchImporter(realService, properties)` 替代直调 service（保留既有 JPA mocking pattern）；`SkillRescanMarketplaceIT` 同样替换；`SkillControllerRescanMarketplaceTest` + `SkillControllerTest` 改 mock `SkillBatchImporter`。**Phase Final 重测 live AC-1（imported=3）+ AC-2（updated=3）通过**，t_skill 多 3 user 行 owner=1 + `data/skills/clawhub/{slug}/{version}/SKILL.md` 落盘 + 清理 reconciler 在 broken AC-1 attempt 后留的 3 个 NULL-owner orphan 行
> - **D9 关键决策**：选 orchestrator 抽取（而非 `@Lazy` self-injection）—— 符合项目 Java rule constructor injection 强约束，且让 IT 真正能验证 `@Transactional` 行为（之前 IT 即使跑也只 mock service，不验 proxy）。新类 ~140 行行为零变更，纯架构清理。0 残留 blocker

## Pipeline 档位说明

走 **Mid Pipeline**（默认档）：

| 红灯检查 | 命中？ |
|---|---|
| 触碰核心文件清单 | 否（不动 AgentLoopEngine / ChatService / SkillStorageService 内部 / SkillImportService.importSkill 内部）|
| 多不变量协议 | 否 |
| 新 entity / schema | 否（V33/V38 字段已齐）|
| 跨 3+ 模块 | 否（仅 skillforge-server + dashboard 的 1 个 ts 类型导出文件）|
| brief >800 字 | 否 |

→ Mid 1 轮对抗 review + Judge + Phase Final。

## 整体架构

```
[curl POST /api/skills/rescan-marketplace?source=clawhub]
                      │
                      ↓ Spring MVC
       ┌────────────────────────────────────────────┐
       │ SkillController.rescanMarketplace()        │
       │   auth check (existing) → currentUserId    │
       │   parse source enum                        │
       └────────────────────────────────────────────┘
                      │ delegates
                      ↓
       ┌────────────────────────────────────────────┐
       │ SkillImportService                          │
       │   batchImportFromMarketplace(source, ownerId)│
       │     ↓                                       │
       │   for each root in allowedSourceRoots:     │
       │     for each subDir:                        │
       │       if SKILL.md missing → skipped         │
       │       else: importSkill(subDir, source, …) │
       │              ↓                              │
       │              IllegalStateException → skip │
       │              other RuntimeException → fail │
       │              success → imported/updated   │
       │                (按 result.conflictResolved │
       │                 分桶)                        │
       └────────────────────────────────────────────┘
                      │
                      ↓
              BatchImportResult JSON
```

## 关键决策

| # | 决策 | 替代方案 | 选定理由 |
|---|---|---|---|
| D1 | 扫描所有 `allowedSourceRoots` 不按 source 过滤 root | 加 `marketplace-roots: Map<source, List<path>>` 配置 | 当前 allowedSourceRoots 只有一个根（clawhub）。多 source 时升级 config 为 map，不在本期做。加 TODO 注释。**不会引入误装风险** —— 即使配置错把 skillhub root 当 clawhub 扫进来，importSkill 的 `IllegalStateException` 跨 source 同名防御会 fail-loud 跳过 |
| D2 | 失败一个不中断整批，分桶到 imported / updated / skipped / failed | abort on first error | reconciler 风格一致；前端能 partial render；用户能看到"哪些成功 / 哪些跳"；与 P1-D `RescanReport` 设计一致 |
| D3 | 同步 endpoint，不开异步任务 | @Async / WebSocket 进度 | 当前 whitelist 子目录数量小（< 20），一次 scan + import 单 digit 秒级。异步反而 UX 复杂。如果将来某个白名单根含成百上千 skill，再升级 |
| D4 | 子目录遍历**只扫第一层**（`Files.list(root)` 一层），不递归 | 递归 walk | importSkill 期望 sourcePath 是 skill 包根目录（含 SKILL.md），按现有 marketplace CLI 约定 ClawHub / SkillHub 都是一层（`workspace/skills/<slug>/SKILL.md`）。GitHub clones 可能多层但 GitHub source 不在本期重点。如未来需要递归，单独扩展 |
| D5 | TS 类型导出但不实现按钮 | 完全不动前端 | 后续设计 agent 接 UI 时只需 import 这个函数 + 类型，不用从 API 文档反推。Stub 已经是测试 boundary |
| D6 | endpoint 用 query param `source` 不用 path param | `POST /api/skills/rescan-marketplace/clawhub` | query 更 RESTful（这是 action，不是 resource），且未来加其他 query（如 `dryRun=true` follow-up）更自然 |

## 实现拆分

### 模块 A — 新增 `BatchImportResult` record（在 server skill 包）

**新文件**：`skillforge-server/src/main/java/com/skillforge/server/skill/BatchImportResult.java`

```java
public record BatchImportResult(
    List<ImportedItem> imported,
    List<ImportedItem> updated,
    List<SkippedItem> skipped,
    List<FailedItem> failed
) {
    public record ImportedItem(String name, String skillPath) {}
    public record SkippedItem(String name, String reason) {}
    public record FailedItem(String name, String error) {}
}
```

或扁平 4 个 List 加同一种 `Item` record 用 type 字段区分 —— 实现时由 dev 选语义最清晰的（**JSON 序列化结构以 PRD F2 定义为准**）。

### 模块 B — `SkillImportService.batchImportFromMarketplace`

**改动文件**：`skillforge-server/src/main/java/com/skillforge/server/skill/SkillImportService.java`

新方法（**注意：不要 `@Transactional`**——每个 importSkill 已经各自 @Transactional，外层不要包大事务，避免一个失败回滚整批）：

```java
public BatchImportResult batchImportFromMarketplace(SkillSource source, Long ownerId) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(ownerId, "ownerId");

    List<ImportedItem> imported = new ArrayList<>();
    List<ImportedItem> updated = new ArrayList<>();
    List<SkippedItem> skipped = new ArrayList<>();
    List<FailedItem> failed = new ArrayList<>();

    // TODO: 当前 allowed-source-roots 是 flat list 不区分 source；
    // 多 source 时升级 config 为 Map<SkillSource, List<Path>>（SKILL-IMPORT-BATCH 后续 follow-up）
    for (Path root : properties.resolvedAllowedRoots()) {
        if (!Files.isDirectory(root)) continue;
        try (Stream<Path> children = Files.list(root)) {
            children
                .filter(Files::isDirectory)
                .forEach(sub -> handleSubdir(sub, source, ownerId,
                                             imported, updated, skipped, failed));
        } catch (IOException e) {
            log.warn("batch rescan: failed to list root {}: {}", root, e.getMessage());
        }
    }

    return new BatchImportResult(imported, updated, skipped, failed);
}

private void handleSubdir(Path sub, SkillSource source, Long ownerId,
                           List<ImportedItem> imported, List<ImportedItem> updated,
                           List<SkippedItem> skipped, List<FailedItem> failed) {
    String name = sub.getFileName().toString();
    if (!Files.isRegularFile(sub.resolve("SKILL.md"))) {
        skipped.add(new SkippedItem(name, "no SKILL.md"));
        return;
    }
    try {
        ImportResult r = importSkill(sub, source, ownerId);  // existing @Transactional method
        if (r.conflictResolved()) {
            updated.add(new ImportedItem(r.name(), r.skillPath()));
        } else {
            imported.add(new ImportedItem(r.name(), r.skillPath()));
        }
    } catch (IllegalStateException e) {
        // SKILL-IMPORT 防御：跨 source 同名 / 不变量违反
        skipped.add(new SkippedItem(name, "cross-source name conflict: " + e.getMessage()));
    } catch (IllegalArgumentException e) {
        // 白名单 / SKILL.md 缺失等 —— 当前路径已经过 list，但 importSkill 内部还会校验
        skipped.add(new SkippedItem(name, e.getMessage()));
    } catch (RuntimeException e) {
        failed.add(new FailedItem(name, e.getClass().getSimpleName() + ": " + e.getMessage()));
        log.warn("batch rescan: subdir={} import failed", sub, e);
    }
}
```

### 模块 C — `SkillController.rescanMarketplace`

**改动文件**：`skillforge-server/src/main/java/com/skillforge/server/controller/SkillController.java`

```java
private final SkillImportService skillImportService;
private final AuthenticationFacade authFacade;  // 或现有 ownerId 提取方式，沿用此 controller 内既有 pattern

@PostMapping("/rescan-marketplace")
public ResponseEntity<?> rescanMarketplace(@RequestParam("source") String sourceWire) {
    SkillSource source;
    try {
        source = SkillSource.valueOf(sourceWire.toUpperCase().replace('-', '_'));
    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "invalid source: " + sourceWire));
    }
    Long ownerId = authFacade.currentUserId();  // 沿用此 controller 内既有 ownerId 提取方式
    BatchImportResult result = skillImportService.batchImportFromMarketplace(source, ownerId);
    return ResponseEntity.ok(result);
}
```

**注意**：`ownerId` 提取方式跟 SkillController 现有其它方法一致（不接受 query 传 ownerId）。dev 自己看现有方法的 auth 写法 mirror。

### 模块 D — Frontend API 类型导出

**改动文件**：`skillforge-dashboard/src/api/index.ts`

加：

```ts
export interface BatchImportResultItem {
  name: string;
  skillPath?: string;
  reason?: string;
  error?: string;
}

export interface BatchImportResult {
  imported: BatchImportResultItem[];
  updated: BatchImportResultItem[];
  skipped: BatchImportResultItem[];
  failed: BatchImportResultItem[];
}

export const rescanMarketplace = (source: string) =>
  api.post<BatchImportResult>(
    `/skills/rescan-marketplace?source=${encodeURIComponent(source)}`
  );
```

**不调用此函数**（前端没人调用），仅作 export 给后续前端设计 agent 接入。

## 测试矩阵

| 测试类 | 覆盖 AC | Key cases |
|---|---|---|
| `SkillImportServiceBatchTest` (unit, Mockito) | AC-1 ~ AC-5 | (1) fresh batch import 多 skill；(2) 同 slug update 路径；(3) 子目录无 SKILL.md skipped；(4) cross-source IllegalStateException skipped；(5) 一个 IO error 不中断其他 |
| `SkillControllerRescanMarketplaceTest` (unit, MockMvc) | AC-6, AC-7（auth 由 controller framework 拦） | (1) 合法 source 走通；(2) 非法 source → 400；(3) service 抛错 → 500（沿用 SkillController 错误处理风格） |
| `SkillRescanMarketplaceIT` (集成 + AbstractPostgresIT 风格) | AC-1 ~ AC-2 端到端 | 真 PG + 真文件系统 fixture（@TempDir）+ 真 importSkill 路径 |

## 风险点

- **`@Transactional` 边界**：外层 batch method **必须不带 `@Transactional`**，否则一个 importSkill 失败会回滚整批。每个 importSkill 已自 `@Transactional`，自然按 sub-tx 隔离。
- **stream 资源泄漏**：`Files.list(root)` 必须 try-with-resources 关闭流，避免文件描述符泄漏（D2 模板已正确）。
- **跨 source 配置歧义**（D1 决策）：当前不修，加 TODO + 在 followup 触发条件之一记到 `/tmp/nits-followup-skill-import-batch.md` 里。多用户加不同 marketplace root 时再升级 config schema。
- **大量子目录性能**：当前每个目录 = 一次 LLM-free filesystem 操作 + 一次 DB upsert + 一次 hash 计算。100 子目录约 1-2 秒，不需异步。如果某天遇到 1000+，再考虑分批 / 异步 / progress reporting。

## Phase Final 验证清单

- [ ] `mvn -pl skillforge-server -am test -Dtest='SkillImportServiceBatch*,SkillControllerRescanMarketplace*,SkillRescanMarketplaceIT'` 全绿（IT 本地无 Docker skip 是 pre-existing baseline）
- [ ] 手动 curl AC-1：登录 cookie + `POST /api/skills/rescan-marketplace?source=clawhub` → 看 BatchImportResult JSON 结构 + t_skill 表多出 user 行
- [ ] 重复跑 AC-2 验证 update 路径
- [ ] dashboard `vite build` 通过（添加 TS 类型不破坏现有 build）
- [ ] `delivery-index.md` + `todo.md` + 包归档 + commit + 等用户批准
- [ ] 不开 backend 重启（user 可自行触发；本期纯增量）
