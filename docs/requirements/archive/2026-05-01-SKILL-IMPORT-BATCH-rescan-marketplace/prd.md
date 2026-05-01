---
id: SKILL-IMPORT-BATCH
type: prd
status: delivered
created: 2026-05-01
updated: 2026-05-01
delivered: 2026-05-01
---

# SKILL-IMPORT-BATCH PRD

## 目标

在 SKILL-IMPORT 基础上加一条 batch rescan 路径：扫描 `allowedSourceRoots` 下所有含 SKILL.md 的子目录，批量调既有 `SkillImportService.importSkill()` 把它们注册到 SkillForge catalog。供 dashboard 按钮使用 / 也可用 curl 触发。

## 功能需求

### F1. `POST /api/skills/rescan-marketplace` REST endpoint

- 路径：`POST /api/skills/rescan-marketplace`
- query 参数：
  - `source: string`（必填，取值 `clawhub | github | skillhub | filesystem`）
  - `userId: number`（必填，作为 ownerId 来源）
- auth：`AuthInterceptor` 拦 `Authorization: Bearer <token>` header → 401。controller 方法接受 `userId` query param 作为 ownerId 来源（mirror 既有 SkillController pattern：uploadSkill / forkSkill / deleteSkill / 等），**pre-existing 全 SkillController 同款 design choice，本期不严控 token user == query userId 一致性**（详见 P12-PRE follow-up "auth model upgrade"）。
- 响应：`200 OK` + `BatchImportResult` JSON（见 F2）；非法 source / 未授权 → 400 / 401

### F2. `BatchImportResult` 输出结构

```json
{
  "imported": [{"name": "...", "skillPath": "..."}],
  "updated":  [{"name": "..."}],
  "skipped":  [{"name": "...", "reason": "..."}],
  "failed":   [{"name": "...", "error": "..."}]
}
```

- `imported` —— 新行（既有 t_skill 行不存在 → INSERT 成功）
- `updated` —— 同 slug 已存在 → UPDATE（既有 ImportResult `conflictResolved=true` 的语义）
- `skipped` —— 业务逻辑拒绝：跨 source 同名（IllegalStateException）/ 缺 SKILL.md / 解析失败
- `failed` —— 系统异常：IO / DB 错误等

### F3. `SkillImportService.batchImportFromMarketplace`

- 新方法 `BatchImportResult batchImportFromMarketplace(SkillSource source, Long ownerId)`
- 扫所有 `properties.resolvedAllowedRoots()` 配置的根（**不按 source 过滤 root**，注释 TODO："当前 allowed-source-roots 是 flat list，多 source 时升级 config 为 root→source map"）
- 对每个 root 下**第一层子目录**遍历：
  - 子目录无 SKILL.md → skipped("no SKILL.md")
  - 调既有 `importSkill(subDir, source, ownerId)`
  - `IllegalStateException`（跨 source 同名 fail-loud）→ skipped(`reason=cross-source name conflict: ${msg}`)
  - 其他 RuntimeException → failed(`error=...`)
  - 成功 → 按 `result.conflictResolved()` 分入 `imported` 或 `updated`
- **失败一个不中断整批**（continue）

### F4. `skillforge-dashboard/src/api/index.ts` 加 API 函数

```ts
export interface BatchImportResultItem { name: string; skillPath?: string; reason?: string; error?: string }
export interface BatchImportResult {
  imported: BatchImportResultItem[];
  updated:  BatchImportResultItem[];
  skipped:  BatchImportResultItem[];
  failed:   BatchImportResultItem[];
}
export const rescanMarketplace = (source: string, userId: number) =>
  api.post<BatchImportResult>(
    `/skills/rescan-marketplace?source=${encodeURIComponent(source)}`,
    null,
    { params: { userId } },
  );
```

让后续前端设计 agent 直接 import。**不本期实现按钮 UI**。

## 验收点（AC）

- **AC-1** `curl -X POST '/api/skills/rescan-marketplace?source=clawhub&userId=<uid>'`（含 `Authorization: Bearer <token>` header）扫 `~/.openclaw/workspace/skills/` 下 3 个目录 → 响应 `imported: 3` + t_skill 多 3 个 user 行
- **AC-2** 再跑一次同样 curl → 响应 `imported: 0, updated: 3`（同 slug 已存在走 UPDATE 路径），t_skill 行数不变（仍是 3 user）
- **AC-3** 构造一个子目录无 SKILL.md → 响应 `skipped: [{name, reason: "no SKILL.md"}]`，不写 t_skill
- **AC-4** 构造跨 source 同名场景（已有 clawhub 同名 row，再 scan 当 github）→ 响应 `skipped: [{name, reason: "cross-source name conflict: ..."}]`，clawhub 那行不变
- **AC-5** 一个目录抛 IO error → 响应 `failed: [{...}]`，**其他目录继续处理**（continue 不 abort）
- **AC-6** 非法 source 值（`?source=hacker`）→ 400 + 错误提示
- **AC-7** 未登录请求 → 401（AuthInterceptor 已 enforce，与本期实现无关）
- **AC-8** 登录用户调用 → ownerId 来自 query `userId`（mirror SkillController 既有 pattern）。本期 **不强约束** token user == query userId 一致性（pre-existing 全 SkillController design gap，详见 P12-PRE follow-up "auth model upgrade"）。

## 非目标

- ❌ dashboard 按钮 UI / Modal / 提示 toast（后续单独 agent 设计前端）
- ❌ 多 source mapping config（当前 `allowed-source-roots` 是 flat list，加 TODO 注释，多 source 时再升级 config 为 root→source map）
- ❌ 自动 source 推断（用户 query param 显式传，dashboard 那边由后续设计 agent 决定 UI 怎么选 source）
- ❌ marketplace 端 cleanup（`npx clawhub` 等 CLI 不调）
- ❌ 增量 / 时间戳 / 文件 mtime 检查（每次都全量扫，幂等性已经在 importSkill 里保证）
- ❌ 后台 / 异步任务（同步 endpoint，scan 几个目录耗时可控）
- ❌ 前端按钮、对话框、toast 文案 —— 全后续

## 验证

- `mvn test -pl skillforge-server -Dtest='SkillImportServiceBatch*,SkillControllerRescanMarketplace*'` 全绿
- 手动 curl AC-1 ~ AC-7（backend running 状态）
- AC-7 由 `AuthInterceptor` enforce（pre-existing），AC-8 是 documentation contract（pre-existing design gap，本期不修，详见 P12-PRE follow-up）—— 都不重复测试
