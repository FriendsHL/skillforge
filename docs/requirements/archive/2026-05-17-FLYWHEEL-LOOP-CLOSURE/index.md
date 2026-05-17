# FLYWHEEL-LOOP-CLOSURE 飞轮 ⑤ A/B 闭环真接 + canary 暂时砍掉

---
id: FLYWHEEL-LOOP-CLOSURE
mode: mid → upgraded full
status: delivered
priority: P1
risk: Mid → upgraded Full (Phase 1.4 NEEDS_CONTEXT 后用户拍板升 Full)
created: 2026-05-16
updated: 2026-05-17
completed: 2026-05-17
commits: pending (Phase Final commit)
---

## 摘要

V1-V5 完整交付飞轮 9 步，但 ③→④ 之间 + ⑤ A/B 这一环架构上**有真 gap**：

1. V3 attribution candidate 没 A/B endpoint（attribution candidate 没 baseline pass_rate 比较起点，不接现有 V2 eval-driven A/B path）
2. SkillDraft attribution 路径是 stub（only description, no actual SKILL.md content）
3. candidate_ready → A/B 没自动 trigger (need @EventListener)
4. attribution-curator 这种 system agent 没 EvalScenario 不能 A/B (need fallback: 从 pattern.members 动态抽 session 生成 临时 scenario)

同时 user 决定 **dogfood 单用户阶段暂时砍掉 canary**（V2 + V4 canary path），等多用户阶段或真需要灰度时再加回来。本期不删 V2/V4 canary 代码 (logic disable + dormant)，未来加回 1 行 toggle。

## 范围

Mid 档，~4-6 工作日:

### A. 飞轮 ⑤ A/B 闭环修 (3 缺口 + 1 leg = 4 子任务)

1. 加 `POST /api/agents/{id}/prompt-versions/{versionId}/run-ab` endpoint (attribution path 走这个)
2. 同款加 skill `POST /api/agents/{id}/skill-drafts/{id}/run-ab`
3. `SkillDraftService.createDraftFromAttribution` 加 sync LLM fill（跟 V3.1 PromptImproverService 同款）
4. 加 `@EventListener` 接 OptimizationEvent stage_change → candidate_ready 自动调 run-ab
5. `/run-ab` endpoint 加 fallback: 如果 agent 没 EvalScenario, 从 event.patternId 关联的 t_session_pattern.members 动态抽 N (default 3) session 生成临时 EvalScenario set

### B. canary 暂时砍掉 (logic disable，不删 code)

1. FE 隐藏 canary UI: SkillAbPanel 不 embed CanaryPanel / 没 Run Canary 按钮 / Insights 不显示 canary tab
2. BE V3 `AttributionApprovalService.ALLOWED_TRANSITIONS` 跳过 canary: ab_passed → promoted 直接（不经 canary_started → canary_metric → ...）
3. `t_scheduled_task` UPDATE `enabled=false WHERE name='metrics-collector-hourly'`
4. **保留**: t_canary_rollout / t_canary_metric_snapshot schema + V2 CanaryRolloutService / Controller / metrics-collector seed (dormant)
5. 未来加回灰度时只需打开 FE Canary UI 入口 + UPDATE cron enabled=true (~2 行改 1 SQL)

## 不在范围内

- **不动 V4 OptimizableSurface / AbstractAbEvalRunner 接口** (Iron Law)
- **不动核心 7+1 BE + 3 FE 文件**
- **不删 V2/V4 canary code** (logic disable，未来加回容易)
- **不引入新 LLM cost** (复用现有 EvalEngine multi-turn judge)
- **不做 EVAL-FEEDBACK-LOOP (V5 transcript → 打标+归因+调 candidate 回路)** —— 那是更深的 V6.x 项

## 阅读顺序

1. [MRD](mrd.md) — 痛点详述 + dogfood 测试断点
2. [PRD](prd.md) — 范围 + 4 ratify + 5 子任务验收点
3. [技术方案](tech-design.md) — 4 缺口修补方案 + canary disable 详细
