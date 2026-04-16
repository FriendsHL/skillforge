package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.EvalRunEntity;
import com.skillforge.server.eval.EvalOrchestrator;
import com.skillforge.server.repository.EvalRunRepository;
import com.skillforge.server.repository.EvalSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private static final Logger log = LoggerFactory.getLogger(EvalController.class);

    private final EvalOrchestrator evalOrchestrator;
    private final EvalRunRepository evalRunRepository;
    private final EvalSessionRepository evalSessionRepository;
    private final ObjectMapper objectMapper;
    // Use evalOrchestratorExecutor (not evalLoopExecutor) to prevent nested-pool deadlock:
    // evalLoopExecutor is used by ScenarioRunnerSkill for inner scenario runs;
    // using it here for outer runEval tasks would cause both pools to block on each other.
    private final ExecutorService evalOrchestratorExecutor;

    public EvalController(EvalOrchestrator evalOrchestrator,
                          EvalRunRepository evalRunRepository,
                          EvalSessionRepository evalSessionRepository,
                          ObjectMapper objectMapper,
                          @Qualifier("evalOrchestratorExecutor") ExecutorService evalOrchestratorExecutor) {
        this.evalOrchestrator = evalOrchestrator;
        this.evalRunRepository = evalRunRepository;
        this.evalSessionRepository = evalSessionRepository;
        this.objectMapper = objectMapper;
        this.evalOrchestratorExecutor = evalOrchestratorExecutor;
    }

    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> triggerEvalRun(@RequestBody Map<String, Object> request) {
        String agentId = (String) request.get("agentId");
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId is required"));
        }
        Long userId = request.containsKey("userId")
                ? ((Number) request.get("userId")).longValue() : 1L;

        String evalRunId = UUID.randomUUID().toString();
        evalOrchestratorExecutor.submit(() -> evalOrchestrator.runEval(agentId, userId, evalRunId));

        return ResponseEntity.accepted().body(Map.of("evalRunId", evalRunId, "status", "RUNNING"));
    }

    @GetMapping("/runs")
    public ResponseEntity<List<Map<String, Object>>> listEvalRuns() {
        List<EvalRunEntity> runs = evalRunRepository.findAll();
        List<Map<String, Object>> result = runs.stream()
                .sorted((a, b) -> {
                    if (a.getStartedAt() == null || b.getStartedAt() == null) return 0;
                    return b.getStartedAt().compareTo(a.getStartedAt());
                })
                .map(this::toSummaryMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> getEvalRun(@PathVariable String id) {
        return evalRunRepository.findById(id)
                .map(run -> {
                    Map<String, Object> detail = toSummaryMap(run);
                    // Parse scenarioResultsJson
                    if (run.getScenarioResultsJson() != null) {
                        try {
                            detail.put("scenarioResults", objectMapper.readValue(
                                    run.getScenarioResultsJson(), List.class));
                        } catch (Exception e) {
                            detail.put("scenarioResults", run.getScenarioResultsJson());
                        }
                    }
                    // Parse improvementSuggestionsJson
                    if (run.getImprovementSuggestionsJson() != null) {
                        try {
                            detail.put("improvementSuggestions", objectMapper.readValue(
                                    run.getImprovementSuggestionsJson(), List.class));
                        } catch (Exception e) {
                            detail.put("improvementSuggestions", run.getImprovementSuggestionsJson());
                        }
                    }
                    detail.put("errorMessage", run.getErrorMessage());
                    detail.put("collabRunId", run.getCollabRunId());
                    detail.put("consecutiveDeclineCount", run.getConsecutiveDeclineCount());
                    // Include eval sessions
                    detail.put("evalSessions", evalSessionRepository.findByEvalRunId(id));
                    return ResponseEntity.ok(detail);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toSummaryMap(EvalRunEntity run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", run.getId());
        map.put("agentDefinitionId", run.getAgentDefinitionId());
        map.put("status", run.getStatus());
        map.put("overallPassRate", run.getOverallPassRate());
        map.put("avgOracleScore", run.getAvgOracleScore());
        map.put("totalScenarios", run.getTotalScenarios());
        map.put("passedScenarios", run.getPassedScenarios());
        map.put("failedScenarios", run.getFailedScenarios());
        map.put("timeoutScenarios", run.getTimeoutScenarios());
        map.put("vetoScenarios", run.getVetoScenarios());
        map.put("primaryAttribution", run.getPrimaryAttribution() != null
                ? run.getPrimaryAttribution().name() : null);
        map.put("attrSkillMissing", run.getAttrSkillMissing());
        map.put("attrSkillExecFailure", run.getAttrSkillExecFailure());
        map.put("attrPromptQuality", run.getAttrPromptQuality());
        map.put("attrContextOverflow", run.getAttrContextOverflow());
        map.put("attrPerformance", run.getAttrPerformance());
        map.put("startedAt", run.getStartedAt());
        map.put("completedAt", run.getCompletedAt());
        return map;
    }
}
