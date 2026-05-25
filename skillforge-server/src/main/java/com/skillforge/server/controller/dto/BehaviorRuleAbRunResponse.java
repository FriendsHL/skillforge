package com.skillforge.server.controller.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — REST response for {@link BehaviorRuleAbRunEntity}.
 *
 * <p>Per {@code java.md} known footgun #6 / #6b: field-for-field mirror of the
 * entity plus one derived flag ({@link #dualCriteriaSatisfied}) so the FE
 * doesn't need to re-implement the gate formula. Outer shape is the single
 * record value (not an envelope) — FE wrapper is
 * {@code api.get<BehaviorRuleAbRunResponse>(...)}.
 *
 * <p>Field name + type contract preserved in
 * {@code BehaviorRuleAbRunResponseContractTest} (Jackson roundtrip).
 *
 * <p><b>FE contract location</b> (r2-BE-3): the canonical FE TypeScript
 * counterpart is {@code skillforge-dashboard/src/api/behaviorRule.ts} —
 * interface {@code BehaviorRuleAbRun}. The {@code scenarioResults} field below
 * MUST be mirrored in that base interface as
 * {@code scenarioResults?: AbScenarioResult[] | null} (not in a local
 * extension/cast subtype) so {@code tsc} verifies the cross-stack contract.
 * Per java.md footgun #6b "outer envelope shape" rule, any future field
 * addition here requires a synchronized FE base-interface edit + a paired
 * assertion in {@code BehaviorRuleAbRunResponseContractTest}.
 *
 * <p>FLYWHEEL-AB-AGENT-AWARE-DATASET V1 (2026-05-25): added
 * {@link #ownerAgentRole} — closed-set role string (see
 * {@code AgentRoleConstants}) of the rule_owner agent, surfaced so the FE
 * Optimization Events row can render the owner role Tag without an extra
 * round-trip. r1-FIX (java-design W1 / architect W2): caller side resolves
 * the role and passes it as a String to {@link #from(BehaviorRuleAbRunEntity,
 * ObjectMapper, String)} — DTO stays a pure mapper (no repository fan-out,
 * N+1 risk avoided, tests do not need to mock AgentRepository).
 */
public record BehaviorRuleAbRunResponse(
        String id,
        String agentId,
        String candidateVersionId,
        String status,
        String abRunKind,
        Double baselinePassRate,
        Double candidatePassRate,
        Double deltaPassRate,
        Double targetDeltaPp,
        Double regressionDeltaPp,
        Integer targetCount,
        Integer regressionCount,
        String datasetVersionId,
        Boolean promoted,
        String failureReason,
        Instant startedAt,
        Instant completedAt,
        Boolean dualCriteriaSatisfied,
        /**
         * BEHAVIOR-RULE-AB-EVAL V1 — FE-BE contract C3 (opportunistic): per-
         * scenario baseline vs candidate scoreboard, parsed from
         * {@code BehaviorRuleAbRunEntity.abScenarioResultsJson}. {@code null}
         * when the entity column is null/blank or the JSON parse fails — FE
         * degrades gracefully (no detail table).
         */
        List<AbScenarioResult> scenarioResults,
        /**
         * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — closed-set agent role of the
         * rule_owner agent. One of {@code general / code / design / research /
         * main_assistant} (see {@code AgentRoleConstants}). {@code null} when
         * the caller could not resolve the role (e.g. agent disappeared mid-
         * lookup). Surfaced for FE row-action Tag rendering — see
         * {@code roleColor / roleLabel} in {@code behaviorRule.ts}.
         */
        String ownerAgentRole) {

    private static final Logger log = LoggerFactory.getLogger(BehaviorRuleAbRunResponse.class);

    /**
     * Pure-mapper {@link #from} — caller pre-resolves the owner role and
     * passes it in. Keeps the DTO independent of {@code AgentRepository} /
     * {@code AgentRoleResolver} (r1-FIX java-design W1). Use the
     * {@link ObjectMapper} overload when scenarioResults should populate
     * from the entity's {@code abScenarioResultsJson} column.
     */
    public static BehaviorRuleAbRunResponse from(BehaviorRuleAbRunEntity e,
                                                  ObjectMapper objectMapper,
                                                  String ownerAgentRole) {
        if (e == null) return null;
        List<AbScenarioResult> scenarios = parseScenarioResults(e.getAbScenarioResultsJson(), objectMapper);
        return new BehaviorRuleAbRunResponse(
                e.getId(),
                e.getAgentId(),
                e.getCandidateVersionId(),
                e.getStatus(),
                e.getAbRunKind(),
                e.getBaselinePassRate(),
                e.getCandidatePassRate(),
                e.getDeltaPassRate(),
                e.getTargetDeltaPp(),
                e.getRegressionDeltaPp(),
                e.getTargetCount(),
                e.getRegressionCount(),
                e.getDatasetVersionId(),
                e.isPromoted(),
                e.getFailureReason(),
                e.getStartedAt(),
                e.getCompletedAt(),
                // Derived: only meaningful once status=COMPLETED. For other
                // statuses we still compute the formula (returns false for
                // missing regression delta), keeping the field non-null on
                // the wire — simplifies FE rendering.
                BehaviorRulePromotionService.isDualCriteriaSatisfied(e),
                scenarios,
                ownerAgentRole);
    }

    /**
     * Backwards-compatible 2-arg overload — passes {@code ownerAgentRole=null}.
     * Pre-FLYWHEEL-AB-AGENT-AWARE-DATASET call sites that don't yet resolve
     * the owner role keep working; FE sees {@code ownerAgentRole=null} and
     * skips the role Tag.
     */
    public static BehaviorRuleAbRunResponse from(BehaviorRuleAbRunEntity e, ObjectMapper objectMapper) {
        return from(e, objectMapper, /*ownerAgentRole*/ null);
    }

    /**
     * Backwards-compatible 1-arg overload — leaves scenarioResults and
     * ownerAgentRole null. Used by call sites that don't want to depend on
     * an ObjectMapper (older tests / pure-projection contexts).
     */
    public static BehaviorRuleAbRunResponse from(BehaviorRuleAbRunEntity e) {
        if (e == null) return null;
        return new BehaviorRuleAbRunResponse(
                e.getId(),
                e.getAgentId(),
                e.getCandidateVersionId(),
                e.getStatus(),
                e.getAbRunKind(),
                e.getBaselinePassRate(),
                e.getCandidatePassRate(),
                e.getDeltaPassRate(),
                e.getTargetDeltaPp(),
                e.getRegressionDeltaPp(),
                e.getTargetCount(),
                e.getRegressionCount(),
                e.getDatasetVersionId(),
                e.isPromoted(),
                e.getFailureReason(),
                e.getStartedAt(),
                e.getCompletedAt(),
                BehaviorRulePromotionService.isDualCriteriaSatisfied(e),
                /*scenarioResults*/ null,
                /*ownerAgentRole*/ null);
    }

    /**
     * Best-effort parse of {@code abScenarioResultsJson}. Returns null on
     * blank input or parse failure — both are non-fatal (FE degrades).
     */
    private static List<AbScenarioResult> parseScenarioResults(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank() || objectMapper == null) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<AbScenarioResult>>() {});
        } catch (Exception ex) {
            log.warn("Failed to parse abScenarioResultsJson (len={}): {}",
                    json.length(), ex.getMessage());
            return null;
        }
    }
}
