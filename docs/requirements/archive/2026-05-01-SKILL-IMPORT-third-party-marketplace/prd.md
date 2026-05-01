---
id: SKILL-IMPORT
type: prd
status: delivered
created: 2026-05-01
updated: 2026-05-01
delivered: 2026-05-01
---

# SKILL-IMPORT PRD

## 目标

让 agent 在用第三方 CLI（ClawHub / GitHub / SkillHub）把 skill 装到外部目录之后，能调用 SkillForge 的 `ImportSkill` Tool 把它注册到 SkillForge —— dashboard 可见、`t_skill` 有行、`SkillRegistry` 已注册、agent 后续 turn 可调。

## 功能需求

### F1. 新增 `ImportSkill` Tool

- 暴露给 agent 的 Java Tool（`com.skillforge.core.skill.Tool`），名称 `ImportSkill`。
- 入参：
  ```json
  {
    "sourcePath": "/Users/youren/.openclaw/workspace/skills/tool-call-retry",
    "source":     "clawhub | github | skillhub | filesystem"
  }
  ```
- 行为（成功路径）：
  1. 校验 `sourcePath` 在白名单内（见 F4）+ 包含 `SKILL.md`。
  2. 解析 `SKILL.md` frontmatter 拿 `name` / `description` / `triggers` / `allowed-tools`，再读 `_meta.json`（如有）补 slug / version。
  3. `SkillStorageService.allocate(SkillSource.<source>, ctx)` 分配 target 路径。
  4. 递归 cp `sourcePath` → target。
  5. 算 `content_hash`（沿用 `SkillCatalogReconciler` 现有 hash 算法）。
  6. UPSERT `t_skill` 行：`is_system=false, owner_id=<currentUserId>, name=<slug 或 SKILL.md name>, source=<wireName>, skill_path=<target>, content_hash=<…>, artifact_status=active, last_scanned_at=now()`。
  7. `SkillRegistry.registerSkillDefinition(definition)`（agent 后续 turn 立即可用）。
  8. 返回 `{ id, name, skillPath, source, conflictResolved: <true|false> }`。

### F2. 同 slug 已存在 → 直接覆盖

- 同 `(is_system=false, owner_id=X, name=<slug>, source=<source>)` 已有 t_skill 行 → **UPDATE 这一行**，不报错、不询问。
- 如果新版本路径与旧不同（`v1.0.0` → `v1.0.1`），row.skill_path 指向新路径，**旧目录变 orphan**：log.warn，**不自动删**（避免误删，保留 P1-D 已有的 orphan 处理风格）。
- 同版本路径 → cp 覆盖文件，更新 `content_hash` / `last_scanned_at` / `updated_at`。
- `SkillRegistry.registerSkillDefinition` 是 putIfAbsent override 语义，覆盖 OK。
- 返回字段 `conflictResolved=true` 让 agent 知道是覆盖了一个已有的。

### F3. 改三个 marketplace system skill 的 `SKILL.md`

对 `system-skills/{clawhub,github,skillhub}/SKILL.md` 各自的 install 章节后追加一段，指引 agent："**装完之后必须调用 `ImportSkill` Tool**，把 skill 注册到 SkillForge，否则 SkillForge 看不到这个 skill"。

ClawHub 例子（追加在现有 install 段后）：

```markdown
## After Install: Register to SkillForge

`npx clawhub install` only puts the skill into ClawHub's own workspace
(`~/.openclaw/workspace/skills/<slug>`). To make it visible to SkillForge
(dashboard, t_skill catalog, future agent turns), you MUST call:

ImportSkill({
  sourcePath: "/Users/<user>/.openclaw/workspace/skills/<slug>",
  source: "clawhub"
})
```

GitHub / SkillHub 类似（slug 改 repo path / skill-hub workspace）。

### F4. Source path 白名单 + 配置驱动

- 白名单走 `application.yml`：

  ```yaml
  skillforge:
    skill-import:
      allowed-source-roots:
        - "~/.openclaw/workspace/skills"
        # 后续可按需追加，如 "~/.skill-hub/skills"、"~/projects/clones"
  ```

- 实现：`@ConfigurationProperties` bean → `SkillImportProperties.allowedSourceRoots: List<String>`，启动时解析 `~` 为 `System.getProperty("user.home")`。
- 校验：`sourcePath` 必须 `.startsWith()` 至少一个 root（normalize 后判断），否则抛 `IllegalArgumentException`，返回错误。
- 设计目标：后续加路径只改配置 + 重启，不改代码。

## 验收点（AC）

- **AC-1** Agent 在 sandbox 里跑：先 `npx clawhub install tool-call-retry`，再 `ImportSkill({sourcePath:"~/.openclaw/workspace/skills/tool-call-retry", source:"clawhub"})` → 返回 success；查 `t_skill` 有 1 行新 user skill；`data/skills/clawhub/tool-call-retry/<version>/SKILL.md` 落盘。
- **AC-2** 同样的命令再跑一次 → tool 返回 success + `conflictResolved=true`，`t_skill` 还是 1 行（覆盖），content_hash 不变（同源同版本）。
- **AC-3** 同 slug 不同版本（手动改 `_meta.json` version 模拟升级）→ row.skill_path 指向新版本目录，旧版本目录在 disk 上保留，log 出现 1 条 `orphan skill dir` warn。
- **AC-4** sourcePath 不在白名单（如 `/etc/`）→ tool 返回 `IllegalArgumentException`，无文件被 cp，无 t_skill 写入。
- **AC-5** sourcePath 没有 `SKILL.md` → tool 返回错误，无写入。
- **AC-6** dashboard Skills 页 invalidate 后能看到新 row 出现。
- **AC-7** ClawHub / GitHub / SkillHub 三个 system skill 的 `SKILL.md` 都加上 "After Install" 段。
- **AC-8** 白名单加新条目（如 `~/.skill-hub/skills`）后，重启 server 能正确受理新路径，不需要改代码。

## 非目标

- **不**自动跑 `npx clawhub install`：agent 自己用 Bash 跑原 CLI，本 tool 只做 import 后续。
- **不**自动清理旧版本 orphan 目录：log.warn 即可，沿用 P1-D 风格。
- **不**做 dashboard 端 import 按钮：现有 `/api/skills/upload` 是 zip 上传，语义不同，不合并。MVP 只 tool。
- **不**做版本链 / fork / A-B（那是 P1 evolution 的范畴）。
- **不**支持 zip / tar 包格式 source（必须是已经解压的目录）。
- **不**做白名单热加载：改配置必须重启。

## 验证

- `mvn test -pl skillforge-server -Dtest='Skill*Import*'`：`SkillImportServiceTest`、`ImportSkillToolTest`、`SkillImportPropertiesTest` 单元覆盖 AC-1 ~ AC-5、AC-8。
- 手动 dashboard 实测：跑 AC-1 流程 → Skills 页能看到 → 拉一次 chat 让 agent 调用新 skill 验证 SkillRegistry 注册成功。
- 三处 SKILL.md 改动：`mvn test` 系统 skill loader 仍能解析 frontmatter（`SkillPackageLoaderTest` 回归）。
