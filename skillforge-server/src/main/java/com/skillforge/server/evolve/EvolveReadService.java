package com.skillforge.server.evolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.evolve.dto.CandidateBundle;
import com.skillforge.server.evolve.dto.EvolveIterationDto;
import com.skillforge.server.evolve.dto.EvolveRunDetailDto;
import com.skillforge.server.evolve.dto.EvolveRunSummaryDto;
import com.skillforge.server.evolve.dto.PredictionDto;
import com.skillforge.server.evolve.dto.ReconciliationDto;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D (FR-D1/FR-D3/FR-D4) — read-only service
 * backing the two evolve trajectory endpoints:
 *
 * <ul>
 *   <li>{@link #listRunsForAgent} — list evolve runs for an agent (newest-first),
 *       computing {@code iterationCount} and {@code finalDelta} (last kept
 *       iteration's delta or null).</li>
 *   <li>{@link #getRunDetail} — evolve run detail + all recorded iterations,
 *       ordered by {@code step_index} (1-based iteration).</li>
 * </ul>
 *
 * <p>All public methods are {@code @Transactional(readOnly=true)}. No writes
 * here — that path lives in {@link com.skillforge.server.flywheel.run.FlywheelRunService}.
 *
 * <p>Constructor injection only (java.md rule). Spring {@code ObjectMapper} bean
 * injected (footgun #1 — must have JavaTimeModule registered).
 */
@Service
public class EvolveReadService {

    private static final Logger log = LoggerFactory.getLogger(EvolveReadService.class);

    private final FlywheelRunRepository runRepository;
    private final FlywheelRunStepRepository stepRepository;
    private final AgentRepository agentRepository;
    private final SkillDraftRepository skillDraftRepository;
    private final ObjectMapper objectMapper;

    public EvolveReadService(FlywheelRunRepository runRepository,
                             FlywheelRunStepRepository stepRepository,
                             AgentRepository agentRepository,
                             SkillDraftRepository skillDraftRepository,
                             ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.agentRepository = agentRepository;
        this.skillDraftRepository = skillDraftRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 (design review W2) — lightweight read-only
     * projection of a skill draft for the adopt card's diff view. Lives in the
     * service (not the controller) so the controller stays free of a direct
     * {@code SkillDraftRepository} dependency. Returns empty when the draft is
     * missing — the controller maps that to 404.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getSkillDraftView(String draftId) {
        return skillDraftRepository.findById(draftId).map(draft -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", draft.getId());
            body.put("name", draft.getName());
            body.put("promptHint", draft.getPromptHint());
            body.put("triggers", draft.getTriggers());
            body.put("requiredTools", draft.getRequiredTools());
            return body;
        });
    }

    /**
     * FR-D4 — list evolve runs for a given agent, newest-first, limited by
     * {@code limit} (clamped to [1, 100]).
     *
     * <p>For each run, counts recorded {@code evolve_iteration} steps and
     * derives {@code finalDelta} from the <em>last kept</em> iteration step
     * (step_output_json {@code kept == true}), ordered by step_index descending.
     * Returns {@code null} if no kept iteration exists yet.
     *
     * @param agentId the target agent id
     * @param limit   max runs to return (clamped to [1, 100])
     * @return list of summary DTOs, newest run first
     */
    @Transactional(readOnly = true)
    public List<EvolveRunSummaryDto> listRunsForAgent(Long agentId, int limit) {
        int clampedLimit = Math.max(1, Math.min(limit, 100));
        List<FlywheelRunEntity> runs = runRepository.findByAgentIdAndLoopKindOrderByCreatedAtDesc(
                agentId,
                FlywheelRunEntity.LOOP_KIND_EVOLVE,
                PageRequest.of(0, clampedLimit));

        List<EvolveRunSummaryDto> result = new ArrayList<>(runs.size());
        for (FlywheelRunEntity run : runs) {
            List<FlywheelRunStepEntity> steps = stepRepository.findByRunIdAndStepKind(
                    run.getId(), FlywheelRunStepEntity.STEP_KIND_EVOLVE_ITERATION);
            int iterationCount = steps.size();
            Double finalDelta = computeFinalDelta(steps);
            result.add(new EvolveRunSummaryDto(
                    run.getId(),
                    run.getStatus(),
                    run.getCreatedAt(),
                    run.getUpdatedAt(),
                    iterationCount,
                    finalDelta));
        }
        return result;
    }

    /**
     * FR-D3 — full detail for a single evolve run, including all recorded
     * iterations ordered by {@code step_index} (= iteration number, 1-based).
     *
     * @param evolveRunId the run UUID
     * @return an {@link Optional} containing the detail DTO, or empty if the
     *         run is not found or is not an {@code evolve} run (FE gets 404)
     */
    @Transactional(readOnly = true)
    public Optional<EvolveRunDetailDto> getRunDetail(String evolveRunId) {
        Optional<FlywheelRunEntity> runOpt = runRepository.findById(evolveRunId);
        if (runOpt.isEmpty()) {
            return Optional.empty();
        }
        FlywheelRunEntity run = runOpt.get();
        if (!FlywheelRunEntity.LOOP_KIND_EVOLVE.equals(run.getLoopKind())) {
            // 404 — not an evolve run (callers must not query non-evolve runs here)
            return Optional.empty();
        }

        String agentName = agentRepository.findById(run.getAgentId())
                .map(a -> a.getName())
                .orElse(null);

        // All evolve_iteration steps for the run, ordered by step_index (= iteration)
        List<FlywheelRunStepEntity> steps = stepRepository
                .findByRunIdAndStepKindOrderByStepIndexAsc(
                        evolveRunId, FlywheelRunStepEntity.STEP_KIND_EVOLVE_ITERATION);

        // AUTOEVOLVE-CLOSE-LOOP P1 — correlate each iteration's candidate-gen agent
        // leaf (subagent_dispatch) sub-session so the FE can "click a node → see its
        // session". In the evolve-loop workflow the ONLY subagent_dispatch steps are
        // the per-iteration candidate leaves (the opt-report sub-flow runs as a
        // SEPARATE run), created in iteration order, so the i-th candidate step ↔ the
        // i-th recorded iteration.
        //
        // SAFE DEGRADE (r1 F1): positional zip is only correct when there is exactly
        // one candidate step per recorded iteration. A run that errored mid-iteration
        // (candidate dispatched, but the iteration never reached RecordIteration) has
        // MORE candidate steps than ledger rows → the tail would mis-align to the
        // WRONG session. Pointing at a wrong session is worse than pointing at none,
        // so unless the counts match exactly we degrade EVERY iteration's subSessionId
        // to null rather than risk a wrong association. The legacy orchestrator path
        // has zero candidate steps (≠ ledger size) → also null, as intended.
        List<FlywheelRunStepEntity> candidateSteps = stepRepository
                .findByRunIdAndStepKindOrderByStepIndexAsc(
                        evolveRunId, FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH);
        boolean subSessionAlignable = candidateSteps.size() == steps.size();

        List<EvolveIterationDto> iterations = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            FlywheelRunStepEntity step = steps.get(i);
            String subSessionId = subSessionAlignable
                    ? candidateSteps.get(i).getSubAgentSessionId() : null;
            EvolveIterationDto dto = parseIterationStep(step, subSessionId);
            if (dto != null) {
                iterations.add(dto);
            }
        }

        return Optional.of(new EvolveRunDetailDto(
                run.getId(),
                run.getAgentId(),
                agentName,
                run.getStatus(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                iterations));
    }

    // ─── private helpers ────────────────────────────────────────────────────

    /**
     * Find the last kept iteration's delta by scanning steps (descending by
     * step_index = iteration number). Returns null when no kept step exists.
     */
    private Double computeFinalDelta(List<FlywheelRunStepEntity> steps) {
        // steps are in ascending order; scan in reverse to get the last kept one
        for (int i = steps.size() - 1; i >= 0; i--) {
            FlywheelRunStepEntity step = steps.get(i);
            JsonNode payload = parseStepOutputJson(step);
            if (payload == null) continue;
            JsonNode keptNode = payload.get("kept");
            if (keptNode != null && keptNode.isBoolean() && keptNode.asBoolean()) {
                JsonNode deltaNode = payload.get("delta");
                if (deltaNode != null && !deltaNode.isNull()) {
                    return deltaNode.asDouble();
                }
            }
        }
        return null;
    }

    /**
     * Parse a {@code step_output_json} string into a {@link EvolveIterationDto}.
     * Returns {@code null} (and logs a warning) if the JSON is missing or
     * cannot be parsed — defensive for partially-written ledger rows.
     */
    private EvolveIterationDto parseIterationStep(FlywheelRunStepEntity step, String subSessionId) {
        JsonNode payload = parseStepOutputJson(step);
        if (payload == null) {
            log.warn("EvolveReadService: step {} has null/unparseable step_output_json; skipping",
                    step.getId());
            return null;
        }

        int iteration = payload.path("iteration").asInt(0);
        String surface = payload.path("surface").asText(null);
        String changeDesc = payload.path("changeDesc").asText(null);
        String candidateId = payload.path("candidateId").asText(null);

        Double baselineScore = nodeToDouble(payload.get("baselineScore"));
        Double candidateScore = nodeToDouble(payload.get("candidateScore"));
        Double delta = nodeToDouble(payload.get("delta"));

        boolean kept = payload.path("kept").asBoolean(false);
        String abRunId = payload.path("abRunId").asText(null);
        // Use empty string sentinel from asText → null when absent
        if ("null".equals(abRunId) || "".equals(abRunId)) {
            abRunId = null;
        }

        CandidateBundle candidateBundle = parseCandidateBundle(payload.get("candidateBundle"));
        // BC-M2b (G3): prediction + reconciliation sidecars (null for pre-G3 rows).
        PredictionDto prediction = parseJsonNode(payload.get("prediction"), PredictionDto.class);
        ReconciliationDto reconciliation =
                parseJsonNode(payload.get("reconciliation"), ReconciliationDto.class);
        // P1: semanticDelta sidecar (null for legacy / pre-P1 rows). Passed through
        // as the raw JSON node — it serializes back to the FE inline.
        JsonNode semanticDelta = objectNodeOrNull(payload.get("semanticDelta"));

        return new EvolveIterationDto(
                iteration,
                surface,
                changeDesc,
                candidateId,
                baselineScore,
                candidateScore,
                delta,
                kept,
                abRunId,
                step.getCreatedAt(),
                candidateBundle,
                prediction,
                reconciliation,
                step.getId(),
                subSessionId,
                semanticDelta);
    }

    /** The node if it is a non-null JSON object, else {@code null}. */
    private static JsonNode objectNodeOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        return node;
    }

    /**
     * BC-M2b (G3) — best-effort convert a {@code step_output_json} sub-node into a
     * typed DTO. Returns null when the node is absent / not an object / unparseable
     * (a malformed sidecar must never fail the whole iteration read).
     */
    private <T> T parseJsonNode(JsonNode node, Class<T> type) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            log.warn("EvolveReadService: failed to parse {} sidecar: {}",
                    type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — parse the {@code candidateBundle} sidecar
     * written by {@code RecordIterationTool}. Returns {@code null} when the
     * node is absent, is not a JSON object (the tool stores a non-parseable
     * bundle as a text fallback — not a pointer tuple we can adopt), or when
     * every pointer is blank.
     */
    private CandidateBundle parseCandidateBundle(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        String promptVersionId = textOrNull(node.get("promptVersionId"));
        String behaviorRuleVersionId = textOrNull(node.get("behaviorRuleVersionId"));
        String skillDraftId = textOrNull(node.get("skillDraftId"));
        CandidateBundle bundle = new CandidateBundle(
                promptVersionId, behaviorRuleVersionId, skillDraftId);
        return bundle.isEmpty() ? null : bundle;
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — the candidate bundles of every kept iteration
     * of an evolve run, in step order. Used by the adopt endpoint to bound the
     * adoptable pointer space (a request pointer must originate from a kept
     * iteration — prevents adopting an arbitrary cross-run/cross-agent version).
     * Empty when the run is missing, not an evolve run, or has no kept bundle.
     */
    @Transactional(readOnly = true)
    public List<CandidateBundle> listKeptCandidateBundles(String evolveRunId) {
        return getRunDetail(evolveRunId)
                .map(detail -> detail.iterations().stream()
                        .filter(EvolveIterationDto::kept)
                        .map(EvolveIterationDto::candidateBundle)
                        .filter(b -> b != null)
                        .toList())
                .orElseGet(List::of);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (!node.isValueNode()) return null;
        String s = node.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private JsonNode parseStepOutputJson(FlywheelRunStepEntity step) {
        String json = step.getStepOutputJson();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("EvolveReadService: failed to parse step_output_json for step {}: {}",
                    step.getId(), e.getMessage());
            return null;
        }
    }

    private static Double nodeToDouble(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.asDouble();
        return null;
    }
}
