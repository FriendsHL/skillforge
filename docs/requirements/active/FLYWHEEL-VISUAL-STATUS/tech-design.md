# FLYWHEEL-VISUAL-STATUS 技术方案

---
id: FLYWHEEL-VISUAL-STATUS
status: design-draft
prd: ./prd.md
risk: Low
mode: mid
created: 2026-05-16
---

## TL;DR

纯 FE 工作，~2-3 工作日 Mid 档。新建 `FlywheelStatusPanel` 跨现有 5 个 BE endpoint 聚合数据 → render 9 step × 3 surface swim-lane。0 BE 改动，0 schema, 0 Iron Law 触碰。

## 现状

5 个独立 page 各管一段飞轮:

| Page | URL | 覆盖 step |
|---|---|---|
| Patterns | `/insights/patterns` | ① ② |
| Optimization Events | `/insights/optimization-events` | ③ candidate-stage |
| Skill Evolution | `agents/{id}/skill-evolution` | ④ ⑤ ⑥ ⑦ ⑧ ⑨ (skill) |
| Behavior Rule Evolution | `/insights/behavior-rules` | ④ ⑤ ⑥ (behavior_rule) |
| Dynamic Sim | `/insights/dynamic-sim` | (V5 trial transcript) |

每个 page 自己拉数据，没全局 aggregation。

## 范围决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 加 BE endpoint 还是 FE 聚合 | **FE 聚合 (多 useQuery 并行)** | 不新建 BE = 0 Iron Law 风险 + 不动 V1-V5 现有 BE 接口 |
| 渲染形态 | **3 列 swim-lane (skill / prompt / behavior_rule)** | 跟 V4 OptimizableSurface 三 surface 抽象一致；operator 容易看明白"哪条 surface 在跑" |
| 数据刷新 | **手动 refresh 按钮** | dogfood 期数据慢，不需 5s polling 浪费 LLM cost；future 接 SSE/WS 时一起改 |
| FE 接入点 | **Insights.tsx 5th tab `'flywheel'`** | 跟 V3/V4/V5 (B′) 同构 |
| 9 step → state mapping | **见下方 state 映射表** | step 颜色编码: blue=in-flight / green=done / red=failed / gray=pending |
| drill-down 链接 | **点 step bar 跳现有 detail page** | 不重复实现 detail；step → page 5 个固定 path |

## 数据源聚合方案

### per-step 数据拉取 + 渲染状态映射

```ts
interface StepState {
  step: number;            // 1-9
  surface: 'skill' | 'prompt' | 'behavior_rule';
  count: number;           // in-flight 数量
  lastUpdated: string;     // ISO timestamp
  status: 'in-flight' | 'done' | 'failed' | 'pending';
  errorMsg?: string;
  drillDownPath: string;   // 点击跳的 URL
}
```

### step 数据源 mapping

```ts
// FE 端伪代码 (新 hook useFlywheelState)
export function useFlywheelState(agentId: string | 'all'): {
  skill: StepState[];     // 9 elements
  prompt: StepState[];    // 9 elements
  behaviorRule: StepState[]; // 9 elements
} {
  // 多 useQuery 并行：
  const annotations = useQuery({...});      // GET /api/sessions?annotated=true&agentId=
  const patterns = useQuery({...});         // GET /api/insights/patterns?agentId=
  const optEvents = useQuery({...});        // GET /api/attribution/events?agentId=
  const skillEvolution = useQuery({...});   // GET /api/agents/{id}/skill-evolution (skill)
  const promptVersions = useQuery({...});   // GET /api/agents/{id}/prompt-versions
  const behaviorRules = useQuery({...});    // GET /api/behavior-rules/versions?agentId=
  const canaryRollouts = useQuery({...});   // GET /api/canary/rollouts?agentId=

  // FE 聚合: 把 9 step × 3 surface = 27 cells 算出
  return useMemo(() => aggregateFlywheel(...), [...]);
}
```

### step state 计算逻辑 (per surface)

```
step 1 标注: annotations 表 filter origin=production AND annotated=true 数量 → count
step 2 聚类: patterns 表 status=open AND surface=<S> 数量 → count
step 3 归因: optEvents stage IN (proposal_pending, proposal_approved) AND surface=<S> → count
step 4 candidate: optEvents stage IN (candidate_generating, candidate_ready) AND surface=<S> → count
step 5 A/B: optEvents stage=ab_running AND surface=<S> → count
step 6 Gate: optEvents stage=ab_passed AND surface=<S> AND NOT promoted → count
step 7 灰度: canaryRollouts surface_type=<S> AND stage=canary → count
step 8 回流: canary metrics 最近 24h bucket count
step 9 决策: optEvents stage=promoted AND surface=<S> AND created_at > 24h ago → count

(对应 step status: 有 in-flight → blue / 有 failed/rolled_back → red / 全过 → green / 无 → gray)
```

## FE 组件结构

```
pages/FlywheelStatus.tsx
  ├─ useFlywheelState(agentId) hook   ← 新增
  ├─ <Select> agent 选择器
  └─ <FlywheelStatusPanel agentId={...}>
       ├─ <FlywheelTimeline surface="skill" steps={skill}/>
       ├─ <FlywheelTimeline surface="prompt" steps={prompt}/>
       ├─ <FlywheelTimeline surface="behavior_rule" steps={behaviorRule}/>
       └─ <ActivityFeed last24hEvents={...}/>

components/flywheel/FlywheelTimeline.tsx (~150 行)
  - 渲染单 surface 的 9 step 纵向 bar
  - 颜色编码 + count + last timestamp + drill-down link

components/flywheel/ActivityFeed.tsx (~80 行)
  - 24h timeline 时间倒序 top 20 events
  - 跨 step 合并 (e.g. "ProposeOptimization fired @ 14:23 / Approve fired @ 14:25 / ...")
```

## drill-down URL mapping

```ts
const DRILL_DOWN: Record<number, (s: SurfaceType) => string> = {
  1: () => '/insights/patterns',
  2: () => '/insights/patterns',
  3: (s) => `/insights/optimization-events?stage=proposal_pending&surface=${s}`,
  4: (s) => `/insights/optimization-events?stage=candidate_ready&surface=${s}`,
  5: (s) => `/insights/optimization-events?stage=ab_running&surface=${s}`,
  6: (s) => `/insights/optimization-events?stage=ab_passed&surface=${s}`,
  7: (s) => s === 'skill' ? '/agents' : '/insights/behavior-rules', // canary 在 agents 或 BR page
  8: (s) => s === 'skill' ? '/agents' : '/insights/behavior-rules', // metric embed in canary panel
  9: (s) => `/insights/optimization-events?stage=promoted&surface=${s}`,
};
```

## 实施计划

### Phase 1.0 — 证伪 (0.5 天)

- grep 现有 endpoint 真实 response shape (注意 V1 patterns / V3 events / V4 BR / V5 sim 各自 DTO)
- 确认每个 endpoint 都能传 `agentId` 过滤
- 红测试: 写一个失败的 FlywheelStatusPanel.test.tsx 锁现状 (panel 不存在)

### Phase 1.1 — useFlywheelState hook + 聚合逻辑 (1 天)

- 新建 `hooks/useFlywheelState.ts` (~150 行)
- 多 useQuery 并行 + useMemo 聚合 27 cells
- 单元 test 1-2 case 锁 aggregation 逻辑 (mock 5 endpoint 输入 → assert 输出 shape)

### Phase 1.2 — Components + Page (1-1.5 天)

- `components/flywheel/FlywheelTimeline.tsx`
- `components/flywheel/ActivityFeed.tsx`
- `components/flywheel/FlywheelStatusPanel.tsx`
- `pages/FlywheelStatus.tsx`

### Phase 1.3 — Insights tab + 测试 + Phase Final (0.5 天)

- Insights.tsx 加 'flywheel' tab
- `FlywheelStatusPanel.test.tsx` 1-2 case (锁基本渲染 + drill-down 跳路径)
- tsc + npm build 双绿
- Iron Law 核心 3 FE 文件 git diff = 0
- 归档

## 风险与边界

### Low Risk
- 5 endpoint 并行拉 → 网络抖动时 panel 可能部分加载，需 graceful degrade (某 endpoint 失败显示该 step "?" + retry button)
- 数据 staleness (operator 手动 refresh) → tooltip 显式提示"上次拉取时间"
- agentId='all' 聚合 → BE endpoint 可能不支持 `agentId=all` 这种 query，需 FE 分别拉每个 agent 再聚合（或者 BE 加 agentId optional 改 OR 跳过 filter）。Phase 1.0 grep 实际行为。

### 已知 follow-up
- 实时刷新 (跟 DYNAMIC-SIM-LIVE-TRANSCRIPT backlog 一起 WS broadcast)
- 历史趋势图
- Cross-agent KPI

## Iron Law

- 核心 7+1 BE + 核心 3 FE 文件 git diff = 0 全程
- 0 BE 改动 (复用现有 endpoint)
- 0 schema 改动
- 0 LLM 调用 (纯 FE 聚合现有数据)

## 测试计划

- FE: `FlywheelStatusPanel.test.tsx` 1-2 case (mock 5 endpoint response → assert swim-lane 渲染 + drill-down link href)
- FE: `useFlywheelState.test.ts` 1-2 case (aggregation 逻辑)
- 全套: tsc + npm build EXIT=0
- 手动 e2e: 启 server + dashboard 真访问 → 9 step swim-lane 显示正确

## 评审记录

- 2026-05-16 创建 design-draft (基于 user "需要自动化归因完整流程图" 反馈)
