package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EvalScenarioDraftRepository extends JpaRepository<EvalScenarioEntity, String> {

    List<EvalScenarioEntity> findByAgentIdOrderByCreatedAtDesc(String agentId);

    List<EvalScenarioEntity> findByAgentId(String agentId);

    List<EvalScenarioEntity> findByStatus(String status);

    List<EvalScenarioEntity> findByAgentIdAndStatus(String agentId, String status);

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4 (2026-05-16): used by
     * {@code PromptImproverService.runAbTestAgainst} to resolve the agent's
     * canonical scenario split (default {@code "held_out"}) when the /run-ab
     * caller doesn't supply explicit {@code evalScenarioIds}. Returns empty
     * when the agent has no scenarios in the requested split — caller falls
     * back to the ephemeral path (ratify #4).
     */
    List<EvalScenarioEntity> findByAgentIdAndSplit(String agentId, String split);

    /**
     * EVAL-DATASET-LAYER V1 (V109): list scenarios by their source_type closed-
     * enum value. Used by the FE EvalScenarios source_type tab.
     */
    List<EvalScenarioEntity> findBySourceTypeOrderByCreatedAtDesc(String sourceType);

    /**
     * EVAL-DATASET-LAYER V1 (V109): list scenarios by their purpose closed-
     * enum value. Used by health / composition policy diagnostics.
     */
    List<EvalScenarioEntity> findByPurposeOrderByCreatedAtDesc(String purpose);

    // ─────────────────────────────────────────────────────────────────────
    // BEHAVIOR-RULE-AB-EVAL V1 (V114/V115): dataset / target-subset queries
    // used by BehaviorRuleAbEvalService to split a dataset version into
    // (target subset, regression subset).
    //
    // **HOT-FIX (commit 700ac29 follow-up)**: The original `s.rule_trigger_hints
    // ?| CAST(:tags AS text[])` syntax fails BE startup because Spring Data
    // JPA's StringQuery parser treats `?` as a positional placeholder and
    // rejects native queries that mix `?` with `:name` form ("Mixing of ?
    // parameters and other forms like ?1 is not supported"). The standard
    // `??` escape (`??|`) is also rejected by Spring Data JPA 3.2.4. The
    // robust portable fix is to use PostgreSQL's built-in `jsonb_exists_any
    // (jsonb, text[])` function — it is the **documented function-form
    // equivalent of `?|`** (PG 9.4+, stable API), produces an identical
    // query plan, and uses the same GIN index (idx_eval_scenario_rule_
    // trigger_hints_gin from V114). Function-form keeps native query
    // PostgreSQL-only at runtime (same constraint as prior EVAL-DATASET-LAYER
    // queries) but free of the `?`-character escaping hazard. H2 unit tests
    // should mock these methods.
    //
    // Phase Final caught what 5 Mockito-mocked unit tests + 395 H2 regression
    // tests silently passed — Spring Data only parses native @Query at
    // `Repository.afterPropertiesSet()` during Spring context init, never
    // during mock-based test runs.
    // ─────────────────────────────────────────────────────────────────────

    @Query(value = """
            SELECT s.* FROM t_eval_scenario s
            JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
            WHERE b.dataset_version_id = :datasetVersionId
              AND jsonb_exists_any(s.rule_trigger_hints, CAST(:tags AS text[]))
            """, nativeQuery = true)
    List<EvalScenarioEntity> findTargetSubsetByDatasetVersionAndTags(
            @Param("datasetVersionId") String datasetVersionId,
            @Param("tags") String[] tags);

    @Query(value = """
            SELECT s.* FROM t_eval_scenario s
            JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
            WHERE b.dataset_version_id = :datasetVersionId
              AND NOT jsonb_exists_any(s.rule_trigger_hints, CAST(:tags AS text[]))
            """, nativeQuery = true)
    List<EvalScenarioEntity> findRegressionSubsetByDatasetVersionAndTags(
            @Param("datasetVersionId") String datasetVersionId,
            @Param("tags") String[] tags);

    /**
     * ★ r1-FIX (architect WARN): fallback when version has no
     * {@code target_trigger_tags} set — load every scenario in the dataset
     * version. Used by {@code BehaviorRuleAbEvalService} for regression-only
     * mode (target subset empty, all scenarios are regression).
     */
    @Query(value = """
            SELECT s.* FROM t_eval_scenario s
            JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
            WHERE b.dataset_version_id = :datasetVersionId
            """, nativeQuery = true)
    List<EvalScenarioEntity> findAllByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId);

    // ─────────────────────────────────────────────────────────────────────
    // FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (V117): role-aware queries used by
    // BehaviorRuleAbEvalService to split a dataset version into
    // (target subset = scenarios matching rule_owner_agent's role) +
    // (regression subset = scenarios tagged 'general'). FE Dataset role
    // filter tab also consumes findAllByAgentRoles.
    //
    // INV-5: BOTH queries MUST use jsonb_exists_any(jsonb, text[]) function
    // form — never the ?| operator. See cc7286b hot-fix; Spring Data JPA's
    // StringQuery parser treats `?` as a positional placeholder and rejects
    // mixing it with :name placeholders. jsonb_exists_any is the documented
    // function-form equivalent of ?| (PG 9.4+, identical query plan, same
    // GIN index idx_eval_scenario_applicable_agent_roles_gin from V117).
    //
    // r1-FIX (architect B2 / database W2): the original tech-design had a
    // 3rd query `findGeneralRegressionByDatasetVersionExcluding` with
    // NOT IN (:excludeIds). Hibernate 6 + Spring Data JPA native query
    // Collection<String>→PG NOT IN binding has empty-list and type-form
    // footguns. Replaced by in-Java filter at the caller (V1 scale ≤49
    // scenarios, O(n) trivial). V2 may add CTE / NOT EXISTS form when
    // dataset size grows past ~1000.
    // ─────────────────────────────────────────────────────────────────────

    @Query(value = """
            SELECT s.* FROM t_eval_scenario s
            JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
            WHERE b.dataset_version_id = :datasetVersionId
              AND jsonb_exists_any(s.applicable_agent_roles, CAST(:roles AS text[]))
            """, nativeQuery = true)
    List<EvalScenarioEntity> findByDatasetVersionAndAgentRoles(
            @Param("datasetVersionId") String datasetVersionId,
            @Param("roles") String[] roles);

    /**
     * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (V117): FE Dataset role filter tab
     * (cross-dataset list of scenarios matching any of the supplied roles).
     * Ordered by created_at DESC so newest scenarios surface first.
     */
    @Query(value = """
            SELECT s.* FROM t_eval_scenario s
            WHERE jsonb_exists_any(s.applicable_agent_roles, CAST(:roles AS text[]))
            ORDER BY s.created_at DESC
            """, nativeQuery = true)
    List<EvalScenarioEntity> findAllByAgentRoles(@Param("roles") String[] roles);
}
