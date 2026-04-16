package com.skillforge.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.improve.ImprovementConflictException;
import com.skillforge.server.improve.ImprovementIneligibleException;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.PromptPromotionService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agents")
public class PromptImproveController {

    private static final Logger log = LoggerFactory.getLogger(PromptImproveController.class);

    private final PromptImproverService promptImproverService;
    private final PromptPromotionService promptPromotionService;
    private final PromptAbRunRepository promptAbRunRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public PromptImproveController(PromptImproverService promptImproverService,
                                    PromptPromotionService promptPromotionService,
                                    PromptAbRunRepository promptAbRunRepository,
                                    PromptVersionRepository promptVersionRepository,
                                    AgentRepository agentRepository,
                                    ObjectMapper objectMapper) {
        this.promptImproverService = promptImproverService;
        this.promptPromotionService = promptPromotionService;
        this.promptAbRunRepository = promptAbRunRepository;
        this.promptVersionRepository = promptVersionRepository;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{agentId}/prompt-improve")
    public ResponseEntity<?> startImprovement(@PathVariable String agentId,
                                               @RequestBody Map<String, Object> request) {
        String evalRunId = (String) request.get("evalRunId");
        if (evalRunId == null || evalRunId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "evalRunId is required"));
        }
        long userId = request.containsKey("userId")
                ? ((Number) request.get("userId")).longValue() : 1L;

        try {
            ImprovementStartResult result = promptImproverService.startImprovement(agentId, evalRunId, userId);
            return ResponseEntity.accepted().body(result);
        } catch (ImprovementConflictException e) {
            return ResponseEntity.status(409).body(Map.of("error", "ALREADY_IMPROVING", "message", e.getMessage()));
        } catch (ImprovementIneligibleException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getReason(), "message", e.getMessage()));
        }
    }

    @GetMapping("/{agentId}/prompt-improve/{abRunId}")
    public ResponseEntity<?> getAbRun(@PathVariable String agentId, @PathVariable String abRunId) {
        return promptAbRunRepository.findById(abRunId)
                .filter(r -> r.getAgentId().equals(agentId))
                .map(abRun -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("abRunId", abRun.getId());
                    result.put("status", abRun.getStatus());
                    result.put("deltaPassRate", abRun.getDeltaPassRate());
                    result.put("candidatePassRate", abRun.getCandidatePassRate());
                    result.put("baselinePassRate", abRun.getBaselinePassRate());
                    result.put("promoted", abRun.isPromoted());
                    result.put("startedAt", abRun.getStartedAt());
                    result.put("completedAt", abRun.getCompletedAt());
                    result.put("failureReason", abRun.getFailureReason());

                    // Parse scenario results and compute completedScenarios count
                    if (abRun.getAbScenarioResultsJson() != null) {
                        try {
                            List<Map<String, Object>> scenarios = objectMapper.readValue(
                                    abRun.getAbScenarioResultsJson(),
                                    new TypeReference<List<Map<String, Object>>>() {});
                            result.put("scenarioResults", scenarios);
                            result.put("completedScenarios", scenarios.size());
                        } catch (Exception e) {
                            result.put("scenarioResults", List.of());
                            result.put("completedScenarios", 0);
                        }
                    } else {
                        result.put("scenarioResults", List.of());
                        result.put("completedScenarios", 0);
                    }

                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{agentId}/prompt-improve/active")
    public ResponseEntity<?> getActiveAbRun(@PathVariable String agentId) {
        // Look for RUNNING first, then recently COMPLETED (within 2h)
        List<PromptAbRunEntity> running = promptAbRunRepository.findByAgentIdAndStatus(agentId, "RUNNING");
        if (!running.isEmpty()) {
            PromptAbRunEntity abRun = running.get(0);
            return ResponseEntity.ok(toAbRunSummary(abRun));
        }

        // Check for recently completed
        Instant twoHoursAgo = Instant.now().minusSeconds(2 * 3600);
        return promptAbRunRepository.findTopByAgentIdAndStatusInOrderByCompletedAtDesc(
                        agentId, List.of("COMPLETED", "FAILED"))
                .filter(r -> r.getCompletedAt() != null && r.getCompletedAt().isAfter(twoHoursAgo))
                .map(abRun -> ResponseEntity.ok(toAbRunSummary(abRun)))
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{agentId}/prompt-versions")
    public ResponseEntity<List<Map<String, Object>>> listVersions(@PathVariable String agentId) {
        List<Map<String, Object>> versions = promptVersionRepository
                .findByAgentIdOrderByVersionNumberDesc(agentId).stream()
                .map(v -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", v.getId());
                    map.put("agentId", v.getAgentId());
                    map.put("versionNumber", v.getVersionNumber());
                    map.put("status", v.getStatus());
                    map.put("source", v.getSource());
                    map.put("deltaPassRate", v.getDeltaPassRate());
                    map.put("baselinePassRate", v.getBaselinePassRate());
                    map.put("createdAt", v.getCreatedAt());
                    map.put("promotedAt", v.getPromotedAt());
                    map.put("deprecatedAt", v.getDeprecatedAt());
                    // content excluded in list view
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(versions);
    }

    @GetMapping("/{agentId}/prompt-versions/{versionId}")
    public ResponseEntity<?> getVersion(@PathVariable String agentId, @PathVariable String versionId) {
        return promptVersionRepository.findById(versionId)
                .filter(v -> v.getAgentId().equals(agentId))
                .map(v -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", v.getId());
                    map.put("agentId", v.getAgentId());
                    map.put("versionNumber", v.getVersionNumber());
                    map.put("status", v.getStatus());
                    map.put("source", v.getSource());
                    map.put("content", v.getContent());
                    map.put("deltaPassRate", v.getDeltaPassRate());
                    map.put("baselinePassRate", v.getBaselinePassRate());
                    map.put("improvementRationale", v.getImprovementRationale());
                    map.put("sourceEvalRunId", v.getSourceEvalRunId());
                    map.put("abRunId", v.getAbRunId());
                    map.put("createdAt", v.getCreatedAt());
                    map.put("promotedAt", v.getPromotedAt());
                    map.put("deprecatedAt", v.getDeprecatedAt());
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{agentId}/prompt-versions/{versionId}/rollback")
    public ResponseEntity<?> rollback(@PathVariable String agentId, @PathVariable String versionId) {
        Long agentLongId = parseAgentId(agentId);
        if (agentLongId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid agentId"));
        }

        AgentEntity agent = agentRepository.findById(agentLongId).orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        PromptVersionEntity targetVersion = promptVersionRepository.findById(versionId)
                .filter(v -> v.getAgentId().equals(agentId))
                .orElse(null);
        if (targetVersion == null) {
            return ResponseEntity.notFound().build();
        }

        if (!"deprecated".equals(targetVersion.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Can only rollback to a deprecated version"));
        }

        // Delegate to service for @Transactional atomic write (3 DB writes)
        promptPromotionService.rollbackToVersion(agent, targetVersion);

        log.info("Rolled back agent {} to prompt version {}", agentId, versionId);
        return ResponseEntity.ok(Map.of("status", "rolled_back", "versionId", versionId));
    }

    @PostMapping("/{agentId}/prompt-improve/resume")
    public ResponseEntity<?> resume(@PathVariable String agentId) {
        Long agentLongId = parseAgentId(agentId);
        if (agentLongId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid agentId"));
        }

        AgentEntity agent = agentRepository.findById(agentLongId).orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        agent.setAbDeclineCount(0);
        agent.setAutoImprovePaused(false);
        agentRepository.save(agent);

        log.info("Resumed auto-improve for agent {}", agentId);
        return ResponseEntity.ok(Map.of("status", "resumed", "agentId", agentId));
    }

    /** Parses agentId path variable to Long, returns null if not a valid number. */
    private Long parseAgentId(String agentId) {
        try {
            return Long.parseLong(agentId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> toAbRunSummary(PromptAbRunEntity abRun) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("abRunId", abRun.getId());
        map.put("status", abRun.getStatus());
        map.put("deltaPassRate", abRun.getDeltaPassRate());
        map.put("candidatePassRate", abRun.getCandidatePassRate());
        map.put("baselinePassRate", abRun.getBaselinePassRate());
        map.put("promoted", abRun.isPromoted());
        map.put("startedAt", abRun.getStartedAt());
        map.put("completedAt", abRun.getCompletedAt());
        return map;
    }
}
