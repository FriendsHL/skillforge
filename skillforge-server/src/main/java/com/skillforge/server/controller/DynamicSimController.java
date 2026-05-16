package com.skillforge.server.controller;

import com.skillforge.server.dto.SimulatorTrialResponse;
import com.skillforge.server.entity.SimulatorTrialEntity;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.SimulationOutcome;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.TrialRequest;
import com.skillforge.server.repository.SimulatorTrialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.3 — REST surface for the user-simulator
 * trial harness.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/dynamic-sim/trials} — kick off one trial per persona
 *       (async dispatch via {@code abEvalLoopExecutor})</li>
 *   <li>{@code GET /api/dynamic-sim/trials} — paginated list, filterable by
 *       {@code scenarioId} or {@code candidateAgentVersionId+candidateSurfaceType}</li>
 *   <li>{@code GET /api/dynamic-sim/trials/{trialId}} — single trial detail
 *       (FE jumps to SessionDetail via {@code sessionId})</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dynamic-sim")
public class DynamicSimController {

    private static final Logger log = LoggerFactory.getLogger(DynamicSimController.class);

    private final SimulatorTrialOrchestrator orchestrator;
    private final SimulatorTrialRepository trialRepository;
    private final ExecutorService loopExecutor;

    public DynamicSimController(SimulatorTrialOrchestrator orchestrator,
                                 SimulatorTrialRepository trialRepository,
                                 @Qualifier("abEvalLoopExecutor") ExecutorService loopExecutor) {
        this.orchestrator = orchestrator;
        this.trialRepository = trialRepository;
        this.loopExecutor = loopExecutor;
    }

    /**
     * Kick off N trials (one per persona) for a (scenario, candidate) pair.
     * Returns 202 Accepted with the synthesized trial-launch metadata; actual
     * orchestration runs on the loop executor pool. FE polls list / detail
     * endpoints for outcome.
     */
    @PostMapping("/trials")
    public ResponseEntity<?> launchTrials(@RequestBody Map<String, Object> request) {
        Object scenarioObj = request.get("scenarioId");
        if (!(scenarioObj instanceof String scenarioId) || scenarioId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "scenarioId is required"));
        }
        String candidateVersionId = asString(request.get("candidateAgentVersionId"));
        String candidateSurfaceType = asString(request.get("candidateSurfaceType"));
        if (candidateVersionId != null && !candidateVersionId.isBlank()
                && (candidateSurfaceType == null || candidateSurfaceType.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "candidateSurfaceType is required when candidateAgentVersionId is set"));
        }
        // V5 known limitation: behavior_rule candidate inject can't take effect without
        // changing AgentLoopEngine (core 7+1 Iron Law file). Reject at the REST boundary
        // so operator never sees a misleading trial that actually ran baseline. V5.1 backlog.
        if ("behavior_rule".equals(candidateSurfaceType)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "behavior_rule dynamic sim 暂不支持 — V4 结构 limitation，V5.1 backlog；"
                            + "仅 prompt + skill surface 当前可用",
                    "supportedSurfaces", List.of("prompt", "skill")));
        }

        @SuppressWarnings("unchecked")
        List<String> personas = request.get("personas") instanceof List
                ? (List<String>) request.get("personas") : List.of();
        if (personas.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "personas[] is required (at least 1 persona string)"));
        }

        Integer maxTurns = null;
        Object maxTurnsObj = request.get("maxTurns");
        if (maxTurnsObj instanceof Number n) {
            maxTurns = n.intValue();
        }

        // Async fan-out — one trial per persona. We don't pre-create the trial rows
        // here (orchestrator does it inside runTrial) so the response describes the
        // launch intent rather than the persisted trial ids (those come via list
        // endpoint after orchestrator runs).
        for (String persona : personas) {
            final String p = persona;
            final String surface = (candidateSurfaceType == null || candidateSurfaceType.isBlank())
                    ? null : candidateSurfaceType;
            final String vid = (candidateVersionId == null || candidateVersionId.isBlank())
                    ? null : candidateVersionId;
            final Integer turns = maxTurns;
            loopExecutor.submit(() -> {
                try {
                    SimulationOutcome outcome = orchestrator.runTrial(
                            new TrialRequest(scenarioId, vid, surface, p, turns));
                    log.info("[DynamicSimController] trial launched: scenarioId={} persona={} → trialId={} reason={}",
                            scenarioId, p, outcome.trialId(), outcome.terminationReason());
                } catch (Exception e) {
                    log.warn("[DynamicSimController] trial launch failed: scenarioId={} persona={}: {}",
                            scenarioId, p, e.getMessage());
                }
            });
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenarioId", scenarioId);
        body.put("personaCount", personas.size());
        body.put("status", "RUNNING");
        return ResponseEntity.accepted().body(body);
    }

    /**
     * Paginated list. Filters: {@code scenarioId} OR
     * ({@code candidateAgentVersionId} + {@code candidateSurfaceType}). All
     * params optional → returns all trials (paginated).
     */
    @GetMapping("/trials")
    public ResponseEntity<Map<String, Object>> listTrials(
            @RequestParam(required = false) String scenarioId,
            @RequestParam(required = false) String candidateAgentVersionId,
            @RequestParam(required = false) String candidateSurfaceType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int cappedSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<SimulatorTrialEntity> result;
        if (scenarioId != null && !scenarioId.isBlank()) {
            result = trialRepository.findByScenarioId(scenarioId, pageable);
        } else if (candidateAgentVersionId != null && !candidateAgentVersionId.isBlank()
                && candidateSurfaceType != null && !candidateSurfaceType.isBlank()) {
            result = trialRepository.findByCandidateAgentVersionIdAndCandidateSurfaceType(
                    candidateAgentVersionId, candidateSurfaceType, pageable);
        } else {
            result = trialRepository.findAll(pageable);
        }

        List<SimulatorTrialResponse> rows = new ArrayList<>(result.getNumberOfElements());
        for (SimulatorTrialEntity e : result.getContent()) {
            rows.add(SimulatorTrialResponse.from(e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", rows);
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    /**
     * Single trial detail. FE uses {@code sessionId} field to jump to
     * {@code SessionDetail} view.
     */
    @GetMapping("/trials/{trialId}")
    public ResponseEntity<?> getTrial(@PathVariable String trialId) {
        Optional<SimulatorTrialEntity> opt = trialRepository.findById(trialId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Trial not found: " + trialId));
        }
        return ResponseEntity.ok(SimulatorTrialResponse.from(opt.get()));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
