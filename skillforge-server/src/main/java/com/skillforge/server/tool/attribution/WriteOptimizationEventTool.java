package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.2 — stage-transition writer for
 * {@code t_optimization_event}. Used by:
 *
 * <ul>
 *   <li>{@code attribution-curator} agent — STEP 4 reject path (low confidence /
 *       out-of-scope surface), writes
 *       {@code newStage=proposal_rejected} with a {@code description} carrying
 *       the reason.</li>
 *   <li>Phase 1.3 {@code AttributionApprovalService} — approve / reject /
 *       candidate_created transitions.</li>
 *   <li>Phase 1.3 downstream A/B + canary integrations — ab_running,
 *       ab_passed/failed, canary_started, promoted, rolled_back, verified.</li>
 * </ul>
 *
 * <p>Wire shape:
 * <ul>
 *   <li>input: {@code { "eventId": long, "newStage": string,
 *       "candidateSkillId"?, "candidatePromptVersionId"?, "abRunId"?,
 *       "canaryId"?, "description"? (override) }}</li>
 *   <li>output: {@code { "ok": true, "eventId", "newStage", "previousStage" }}</li>
 * </ul>
 *
 * <p>Validation: {@code newStage} must be one of the canonical
 * {@link OptimizationEventEntity} stage constants. Mutation rules
 * (e.g. "can't transition from rolled_back back to ab_running") are intentionally
 * NOT enforced here — that policy lives in Phase 1.3
 * {@code OptimizationEventService}. This tool is a thin adapter so the curator
 * + future approval/rollout services can hit a single uniform endpoint.
 */
public class WriteOptimizationEventTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WriteOptimizationEventTool.class);

    static final Set<String> KNOWN_STAGES = Set.of(
            OptimizationEventEntity.STAGE_DISPATCH_INITIATED,
            OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
            OptimizationEventEntity.STAGE_PROPOSAL_APPROVED,
            OptimizationEventEntity.STAGE_PROPOSAL_REJECTED,
            OptimizationEventEntity.STAGE_CANDIDATE_GENERATING,
            OptimizationEventEntity.STAGE_CANDIDATE_READY,
            OptimizationEventEntity.STAGE_CANDIDATE_FAILED,
            OptimizationEventEntity.STAGE_CANDIDATE_CREATED,  // legacy alias
            OptimizationEventEntity.STAGE_AB_RUNNING,
            OptimizationEventEntity.STAGE_AB_PASSED,
            OptimizationEventEntity.STAGE_AB_FAILED,
            OptimizationEventEntity.STAGE_CANARY_STARTED,
            OptimizationEventEntity.STAGE_PROMOTED,
            OptimizationEventEntity.STAGE_ROLLED_BACK,
            OptimizationEventEntity.STAGE_VERIFIED);

    private final OptimizationEventRepository eventRepository;
    private final SessionPatternRepository patternRepository;
    private final ObjectMapper objectMapper;

    public WriteOptimizationEventTool(OptimizationEventRepository eventRepository,
                                      SessionPatternRepository patternRepository,
                                      ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.patternRepository = patternRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "WriteOptimizationEvent";
    }

    @Override
    public String getDescription() {
        return "Stage-transition writer for t_optimization_event. Used by the "
                + "attribution-curator reject path and by Phase 1.3 ApprovalService / "
                + "downstream A/B + canary services. Updates newStage + any optional "
                + "candidate / abRun / canary fields. Does NOT enforce transition "
                + "policy — call site's responsibility. "
                + "Accept either `eventId` (direct row id) OR `patternId` (resolves "
                + "the active dispatch_initiated sentinel for that pattern — "
                + "symmetric with ProposeOptimization for the agent reject path).";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("eventId", Map.of(
                "type", "integer",
                "description", "Either eventId or patternId required. "
                        + "eventId = direct t_optimization_event.id to update."));
        properties.put("patternId", Map.of(
                "type", "integer",
                "description", "Either eventId or patternId required. "
                        + "patternId = resolves the dispatch_initiated sentinel for "
                        + "that pattern (curator reject path: agent has patternId but "
                        + "not the sentinel's auto-generated event id). Mirrors "
                        + "ProposeOptimization's patternId→sentinel resolution."));
        properties.put("newStage", Map.of(
                "type", "string",
                "description", "Required: one of " + KNOWN_STAGES + "."));
        properties.put("candidateSkillId", Map.of(
                "type", "integer",
                "description", "Optional: set when transitioning to candidate_created with surface=skill."));
        properties.put("candidatePromptVersionId", Map.of(
                "type", "integer",
                "description", "Optional: set when transitioning to candidate_created with surface=prompt."));
        properties.put("abRunId", Map.of(
                "type", "integer",
                "description", "Optional: set when transitioning to ab_running."));
        properties.put("canaryId", Map.of(
                "type", "integer",
                "description", "Optional: set when transitioning to canary_started."));
        properties.put("description", Map.of(
                "type", "string",
                "description", "Optional override for description (e.g. reject reason)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // 'eventId' OR 'patternId' must be provided — JSON Schema oneOf not
        // universally supported by all LLM provider validators; we enforce at
        // runtime in execute() instead and only list newStage as required.
        schema.put("required", List.of("newStage"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (eventId + newStage at minimum)");
            }
            Long eventId = SkillInputUtils.toLong(input.get("eventId"));
            Long patternId = SkillInputUtils.toLong(input.get("patternId"));
            if ((eventId == null || eventId <= 0) && (patternId == null || patternId <= 0)) {
                return SkillResult.validationError(
                        "either eventId or patternId required (positive integer)");
            }
            Object rawStage = input.get("newStage");
            String newStage = rawStage == null ? null : rawStage.toString().trim();
            if (newStage == null || newStage.isEmpty()) {
                return SkillResult.validationError("newStage is required");
            }
            if (!KNOWN_STAGES.contains(newStage)) {
                return SkillResult.validationError(
                        "newStage must be one of " + KNOWN_STAGES + " — got: " + newStage);
            }

            // BUG-1 fix (V3 e2e dogfood 2026-05-15): support patternId fallback
            // for the curator reject path. The agent receives patternId in its
            // user message but the dispatch_initiated sentinel's eventId is
            // auto-generated by the dispatcher and never surfaced. Resolve here
            // so the agent can call WriteOptimizationEvent({patternId, newStage})
            // symmetric with ProposeOptimization. Direct eventId still works.
            OptimizationEventEntity event;
            if (eventId != null && eventId > 0) {
                Optional<OptimizationEventEntity> opt = eventRepository.findById(eventId);
                if (opt.isEmpty()) {
                    return SkillResult.validationError("optimization event not found: " + eventId);
                }
                event = opt.get();
            } else {
                List<OptimizationEventEntity> sentinels = eventRepository
                        .findByPatternIdAndStage(patternId,
                                OptimizationEventEntity.STAGE_DISPATCH_INITIATED);
                if (sentinels.isEmpty()) {
                    // V3.1 bug fix (2026-05-15 dogfood): no sentinel = agent was
                    // invoked outside the cron-dispatcher flow (operator chat,
                    // manual session, or curator self-decided to rewrite stage
                    // without going through ProposeOptimization first). Auto-INSERT
                    // a new event row instead of failing. Pulls agent_id +
                    // surface_type from t_session_pattern so the row is well-formed.
                    Optional<SessionPatternEntity> patternOpt = patternRepository.findById(patternId);
                    if (patternOpt.isEmpty()) {
                        return SkillResult.validationError(
                                "patternId not found in t_session_pattern: " + patternId);
                    }
                    SessionPatternEntity pattern = patternOpt.get();
                    OptimizationEventEntity created = new OptimizationEventEntity();
                    created.setPatternId(patternId);
                    created.setAgentId(pattern.getAgentId() != null ? pattern.getAgentId() : 0L);
                    created.setSurfaceType(pattern.getSuspectSurface() != null
                            ? pattern.getSuspectSurface() : "unclear");
                    created.setStage(newStage);
                    // attribution_session_id stamped from caller context so the
                    // timeline can drill back to the originating session.
                    if (context != null && context.getSessionId() != null) {
                        created.setAttributionSessionId(context.getSessionId());
                    }
                    log.info("[WriteOptimizationEvent] auto-INSERT (no sentinel): patternId={} "
                            + "newStage={} surfaceType={} (operator-triggered curator path)",
                            patternId, newStage, created.getSurfaceType());
                    event = created;
                } else {
                    if (sentinels.size() > 1) {
                        log.warn("[WriteOptimizationEvent] {} sentinels for patternId={} — "
                                + "using oldest by createdAt (id={})",
                                sentinels.size(), patternId, sentinels.get(0).getId());
                    }
                    event = sentinels.get(0);
                }
            }
            String previousStage = event.getStage();
            event.setStage(newStage);

            Long candidateSkillId = SkillInputUtils.toLong(input.get("candidateSkillId"));
            if (candidateSkillId != null) event.setCandidateSkillId(candidateSkillId);
            Long candidatePromptVersionId = SkillInputUtils.toLong(input.get("candidatePromptVersionId"));
            if (candidatePromptVersionId != null) event.setCandidatePromptVersionId(candidatePromptVersionId);
            Long abRunId = SkillInputUtils.toLong(input.get("abRunId"));
            if (abRunId != null) event.setAbRunId(abRunId);
            Long canaryId = SkillInputUtils.toLong(input.get("canaryId"));
            if (canaryId != null) event.setCanaryId(canaryId);
            Object rawDescription = input.get("description");
            if (rawDescription != null) {
                String descOverride = rawDescription.toString();
                if (!descOverride.isBlank()) {
                    event.setDescription(descOverride.trim());
                }
            }
            // updatedAt is auto-populated by @PreUpdate (Phase 1.1 fix).

            OptimizationEventEntity saved = eventRepository.save(event);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("eventId", saved.getId());
            response.put("newStage", saved.getStage());
            response.put("previousStage", previousStage);
            log.info("WriteOptimizationEvent: id={} {} → {}", saved.getId(), previousStage, saved.getStage());
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("WriteOptimizationEvent execute failed", e);
            return SkillResult.error("WriteOptimizationEvent error: " + e.getMessage());
        }
    }
}
