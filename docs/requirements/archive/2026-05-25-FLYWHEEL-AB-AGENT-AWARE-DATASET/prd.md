# FLYWHEEL-AB-AGENT-AWARE-DATASET — PRD

## 目标

修 BEHAVIOR-RULE-AB-EVAL V1 dogfood 暴露的 wrong-agent A/B noise — 让 behavior_rule A/B 真活只跑该 rule 适用的 agent 的 scenarios，并区分通用 benchmark 跟 agent-specific benchmark 作 target / regression 自然分桶。

## 5 决策详解（ratified）

### D1 EvalScenario 加 `applicable_agent_roles JSONB`

**方案 (a)** — EvalScenario 加 JSONB `applicable_agent_roles` 字段 (e.g. `["design","general"]`)，一题可标多个 role；不动 EvalDataset 三层 schema。

**Why not (b)** "每 agent 独立 dataset" — 通用题（GAIA）要复制到每个 agent dataset，违反单源真理 + bloat；新加 agent 要批量 INSERT 全部 generic 题。

**Why not (c)** 混合 — V1 复杂度爆炸 (要同时管 EvalDataset.agent_role + EvalScenario.applicable_agent_roles 两源真理)；先 (a) 走通验证后 V2 加 (c) 也容易。

**Schema 细节**:
```sql
ALTER TABLE t_eval_scenario
    ADD COLUMN applicable_agent_roles JSONB NOT NULL DEFAULT '[]'::jsonb;
-- GIN partial index 同 V114 rule_trigger_hints pattern
CREATE INDEX idx_eval_scenario_applicable_agent_roles_gin
    ON t_eval_scenario USING GIN (applicable_agent_roles)
    WHERE jsonb_array_length(applicable_agent_roles) > 0;
```

**Query pattern**: 复用 cc7286b hot-fix 同款 `jsonb_exists_any(applicable_agent_roles, ARRAY[...])`，**绝不用 `?|`** (Spring Data JPA 解析冲突教训)。

### D2 Agent role taxonomy: 5 个 role

| Role | 适用 agent | 典型 scenario |
|---|---|---|
| `general` | 任何 agent | GAIA, τ-bench, AgentBench 通用题 |
| `code` | Code Agent / Main Assistant 编码任务 | SWE-bench-Lite, 写函数, debug, refactor |
| `design` | Design Agent / UI 交互任务 | 前端组件设计, UX flow, 交互逻辑 |
| `research` | Research Agent / search&summarize | 信息检索 + 综合, 总结报告 |
| `main_assistant` | Main Assistant 通用对话任务 | 多步骤助理任务, 通用 Q&A |

**Constant 定义** (`AgentRoleConstants.java` 新文件):
```java
public final class AgentRoleConstants {
    public static final String GENERAL        = "general";
    public static final String CODE           = "code";
    public static final String DESIGN         = "design";
    public static final String RESEARCH       = "research";
    public static final String MAIN_ASSISTANT = "main_assistant";
    public static final Set<String> ALL = Set.of(GENERAL, CODE, DESIGN, RESEARCH, MAIN_ASSISTANT);
    private AgentRoleConstants() {}
}
```

**Per-AgentEntity role resolution** (V1 用 agent name heuristic + V117 backfill 同款逻辑):
- agent.name LIKE '%Design%' → `design`
- agent.name LIKE '%Code%' → `code`
- agent.name LIKE '%Research%' → `research`
- agent.name LIKE '%Main%' OR '%Assistant%' → `main_assistant`
- Other → fallback `general` (但 V1 不期望此情况发生，log.warn 标记)

> **V2 可考虑**: AgentEntity 加 `agent_role VARCHAR(32)` 真字段 + V118 backfill + admin UI 改。V1 用 name heuristic 是 deliberate scope-bound (避免改 AgentEntity schema 引爆其它路径)。

### D3 A/B dataset = target subset (agent-specific) + regression subset (general) union

`BehaviorRuleAbEvalService.runAsync` 改:
```
ownerRole = resolveRole(rule.agent_id)        // e.g. "design"
targetSubset = scenarios where applicable_agent_roles ∋ ownerRole
regressionSubset = scenarios where applicable_agent_roles ∋ 'general'
// 注意去重: GAIA 题被标 ["general","design"] 时会同时进 target 和 regression
// 决策: 优先归 target (rule 主战场)，regression 用 NOT IN target_ids 过滤
```

**dual-criteria 自然兼容**:
- `target_delta_pp` = candidate vs baseline on target subset (= agent-specific scenarios)
- `regression_delta_pp` = candidate vs baseline on regression subset (= general benchmark)
- Promotion gate 不变 (INV-5)：`(target>=10 OR target IS NULL) AND regression>=-3`
- 如果 target subset 仍为空 (新 agent 还没 dogfood 题) → fallback 走 general-only regression check (跟 V1 fallback 模式一致)

### D4 V117 migration backfill 现有 49 scenarios

> **r1-FIX (database reviewer)**: Research Agent 真活 `agent_id=5`（不是 4；id=4 是 Session Analyzer 无 scenarios）。V117 SQL 用 `a.name ILIKE '%research%'` JOIN 不影响 migration，但 test fixture 需 verify。

```sql
-- 30 generic benchmark (agent_id IS NULL, source_type='benchmark')
UPDATE t_eval_scenario
   SET applicable_agent_roles = '["general"]'::jsonb
 WHERE agent_id IS NULL AND source_type = 'benchmark'
   AND NOT (applicable_agent_roles @> '["general"]'::jsonb);

-- agent-specific dogfood (按 t_agent.name heuristic)
UPDATE t_eval_scenario s SET applicable_agent_roles = '["design"]'::jsonb
 FROM t_agent a WHERE a.id::text = s.agent_id AND a.name ILIKE '%design%'
   AND NOT (s.applicable_agent_roles @> '["design"]'::jsonb);

UPDATE t_eval_scenario s SET applicable_agent_roles = '["code"]'::jsonb
 FROM t_agent a WHERE a.id::text = s.agent_id AND a.name ILIKE '%code%'
   AND NOT (s.applicable_agent_roles @> '["code"]'::jsonb);

UPDATE t_eval_scenario s SET applicable_agent_roles = '["research"]'::jsonb
 FROM t_agent a WHERE a.id::text = s.agent_id AND a.name ILIKE '%research%'
   AND NOT (s.applicable_agent_roles @> '["research"]'::jsonb);

UPDATE t_eval_scenario s SET applicable_agent_roles = '["main_assistant"]'::jsonb
 FROM t_agent a WHERE a.id::text = s.agent_id
   AND (a.name ILIKE '%main%' OR a.name ILIKE '%assistant%')
   AND a.name NOT ILIKE '%design%' AND a.name NOT ILIKE '%code%' AND a.name NOT ILIKE '%research%'
   AND NOT (s.applicable_agent_roles @> '["main_assistant"]'::jsonb);

-- AC-1 enforce: 全部 49 行非空
DO $$
DECLARE missing INTEGER;
BEGIN
    SELECT COUNT(*) INTO missing FROM t_eval_scenario
        WHERE jsonb_array_length(applicable_agent_roles) = 0;
    IF missing > 0 THEN
        RAISE EXCEPTION '[V117] AC-1 violation: % scenarios lack applicable_agent_roles', missing;
    END IF;
END $$;
```

**确认计数**: 30 + 11 + 1 + 1 + 6 = 49 (跟 mrd.md 真活 query 结果一致)。

### D4.1 V2 constraint ratify (r1-FIX java-design W2)

**显式 ratify**：V2 加第 6 个 role（e.g. `testing` / `data_analysis`）**必须先做** `AgentEntity.agent_role VARCHAR(32)` 真字段 + 对应 admin UI + V118 backfill，**之后才允许扩展 AgentRoleConstants/Resolver/V_n SQL**。**禁止**在 V2 真字段 ship 前单独加新 role —— 否则 dual-source (`AgentRoleResolver` Java pattern + V117 SQL pattern + FE label/color map 3 处) drift 重蹈本需求 V1 同款风险。

### D5 V1 scope = MVP

| 做 | 不做 (V2) |
|---|---|
| V117 migration + AC-1 enforce | agent-specific 题库扩充 (SWE-bench / UI / search 题) |
| EvalScenarioEntity 加字段 + Repository 加 finder | AgentEntity.agent_role 真字段 (V1 用 name heuristic) |
| AgentRoleResolver service (name → role heuristic) | LLM-based scenario auto-classify |
| BehaviorRuleAbEvalService.runAsync 改用 D3 subset 算法 | prompt surface / skill surface 同款 agent-aware filter (先 behavior_rule 跑通) |
| BehaviorRuleAbRunResponse 加 `ownerAgentRole` 字段 | dataset diff UI |
| FE Dataset 页 role filter tab | cross-tenant sharing |
| FE OptEvents behavior_rule row 显 owner role tag | 多 role rule promotion 策略 |
| 重跑 Event #123 验证 wrong-agent fix + AC-2 ~ AC-8 真活 | observability traceId fix / WS userId fix (单独 backlog) |

## 用例

### UC-1 Event #123 retry (重跑 wrong-agent fix 验证)

```
preconditions:
- V117 已 apply → 49 scenarios applicable_agent_roles backfill
- t_eval_scenario:
    30 ['general']  (benchmark, agent_id=null)
    11 ['design']   (Design Agent dogfood, agent_id=1)
    6  ['main_assistant'] (Main Assistant dogfood, agent_id=3)
    1  ['code']     (Code Agent dogfood, agent_id=2)
    1  ['research'] (Research Agent, agent_id=5)

User clicks "Retry A/B" on Event #123 (Design Agent rule)
System (BehaviorRuleAbEvalService.runAsync):
- resolveRole(agent_id=1) → "design"  (from agent.name "Design Agent")
- targetSubset = WHERE applicable_agent_roles ∋ 'design' → 11 scenarios
- regressionSubset = WHERE applicable_agent_roles ∋ 'general' AND id NOT IN target_ids → 30
  - (假设没题同时标 design + general，否则去重)
- target_count = 11, regression_count = 30, total = 41 (不再 49)
- 跑 41 scenarios × 2 (baseline + candidate) ≈ 18-22 分钟
- target_delta_pp 真活算出
- regression_delta_pp 真活算出

预期结果: target_delta_pp 揭示该 rule 对 Design Agent 的真实影响，
regression check 仍 pass (general subset 不该受 design-specific rule 影响)
```

### UC-2 Dataset 页 role filter (新 FE 入口)

```
User opens /eval/datasets → DatasetBrowser
Now sees segmented tabs: [All] [General] [Design] [Code] [Research] [Main Assistant]
Click "Design" → 只显 applicable_agent_roles ∋ 'design' 的 scenarios
Useful for: operator 看哪 agent 有多少 scenarios + 评估 dataset coverage
```

### UC-3 OptEvents behavior_rule row 显 owner role

```
OptimizationEvents page Event #123 row Actions 列:
- 之前: [Retry A/B] / [Promote v1] / spinner
- 现在: [Design Agent] tag + [Retry A/B] / [Promote v1] / spinner
让 operator 一眼看出 rule 是给哪个 agent 用的
```

### UC-4 Edge case — target subset 仍为空 (fallback regression-only)

```
User attribution-approve 一个 generic-role agent (e.g. 新 agent 还没 dogfood 题)
→ resolveRole 返 "general" (fallback)
→ targetSubset = scenarios ∋ 'general' (跟 regression overlap)
→ 决策: 当 ownerRole == 'general' 时直接 fallback regression-only mode
  (跟 BEHAVIOR-RULE-AB-EVAL V1 fallback 一致)
```

## 关键不变量

| INV | 描述 |
|---|---|
| **INV-1** | V117 apply 后 `SELECT COUNT(*) FROM t_eval_scenario WHERE jsonb_array_length(applicable_agent_roles) = 0` = 0 (AC-1 enforce via DO $$ RAISE EXCEPTION) |
| **INV-2** | resolveRole(agent_id) 必返 5 个 role 之一 (含 fallback `general`)，绝不返 null |
| **INV-3** | target subset + regression subset 计 41 (或动态值) 不会含 wrong-agent scenarios — UT 校 + AC-5 真活 log 不含 wrong agent name |
| **INV-4** | dual-criteria 公式 (INV-5 from V1) 不变；只改 subset 定义 |
| **INV-5** | jsonb_exists_any 函数使用 — 不准用 `?|` 操作符 (Spring Data JPA 冲突教训) |
| **INV-6** | Iron Law: 核心 7+1 BE + 核心 3 FE git diff = 0 (本需求只改 EvalScenarioEntity/Repository/BehaviorRuleAbEvalService/Controller/DTO/FE optimization 组件) |

## 风险与回滚

| 风险 | 应对 |
|---|---|
| V117 backfill 名字 heuristic 不全 (新 agent 名字不在 5 类) | AC-1 enforce 跑挂 → operator 手动 patch V117 加 ILIKE 模式 |
| AgentRoleResolver name heuristic 跟 V117 SQL 双源真理 drift | Constants 集中放 `AgentRoleConstants` + V117 SQL pattern 跟 Java resolver pattern 内嵌注释 cross-ref |
| 重跑 Event #123 时旧 ab_run 状态污染 | INV-6 (V1) 已实现: `findFirstByCandidateVersionIdAndStatusIn` 把老 RUNNING/PENDING run mark SUPERSEDED, 跑 new run |
| target subset overlap regression (一题同时标 design+general) | 去重: regression = scenarios ∋ 'general' AND id NOT IN target_ids |
| Prompt surface / skill surface 不接同款 fix | V1 显式 not in scope; V2 扩 |
| FE Dataset 页 role tab 跟现有 source_type tab 冲突 | UX: 2 个 tabbar 独立 (source_type 上 / role 下), 类似已有 SegmentedControl 嵌套 |

## 跟其它需求关系

- 依赖 **BEHAVIOR-RULE-AB-EVAL V1** ✅ (已交付): BehaviorRuleAbEvalService / runWithExplicitDefs / dual-criteria / V114-V116 schema
- 依赖 **EVAL-DATASET-LAYER V1** ✅ (已交付): EvalScenarioEntity / EvalDataset 三层
- 不依赖 V2 backlog: AgentEntity.agent_role 字段 (V1 用 name heuristic 绕过)
