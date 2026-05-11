# SKILL-DASHBOARD-POLISH

---
id: SKILL-DASHBOARD-POLISH
mode: lite
status: done
priority: P0
risk: Mid
created: 2026-05-08
updated: 2026-05-11
---

## 用户原话（2026-05-08）

> "现在看起来是 跑完评估之后 会有提升 然后自动切换到另一个版本。但是 没有版本对比，比如说 改了哪些 导致 有提升的。 现在卡片 同一个name的有好几个，然后 还有草稿之类的。"
>
> "这些你先都来优化吧。先搞一版看看"

## 现状缺口

SKILL-EVOLVE-LOOP 闭环已通，但 dashboard UX 缺：
1. **同 name 多卡片**：`listSkills` 返所有 row 含 disabled fork candidate，列表平铺混乱
2. **改了什么 / 为什么不可见**：`SkillEvolutionRun.improvedSkillMd` + `evolutionReasoning` 后端有，前端不渲染；parent SKILL.md 也没 endpoint
3. **A/B 决策黑盒**：`Promoted/Not promoted` Tag 不显示阈值理由
4. **没有手动 promote/rollback**：自动 promote 阈值过严或 false positive 时用户无 override
5. **Drafts 没顶层入口**：cron 自动产 draft 用户难发现
6. **同名 draft 反复产**：cron 每天扫 → similarity 没卡 exact-name → approve 必撞 unique

## 范围（V1，本次"搞一版看看"）

| Item | 实现 | scope |
|---|---|---|
| **A. 同 name 聚合** | `SkillTable` 同 name 折成 1 行（主行=enabled，副行展开看 candidate v2 / archived v1）；列加 `version` 显示 semver | FE only ~80 行 |
| **B. Evolution Diff Tab** | drawer 加 "Evolution Detail" tab：左 parent SKILL.md / 右 improved_skill_md / 中间 evolution_reasoning markdown / per-scenario A/B 表（`abScenarioResultsJson` 解析）| BE: GET `/api/skills/{id}/skill-md` 返当前 SKILL.md content（读 skill_path 下 SKILL.md）+ FE diff component (`react-diff-viewer-continued` 已有 or `monaco-editor` 已有) |
| **C. A/B 决策 Tooltip** | `SkillAbPanel` Promoted Tag hover 显: "delta=20pp ≥ 15 ✓ candidate=85% ≥ 40% ✓" 或 "Not promoted: delta=10pp < 15" | FE only ~30 行 |
| **D. 手动 Promote / Rollback** | `SkillAbPanel` 加按钮：completed 状态下 "Promote anyway"（候选 promote 同既有 logic），enabled candidate 状态下 "Rollback to v1"（candidate disable + parent enable，反向 promote）| BE: 2 endpoints `POST /api/skills/abrun/{id}/promote-manual`、`POST /api/skills/{id}/rollback` + FE 2 buttons + confirm Modal |
| **E. Drafts 顶层入口** | Layout sidebar 加 `/skill-drafts` 链接 + 现有 `SkillDraftPanel` 提升为独立页面 + unread count 红点（pending draft 数） | FE only ~80 行 |
| **F. Same-name draft skip** | `SkillDraftService.extractFromRecentSessions` line ~204 循环里 exact-name match 跳过 + log debug | BE only ~10 行 |

## V2 推迟

- Dashboard 顶层"本周自动升级 X / 待 review draft Y"概览卡（独立需求包）
- 真 merge UX（approve 时 exact-name match → "Update existing" 而非 "Create new"，独立需求包）
- 版本时间线 tree drawer（parent → v2 → v3 树形）

## 验收

- [x] SkillList 同 name 折叠：v1+v2 共 1 行，可展开看 candidates
- [x] Drawer "Evolution Detail" tab：左右 SKILL.md diff + reasoning + per-scenario 表
- [x] A/B Tag hover Tooltip 显示阈值理由
- [x] Promoted candidate row 有 "Rollback" 按钮 / Not-promoted candidate 有 "Promote anyway" 按钮
- [x] Sidebar Drafts 入口 + pending count 红点
- [x] cron 跑下次不再产同名 draft
- [x] `mvn test` 全套绿（保 1105+）
- [x] `npm run build` EXIT=0

## 交付状态

**已交付（2026-05-08，commit `5cfd03b`）**。

交付内容包括：同名 skill 聚合展示、Evolution Detail diff/推理/场景表、A/B 决策 tooltip、手动 promote/rollback、Drafts 顶层入口与 pending count、同名 draft 跳过逻辑，以及对应后端 endpoint 与测试。

## 实施

- BE 1 dev (Mid 档单一 fix 轮)
- FE 1 dev (并行)
- 完成后 Claude 主会话目检 + commit
