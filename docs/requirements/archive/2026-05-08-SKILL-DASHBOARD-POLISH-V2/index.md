# SKILL-DASHBOARD-POLISH-V2

---
id: SKILL-DASHBOARD-POLISH-V2
mode: lite
status: done
priority: P1
risk: Mid
created: 2026-05-08
updated: 2026-05-11
---

## 背景

接 [SKILL-DASHBOARD-POLISH](../2026-05-08-SKILL-DASHBOARD-POLISH/)（V1 commit `5cfd03b`）。V1 解决 6 个 P0/P1 缺口；V2 收尾 3 项 P2/P3 优化。

## 范围

### G. Dashboard 概览卡（P2）

`Dashboard.tsx` 顶部新增 1 个 SkillSummaryCard：
- 本周自动升级 X（promoted ab runs created_at > now-7d AND triggeredByUserId=0L 即系统）
- 待 review pending drafts Y（status=draft）
- 本周 failed evolve Z（SkillEvolutionRun status=FAILED created_at > now-7d）
- 全部 enabled skill 数 N
- Low-score skill 数 M（latest history composite < 60）

**BE**: 新 endpoint `GET /api/dashboard/skill-summary?userId=X` 返聚合 stats（5 字段）。复用现有 repo（`SkillRepository.findByOwnerId`、`SkillEvalHistoryRepository.findFirstBySkillIdOrderByCreatedAtDesc`、`SkillEvolutionRunRepository`、`SkillAbRunRepository`、`SkillDraftRepository`）。

**FE**: 新组件 `DashboardSummaryCard.tsx` mount 在 Dashboard 页顶部 grid。每个数字 click 跳到对应 deep-link（auto upgraded → `/skills?filter=auto-upgraded`、pending drafts → `/skill-drafts`、failed evolve → `/skills?filter=failed-evolve`）。

如果 `Dashboard.tsx` 不存在 → 选 SkillList 顶部加，避免新建顶层路由。grep 项目结构确认。

### H. 真 merge UX（P2）

**当前问题**：`SkillDraftService.approveDraft` 同 name 撞 → 撤销 throw；用户没有"merge"路径，唯一选择是 reject draft。

**新行为**：approve flow 检测 exact-name match：
- 不撞高相似度 ≥ 0.85 → 走原 forceCreate gate（不变）
- exact-name match (case-insensitive) → return 409 `{ "error": "name_conflict", "existingSkillId": X }` (不写 skill, 不改 draft status)
- FE 弹 Modal：「Skill `X` exists, what to do?」3 选项：
  - **Update existing** → 调新 endpoint `POST /api/skill-drafts/{id}/merge?targetSkillId=X` → 写 draft 的 description/triggers/promptHint/SKILL.md 到 existing skill (用 SkillCreatorService.render 重写 SKILL.md)，draft.status=approved + skill_id=existingSkillId
  - **Rename and create new** → 用户输入新 name，draft.name 改后 retry approve
  - **Reject draft** → draft.status=rejected

**BE**:
- `SkillDraftController` 加 `POST /skill-drafts/{id}/merge?targetSkillId=X&reviewedBy=Y`
- `SkillDraftService.mergeIntoExistingSkill(draftId, targetSkillId, reviewedBy)`：
  - lock draft + validate status
  - load target skill
  - rewrite SKILL.md to target.skillPath using `SkillCreatorService.render(draft, targetPath)`
  - update target skill description / triggers / promptHint
  - draft.status=approved + skill_id=targetSkillId + reviewedAt + reviewedBy
- `SkillDraftService.approveDraft` 加预检测：循环 existingSkills，如有 exact-name match → throw `SkillNameConflictException(message, name, existingSkillId)`（已有 exception 类型，可 enrich 加 existingSkillId 字段）

**FE**:
- `SkillDraftPanel`/`SkillDraftsSection` approve handler catch 409 → Modal 3 选项
- API client 加 `mergeIntoExistingSkill(draftId, targetSkillId, userId)`

### I. 版本时间线 tree（P3）

drawer 加 "Version Tree" tab：
- 取当前 skill 的 ancestor chain：从 skill.parentSkillId 一直递归到 root（max 10 层防 infinite loop）
- 取 descendants：from current skill 反查所有 children（`findByParentSkillId`）+ 递归
- 渲染：vertical tree ascii / 简单缩进列表，每节点 line：
  ```
  v1 [archived] · 2026-05-08 · 50 score · [Open]
  └─ v2 [active]   · 2026-05-08 · 75 score · ← current
     └─ v3 [draft] · 2026-05-08 · — score · [Open]
  ```
- "Open" link 切换 drawer 的 currentSkillId

**BE**: 新 endpoint `GET /api/skills/{id}/version-tree` 返完整树结构（含 children 链）+ 每节点附 latest history score（join `t_skill_eval_history`）。

**FE**: 新组件 `SkillVersionTree.tsx` 在 SkillDrawer 加 "Version Tree" tab；解析 BE 树形 JSON 渲染（递归 React 组件 / 简单 indent）。

## V3 推迟

- Dashboard 概览卡的 deep-link filter 实际生效（FE filter UI 在 SkillList 加）
- merge UX 的 SKILL.md side-by-side preview before merge
- Version Tree 节点显示 evolution_reasoning preview

## 验收

- [x] Dashboard / SkillList 顶部新增概览卡 + 5 个数字
- [x] approve draft 撞同名 → Modal 3 选项（Update existing / Rename / Reject）
- [x] Update existing → 写 SKILL.md 到 target 目录 + draft 标 approved
- [x] drawer "Version Tree" tab 显示 ancestor + descendant 链
- [x] `mvn test` 全套绿（保 1120+）
- [x] `npm run build` EXIT=0

## 交付状态

**已交付（2026-05-08，commit `311ff34`）**。

交付内容包括：Dashboard skill summary card、draft 同名冲突 merge UX、version tree endpoint + drawer tab，以及对应 service/controller/frontend 测试。

## 实施

- BE 1 dev (Mid 单一 fix 轮)
- FE 1 dev (并行)
- 完成后主会话目检 + commit
