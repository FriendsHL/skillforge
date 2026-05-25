# FLYWHEEL-AB-AGENT-AWARE-DATASET — Tech Design

> 实现层 — schema / entity / service / REST / FE / 测试。
> 5 决策详见 [prd.md](prd.md)。

## §0 现状速查（侦察结论 + 复用点）

| 触点 | 现状 | 影响 |
|---|---|---|
| `EvalScenarioEntity.java` | 已有 `ruleTriggerHints JSONB List<String>` (V114 + BEHAVIOR-RULE-AB-EVAL V1) | 加同款 pattern `applicableAgentRoles` |
| `EvalScenarioDraftRepository.java` | 3 个 native query 用 `jsonb_exists_any(rule_trigger_hints, CAST(:tags AS text[]))` (cc7286b hot-fix 后) | 加 3 个同款 finder by `applicable_agent_roles` |
| `BehaviorRuleAbEvalService.runAsync` | line ~210 用 candidate.target_trigger_tags 区分 target/regression subset | 改用 `ownerRole` + `general` 作 subset split keys (D3) |
| `BehaviorRuleAbRunResponse` | 19 字段（含 scenarioResults from C3）| 加 `ownerAgentRole` 字段 (D5) |
| `t_agent.name` | "Design Agent" / "Code Agent" / "Research Agent" / "Main Assistant" 等 | name → role heuristic V1 用 (V2 加 agent_role 真字段) |
| `BehaviorRuleAbBadge.tsx` / `BehaviorRuleAbRowActions.tsx` | render 8 分支 | 加 ownerAgentRole tag |
| `EvalDatasets.tsx` / `DatasetBrowser` | 已有 source_type 4 tab segmented | 加 role 5 tab segmented (并列另一行) |
| V114 → V116 已用 V1 | V114/V115/V116 migration applied | 下一 migration 序号 V117 |

**关键洞察**:
- BEHAVIOR-RULE-AB-EVAL V1 已建立 JSONB+GIN+jsonb_exists_any pattern 全套路径 → 本需求 100% 复用，schema 改动 + Java 改动都是 same pattern same file
- 不需要新 service 文件 (AgentRoleResolver 可以放 BehaviorRuleAbEvalService 同包或独立 util)
- 不动 AgentEntity / AgentService (V1 用 name heuristic, V2 才加 agent_role 字段)

---

## §1 Schema (V117)

### V117__eval_scenario_add_applicable_agent_roles.sql

```sql
-- FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (2026-05-25): give every EvalScenario
-- an applicable_agent_roles JSONB tag list that BehaviorRuleAbEvalService
-- consumes to split a dataset into (target subset = scenarios matching
-- rule_owner_agent's role) + (regression subset = scenarios tagged
-- 'general'). Mirrors V114 rule_trigger_hints pattern (JSONB + GIN partial
-- index + ?| operator → jsonb_exists_any() function call to avoid Spring
-- Data JPA placeholder conflict, see cc7286b hot-fix).
--
-- Backfill the 49 existing scenarios via name heuristic on t_agent (V1 uses
-- agent.name LIKE patterns; V2 may add t_agent.agent_role real column).
-- Expected distribution (verified via dogfood query 2026-05-25):
--   30 ['general']         (agent_id IS NULL + source_type='benchmark')
--   11 ['design']          (Design Agent dogfood)
--    6 ['main_assistant']  (Main Assistant dogfood)
--    1 ['code']            (Code Agent dogfood)
--    1 ['research']        (Research Agent dogfood)
--    -----
--   49 total → AC-1 enforce via DO $$ RAISE EXCEPTION if any row stays empty

ALTER TABLE t_eval_scenario
    ADD COLUMN IF NOT EXISTS applicable_agent_roles JSONB NOT NULL DEFAULT '[]'::jsonb;

-- GIN partial index — same pattern as V114 rule_trigger_hints. Partial because
-- vast majority of empty arrays would bloat the index for no use.
CREATE INDEX IF NOT EXISTS idx_eval_scenario_applicable_agent_roles_gin
    ON t_eval_scenario USING GIN (applicable_agent_roles)
    WHERE jsonb_array_length(applicable_agent_roles) > 0;

-- ─── Backfill (idempotent, JSONB || concat + NOT @> guard, V116 pattern) ───

-- 1) Generic benchmark scenarios (agent_id IS NULL + source_type='benchmark')
--    → 'general'. 30 rows expected.
UPDATE t_eval_scenario
   SET applicable_agent_roles = applicable_agent_roles || '["general"]'::jsonb
 WHERE agent_id IS NULL AND source_type = 'benchmark'
   AND NOT (applicable_agent_roles @> '["general"]'::jsonb);

-- 2) Design Agent dogfood → 'design'. 11 rows expected.
UPDATE t_eval_scenario s
   SET applicable_agent_roles = s.applicable_agent_roles || '["design"]'::jsonb
  FROM t_agent a
 WHERE a.id::text = s.agent_id
   AND a.name ILIKE '%design%'
   AND NOT (s.applicable_agent_roles @> '["design"]'::jsonb);

-- 3) Code Agent dogfood → 'code'. 1 row expected.
UPDATE t_eval_scenario s
   SET applicable_agent_roles = s.applicable_agent_roles || '["code"]'::jsonb
  FROM t_agent a
 WHERE a.id::text = s.agent_id
   AND a.name ILIKE '%code%'
   AND NOT (s.applicable_agent_roles @> '["code"]'::jsonb);

-- 4) Research Agent dogfood → 'research'. 1 row expected.
UPDATE t_eval_scenario s
   SET applicable_agent_roles = s.applicable_agent_roles || '["research"]'::jsonb
  FROM t_agent a
 WHERE a.id::text = s.agent_id
   AND a.name ILIKE '%research%'
   AND NOT (s.applicable_agent_roles @> '["research"]'::jsonb);

-- 5) Main Assistant dogfood → 'main_assistant'. 6 rows expected.
--    Negative ILIKE guards prevent matching "Design", "Code", "Research"
--    that may contain "main" substring (none currently do; defensive).
UPDATE t_eval_scenario s
   SET applicable_agent_roles = s.applicable_agent_roles || '["main_assistant"]'::jsonb
  FROM t_agent a
 WHERE a.id::text = s.agent_id
   AND (a.name ILIKE '%main%' OR a.name ILIKE '%assistant%')
   AND a.name NOT ILIKE '%design%'
   AND a.name NOT ILIKE '%code%'
   AND a.name NOT ILIKE '%research%'
   AND NOT (s.applicable_agent_roles @> '["main_assistant"]'::jsonb);

-- AC-1 enforce: ALL 49 scenarios must have non-empty applicable_agent_roles.
-- Migration fails loudly if any row slipped through the 5 UPDATE patterns —
-- forces operator to extend heuristics before deploy (no silent backfill loss).
DO $$
DECLARE missing INTEGER; total INTEGER;
BEGIN
    SELECT COUNT(*) INTO missing FROM t_eval_scenario
        WHERE jsonb_array_length(applicable_agent_roles) = 0;
    SELECT COUNT(*) INTO total FROM t_eval_scenario;
    IF missing > 0 THEN
        RAISE EXCEPTION '[V117] AC-1 violation: % / % scenarios lack applicable_agent_roles. '
                        'Check ILIKE pattern coverage + t_agent.name conventions.', missing, total;
    END IF;
    RAISE NOTICE '[V117] backfill complete: %/% scenarios tagged (AC-1 OK)', total - missing, total;
END $$;
```

---

## §2 Java Entity / Repository 改动

### EvalScenarioEntity.java
```java
// 加紧贴 ruleTriggerHints 字段（同款 pattern）
@Column(name = "applicable_agent_roles", columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
private List<String> applicableAgentRoles = new ArrayList<>();

public List<String> getApplicableAgentRoles() { return applicableAgentRoles; }
public void setApplicableAgentRoles(List<String> v) {
    this.applicableAgentRoles = v == null ? new ArrayList<>() : v;
}
```

### EvalScenarioDraftRepository.java — 加 2 native query (复用 cc7286b 同款 jsonb_exists_any)

> **r1-FIX (architect B2 / database W2)**: 原 design 加 3 个 query 含 `findGeneralRegressionByDatasetVersionExcluding(... Collection<String> excludeIds)` — Hibernate 6 + Spring Data JPA native query 绑定 `Collection<String>` 给 PG `NOT IN (:excludeIds)` 在 empty/非 String[]-form 有 type 风险。**改用 in-Java 内存 filter** —— V1 scale ≤49 scenarios, 30 general 题 filter 11 target id 是 O(n) trivial，避免 JPA binding footgun，同时不需要第 3 个 query。

```java
// 紧贴 findTargetSubsetByDatasetVersionAndTags / findRegressionSubsetByDatasetVersionAndTags

@Query(value = """
        SELECT s.* FROM t_eval_scenario s
        JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
        WHERE b.dataset_version_id = :datasetVersionId
          AND jsonb_exists_any(s.applicable_agent_roles, CAST(:roles AS text[]))
        """, nativeQuery = true)
List<EvalScenarioEntity> findByDatasetVersionAndAgentRoles(
        @Param("datasetVersionId") String datasetVersionId,
        @Param("roles") String[] roles);

// FE filter (per Dataset 页 role tab; loads all scenarios regardless of dataset)
// 同时被 BehaviorRuleAbEvalService.runAsync 当作 general regression subset 的源
// (caller in-Java filter 出 target ids 即可)。V2 dataset 量级 >1000 题时考虑
// 加 NOT EXISTS / CTE 形式的 server-side filter (避免 in-Java N^2)，V1 不做。
@Query(value = """
        SELECT s.* FROM t_eval_scenario s
        WHERE jsonb_exists_any(s.applicable_agent_roles, CAST(:roles AS text[]))
        ORDER BY s.created_at DESC
        """, nativeQuery = true)
List<EvalScenarioEntity> findAllByAgentRoles(@Param("roles") String[] roles);

// V1 复用现有 findAllByDatasetVersionId(BEHAVIOR-RULE-AB-EVAL V1 已加) 作 general
// regression 源 + in-Java filter 排除 target ids. 不另起 finder.
```

**INV-5 守护**: 2 个 query 全用 `jsonb_exists_any()` 函数 — 不准 `?|` 操作符（cc7286b 教训）。

---

## §3 Service 层

### §3.1 AgentRoleConstants (新 util class)

`com.skillforge.server.improve.behavior.AgentRoleConstants`

```java
/**
 * Closed set of 5 agent roles used by FLYWHEEL-AB-AGENT-AWARE-DATASET V1.
 * V2 may add agent_role real column on AgentEntity; until then AgentRoleResolver
 * maps t_agent.name via ILIKE patterns. SQL backfill in V117 uses the same
 * patterns — KEEP THE TWO IN SYNC (V1 known dual-source drift risk).
 */
public final class AgentRoleConstants {
    public static final String GENERAL        = "general";
    public static final String CODE           = "code";
    public static final String DESIGN         = "design";
    public static final String RESEARCH       = "research";
    public static final String MAIN_ASSISTANT = "main_assistant";

    public static final Set<String> ALL = Set.of(
        GENERAL, CODE, DESIGN, RESEARCH, MAIN_ASSISTANT);

    private AgentRoleConstants() {}
}
```

### §3.2 AgentRoleResolver (新 util class)

`com.skillforge.server.improve.behavior.AgentRoleResolver`

```java
/**
 * Map AgentEntity → role string via name heuristic. V1 dual source with
 * V117 SQL backfill — pattern order MUST match V117 5 UPDATE blocks:
 *   1. design     (ILIKE '%design%')
 *   2. code       (ILIKE '%code%')
 *   3. research   (ILIKE '%research%')
 *   4. main_assistant  (ILIKE '%main%' OR '%assistant%' AND NOT 上面 3 个)
 *   5. general    (fallback - unknown agent names log.warn)
 *
 * INV-2 (prd.md): never returns null; unknown → GENERAL fallback + warn.
 */
@Component
public class AgentRoleResolver {
    private static final Logger log = LoggerFactory.getLogger(AgentRoleResolver.class);

    public String resolveRole(AgentEntity agent) {
        if (agent == null || agent.getName() == null) return AgentRoleConstants.GENERAL;
        String name = agent.getName().toLowerCase(Locale.ROOT);
        if (name.contains("design"))   return AgentRoleConstants.DESIGN;
        if (name.contains("code"))     return AgentRoleConstants.CODE;
        if (name.contains("research")) return AgentRoleConstants.RESEARCH;
        if (name.contains("main") || name.contains("assistant"))
            return AgentRoleConstants.MAIN_ASSISTANT;
        log.warn("[AgentRoleResolver] unknown agent name='{}' agent_id={} → fallback GENERAL. "
                + "Consider extending heuristic + V118 backfill if this agent will have dogfood scenarios.",
                agent.getName(), agent.getId());
        return AgentRoleConstants.GENERAL;
    }
}
```

### §3.3 BehaviorRuleAbEvalService.runAsync 改造

替换原 subset split 逻辑：

```java
// 原 V1 (用 candidate.target_trigger_tags):
//   List<String> tags = candidate.getTargetTriggerTags();
//   if (tags == null || tags.isEmpty()) { targetSubset = []; regressionSubset = all; }
//   else { targetSubset = findTargetSubsetByTags(tags); regressionSubset = findNotInTags(tags); }
//
// V1.1 (本需求):

AgentEntity ownerAgent = agentRepository.findById(Long.valueOf(candidate.getAgentId()))
    .orElseThrow(() -> new IllegalStateException("agent not found: " + candidate.getAgentId()));
String ownerRole = agentRoleResolver.resolveRole(ownerAgent);

List<EvalScenarioEntity> targetSubset;
List<EvalScenarioEntity> regressionSubset;

if (AgentRoleConstants.GENERAL.equals(ownerRole)) {
    // Edge case (UC-4): owner is generic → fallback regression-only mode
    // (跟 BEHAVIOR-RULE-AB-EVAL V1 fallback 一致)
    log.info("[BehaviorRuleAb] owner role=general for versionId={}, "
        + "running in regression-only mode (full dataset)", candidate.getId());
    targetSubset = List.of();
    regressionSubset = scenarioRepository.findAllByDatasetVersionId(abRun.getDatasetVersionId());
} else {
    targetSubset = scenarioRepository.findByDatasetVersionAndAgentRoles(
        abRun.getDatasetVersionId(), new String[]{ownerRole});
    Set<String> targetIds = targetSubset.stream().map(EvalScenarioEntity::getId)
        .collect(Collectors.toSet());
    if (targetIds.isEmpty()) {
        // No target scenarios for this agent role → fall back like above
        log.info("[BehaviorRuleAb] no scenarios match role={} for versionId={}, "
            + "running regression-only", ownerRole, candidate.getId());
        regressionSubset = scenarioRepository.findAllByDatasetVersionId(abRun.getDatasetVersionId());
    } else {
        // ★ r1-FIX (architect B2 / database W2): in-Java filter 替代
        //   native NOT IN (:excludeIds) 避开 Hibernate 6 native query
        //   Collection<String> binding footgun. V1 scale (30 general)
        //   filter cost = O(n) trivial, V2 >1000 题时改 NOT EXISTS / CTE.
        List<EvalScenarioEntity> generalAll = scenarioRepository
            .findByDatasetVersionAndAgentRoles(
                abRun.getDatasetVersionId(),
                new String[]{AgentRoleConstants.GENERAL});
        regressionSubset = generalAll.stream()
            .filter(s -> !targetIds.contains(s.getId()))
            .toList();
    }
    log.info("[BehaviorRuleAb] role-aware subset: ownerRole={} target_n={} regression_n={} "
        + "(versionId={})", ownerRole, targetSubset.size(), regressionSubset.size(),
        candidate.getId());
}

abRun.setTargetCount(targetSubset.size());
abRun.setRegressionCount(regressionSubset.size());
// ... rest of runAsync 同 V1 (build baselineDef + candidateDef, runWithExplicitDefs, 算 delta, etc.)
```

**INV-3 守护**: log 输出 `ownerRole=design target_n=11 regression_n=30` 真活验证不再含 wrong-agent scenarios (AC-5)。

**注意**: candidate.targetTriggerTags (V1 字段) **仍保留**不删 — 别的潜在用法可能需要 (将来 V2 layered tag system 加回 rule-level filter)，本需求只是**忽略** targetTriggerTags 改走 ownerRole 优先。如果 V2 真需要 rule-level fine-grained filter 再合并两者。

### §3.4 BehaviorRuleAbRunResponse 加 ownerAgentRole 字段

> **r1-FIX (java-design W1 / architect W2)**: 原 design 在 `from()` 静态工厂内调用 `agentRepo.findById()` —— DTO 吸收 repository 依赖是抽象泄漏 + N+1 风险 + 测试需 mock repo。**改为 caller (Service/Controller 层) 预先 resolve role**，传 `String ownerAgentRole` 进 `from()`，DTO 还原为纯 mapper。

```java
public record BehaviorRuleAbRunResponse(
    String id, String agentId, String candidateVersionId,
    String status, String abRunKind,
    // ... 同 V1 ...
    String ownerAgentRole,    // ← 新加: D5 / AC-8 FE 显 owner role tag
    List<AbScenarioResult> scenarioResults
) {
    /** r1-FIX: pure mapper，role 由 caller 预先 resolve 传入。 */
    public static BehaviorRuleAbRunResponse from(BehaviorRuleAbRunEntity e,
                                                  ObjectMapper objectMapper,
                                                  String ownerAgentRole) {
        // ... 同 V1 build response，additionally fill ownerAgentRole=ownerAgentRole
    }
}

// Caller 侧 (BehaviorRuleVersionController.latestAbRun 或 BehaviorRuleAbEvalService):
AgentEntity agent = agentRepository.findById(Long.valueOf(e.getAgentId())).orElse(null);
String ownerRole = agent != null ? agentRoleResolver.resolveRole(agent) : AgentRoleConstants.GENERAL;
BehaviorRuleAbRunResponse resp = BehaviorRuleAbRunResponse.from(e, objectMapper, ownerRole);
```

---

## §4 REST

> **r1-FIX (architect B1 / W3)**: 原 design 加新 `@GetMapping("/scenarios")` method — 跟 `EvalController.java:156` 现有 `listScenarios` (含 `?agentId=` / `?sourceType=` query) 同 path collision → Spring 启动 `IllegalStateException: Ambiguous handler methods`。**改为扩展现有 method 加 `?roles=` query param + `toScenarioEntityMap` 加 applicableAgentRoles 字段** (一并解 W3-architect FE TypeError 风险)。

**扩展（不新增）endpoint**:

| Method | Path | query 扩展 | 行为 |
|---|---|---|---|
| `GET` | `/api/eval/scenarios` | 新加 `?roles=design,general` | OR semantics: 任一 role 命中即返；跟现有 `?sourceType=` / `?agentId=` 同款 AND 组合 |

改 `EvalController.listScenarios` (现有 method):
```java
@GetMapping("/scenarios")
public ResponseEntity<List<Map<String, Object>>> listScenarios(
        @RequestParam(required = false) String agentId,
        @RequestParam(required = false) String sourceType,
        @RequestParam(required = false) String roles) {  // ← 新加
    String[] roleArr = (roles == null || roles.isBlank()) ? null : roles.split(",");
    List<EvalScenarioEntity> rows;
    if (roleArr != null && roleArr.length > 0) {
        rows = scenarioRepository.findAllByAgentRoles(roleArr);
    } else if (...) { /* 现有 agentId / sourceType branch */ }
    // ... 同款 toScenarioEntityMap 输出 List<Map<String,Object>>
    return ResponseEntity.ok(rows.stream().map(this::toScenarioEntityMap).toList());
}
```

**`toScenarioEntityMap` 必须加** `applicableAgentRoles` 字段 (r1-FIX W3-architect, FE DatasetBrowser 依赖此字段做 role filter，缺则 runtime TypeError):
```java
map.put("applicableAgentRoles", entity.getApplicableAgentRoles());
// 同时若未 expose, 同款补 ruleTriggerHints (V1 历史遗漏, V2 backlog)
```

**FE 不再用** `EvalScenarioBrief` 新 DTO — 跟现有 `Map<String, Object>` shape 保持一致 (避免两 endpoint 返不同 shape FE 困惑)。FE TS interface 直接消费现有 shape + 加 optional `applicableAgentRoles?: string[]`。

> **注意 (architect W3 注解)**: 当前 `EvalController` 直调 Repository 是项目历史模式 (V1 已有)。**若未来 roles 过滤需 pagination / 权限 / ownership 检查, 提取到 `EvalDatasetService`**。本 V1 不动 (deliberate simplification 非遗漏)。

`BehaviorRuleVersionController.latestAbRun` (V1 endpoint) **小改**: caller 侧 (controller method 内) 预先 resolve role 后传 `String` 给 `BehaviorRuleAbRunResponse.from(...)` (r1-FIX W1-java-design)，不让 DTO 内调 repository。

---

## §5 FE

### §5.1 类型扩展

`src/api/behaviorRule.ts`:
```ts
export interface BehaviorRuleAbRun {
  // ... 19 字段同 V1 ...
  ownerAgentRole: string | null;   // ← 新字段
}

export type AgentRole = 'general' | 'code' | 'design' | 'research' | 'main_assistant';
```

`src/api/evalDataset.ts` 或新 `src/api/evalScenario.ts`:
```ts
export interface EvalScenarioBrief {
  id: string;
  name: string;
  task: string;
  sourceType: 'benchmark' | 'session_derived' | 'manual';
  purpose: 'baseline_anchor' | 'regression' | 'ablation';
  applicableAgentRoles: AgentRole[];
  // ... 其它 V1 字段 ...
}

export const evalScenarioApi = {
  listByRoles: (roles: AgentRole[]) =>
    api.get<EvalScenarioBrief[]>(`/eval/scenarios?roles=${roles.join(',')}`),
};
```

### §5.2 BehaviorRuleAbRowActions / Drawer 加 owner role tag

`BehaviorRuleAbRowActions.tsx`:
```tsx
{data?.ownerAgentRole && (
  <Tag color={roleColor(data.ownerAgentRole)}>
    {roleLabel(data.ownerAgentRole)}
  </Tag>
)}
{/* ... 原 retry / promote buttons ... */}
```

`roleColor` / `roleLabel` 放在 behaviorRule.ts 或 utils：
```ts
export function roleLabel(role: AgentRole): string {
  return { general: 'General', code: 'Code', design: 'Design',
           research: 'Research', main_assistant: 'Main Assistant' }[role];
}
export function roleColor(role: AgentRole): string {
  return { general: 'default', code: 'cyan', design: 'magenta',
           research: 'orange', main_assistant: 'blue' }[role];
}
```

### §5.3 DatasetBrowser role tab

`DatasetBrowser.tsx` 已有 source_type Segmented：现加并列 role Segmented:
```tsx
<Segmented options={['All','General','Design','Code','Research','Main Assistant']}
           value={roleTab} onChange={setRoleTab} />
// scenarios filter: 在 source_type 已 filter 后再 filter by roleTab
const filtered = scenarios.filter(s =>
  roleTab === 'All' || s.applicableAgentRoles.includes(roleTab.toLowerCase().replace(' ', '_')));
```

注意 useMemo + 类型 narrow（避免 r2-FE truthy bug 重演）。

---

## §6 测试

| Layer | Class | 覆盖点 |
|---|---|---|
| Unit | `AgentRoleResolverTest` | 5 name pattern + null/blank agent → GENERAL fallback + ILIKE 优先级 (e.g. "MainCodeAgent" → code 还是 main_assistant 由顺序决定，测明示)。**r1-FIX W1-architect / N3-java-design**: test 用 V117 SQL 字面 substring (`"design"`, `"code"`, `"research"`, `"main"`/`"assistant"`) hardcode 在 test case 名字 + 输入派生 — Java pattern 跟 V117 SQL 改谁都会触发 test fail (机器对账强于注释 cross-ref)。同包加 `// IMPORTANT: patterns must match V117 SQL UPDATE blocks` 注释。 |
| Unit | `BehaviorRuleAbEvalServiceRoleAwareTest` (Mockito) | runAsync 3 path: design owner → target+regression 双分桶 / general owner → fallback regression-only / no scenario match role → fallback |
| Unit | `EvalScenarioBriefTest` | record roundtrip + applicableAgentRoles 字段非空 |
| IT (PG) | `EvalScenarioRepositoryRoleQueryIT` (Testcontainers, optional, V117 真活 migration apply) | findByDatasetVersionAndAgentRoles / findGeneralRegressionByDatasetVersionExcluding / findAllByAgentRoles 真活 jsonb_exists_any 通过 |
| Contract | `BehaviorRuleAbRunResponseRoleFieldTest` (per java.md #6) | DTO 20 字段 (含 ownerAgentRole), ObjectMapper roundtrip 字段名校 |
| FE Unit | `BehaviorRuleAbRowActions.test.tsx` | renders ownerAgentRole tag when present / 空时不 render |
| FE Unit | `DatasetBrowserRoleFilter.test.tsx` | Role Segmented filter switching + applicableAgentRoles intersection 正确 |

---

## §7 不变量映射 (prd.md INV-1 ~ INV-6)

| INV | 实现位置 |
|---|---|
| INV-1 V117 AC-1 enforce | V117 末尾 `DO $$ RAISE EXCEPTION` block |
| INV-2 resolveRole 不返 null | AgentRoleResolver.resolveRole fallback `GENERAL` + log.warn |
| INV-3 target+regression 不含 wrong-agent | BehaviorRuleAbEvalService 用 ownerRole filter; UT + IT 校 + AC-5 真活 log assert |
| INV-4 dual-criteria 公式不变 | 改 subset 定义不改算法; isDualCriteriaSatisfied (V1) 不动 |
| INV-5 jsonb_exists_any 必用 | 3 个新 native query 用 function-form; reviewer 显式 grep `s.applicable_agent_roles \?\|` 应 0 命中 |
| INV-6 Iron Law | 不动 7+1 BE / 3 FE 核心；改 EvalScenarioEntity (扩展不变接口) / Repository (加 query) / BehaviorRuleAbEvalService (改 runAsync subset 部分) / Response DTO (加字段) / FE optimization 组件 (加 tag 显示) |

---

## §8 风险与回滚

| 风险 | 应对 |
|---|---|
| V117 ILIKE pattern 漏 (新 agent 不在 5 类) | AC-1 RAISE EXCEPTION block fail loudly, operator extend V117 patterns |
| AgentRoleResolver 跟 V117 SQL 双源 drift | 内嵌 cross-ref 注释 + Constants 集中 + AgentRoleResolverTest 跟 V117 pattern 字面一致 测试 |
| 重跑 Event #123 时 V1 ab_run 状态污染 | INV-6 (V1 BehaviorRuleAbEvalService): `findFirstByCandidateVersionIdAndStatusIn` mark old PENDING/RUNNING SUPERSEDED |
| target subset 跟 regression subset overlap (一题 ['design','general']) | regression query 用 `id NOT IN (:excludeIds)` 去重 |
| Prompt/Skill surface 不接同款 fix → user 困惑 | V1 显式 not in scope, doc 说明 + V2 backlog 留入口 |
| FE Segmented 2 行 (source_type 上, role 下) UX 拥挤 | 设计层 review 时 confirm; 若拥挤考虑 dropdown 替代 role |
| AgentRoleResolver 在 multi-instance 部署 name heuristic drift | V1 不考虑 multi-instance (单 BE), V2 加 agent_role 真字段时 obviated |

---

## §9 Dev 任务清单 (Phase 2 拆分)

### BE Dev (Opus, 单 dev)
1. V117 migration SQL (含 backfill 5 UPDATE + AC-1 RAISE EXCEPTION)
2. EvalScenarioEntity 加 `applicableAgentRoles` 字段
3. EvalScenarioDraftRepository 加 **2 native query** (r1-FIX: 砍掉原 `findGeneralRegressionByDatasetVersionExcluding`，改 in-Java filter; jsonb_exists_any, **绝不用 `?|`**)
4. `AgentRoleConstants` 新 util class (内嵌 V117 SQL pattern 跟 Java pattern 对账注释)
5. `AgentRoleResolver` 新 @Component
6. BehaviorRuleAbEvalService.runAsync 改 subset split (3 path, normal branch in-Java filter regression - target)
7. BehaviorRuleAbRunResponse 加 `ownerAgentRole` 字段 + **r1-FIX**: from() 改 pure mapper (3-arg: entity / objectMapper / String ownerRole), caller 预 resolve role
8. **r1-FIX**: 扩展 `EvalController.listScenarios` 加 `?roles=` query param (不新加 @GetMapping method) + `toScenarioEntityMap` 加 `applicableAgentRoles` 字段
9. **r1-FIX**: `BehaviorRuleVersionController.latestAbRun` caller 侧 resolve role 后传 String 给 from(); 加 AgentRoleResolver + AgentRepository 注入
10. 单测 + IT (§6) — 必含 AgentRoleResolverTest (含 V117 SQL pattern 机器对账), BehaviorRuleAbEvalServiceRoleAwareTest 3 path, BehaviorRuleAbRunResponseRoleFieldTest (纯 ObjectMapper roundtrip, **不 mock repo**)

### FE Dev (Opus, 单 dev)
1. `src/api/behaviorRule.ts` 加 `ownerAgentRole`, `AgentRole` type, `roleLabel`/`roleColor` helpers
2. **r1-FIX**: `src/api/evalDataset.ts` (现有 wrapper) 加 `?roles=` query param 支持 (扩展现有 listScenarios wrapper，不新加 evalScenario.ts)；EvalScenario TS interface 加 `applicableAgentRoles?: AgentRole[]` optional 字段 (V1 BE 已 expose, V2 可必填)
3. `BehaviorRuleAbRowActions.tsx` 加 ownerAgentRole Tag render
4. `BehaviorRuleAbDetailDrawer.tsx` Drawer 顶部加 ownerAgentRole 标签
5. `DatasetBrowser.tsx` 加 role Segmented filter (并列 source_type)，filter 用 `s.applicableAgentRoles?.includes(roleTab)`（注意 optional chain 防 BE 返 undefined）
6. FE 单测 (BehaviorRuleAbRowActions.test.tsx 加 ownerAgentRole render branch + DatasetBrowserRoleFilter.test.tsx)

---

## §10 跟 SkillForge 不变量交叉

| 规则 | 是否触发 | 应对 |
|---|---|---|
| persistence-shape-invariant.md | **不触发** — 不动 ChatService/AgentLoopEngine/Message/ContentBlock | ✅ |
| identity-column-on-rewrite.md | **不触发** — 新列在 t_eval_scenario, 不在 t_session_message | ✅ |
| java.md footgun #1 (ObjectMapper JavaTimeModule) | **触发** — Response DTO 含字段，必用 Spring 注入 ObjectMapper | ✅ |
| java.md footgun #6/#6b (FE-BE 契约) | **触发** — 新加 1 endpoint + DTO 新字段 ownerAgentRole | reviewer 显式审 outer envelope (单对象 not envelope) + 字段名/类型 + curl smoke |
| verification-before-completion.md | **触发** — Dev 完成时必跑 `mvn test` + `npm run build` + curl smoke + Event #123 真活重跑 | dev 完工 message 必带证据 |
| pipeline.md 红灯触发条件 | **触发** — schema 改 (V117) + Service 改 BehaviorRuleAbEvalService (核心 V1 路径) + 跨 BE+FE → Full pipeline 红灯 | 走 Full |
| **JSONB cc7286b 教训** (Spring Data JPA placeholder 冲突) | **触发** — 3 个新 query 都用 JSONB ?| 等价 | INV-5: 强制 `jsonb_exists_any()` function-form, 不准 `?|` 操作符 |
