# MULTI-SURFACE-FLYWHEEL V4 Technical Design (Draft)

**Status**: Phase 1.0 Investigation Complete → Awaiting User Ratification  
**Created**: 2026-05-15  
**Scope**: Unifying Skill + Prompt + Behavior Rule A/B evaluation under OptimizableSurface abstraction

---

## §1 Overview

V4 enables behavior rule versioning + A/B evaluation + canary rollout under the same framework as skill (V2) and prompt (V3). Core mechanism: extract shared `AbstractAbEvalRunner` template method from two existing implementations, fill in `OptimizableSurface<V>` interface skeleton (ratified V1), and implement three surface handlers (Skill / Prompt / BehaviorRule).

**Key architectural constraint** (inherited from V2): one active canary per agent (prevents confounding).

---

## §2 OptimizableSurface<V> Abstraction

### 2.1 Interface Definition

```java
/**
 * V4 multi-surface optimization abstraction.
 * Strategy pattern: each surface type implements one interface.
 */
public interface OptimizableSurface<V> {
    
    /**
     * Surface identifier for routing / validation.
     */
    String surfaceType();  // "skill" / "prompt" / "behavior_rule"
    
    /**
     * Load the currently active baseline version for an agent.
     * @return active version entity, or null if none
     */
    V loadActive(String agentId);
    
    /**
     * Load a specific version by id (for sandbox override / audit).
     */
    V loadVersion(String versionId);
    
    /**
     * Build a new candidate version from baseline + improvement context.
     * May trigger LLM calls (e.g., prompt improvement).
     * @param baselineVersionId anchor for improvement
     * @param agentId target agent uuid
     * @param improvementRationale why this change (from attribution proposal)
     * @param baselineEvalRunId eval task for metrics anchoring
     * @return newly created candidate (persisted)
     */
    V createCandidate(String baselineVersionId, String agentId, 
                      String improvementRationale, String baselineEvalRunId);
    
    /**
     * Inject a version into agent definition for sandbox evaluation.
     */
    void injectForSandbox(AgentDefinition agentDef, V version);
    
    /**
     * Promote a candidate to active (atomic operation).
     * Called by PromoteGate after A/B passes threshold.
     */
    void promote(V candidateVersion);
    
    /**
     * Rollback a promoted version (restore prior active).
     * Called by CanaryRolloutService on auto-rollback signal.
     */
    void rollback(String versionId);
}
```

### 2.2 Implementation Classes (Phase 1.1+)

| Class | Surface | Delegates To |
|-------|---------|--------------|
| `SkillSurface` | skill | SkillAbEvalService + SkillRegistry |
| `PromptSurface` | prompt | AbEvalPipeline + PromptImproverService |
| `BehaviorRuleSurface` | behavior_rule | BehaviorRuleImproverService + BehaviorRuleRegistry |

### 2.3 SurfaceRegistry

```java
@Component
public class SurfaceRegistry {
    private final Map<String, OptimizableSurface<?>> handlers;
    
    public <V> OptimizableSurface<V> get(String surfaceType) { ... }
}
```

Auto-wired via Spring component scan; three bean registrations (one per surface handler).

---

## §3 AbstractAbEvalRunner Template Method

### 3.1 Hook Sequence

```
AbstractAbEvalRunner<V>.run(abRun, baseline, candidate, agent):
    1. setRunning()
    2. loadScenarios()
    3. computeBaselineRate(scenarios)
    4. injectForSandbox(agentDef, candidate)     ← hook_1
    5. loop scenarios:
         runSingleScenario() → judge()           ← hook_2
    6. aggregate(results)
    7. shouldPromote(agg)?                       ← hook_3
    8. promote(candidate) / stay_draft           ← hook_4
    9. return AbRunRecord<V>
```

### 3.2 Hook Signatures

```java
public abstract class AbstractAbEvalRunner<V> {
    
    /**
     * Surface-specific injection into sandbox agent definition.
     */
    protected abstract void injectForSandbox(AgentDefinition def, V version);
    
    /**
     * Surface-specific judgment comparison (baseline vs candidate).
     */
    protected abstract AbScenarioResult judgeAndCompare(
        EvalScenario scenario, 
        ScenarioRunResult baselineResult,
        ScenarioRunResult candidateResult);
    
    /**
     * Promotion threshold decision.
     */
    protected abstract boolean shouldPromote(AbRunAggregate scores);
    
    /**
     * Atomic promotion execution.
     */
    protected abstract void promoteIfNeeded(V candidateVersion, boolean passed);
    
    public final AbRunRecord<V> run(
        AbRunEntity abRun,
        V baselineVersion,
        V candidateVersion,
        AgentEntity originalAgent) {
        // Template method body (shared across all surfaces)
    }
}
```

### 3.3 Existing Code → Refactor Map

- **SkillAbEvalService.runAbTestAsync()** → `SkillAbEvalRunner extends AbstractAbEvalRunner<SkillEntity>`
- **AbEvalPipeline.run()** → `PromptAbEvalRunner extends AbstractAbEvalRunner<PromptVersionEntity>`
- **[New] BehaviorRuleImproverService** → `BehaviorRuleAbEvalRunner extends AbstractAbEvalRunner<BehaviorRuleVersionEntity>`

**Regression test requirement**: existing skill A/B flow must pass post-refactor (no behavioral change).

---

## §4 Behavior Rule Versioning

### 4.1 Schema (Flyway V#___)

```sql
-- t_behavior_rule_version (analog to t_prompt_version)
CREATE TABLE t_behavior_rule_version (
    id UUID PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    baseline_version_id UUID,
    content TEXT NOT NULL,                    -- JSON rules list
    status VARCHAR(32) NOT NULL,              -- active / candidate / draft / rejected
    improvement_rationale VARCHAR(1024),      -- from attribution proposal
    source_eval_run_id VARCHAR(128),          -- eval task for baseline metrics
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (agent_id) REFERENCES t_agent(id),
    FOREIGN KEY (baseline_version_id) REFERENCES t_behavior_rule_version(id)
);

-- t_behavior_rule_ab_run (analog to t_prompt_ab_run)
CREATE TABLE t_behavior_rule_ab_run (
    id UUID PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    baseline_version_id UUID NOT NULL,
    candidate_version_id UUID NOT NULL,
    scenario_results_json TEXT,               -- AbScenarioResult list
    composite_score NUMERIC(5, 2),            -- 0.0-100.0
    dimension_status VARCHAR(32),             -- per M4 formula
    status VARCHAR(32) NOT NULL,              -- COMPLETED / RUNNING / FAILED
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (agent_id) REFERENCES t_agent(id),
    FOREIGN KEY (baseline_version_id) REFERENCES t_behavior_rule_version(id),
    FOREIGN KEY (candidate_version_id) REFERENCES t_behavior_rule_version(id)
);
```

### 4.2 BehaviorRuleImproverService

```java
@Service
public class BehaviorRuleImproverService {
    
    @Transactional
    public BehaviorRuleAbEvalStartResult startImprovement(
        String agentId,
        String evalRunId,          // baseline eval task
        Long userId,
        String improvementRationale) {
        // 1. Load baseline BehaviorRuleDefinition (from BehaviorRuleRegistry.loadActive)
        // 2. Generate candidate via LLM (defaultProvider)
        // 3. Create BehaviorRuleVersionEntity(status=candidate)
        // 4. Return result
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BehaviorRuleAbRunEntity runAbTest(
        BehaviorRuleVersionEntity baseline,
        BehaviorRuleVersionEntity candidate,
        AgentEntity agent,
        EvalTaskEntity baselineEvalRun,
        Long userId) {
        // Delegate to BehaviorRuleAbEvalRunner.run()
    }
}
```

### 4.3 BehaviorRuleRegistry Query Extension

Current: loads `behavior-rules.json` at startup (read-only).  
V4: adds method to query active version from `t_behavior_rule_version`.

```java
public Optional<BehaviorRuleVersionEntity> findActiveVersion(String agentId) {
    // SELECT * FROM t_behavior_rule_version 
    // WHERE agent_id = ? AND status = 'active' 
    // ORDER BY created_at DESC LIMIT 1
}
```

---

## §5 Canary Rollout Generalization

### 5.1 CanaryAllocator Refactor

**Current** (V2): hardcoded to skill surface.  
**V4**: templatized to any surface (via SurfaceRegistry).

```java
@Component
public class CanaryAllocator {
    
    /**
     * @param sessionId session identifier (stable across session lifetime)
     * @param agentId   agent uuid
     * @param surface   surface type ("skill" / "prompt" / "behavior_rule")
     * @param baselineId active baseline version id
     * @return allocated version id (baseline or candidate)
     */
    public String allocate(String sessionId, Long agentId, 
                          String surface, String baselineId) {
        // 1. Query CanaryRolloutEntity for (agent_id, surface_type=surface, stage='canary')
        // 2. If no active canary → return baseline
        // 3. If pct >= 100 → return candidate
        // 4. If session already assigned → return prior assignment
        // 5. Hash-allocate based on percentage
        // 6. Persist assignment to t_session_annotation(canary_group)
        // 7. Return picked version
    }
}
```

### 5.2 Canary Mutual Exclusion (Constraint #4)

**Schema constraint** (on `t_canary_rollout`):
```sql
UNIQUE (agent_id, rollout_stage)
  WHERE rollout_stage = 'canary'
```

**Behavior**: only one surface per agent can be in `rollout_stage='canary'` simultaneously.

**Attribution-curator interaction** (V3):
- If proposal suggests surface X when surface Y is in canary:
  - Proposal still moves to `proposal_pending`
  - Dashboard shows UI warning: "Agent Y canary active; recommend completing or rolling back"
  - Operator can approve (A/B will run but canary start is queued) or abort

---

## §6 Persistence & Identity Columns

### 6.1 No t_session_message Changes

Per project constraints: no new identity columns on `t_session_message`.  
All canary / optimization data flows through new tables.

### 6.2 Session Annotation Reuse (V1)

Existing `t_session_annotation` table (V1 PROD-LABEL-CLUSTER) already supports canary assignment:

```
annotation_type = 'canary_group'
annotation_value = '<surface>:<versionId>'
source = 'system'
```

**Per-surface examples**:
- skill: `'skill:my-skill-v2'`
- prompt: `'prompt:prompt-uuid-1234'`
- behavior_rule: `'behavior_rule:rule-uuid-5678'`

---

## §7 Refactoring Checklist (Phase 1.1)

### 7.1 Code Changes
- [ ] Extract AbstractAbEvalRunner from SkillAbEvalService.runAbTestAsync + AbEvalPipeline.run
- [ ] Create SkillAbEvalRunner, PromptAbEvalRunner extending AbstractAbEvalRunner
- [ ] Verify existing SkillAbEvalService + PromptImproverService behavior unchanged (regression test)
- [ ] Implement BehaviorRuleImproverService (delegate to BehaviorRuleAbEvalRunner)
- [ ] Create BehaviorRuleSurface handler
- [ ] Templatize CanaryAllocator (surface-agnostic)
- [ ] Extend SurfaceRegistry with three handlers

### 7.2 Schema Changes
- [ ] Flyway V# migration: t_behavior_rule_version + t_behavior_rule_ab_run
- [ ] Flyway V#+1 migration: seed data (none for V4; behavior rule table starts empty)
- [ ] Add Repository + Entity classes

### 7.3 Dashboard (Phase 1.2)
- [ ] Behavior rule versioning panel (analog to skill/prompt panels)
- [ ] Behavior rule A/B panel (show candidate + baseline scores)
- [ ] Canary panel on behavior rule (reuse V2 component template)

### 7.4 Testing
- [ ] Regression: skill A/B full path (unchanged behavior)
- [ ] Regression: prompt A/B full path (unchanged behavior)
- [ ] New: BehaviorRuleAbEvalRunner happy path
- [ ] New: Surface router (SurfaceRegistry) returns correct handler per type
- [ ] New: CanaryAllocator works across surfaces (surface-agnostic)

---

## §8 Ratify Decisions (Phase 1.0 → Phase 1.1 Gate)

**All 5 decisions ratified by user on 2026-05-15. Phase 1.1 gated open.**

| # | Question | Decision | Status |
|---|----------|----------|--------|
| 1 | Lifecycle hook versioning? | **Push to V5** | ✅ Ratified 2026-05-15 |
| 2 | OptimizableSurface signature? | **6-method interface** (surfaceType / loadActive / loadVersion / createCandidate / injectForSandbox / promote / rollback) | ✅ Ratified 2026-05-15 |
| 3 | AbstractAbEvalRunner hooks? | **4-hook extraction** (injectForSandbox → judgeAndCompare → shouldPromote → promoteIfNeeded) | ✅ Ratified 2026-05-15 |
| 4 | Canary mutual exclusion? | **Keep "1 active canary per agent" cross all surfaces** (prevent confounding) | ✅ Ratified 2026-05-15 |
| 5 | Behavior rule LLM model? | **Always defaultProvider** (consistent with PromptImprover) | ✅ Ratified 2026-05-15 |

**Phase 1.0 → Phase 1.1 gate**: OPEN. Begin Phase 1.1 implementation.

---

## §8.1 Phase 1.1 Scope (V4 first slice)

Build behavior_rule surface — the second OptimizableSurface (after V2 skill, V3 prompt that
existed). Does not yet refactor the existing two services into AbstractAbEvalRunner — that's
Phase 1.2 (lower risk: validate the new surface works standalone first, then carefully extract
the template method once 3 surfaces give a concrete signature).

**Deliverables**:
- V82 Flyway migration: `t_behavior_rule_version` (mirrors PromptVersionEntity shape) +
  `t_behavior_rule_ab_run` (mirrors PromptAbRunEntity shape)
- Java entities + repositories
- `OptimizableSurface<V>` interface (replace V1 empty skeleton with the 6-method shape from §2.1)
- `SkillSurface` / `PromptSurface` / `BehaviorRuleSurface` 3 implementation classes
- `SurfaceRegistry` Spring @Component
- `BehaviorRuleImproverService` + `BehaviorRuleAbEvalService` (clones of PromptImprover /
  SkillAbEval templates, narrowed to behavior_rule entity types)
- JPA IT (Testcontainers) 4 case: version write+read, ab_run round-trip, FK CASCADE, query patterns
- BehaviorRuleRegistry: extend with DB-driven `getActiveVersion(agentId)` query (fallback to
  startup-loaded behavior-rules.json baseline if no active row)
- AttributionApprovalService: add `dispatchBehaviorRuleSurface` branch (mirrors existing
  dispatchSkillSurface / dispatchPromptSurface)
- Mid-pipeline reviewer adversarial (1-2 round per pipeline.md Full档)

**Out of Phase 1.1** (Phase 1.2+):
- AbstractAbEvalRunner Template Method extraction (refactor existing 2 services)
- CanaryAllocator generalization (only needed when behavior_rule canary requested)
- Dashboard behavior rule panel UI
- AttributionApprovalService stage transitions for behavior_rule
- BehaviorRuleAbEvalService eval pipeline wiring (Phase 1.2 with the Template Method refactor)

---

## §9 Risks & Mitigation

| Risk | Mitigation |
|------|-----------|
| Refactoring SkillAbEvalService breaks existing flow | Regression test (skill A/B full path on existing agent) |
| Canary constraint too strict (blocks concurrent improvements) | V5 roadmap: user simulator enables true multi-arm trials |
| Behavior rule LLM improvement quality | Start with simple rules (copy+annotate baseline) in V4; advance heuristics in V4.1 |
| BehaviorRuleRegistry DB query adds startup latency | Query only on demand (not during bootstrap); cache with TTL |

---

## §10 Unknowns (Deferred to Phase 1.1)

- Exact behavior rule improvement prompt wording (depends on V3 real patterns)
- Dashboard behavior rule panel UI design (follows skill/prompt template but may vary)
- Performance impact of CanaryAllocator templatization (expect minimal; same logic)

---

## Appendix: Investigation Report Location

Full Phase 1.0 investigation: `/tmp/v4-phase1.0-investigation.md`

Includes:
- Code audit results (5 ratify decisions, each with code references)
- Alternative analyses (CanaryAllocator cost, test coverage)
- Confidence levels per recommendation

