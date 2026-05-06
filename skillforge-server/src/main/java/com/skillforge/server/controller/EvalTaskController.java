package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.dto.EvalAnalysisCreateRequest;
import com.skillforge.server.dto.EvalAnalysisSessionResponse;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.EvalTaskItemEntity;
import com.skillforge.server.entity.EvalAnalysisSessionEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.eval.EvalOrchestrator;
import com.skillforge.server.improve.ImprovementConflictException;
import com.skillforge.server.improve.ImprovementIneligibleException;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.repository.EvalTaskItemRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.service.EvalAnalysisSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * EVAL-V2 M3a (b2): new task-shaped API surface.
 *
 * <ul>
 *   <li>{@code POST /api/eval/tasks} — replaces {@code POST /api/eval/runs} for
 *       the new model. Inserts a PENDING {@link EvalTaskEntity} row + dispatches
 *       async runEval. Optional {@code datasetFilter} JSON.</li>
 *   <li>{@code GET /api/eval/tasks?agentId&status} — list tasks, filter by
 *       agentId / status.</li>
 *   <li>{@code GET /api/eval/tasks/{id}} — task detail (top-level aggregates).</li>
 *   <li>{@code GET /api/eval/tasks/{id}/items} — list per-case rows from
 *       {@link EvalTaskItemEntity}.</li>
 *   <li>{@code GET /api/eval/tasks/{id}/items/{itemId}} — single item detail.</li>
 * </ul>
 *
 * <p>The legacy {@code /api/eval/runs*} endpoints in {@link EvalController}
 * are retained for backward compat (FE migrates in M3a-b3, full removal in
 * M3a-b3 + 1 sprint).
 */
@RestController
@RequestMapping("/api/eval/tasks")
public class EvalTaskController {

    private static final Logger log = LoggerFactory.getLogger(EvalTaskController.class);

    private final EvalOrchestrator evalOrchestrator;
    private final EvalTaskRepository evalTaskRepository;
    private final EvalTaskItemRepository evalTaskItemRepository;
    private final ExecutorService evalOrchestratorExecutor;
    private final ObjectMapper objectMapper;
    private final EvalAnalysisSessionService evalAnalysisSessionService;
    private final PromptImproverService promptImproverService;

    public EvalTaskController(EvalOrchestrator evalOrchestrator,
                               EvalTaskRepository evalTaskRepository,
                               EvalTaskItemRepository evalTaskItemRepository,
                               @Qualifier("evalOrchestratorExecutor") ExecutorService evalOrchestratorExecutor,
                               ObjectMapper objectMapper,
                               EvalAnalysisSessionService evalAnalysisSessionService,
                               PromptImproverService promptImproverService) {
        this.evalOrchestrator = evalOrchestrator;
        this.evalTaskRepository = evalTaskRepository;
        this.evalTaskItemRepository = evalTaskItemRepository;
        this.evalOrchestratorExecutor = evalOrchestratorExecutor;
        this.objectMapper = objectMapper;
        this.evalAnalysisSessionService = evalAnalysisSessionService;
        this.promptImproverService = promptImproverService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody Map<String, Object> request) {
        Object rawAgentId = request.get("agentId");
        if (rawAgentId == null || String.valueOf(rawAgentId).isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId is required"));
        }
        String agentId = String.valueOf(rawAgentId).trim();
        try {
            Long.parseLong(agentId);
        } catch (NumberFormatException nfe) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "agentId must be a numeric string (got: " + agentId + ")"));
        }

        Long userId = request.containsKey("userId") && request.get("userId") != null
                ? ((Number) request.get("userId")).longValue() : 1L;

        String datasetFilterJson = null;
        Object rawFilter = request.get("datasetFilter");
        if (rawFilter != null) {
            try {
                datasetFilterJson = (rawFilter instanceof String s)
                        ? s
                        : objectMapper.writeValueAsString(rawFilter);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "datasetFilter must be JSON-serializable"));
            }
        }

        String taskId = UUID.randomUUID().toString();

        // Persist PENDING row so the task surfaces in list immediately.
        EvalTaskEntity task = new EvalTaskEntity();
        task.setId(taskId);
        task.setAgentDefinitionId(agentId);
        task.setStatus("PENDING");
        task.setStartedAt(Instant.now());
        task.setDatasetFilter(datasetFilterJson);
        task.setTriggeredByUserId(userId);
        evalTaskRepository.save(task);

        // Async dispatch.
        evalOrchestratorExecutor.submit(() -> evalOrchestrator.runEval(agentId, userId, taskId));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", taskId);
        resp.put("agentId", agentId);
        resp.put("status", "PENDING");
        if (datasetFilterJson != null) resp.put("datasetFilter", datasetFilterJson);
        return ResponseEntity.accepted().body(resp);
    }

    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareTasks(@RequestParam("ids") String ids) {
        List<String> taskIds = ids == null ? List.of() : java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (taskIds.size() != 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "compare requires exactly 2 task ids"));
        }

        List<EvalTaskEntity> tasks = new ArrayList<>();
        Map<String, List<EvalTaskItemEntity>> itemsByTaskId = new LinkedHashMap<>();
        for (String taskId : taskIds) {
            EvalTaskEntity task = evalTaskRepository.findById(taskId).orElse(null);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }
            tasks.add(task);
            itemsByTaskId.put(taskId, evalTaskItemRepository.findByTaskIdOrderByCreatedAtAsc(taskId));
        }

        Set<String> sharedScenarioIds = new HashSet<>(itemsByTaskId.get(taskIds.get(0)).stream()
                .map(EvalTaskItemEntity::getScenarioId)
                .collect(Collectors.toSet()));
        sharedScenarioIds.retainAll(itemsByTaskId.get(taskIds.get(1)).stream()
                .map(EvalTaskItemEntity::getScenarioId)
                .collect(Collectors.toSet()));

        List<Map<String, Object>> rows = itemsByTaskId.get(taskIds.get(0)).stream()
                .filter(item -> sharedScenarioIds.contains(item.getScenarioId()))
                .map(leftItem -> {
                    EvalTaskItemEntity rightItem = itemsByTaskId.get(taskIds.get(1)).stream()
                            .filter(item -> leftItem.getScenarioId().equals(item.getScenarioId()))
                            .findFirst()
                            .orElse(null);
                    if (rightItem == null) {
                        return null;
                    }
                    return toCompareRow(leftItem.getScenarioId(), List.of(leftItem, rightItem));
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("scenarioId"))))
                .collect(Collectors.toList());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskCount", tasks.size());
        resp.put("scenarioCount", rows.size());
        resp.put("tasks", tasks.stream().map(this::toSummaryMap).collect(Collectors.toList()));
        resp.put("rows", rows);
        return ResponseEntity.ok(resp);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTasks(
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "status", required = false) String status) {

        List<EvalTaskEntity> tasks;
        if (agentId != null && !agentId.isBlank() && status != null && !status.isBlank()) {
            tasks = evalTaskRepository
                    .findByAgentDefinitionIdAndStatusOrderByStartedAtDesc(agentId, status);
        } else if (agentId != null && !agentId.isBlank()) {
            tasks = evalTaskRepository.findByAgentDefinitionIdOrderByStartedAtDesc(agentId);
        } else {
            tasks = evalTaskRepository.findAll();
            tasks.sort((a, b) -> {
                if (a.getStartedAt() == null || b.getStartedAt() == null) return 0;
                return b.getStartedAt().compareTo(a.getStartedAt());
            });
        }

        List<Map<String, Object>> result = tasks.stream()
                .map(this::toSummaryMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable String id) {
        return evalTaskRepository.findById(id)
                .map(task -> {
                    Map<String, Object> detail = toSummaryMap(task);
                    detail.put("errorMessage", task.getErrorMessage());
                    detail.put("collabRunId", task.getCollabRunId());
                    detail.put("consecutiveDeclineCount", task.getConsecutiveDeclineCount());
                    detail.put("attributionSummary", task.getAttributionSummary());
                    detail.put("improvementSuggestion", task.getImprovementSuggestion());
                    detail.put("analysisSessionId", task.getAnalysisSessionId());
                    detail.put("datasetFilter", task.getDatasetFilter());
                    detail.put("itemCount", evalTaskItemRepository.countByTaskId(id));
                    return ResponseEntity.ok(detail);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/items")
    public ResponseEntity<List<Map<String, Object>>> listItems(@PathVariable String id) {
        if (!evalTaskRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<EvalTaskItemEntity> items = evalTaskItemRepository.findByTaskIdOrderByCreatedAtAsc(id);
        List<Map<String, Object>> result = items.stream()
                .map(this::toItemMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/items/{itemId}")
    public ResponseEntity<Map<String, Object>> getItem(@PathVariable String id,
                                                        @PathVariable Long itemId) {
        return evalTaskItemRepository.findById(itemId)
                .filter(item -> id.equals(item.getTaskId()))
                .map(item -> ResponseEntity.ok(toItemMap(item)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/analysis-sessions")
    public ResponseEntity<List<Map<String, Object>>> listTaskAnalysisSessions(@PathVariable String id,
                                                                              @RequestParam("userId") Long userId) {
        if (!evalTaskRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> result = evalAnalysisSessionService.listTaskAnalysisSessions(id, userId)
                .stream()
                .map(view -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("sessionId", view.session().getId());
                    map.put("analysisType", view.link().getAnalysisType());
                    map.put("taskId", view.link().getTaskId());
                    map.put("itemId", view.link().getTaskItemId());
                    map.put("scenarioId", view.link().getScenarioId());
                    map.put("title", view.session().getTitle());
                    map.put("runtimeStatus", view.session().getRuntimeStatus());
                    map.put("messageCount", view.session().getMessageCount());
                    map.put("createdAt", view.session().getCreatedAt());
                    map.put("updatedAt", view.session().getUpdatedAt());
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<?> analyzeTask(@PathVariable String id,
                                         @RequestBody EvalAnalysisCreateRequest request) {
        if (evalTaskRepository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Long userId = request.userId();
        Long agentId = request.agentId();
        if (userId == null || agentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId and agentId are required"));
        }
        SessionEntity session = evalAnalysisSessionService.createTaskOverallAnalysisSession(userId, agentId, id);
        return ResponseEntity.ok(new EvalAnalysisSessionResponse(
                session.getId(),
                EvalAnalysisSessionEntity.TYPE_RUN_OVERALL,
                null,
                id,
                null
        ));
    }

    @PostMapping("/{id}/items/{itemId}/analyze")
    public ResponseEntity<?> analyzeItem(@PathVariable String id,
                                         @PathVariable Long itemId,
                                         @RequestBody EvalAnalysisCreateRequest request) {
        if (evalTaskRepository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var itemOpt = evalTaskItemRepository.findById(itemId).filter(item -> id.equals(item.getTaskId()));
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Long userId = request.userId();
        Long agentId = request.agentId();
        if (userId == null || agentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId and agentId are required"));
        }
        EvalTaskItemEntity item = itemOpt.get();
        SessionEntity session = evalAnalysisSessionService.createTaskItemAnalysisSession(
                userId, agentId, id, itemId, item.getScenarioId());
        return ResponseEntity.ok(new EvalAnalysisSessionResponse(
                session.getId(),
                EvalAnalysisSessionEntity.TYPE_RUN_CASE,
                item.getScenarioId(),
                id,
                itemId
        ));
    }

    @PostMapping("/{id}/apply-improvement")
    public ResponseEntity<?> applyImprovement(@PathVariable String id,
                                              @RequestBody(required = false) Map<String, Object> request) {
        EvalTaskEntity task = evalTaskRepository.findById(id).orElse(null);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        if (task.getImprovementSuggestion() == null || task.getImprovementSuggestion().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "improvementSuggestion is required"));
        }

        long userId = 1L;
        if (request != null && request.get("userId") instanceof Number number) {
            userId = number.longValue();
        }

        try {
            ImprovementStartResult result = promptImproverService.startImprovement(
                    task.getAgentDefinitionId(),
                    task.getId(),
                    userId,
                    task.getImprovementSuggestion()
            );
            return ResponseEntity.accepted().body(result);
        } catch (ImprovementConflictException e) {
            return ResponseEntity.status(409).body(Map.of("error", "ALREADY_IMPROVING", "message", e.getMessage()));
        } catch (ImprovementIneligibleException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getReason(), "message", e.getMessage()));
        }
    }

    private Map<String, Object> toSummaryMap(EvalTaskEntity task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("agentDefinitionId", task.getAgentDefinitionId());
        map.put("status", task.getStatus());
        map.put("scenarioCount", task.getScenarioCount());
        map.put("totalScenarios", task.getTotalScenarios());
        map.put("passCount", task.getPassCount());
        map.put("failCount", task.getFailCount());
        map.put("compositeAvg", task.getCompositeAvg());
        // legacy fields kept for FE backward compat
        map.put("overallPassRate", task.getOverallPassRate());
        map.put("avgOracleScore", task.getAvgOracleScore());
        map.put("failedScenarios", task.getFailedScenarios());
        map.put("timeoutScenarios", task.getTimeoutScenarios());
        map.put("vetoScenarios", task.getVetoScenarios());
        map.put("primaryAttribution", task.getPrimaryAttribution() != null
                ? task.getPrimaryAttribution().name() : null);
        map.put("attrSkillMissing", task.getAttrSkillMissing());
        map.put("attrSkillExecFailure", task.getAttrSkillExecFailure());
        map.put("attrPromptQuality", task.getAttrPromptQuality());
        map.put("attrContextOverflow", task.getAttrContextOverflow());
        map.put("attrPerformance", task.getAttrPerformance());
        map.put("attrMemoryInterference", task.getAttrMemoryInterference());
        map.put("attrMemoryMissing", task.getAttrMemoryMissing());
        map.put("startedAt", task.getStartedAt());
        map.put("completedAt", task.getCompletedAt());
        return map;
    }

    private Map<String, Object> toItemMap(EvalTaskItemEntity item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("taskId", item.getTaskId());
        map.put("scenarioId", item.getScenarioId());
        map.put("scenarioSource", item.getScenarioSource());
        map.put("sessionId", item.getSessionId());
        map.put("rootTraceId", item.getRootTraceId());
        map.put("compositeScore", item.getCompositeScore());
        map.put("qualityScore", item.getQualityScore());
        map.put("efficiencyScore", item.getEfficiencyScore());
        map.put("latencyScore", item.getLatencyScore());
        map.put("costScore", item.getCostScore());
        map.put("costUsd", item.getCostUsd());
        map.put("scoreFormulaVersion", item.getScoreFormulaVersion());
        map.put("scoreBreakdownJson", item.getScoreBreakdownJson());
        map.put("status", item.getStatus());
        map.put("loopCount", item.getLoopCount());
        map.put("toolCallCount", item.getToolCallCount());
        map.put("latencyMs", item.getLatencyMs());
        map.put("attribution", item.getAttribution());
        map.put("judgeRationale", item.getJudgeRationale());
        map.put("agentFinalOutput", item.getAgentFinalOutput());
        map.put("startedAt", item.getStartedAt());
        map.put("completedAt", item.getCompletedAt());
        map.put("createdAt", item.getCreatedAt());
        return map;
    }

    private Map<String, Object> toCompareRow(String scenarioId, List<EvalTaskItemEntity> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scenarioId", scenarioId);
        List<Map<String, Object>> entries = items.stream()
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("taskId", item.getTaskId());
                    entry.put("status", item.getStatus());
                    entry.put("compositeScore", item.getCompositeScore());
                    entry.put("qualityScore", item.getQualityScore());
                    entry.put("efficiencyScore", item.getEfficiencyScore());
                    entry.put("latencyScore", item.getLatencyScore());
                    entry.put("costScore", item.getCostScore());
                    entry.put("costUsd", item.getCostUsd());
                    entry.put("scoreFormulaVersion", item.getScoreFormulaVersion());
                    entry.put("attribution", item.getAttribution());
                    entry.put("latencyMs", item.getLatencyMs());
                    entry.put("loopCount", item.getLoopCount());
                    entry.put("toolCallCount", item.getToolCallCount());
                    entry.put("rootTraceId", item.getRootTraceId());
                    entry.put("agentFinalOutput", item.getAgentFinalOutput());
                    return entry;
                })
                .collect(Collectors.toList());
        map.put("entries", entries);

        double maxScore = items.stream()
                .map(EvalTaskItemEntity::getCompositeScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .max()
                .orElse(0D);
        double minScore = items.stream()
                .map(EvalTaskItemEntity::getCompositeScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .min()
                .orElse(0D);
        map.put("scoreDelta", maxScore - minScore);

        List<String> outputs = items.stream()
                .map(EvalTaskItemEntity::getAgentFinalOutput)
                .filter(output -> output != null && !output.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
        map.put("outputDiffers", outputs.size() > 1);
        return map;
    }
}
