package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.EvalRunEntity;
import com.skillforge.server.eval.EvalOrchestrator;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.EvalRunRepository;
import com.skillforge.server.repository.EvalSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import jakarta.transaction.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
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
    // evalLoopExecutor is used by ScenarioRunnerTool for inner scenario runs;
    // using it here for outer runEval tasks would cause both pools to block on each other.
    private final ExecutorService evalOrchestratorExecutor;
    private final ScenarioLoader scenarioLoader;

    public EvalController(EvalOrchestrator evalOrchestrator,
                          EvalRunRepository evalRunRepository,
                          EvalSessionRepository evalSessionRepository,
                          ObjectMapper objectMapper,
                          @Qualifier("evalOrchestratorExecutor") ExecutorService evalOrchestratorExecutor,
                          ScenarioLoader scenarioLoader) {
        this.evalOrchestrator = evalOrchestrator;
        this.evalRunRepository = evalRunRepository;
        this.evalSessionRepository = evalSessionRepository;
        this.objectMapper = objectMapper;
        this.evalOrchestratorExecutor = evalOrchestratorExecutor;
        this.scenarioLoader = scenarioLoader;
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

    @Transactional
    @DeleteMapping("/runs/{id}")
    public ResponseEntity<Void> deleteEvalRun(@PathVariable String id) {
        if (!evalRunRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        evalSessionRepository.deleteByEvalRunId(id);
        evalRunRepository.deleteById(id);
        log.info("Deleted eval run: {}", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/scenarios")
    public ResponseEntity<List<Map<String, Object>>> listScenarios() {
        List<EvalScenario> scenarios = scenarioLoader.loadAll()
                .stream()
                .sorted(Comparator.comparing(EvalScenario::getId))
                .collect(Collectors.toList());

        List<Map<String, Object>> result = scenarios.stream()
                .map(s -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", s.getId());
                    map.put("name", s.getName());
                    map.put("description", s.getDescription());
                    map.put("category", s.getCategory());
                    map.put("split", s.getSplit());
                    map.put("task", s.getTask());
                    map.put("maxLoops", s.getMaxLoops());
                    map.put("tags", s.getTags());
                    map.put("toolsHint", s.getToolsHint());
                    map.put("performanceThresholdMs", s.getPerformanceThresholdMs());
                    if (s.getOracle() != null) {
                        Map<String, Object> oracle = new LinkedHashMap<>();
                        oracle.put("type", s.getOracle().getType());
                        oracle.put("expected", s.getOracle().getExpected());
                        oracle.put("expectedList", s.getOracle().getExpectedList());
                        map.put("oracle", oracle);
                    }
                    if (s.getSetup() != null && s.getSetup().getFiles() != null) {
                        map.put("setupFiles", new java.util.ArrayList<>(s.getSetup().getFiles().keySet()));
                    }
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
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
        map.put("attrMemoryInterference", run.getAttrMemoryInterference());
        map.put("attrMemoryMissing", run.getAttrMemoryMissing());
        map.put("startedAt", run.getStartedAt());
        map.put("completedAt", run.getCompletedAt());
        return map;
    }
}
