package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.attribution.AttributionEventBroadcaster;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.2 — STEP 4 (happy-path) of the
 * {@code attribution-curator} pipeline. Writes a new
 * {@code t_optimization_event} row at {@code stage=proposal_pending} with a
 * 24h cooldown so the dispatcher won't re-fire on the same pattern until the
 * proposal is approved / rejected / promoted.
 *
 * <p>Wire shape:
 * <ul>
 *   <li>input: {@code { patternId, agentId, surface, changeType, description,
 *       expectedImpact, confidence, risk }}</li>
 *   <li>output: {@code { ok, optimizationEventId, stage, cooldownExpiresAt }}</li>
 * </ul>
 *
 * <p>Validation (per system-prompt CONSTRAINTS 1-3):
 * <ul>
 *   <li>{@code surface} ∈ \{skill, prompt, behavior_rule\} — V4 Phase 1.4 widened from
 *       V3's \{skill, prompt\} (ratify #6) to include behavior_rule once the
 *       AttributionApprovalService.dispatchBehaviorRuleSurface branch shipped in
 *       Phase 1.3 (commit 9cd74d8); {@code other} / {@code unclear} still rejected.</li>
 *   <li>{@code confidence} ∈ [0, 1]</li>
 *   <li>{@code risk} ∈ \{low, medium, high\}</li>
 *   <li>{@code description} / {@code expectedImpact} non-blank</li>
 *   <li>{@code patternId} must exist in {@code t_session_pattern}</li>
 * </ul>
 *
 * <p>For low-confidence (<0.5) or out-of-scope surface rejections the agent
 * should call {@link WriteOptimizationEventTool} with
 * {@code newStage=proposal_rejected} instead — that path lives outside this
 * tool by design (cleaner separation of "I have a real proposal" vs "I'm
 * recording a non-action").
 *
 * <p>{@code attributionSessionId} is auto-captured from
 * {@link SkillContext#getSessionId()} so the dashboard can replay the
 * curator's reasoning trail when an operator inspects a proposal.
 */
public class ProposeOptimizationTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ProposeOptimizationTool.class);

    static final Set<String> ALLOWED_SURFACES = Set.of(
            OptimizationEventEntity.SURFACE_SKILL,
            OptimizationEventEntity.SURFACE_PROMPT,
            OptimizationEventEntity.SURFACE_BEHAVIOR_RULE);
    static final Set<String> ALLOWED_RISKS = Set.of(
            OptimizationEventEntity.RISK_LOW,
            OptimizationEventEntity.RISK_MEDIUM,
            OptimizationEventEntity.RISK_HIGH);
    static final Duration COOLDOWN_DURATION = Duration.ofHours(24);

    private final OptimizationEventRepository eventRepository;
    private final SessionPatternRepository patternRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AttributionEventBroadcaster broadcaster;

    public ProposeOptimizationTool(OptimizationEventRepository eventRepository,
                                   SessionPatternRepository patternRepository,
                                   ObjectMapper objectMapper,
                                   Clock clock,
                                   AttributionEventBroadcaster broadcaster) {
        this.eventRepository = eventRepository;
        this.patternRepository = patternRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.broadcaster = broadcaster;
    }

    @Override
    public String getName() {
        return "ProposeOptimization";
    }

    @Override
    public String getDescription() {
        return "STEP 4 of the attribution-curator pipeline (happy path). "
                + "Writes a new t_optimization_event row at stage=proposal_pending "
                + "with 24h cooldown. Allowed surfaces: skill / prompt / behavior_rule "
                + "(V4 Phase 1.4 widened from V3's skill+prompt — see ratify #6). "
                + "Allowed risk: low / medium / high. confidence ∈ [0,1]. "
                + "Rejects low-confidence (<0.5) and out-of-scope surfaces — the "
                + "curator should call WriteOptimizationEvent stage=proposal_rejected "
                + "instead for those cases.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("patternId", Map.of(
                "type", "integer", "description", "Required t_session_pattern.id."));
        properties.put("agentId", Map.of(
                "type", "integer",
                "description", "Required agent_id (may be inherited from pattern.agentId)."));
        properties.put("surface", Map.of(
                "type", "string",
                "description", "Required: 'skill' / 'prompt' / 'behavior_rule'. V4 Phase 1.4 — other/unclear rejected."));
        properties.put("changeType", Map.of(
                "type", "string",
                "description", "Required: free-form identifier of the change (e.g. 'rewrite_skill_md', 'tune_prompt')."));
        properties.put("description", Map.of(
                "type", "string",
                "description", "Required: 1-3 sentences citing specific session evidence. Non-blank."));
        properties.put("expectedImpact", Map.of(
                "type", "string",
                "description", "Required: one sentence describing expected metric change. Non-blank."));
        properties.put("confidence", Map.of(
                "type", "number",
                "description", "Required: self-rated probability ∈ [0,1]. <0.5 → use WriteOptimizationEvent reject path instead."));
        properties.put("risk", Map.of(
                "type", "string",
                "description", "Required: 'low' / 'medium' / 'high'."));
        properties.put("memoryContextHash", Map.of(
                "type", "string",
                "description", "Optional SHA-256 contextHash returned by ListRelevantMemories."));
        properties.put("memoryIds", Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "Optional memory ids returned by ListRelevantMemories."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("patternId", "agentId", "surface", "changeType",
                "description", "expectedImpact", "confidence", "risk"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required");
            }

            Long patternId = SkillInputUtils.toLong(input.get("patternId"));
            Long agentId = SkillInputUtils.toLong(input.get("agentId"));
            String surface = stringify(input.get("surface"));
            String changeType = stringify(input.get("changeType"));
            String description = stringify(input.get("description"));
            String expectedImpact = stringify(input.get("expectedImpact"));
            BigDecimal confidence = toBigDecimal(input.get("confidence"));
            String risk = stringify(input.get("risk"));
            String memoryContextHash = stringify(input.get("memoryContextHash"));
            List<Long> memoryIds = parseLongList(input.get("memoryIds"));

            // Validate required fields.
            if (patternId == null || patternId <= 0) {
                return SkillResult.validationError("patternId must be a positive integer");
            }
            if (agentId == null || agentId <= 0) {
                return SkillResult.validationError("agentId must be a positive integer");
            }
            if (!ALLOWED_SURFACES.contains(surface)) {
                return SkillResult.validationError(
                        "surface must be one of " + ALLOWED_SURFACES
                                + " (V3 ratify #6) — got: " + surface);
            }
            if (isBlank(changeType)) {
                return SkillResult.validationError("changeType is required and must be non-blank");
            }
            if (isBlank(description)) {
                return SkillResult.validationError("description is required and must be non-blank");
            }
            if (isBlank(expectedImpact)) {
                return SkillResult.validationError("expectedImpact is required and must be non-blank");
            }
            if (confidence == null
                    || confidence.compareTo(BigDecimal.ZERO) < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0) {
                return SkillResult.validationError("confidence must be in [0, 1]; got: " + confidence);
            }
            if (!ALLOWED_RISKS.contains(risk)) {
                return SkillResult.validationError(
                        "risk must be one of " + ALLOWED_RISKS + " — got: " + risk);
            }

            // Verify the pattern exists. We deliberately do NOT pre-load it for
            // FK validation against the DB — the FK is the source of truth and
            // a missing pattern would be a deeper data corruption that's worth
            // surfacing loudly via the persistence layer. The findById here is
            // a cheap up-front check that returns a cleaner LLM-readable error
            // than the eventual SQLIntegrityConstraintViolationException.
            Optional<SessionPatternEntity> patternOpt = patternRepository.findById(patternId);
            if (patternOpt.isEmpty()) {
                return SkillResult.validationError("patternId not found in t_session_pattern: " + patternId);
            }

            Instant now = clock.instant();
            Instant cooldownExpiresAt = now.plus(COOLDOWN_DURATION);

            // Phase 1.3 ratify: AttributionDispatcherService writes a
            // dispatch_initiated sentinel BEFORE chatAsync; this tool UPDATEs
            // that sentinel into proposal_pending. UPDATE preserves the original
            // createdAt (the real dispatch time), avoids INSERT-then-DELETE
            // cascade noise, and keeps the row's id stable for downstream
            // consumers (Phase 1.4 dashboard / WS notify).
            //
            // Fallback INSERT path retained for resilience: if a curator session
            // somehow runs without a dispatcher-written sentinel (e.g. manual
            // tool invocation during dogfood, future REST-triggered curator
            // run), we still write a fresh proposal_pending row. The
            // dispatcher's own contract is "always sentinel first" so this
            // path is purely defensive.
            List<OptimizationEventEntity> sentinels = eventRepository
                    .findByPatternIdAndStage(patternId,
                            OptimizationEventEntity.STAGE_DISPATCH_INITIATED);

            OptimizationEventEntity event;
            String previousStage;
            if (!sentinels.isEmpty()) {
                if (sentinels.size() > 1) {
                    log.warn("ProposeOptimization: {} dispatch_initiated sentinels for patternId={}; "
                            + "using oldest (id={}) and leaving the rest for ops cleanup",
                            sentinels.size(), patternId, sentinels.get(0).getId());
                }
                event = sentinels.get(0);
                // Defensive contract check — should never fire because the
                // findByPatternIdAndStage filter pins both axes, but reviewers
                // asked for explicit guard.
                if (!OptimizationEventEntity.STAGE_DISPATCH_INITIATED.equals(event.getStage())
                        || !patternId.equals(event.getPatternId())) {
                    throw new IllegalStateException(
                            "Expected dispatch_initiated sentinel for patternId=" + patternId
                                    + ", found stage=" + event.getStage()
                                    + " patternId=" + event.getPatternId());
                }
                previousStage = event.getStage();
                event.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
            } else {
                log.warn("ProposeOptimization: no dispatch_initiated sentinel for patternId={} — "
                        + "creating fresh proposal_pending row (defensive fallback)", patternId);
                event = new OptimizationEventEntity();
                event.setPatternId(patternId);
                event.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
                previousStage = null;  // INSERT path — no prior stage to broadcast.
            }
            event.setAgentId(agentId);
            event.setSurfaceType(surface);
            event.setChangeType(changeType.trim());
            event.setDescription(description.trim());
            event.setExpectedImpact(expectedImpact.trim());
            event.setConfidence(confidence.setScale(2, RoundingMode.HALF_UP));
            event.setRisk(risk);
            event.setCooldownExpiresAt(cooldownExpiresAt);
            if (context != null && !isBlank(context.getSessionId())) {
                event.setAttributionSessionId(context.getSessionId());
            }
            if (!isBlank(memoryContextHash)) {
                event.setMemoryContextHash(memoryContextHash.trim());
            }
            if (!memoryIds.isEmpty()) {
                event.setMemoryContextMemoryIds(objectMapper.writeValueAsString(memoryIds));
            }
            // updatedAt auto-populated by @PreUpdate; createdAt preserved on UPDATE
            // path (column is updatable=false), set by @PrePersist on INSERT path.

            OptimizationEventEntity saved = eventRepository.save(event);
            // Phase 1.4: WS notify dashboard of stage transition. broadcaster
            // null-safe-tolerates no connected sessions.
            // Broadcast in-tx (V3 dogfood trade-off; see AttributionApprovalService class javadoc).
            if (broadcaster != null) {
                broadcaster.broadcastStageTransition(saved, previousStage);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("optimizationEventId", saved.getId());
            response.put("stage", saved.getStage());
            response.put("cooldownExpiresAt", saved.getCooldownExpiresAt().toString());
            log.info("ProposeOptimization: wrote event id={} patternId={} surface={} stage={}",
                    saved.getId(), patternId, surface, saved.getStage());
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("ProposeOptimization execute failed", e);
            return SkillResult.error("ProposeOptimization error: " + e.getMessage());
        }
    }

    private static String stringify(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Long> parseLongList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> parsed = new LinkedHashSet<>();
        for (Object item : list) {
            Long id = SkillInputUtils.toLong(item);
            if (id != null && id > 0) {
                parsed.add(id);
            }
        }
        return new ArrayList<>(parsed);
    }
}
