# FLYWHEEL-VISUAL-STATUS MRD

---
id: FLYWHEEL-VISUAL-STATUS
status: design-draft
created: 2026-05-16
---

## 痛点

V1-V5 累计交付完整 9 步飞轮：
```
① 标注 (V1) → ② 聚类 (V1) → ③ 归因 (V3) → ④ candidate 生成 (V3+V4) →
⑤ A/B 评测 (V2+V5 Phase 1) → ⑥ Gate (V2 auto-promote) → ⑦ 灰度 (V2+V4) →
⑧ 回流 (V2) → ⑨ 决策 (V2+V3) → 回 ①
```

每步对应一张表 + 一个 page：

| 步 | 数据源 | dashboard page |
|---|---|---|
| ① 标注 | t_session_annotation | `/insights/patterns` (cluster) |
| ② 聚类 | t_session_pattern | `/insights/patterns` |
| ③ 归因 | t_optimization_event | `/insights/optimization-events` |
| ④ candidate | t_skill_draft / t_prompt_version / t_behavior_rule_version | `agents/{id}/skill-evolution` 或 `/insights/behavior-rules` |
| ⑤ A/B | t_skill_ab_run / t_prompt_ab_run | `agents/{id}/skill-evolution` |
| ⑥ Gate | auto_promote config + manual publish | (no UI) |
| ⑦ 灰度 | t_canary_rollout | embed in SkillAbPanel / BehaviorRuleEvolution |
| ⑧ 回流 | t_canary_metric_snapshot | embed in SkillAbPanel |
| ⑨ 决策 | rollout_stage='production'/'rolled_back' | embed in SkillAbPanel |

**operator 痛点 3 条**:

1. **跨页面拼凑** — 想看 "现在哪些 candidate 跑到了哪步" 需要打开 5 个独立 page 比对，没有全局视图
2. **状态不直观** — 例如某个 attribution event 在 `proposal_pending` → 怎么去看是哪个 pattern？哪个 agent？需要 click drawer 才看到，无横向对比
3. **3 surface 状态混杂** — skill / prompt / behavior_rule 各有自己的 candidate / A/B / canary，operator 想看 "agent X 当前哪条 surface 在跑 candidate" 需要分别去 3 个地方查

## 用户场景

### 场景 A: stakeholder demo 时一眼讲清飞轮

```
用户: "你做的这个 SkillForge 整体优化流程是怎么自动跑的？"
operator: "你看这个 dashboard 一眼能看明白每一步…"

(打开 dashboard，能直接看到 9 步 + 当前每步有几个 candidate / 哪些 stage)
```

V1-V5 累计交付的真实"飞轮"价值，需要可视化才能讲清。

### 场景 B: operator daily check 看飞轮跑得对不对

```
operator 每天打开 dashboard 看：
- attribution-curator 半点 cron 跑过没？产出多少 proposal？
- pending approval 有几条等审？
- 上周 approve 的 candidate 现在跑到 A/B 哪步？
- canary 在跑的有几条？指标怎么样？
- 有没有失败 / 卡住的 stage？
```

不用每天点 5 个 page。

### 场景 C: dogfood 数据少，调 cron 频率 / risk threshold 时看效果

```
operator 改了 attribution-curator system prompt 加了 behavior_rule 决策表
→ 飞轮可视化看 surface=behavior_rule 的 proposal 触发率有没有上涨
→ 看 candidate 生成质量
→ 看 A/B 通过率
```

V5 完成后调 dogfood 时强烈需要这种 surface-by-surface metrics view。

## 不在 MRD 范围内

- **不做实时刷新** (SSE/polling 推到 V5.5 follow-up)
- **不做历史回放** (按时间轴看过去 N 天的飞轮变化)
- **不做跨 agent 聚合** (per-agent 视角，不做 cross-agent KPI)
- **不替换** 5 个独立 page (FlywheelStatus 是补全总览，drill-down 仍跳现有 page)

## 用户 quote / 决策来源

- 2026-05-16 与 user 对话: "后续我需要一个 自动化归因的完整流程图。能够看到 到哪步骤了"
- 推荐"现在建需求包 + 后续另起 team 实施"
