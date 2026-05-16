# FLYWHEEL-VISUAL-STATUS PRD

---
id: FLYWHEEL-VISUAL-STATUS
status: design-draft
owner: youren
priority: P2
risk: Low
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-16
---

## 摘要

Dashboard 加 `/insights/flywheel` tab（Insights 第 5 tab，跟 OptimizationEvents / BehaviorRuleEvolution / DynamicSim 同构 (B′) embed pattern），展示完整 9 步飞轮 + 3 surface 当前状态 + drill-down 跳现有 page。

## 用户流程

1. operator 打开 Insights → Flywheel tab
2. 顶部 agent 选择器（默认显示所有 agent 聚合，或选具体 agent）
3. 中部三列 swim-lane: skill / prompt / behavior_rule，每条 surface 一列
4. 每列纵向 9 个 step bar，每个 step 显示:
   - **count** in-flight (当前在该 step 的 candidate 数)
   - **last activity timestamp**
   - **last error message** (如有 failed / rolled_back)
5. 点 step bar → 跳对应 detail page (例如 ② 聚类 跳 `/insights/patterns`, ③ 归因 跳 `/insights/optimization-events?stage=proposal_pending`, ...)
6. 底部最近 24h activity feed (timeline 时间倒序 N 条最近事件)

## 功能需求

### F1. 飞轮 9 step state aggregation BE

**不新建 BE endpoint**，FE 多 useQuery 并行拉:

| step | 数据源 endpoint | filter |
|---|---|---|
| ① 标注 | `GET /api/sessions?annotated=true&agentId=` | session count + last annotated_at |
| ② 聚类 | `GET /api/insights/patterns?agentId=&status=open` | pattern count + recent ones |
| ③ 归因 | `GET /api/attribution/events?stage=proposal_pending&agentId=` | proposal count per stage |
| ④ candidate | `GET /api/agents/{id}/skill-evolution` (合并 prompt / behavior_rule) | candidate count per stage |
| ⑤ A/B | `GET /api/skill-ab-runs?agentId=&status=running` | running A/B count |
| ⑥ Gate | (无独立 endpoint，从 stage='ab_passed' 推断) | pending publish count |
| ⑦ 灰度 | `GET /api/canary/rollouts?agentId=&stage=canary` | active canary count |
| ⑧ 回流 | embed in canary endpoint（已含 metrics） | last bucket_at |
| ⑨ 决策 | rollout_stage='production'/'rolled_back' | recent promotions / rollbacks count |

FE 端聚合 → 渲染 swim-lane。

### F2. FlywheelTimeline component

Per surface (skill / prompt / behavior_rule):
- 9 step bar 纵向布局
- 每 bar: { stepName, count, lastUpdated, hasError, errorMsg? }
- 颜色编码: in-flight (蓝) / done (绿) / failed (红) / pending (灰)
- 点 bar 跳现有 detail page

### F3. FlywheelStatusPanel (主 panel)

- 顶部 agent 选择器（option `all` / specific agent）
- 中部 3 列 FlywheelTimeline (skill / prompt / behavior_rule)
- 底部 24h activity feed (top 20 事件，时间倒序)
- 右上角 manual refresh 按钮（不做自动 polling，operator 手动控）

### F4. Insights.tsx 加 Flywheel tab

- INSIGHTS_TABS 加 'flywheel'
- activeTab handler 加 case → `<FlywheelStatusPage />`
- ~15 行 surgical change (B′ pattern)

### F5. (可选) FE config: 收起 / 展开 surface 列

operator 只关心 skill surface 时可隐藏 prompt / behavior_rule 列。localStorage 持久化。

## 非目标

- 不新建 BE endpoint (复用现有 V1-V5 endpoint)
- 不做实时刷新 (推 V5.5 DYNAMIC-SIM-LIVE-TRANSCRIPT 同款 WS broadcast 整体方案)
- 不做历史回放 / 趋势图
- 不做 cross-agent KPI 聚合
- 不动核心 7+1 BE + 3 FE 文件 (Iron Law)
- 不引入新 LLM / 新 schema

## 验收标准

- [ ] 新建 `pages/FlywheelStatus.tsx` + `components/flywheel/FlywheelTimeline.tsx` + `components/flywheel/FlywheelStatusPanel.tsx`
- [ ] Insights.tsx 加 'flywheel' tab + ~15 行
- [ ] 跨 5 endpoint 多 useQuery 并行拉数据 + FE aggregation
- [ ] 9 step swim-lane 渲染 + 颜色编码 + count + last timestamp
- [ ] 点 step bar 跳现有 detail page (5 个 drill-down link)
- [ ] 24h activity feed (top 20 events)
- [ ] FE tsc + npm build EXIT=0
- [ ] Iron Law 核心 3 FE 文件 git diff = 0
- [ ] BE 不动 (0 改动)
- [ ] 测试: FlywheelStatusPanel.test.tsx 1-2 case 锁基本渲染 + drill-down 跳路径

## 后续 backlog

- WS / SSE 实时刷新（跟 V5.5 DYNAMIC-SIM-LIVE-TRANSCRIPT 一起做）
- 历史趋势图（按 time-bucket aggregate）
- Cross-agent KPI dashboard（"全部 agent 上周提了多少 proposal / 通过多少"）
