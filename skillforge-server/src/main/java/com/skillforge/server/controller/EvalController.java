package com.skillforge.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.EvalRunEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.eval.EvalOrchestrator;
import com.skillforge.server.eval.scenario.BaseScenarioService;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.EvalRunRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.EvalSessionRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import jakarta.transaction.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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
    private final EvalScenarioDraftRepository evalScenarioDraftRepository;
    private final SessionRepository sessionRepository;
    private final BaseScenarioService baseScenarioService;

    public EvalController(EvalOrchestrator evalOrchestrator,
                          EvalRunRepository evalRunRepository,
                          EvalSessionRepository evalSessionRepository,
                          ObjectMapper objectMapper,
                          @Qualifier("evalOrchestratorExecutor") ExecutorService evalOrchestratorExecutor,
                          ScenarioLoader scenarioLoader,
                          EvalScenarioDraftRepository evalScenarioDraftRepository,
                          SessionRepository sessionRepository,
                          BaseScenarioService baseScenarioService) {
        this.evalOrchestrator = evalOrchestrator;
        this.evalRunRepository = evalRunRepository;
        this.evalSessionRepository = evalSessionRepository;
        this.objectMapper = objectMapper;
        this.evalOrchestratorExecutor = evalOrchestratorExecutor;
        this.scenarioLoader = scenarioLoader;
        this.evalScenarioDraftRepository = evalScenarioDraftRepository;
        this.sessionRepository = sessionRepository;
        this.baseScenarioService = baseScenarioService;
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
    public ResponseEntity<List<Map<String, Object>>> listScenarios(
            @RequestParam(value = "agentId", required = false) String agentId) {
        // EVAL-V2 M0: when agentId is given, return DB-stored EvalScenarioEntity
        // (per-agent dataset). Without agentId, retain legacy behavior of returning
        // classpath YAML scenarios (used by other consumers).
        if (agentId != null && !agentId.isBlank()) {
            List<EvalScenarioEntity> rows = evalScenarioDraftRepository
                    .findByAgentIdOrderByCreatedAtDesc(agentId);
            List<Map<String, Object>> result = rows.stream()
                    .map(this::toScenarioEntityMap)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(result);
        }

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
                    if (s.getSource() != null) {
                        map.put("source", s.getSource());
                    }
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * EVAL-V2 M0: returns the recent N runs in which the given scenarioId was
     * exercised. Each entry has {evalRunId, completedAt, compositeScore, status}.
     * Implementation parses {@code t_eval_run.scenario_results_json} (jsonb-encoded
     * array of scenario result maps) and extracts the entry whose {@code scenarioId}
     * matches. Missing entries are skipped (a scenario may not run in every batch).
     */
    @GetMapping("/scenarios/{id}/recent-runs")
    public ResponseEntity<List<Map<String, Object>>> getScenarioRecentRuns(
            @PathVariable("id") String scenarioId,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        if (scenarioId == null || scenarioId.isBlank()) {
            return ResponseEntity.badRequest().body(List.of());
        }
        int safeLimit = Math.max(1, Math.min(limit, 50));

        // B1 fix: bound the search to runs of THIS scenario's agent only, and
        // page-bound at safeLimit * 5 so we never hydrate every t_eval_run row
        // (each carries a potentially large scenario_results_json blob).
        // The 5x over-fetch covers eval runs that don't include this specific
        // scenario (e.g. older runs from before the scenario was activated).
        EvalScenarioEntity scenarioEntity = evalScenarioDraftRepository.findById(scenarioId)
                .orElse(null);
        if (scenarioEntity == null) {
            // Classpath YAML scenarios (and any unknown ids) are not stored in
            // t_eval_scenario; the recent-runs endpoint is exposed from the
            // Datasets browser, which only lists DB scenarios — so empty is
            // the correct shape.
            return ResponseEntity.ok(List.of());
        }
        String agentId = scenarioEntity.getAgentId();
        int fetchLimit = Math.max(safeLimit * 5, safeLimit);
        List<EvalRunEntity> runs = evalRunRepository
                .findByAgentDefinitionIdOrderByStartedAtDesc(agentId, PageRequest.of(0, fetchLimit))
                .stream()
                .filter(r -> r.getScenarioResultsJson() != null && !r.getScenarioResultsJson().isBlank())
                .toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (EvalRunEntity run : runs) {
            if (result.size() >= safeLimit) break;
            try {
                List<Map<String, Object>> scenarioResults = objectMapper.readValue(
                        run.getScenarioResultsJson(),
                        new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> sr : scenarioResults) {
                    if (scenarioId.equals(sr.get("scenarioId"))) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("evalRunId", run.getId());
                        entry.put("completedAt", run.getCompletedAt());
                        entry.put("startedAt", run.getStartedAt());
                        entry.put("compositeScore", sr.get("compositeScore"));
                        entry.put("status", sr.get("status"));
                        entry.put("attribution", sr.get("attribution"));
                        result.add(entry);
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse scenarioResultsJson for run {}: {}", run.getId(), e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    /**
     * EVAL-V2 Q1: list chat sessions opened to analyze a given eval scenario
     * (i.e. {@code t_session.source_scenario_id = scenarioId}). Filtered by
     * {@code userId} so users only see their own analysis sessions.
     */
    @GetMapping("/scenarios/{id}/analysis-sessions")
    public ResponseEntity<List<Map<String, Object>>> listAnalysisSessions(
            @PathVariable("id") String scenarioId,
            @RequestParam("userId") Long userId) {
        if (scenarioId == null || scenarioId.isBlank() || userId == null) {
            return ResponseEntity.badRequest().body(List.of());
        }
        List<SessionEntity> sessions = sessionRepository
                .findBySourceScenarioIdAndUserIdOrderByUpdatedAtDesc(scenarioId, userId);
        List<Map<String, Object>> result = sessions.stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("agentId", s.getAgentId());
                    m.put("title", s.getTitle());
                    m.put("status", s.getStatus());
                    m.put("runtimeStatus", s.getRuntimeStatus());
                    m.put("messageCount", s.getMessageCount());
                    m.put("createdAt", s.getCreatedAt());
                    m.put("updatedAt", s.getUpdatedAt());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * EVAL-V2 Q2: write a base eval scenario JSON to the home dir
     * ({@code ~/.skillforge/eval-scenarios/<id>.json}). The classpath seed
     * scenarios stay read-only — anything operators / agents add lands in
     * the home dir so it can be edited / removed without rebuilding the jar.
     *
     * <p>Body is a free-form JSON {@link EvalScenario} payload (id / name /
     * task / oracle / setup / etc.); validation lives in
     * {@link BaseScenarioService}.
     */
    @PostMapping("/scenarios/base")
    public ResponseEntity<Map<String, Object>> addBaseScenario(
            @RequestBody Map<String, Object> body,
            @RequestParam(value = "force", defaultValue = "false") boolean force) {
        try {
            String savedId = baseScenarioService.addBaseScenario(body, force);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", savedId);
            resp.put("name", body.get("name"));
            resp.put("task", body.get("task"));
            resp.put("category", body.getOrDefault("category", "session_derived"));
            resp.put("split", body.getOrDefault("split", "held_out"));
            resp.put("source", EvalScenario.SOURCE_HOME);
            resp.put("status", "saved");
            resp.put("path", baseScenarioService.pathFor(savedId).toString());
            return ResponseEntity.status(201).body(resp);
        } catch (BaseScenarioService.ScenarioAlreadyExistsException e) {
            // 409 Conflict — operator must opt in to overwrite via ?force=true
            return ResponseEntity.status(409).body(Map.of(
                    "error", e.getMessage(),
                    "id", e.getId(),
                    "hint", "pass ?force=true to overwrite"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to add base scenario", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * EVAL-V2 Q2/Q3: list "base" scenarios — classpath seeds + home dir
     * additions, merged by id (classpath wins on collision). Each entry has a
     * {@code source} field ({@code classpath} | {@code home}) so the UI can
     * label "system built-in" vs "user-added". No agent filter — base
     * scenarios are global to the build.
     */
    @GetMapping("/scenarios/base")
    public ResponseEntity<List<Map<String, Object>>> listBaseScenarios() {
        List<EvalScenario> scenarios = scenarioLoader.loadBaseScenarios()
                .stream()
                .sorted(Comparator.comparing(EvalScenario::getId))
                .toList();
        List<Map<String, Object>> result = scenarios.stream()
                .map(this::toBaseScenarioMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toBaseScenarioMap(EvalScenario s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("name", s.getName());
        map.put("description", s.getDescription());
        map.put("category", s.getCategory());
        map.put("split", s.getSplit());
        map.put("task", s.getTask());
        map.put("source", s.getSource());
        if (s.getOracle() != null) {
            map.put("oracleType", s.getOracle().getType());
            map.put("oracleExpected", s.getOracle().getExpected());
        }
        return map;
    }

    private Map<String, Object> toScenarioEntityMap(EvalScenarioEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("agentId", entity.getAgentId());
        map.put("name", entity.getName());
        map.put("description", entity.getDescription());
        map.put("category", entity.getCategory());
        map.put("split", entity.getSplit());
        map.put("task", entity.getTask());
        map.put("oracleType", entity.getOracleType());
        map.put("oracleExpected", entity.getOracleExpected());
        map.put("status", entity.getStatus());
        map.put("sourceSessionId", entity.getSourceSessionId());
        map.put("extractionRationale", entity.getExtractionRationale());
        map.put("createdAt", entity.getCreatedAt());
        map.put("reviewedAt", entity.getReviewedAt());
        return map;
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
