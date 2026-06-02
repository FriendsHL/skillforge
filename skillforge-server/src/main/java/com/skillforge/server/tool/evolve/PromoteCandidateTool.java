package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.improve.PromotionResult;
import com.skillforge.server.improve.PromptPromotionService;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module B (FR-B3) — promote an adopted candidate via
 * the EXISTING guarded promote service. This tool NEVER bypasses a guard: prompt
 * keeps its delta≥15pp / promoted-today / 24h-cooldown / paused gates; skill
 * keeps its V64 ordering; behavior_rule keeps dual-criteria.
 *
 * <p><b>SECURITY — candidate ownership.</b> The promote-service guards gate
 * <em>whether</em> to promote, NOT <em>which</em> candidate is legitimate. A
 * prompt bug (or a confused orchestrator) could pass another agent's
 * {@code candidateId}. Before routing, this tool validates that the candidate
 * (resolved via its ab_run for prompt/skill, or directly for behavior_rule)
 * belongs to {@code targetAgentId}, and that the supplied {@code candidateId}
 * actually matches the candidate the ab_run promotes. A mismatch is rejected.
 *
 * <p><b>abRunId.</b> The prompt and skill promote services operate off the
 * ab_run row, so those surfaces require an {@code abRunId} (the one returned by
 * TriggerAbEval). behavior_rule promotes the version directly, so its
 * {@code candidateId} IS the version id and no abRunId is needed.
 *
 * <p><b>Recursion guard (invariant).</b> Registered ONLY in the main
 * {@code SkillRegistry}; deliberately ABSENT from
 * {@code WorkflowSkillRegistryFactory} (the workflow sub-agent registry) — same
 * invariant as {@code RunWorkflowTool.java:46-50}.
 */
public class PromoteCandidateTool implements Tool {

    public static final String NAME = "PromoteCandidate";

    private static final Logger log = LoggerFactory.getLogger(PromoteCandidateTool.class);

    private final PromptPromotionService promptPromotionService;
    private final SkillAbEvalService skillAbEvalService;
    private final BehaviorRulePromotionService behaviorRulePromotionService;
    private final PromptAbRunRepository promptAbRunRepository;
    private final SkillAbRunRepository skillAbRunRepository;
    private final BehaviorRuleVersionRepository behaviorRuleVersionRepository;
    private final ObjectMapper objectMapper;

    public PromoteCandidateTool(PromptPromotionService promptPromotionService,
                                SkillAbEvalService skillAbEvalService,
                                BehaviorRulePromotionService behaviorRulePromotionService,
                                PromptAbRunRepository promptAbRunRepository,
                                SkillAbRunRepository skillAbRunRepository,
                                BehaviorRuleVersionRepository behaviorRuleVersionRepository,
                                ObjectMapper objectMapper) {
        this.promptPromotionService = promptPromotionService;
        this.skillAbEvalService = skillAbEvalService;
        this.behaviorRulePromotionService = behaviorRulePromotionService;
        this.promptAbRunRepository = promptAbRunRepository;
        this.skillAbRunRepository = skillAbRunRepository;
        this.behaviorRuleVersionRepository = behaviorRuleVersionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Promote an adopted candidate to active, through the existing guarded promote "
                + "service (delta gate / cooldown / paused etc. still apply — promotion may be "
                + "rejected). Inputs:\n"
                + "- \"surface\": one of \"prompt\", \"skill\", \"behavior_rule\".\n"
                + "- \"candidateId\": the candidate id (prompt version id / skill id / behavior-rule "
                + "version id) — validated to belong to targetAgentId.\n"
                + "- \"targetAgentId\": the agent owning the candidate.\n"
                + "- \"abRunId\": the A/B run id from TriggerAbEval (REQUIRED for prompt and skill; "
                + "not used for behavior_rule, which promotes the version directly).\n"
                + "Returns {status:\"promoted\"|\"rejected\", reason}. A guard rejection or an "
                + "ownership mismatch returns status=\"rejected\" with a reason.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("surface", Map.of(
                "type", "string",
                // No "agent" here (§7 B2): V1 has no atomic whole-bundle promote —
                // promote each surface of the winning bundle separately.
                "enum", EvolveSurface.v1NonAgentWireValues(),
                "description", "Optimisation surface: \"prompt\", \"skill\", or \"behavior_rule\"."
        ));
        properties.put("candidateId", Map.of(
                "type", "string",
                "description", "Candidate id (prompt version id / skill id / behavior-rule version id)."
        ));
        properties.put("targetAgentId", Map.of(
                "type", "string",
                "description", "The agent that owns the candidate (numeric agent id)."
        ));
        properties.put("abRunId", Map.of(
                "type", "string",
                "description", "A/B run id from TriggerAbEval (required for prompt and skill)."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("surface", "candidateId", "targetAgentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError(
                        "input is required (surface, candidateId, targetAgentId)");
            }
            EvolveSurface surface = EvolveSurface.fromWire(trimToNull(input.get("surface")));
            if (surface == null) {
                // §7 B2 / W-WARN-1: this tool doesn't accept agent — don't list it.
                return SkillResult.validationError(
                        "surface is required and must be one of: "
                                + EvolveSurface.v1NonAgentAcceptedValues());
            }
            String candidateId = trimToNull(input.get("candidateId"));
            if (candidateId == null) {
                return SkillResult.validationError("candidateId is required");
            }
            String targetAgentId = trimToNull(input.get("targetAgentId"));
            if (targetAgentId == null) {
                return SkillResult.validationError("targetAgentId is required");
            }
            String abRunId = trimToNull(input.get("abRunId"));

            Long userId = context != null ? context.getUserId() : null;

            return switch (surface) {
                case PROMPT -> promotePrompt(candidateId, targetAgentId, abRunId);
                case SKILL -> promoteSkill(candidateId, targetAgentId, abRunId, userId);
                case BEHAVIOR_RULE -> promoteBehaviorRule(candidateId, targetAgentId, userId);
                // §7 B2: V1 has no atomic whole-agent/bundle promote. Reject cleanly
                // (not a crash) and tell the orchestrator to promote per-surface.
                case AGENT -> rejected(
                        "agent surface not supported by PromoteCandidate in V1: promote each surface "
                                + "(prompt / skill / behavior_rule) of the winning bundle separately");
            };
        } catch (IllegalArgumentException e) {
            return SkillResult.validationError(e.getMessage());
        } catch (IllegalStateException e) {
            // Surface precondition not met (e.g. dual-criteria, not-COMPLETED) — the
            // guarded service refused. Surface as a rejection, not a crash.
            return rejected(e.getMessage());
        } catch (Exception e) {
            log.error("PromoteCandidate execute failed", e);
            return SkillResult.error("PromoteCandidate error: " + e.getMessage());
        }
    }

    private SkillResult promotePrompt(String candidateId, String targetAgentId, String abRunId) {
        if (abRunId == null) {
            return SkillResult.validationError("abRunId is required for surface=prompt");
        }
        PromptAbRunEntity run = promptAbRunRepository.findById(abRunId).orElse(null);
        if (run == null) {
            return rejected("A/B run not found: " + abRunId);
        }
        // SECURITY: ab_run must belong to the target agent, and the candidate the
        // run promotes must be the one the caller asked to promote.
        if (!targetAgentId.equals(run.getAgentId())) {
            return ownershipMismatch("prompt", abRunId, run.getAgentId(), targetAgentId);
        }
        if (!candidateId.equals(run.getPromptVersionId())) {
            return candidateMismatch("prompt", candidateId, run.getPromptVersionId());
        }
        PromotionResult result = promptPromotionService.evaluateAndPromote(abRunId, targetAgentId);
        return fromPromotionResult(result.status(), result.reason());
    }

    private SkillResult promoteSkill(String candidateId, String targetAgentId,
                                     String abRunId, Long userId) {
        if (abRunId == null) {
            return SkillResult.validationError("abRunId is required for surface=skill");
        }
        SkillAbRunEntity run = skillAbRunRepository.findById(abRunId).orElse(null);
        if (run == null) {
            return rejected("A/B run not found: " + abRunId);
        }
        if (!targetAgentId.equals(run.getAgentId())) {
            return ownershipMismatch("skill", abRunId, run.getAgentId(), targetAgentId);
        }
        Long candidateSkillId;
        try {
            candidateSkillId = Long.valueOf(candidateId);
        } catch (NumberFormatException nfe) {
            return SkillResult.validationError(
                    "candidateId must be a numeric skill id for surface=skill, got: " + candidateId);
        }
        if (!candidateSkillId.equals(run.getCandidateSkillId())) {
            return candidateMismatch("skill", candidateId,
                    String.valueOf(run.getCandidateSkillId()));
        }
        // manualPromote routes through the V64-safe promoteCandidate ordering and
        // requires the run to be COMPLETED (it throws otherwise → rejected).
        skillAbEvalService.manualPromote(abRunId, userId);
        return fromPromotionResult("promoted", "Skill candidate " + candidateId
                + " promoted (abRun=" + abRunId + ")");
    }

    private SkillResult promoteBehaviorRule(String candidateId, String targetAgentId, Long userId) {
        // behavior_rule promotes the version directly — candidateId IS the version id.
        BehaviorRuleVersionEntity version =
                behaviorRuleVersionRepository.findById(candidateId).orElse(null);
        if (version == null) {
            return rejected("BehaviorRuleVersion not found: " + candidateId);
        }
        if (!targetAgentId.equals(version.getAgentId())) {
            return ownershipMismatch("behavior_rule", candidateId,
                    version.getAgentId(), targetAgentId);
        }
        // promoteManual runs the dual-criteria gate then the atomic transition.
        BehaviorRulePromotionService.PromoteResult result =
                behaviorRulePromotionService.promoteManual(candidateId, userId);
        return fromPromotionResult(result.status(), result.reason());
    }

    private SkillResult fromPromotionResult(String status, String reason) {
        Map<String, Object> response = new LinkedHashMap<>();
        // Normalise behavior_rule's "noop" (already-active idempotent) to "promoted"
        // so the orchestrator treats a re-promote as a success, not a failure.
        String normalized = "noop".equals(status) ? "promoted" : status;
        response.put("status", normalized);
        response.put("reason", reason);
        try {
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            return SkillResult.error("PromoteCandidate serialization error: " + e.getMessage());
        }
    }

    private SkillResult rejected(String reason) {
        return fromPromotionResult("rejected", reason);
    }

    private SkillResult ownershipMismatch(String surface, String idRef,
                                          String actualAgentId, String targetAgentId) {
        String msg = "candidate ownership mismatch (surface=" + surface + ", ref=" + idRef
                + "): belongs to agent " + actualAgentId + ", not targetAgentId " + targetAgentId;
        log.warn("[PromoteCandidate] REJECTED — {}", msg);
        return rejected(msg);
    }

    private SkillResult candidateMismatch(String surface, String suppliedCandidateId,
                                          String runCandidateId) {
        String msg = "candidateId mismatch (surface=" + surface + "): supplied " + suppliedCandidateId
                + " but the A/B run promotes candidate " + runCandidateId;
        log.warn("[PromoteCandidate] REJECTED — {}", msg);
        return rejected(msg);
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
