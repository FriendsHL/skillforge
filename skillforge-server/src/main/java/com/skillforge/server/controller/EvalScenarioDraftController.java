package com.skillforge.server.controller;

import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.improve.SessionScenarioExtractorService;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agents")
public class EvalScenarioDraftController {

    private static final Logger log = LoggerFactory.getLogger(EvalScenarioDraftController.class);

    private final SessionScenarioExtractorService extractorService;
    private final EvalScenarioDraftRepository evalScenarioDraftRepository;
    private final ExecutorService coordinatorExecutor;

    public EvalScenarioDraftController(SessionScenarioExtractorService extractorService,
                                       EvalScenarioDraftRepository evalScenarioDraftRepository,
                                       @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor) {
        this.extractorService = extractorService;
        this.evalScenarioDraftRepository = evalScenarioDraftRepository;
        this.coordinatorExecutor = coordinatorExecutor;
    }

    @PostMapping("/{agentId}/scenario-drafts")
    public ResponseEntity<Map<String, Object>> triggerExtraction(@PathVariable String agentId) {
        // Validate agentId format upfront to surface errors before async dispatch
        if (!agentId.matches("\\d+")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid agentId: must be a numeric string"));
        }

        // Basic idempotency guard: if draft scenarios already exist, warn the caller
        long existingDrafts = evalScenarioDraftRepository
                .findByAgentIdAndStatus(agentId, "draft").size();
        if (existingDrafts > 0) {
            return ResponseEntity.ok(Map.of(
                    "status", "already_has_drafts",
                    "agentId", agentId,
                    "draftCount", existingDrafts,
                    "message", "Review or discard existing drafts before generating more"
            ));
        }

        // Submit to I/O-bound executor (reuses abEvalCoordinatorExecutor, avoids ForkJoinPool starvation)
        coordinatorExecutor.submit(() -> {
            try {
                extractorService.extractFromSessions(agentId, null);
            } catch (Exception e) {
                log.error("Async scenario extraction failed for agent {}: {}", agentId, e.getMessage(), e);
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "extracting",
                "agentId", agentId
        ));
    }

    @GetMapping("/{agentId}/scenario-drafts")
    public ResponseEntity<List<Map<String, Object>>> listDrafts(@PathVariable String agentId) {
        List<EvalScenarioEntity> scenarios = evalScenarioDraftRepository
                .findByAgentIdOrderByCreatedAtDesc(agentId);

        List<Map<String, Object>> result = scenarios.stream()
                .map(this::toMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PatchMapping("/scenario-drafts/{id}")
    public ResponseEntity<Map<String, Object>> reviewDraft(
            @PathVariable String id,
            @RequestBody Map<String, String> request) {

        return evalScenarioDraftRepository.findById(id)
                .map(entity -> {
                    String action = request.get("action");
                    if ("approve".equals(action)) {
                        // Apply optional edits before approving
                        if (request.containsKey("name") && request.get("name") != null) {
                            entity.setName(request.get("name"));
                        }
                        if (request.containsKey("task") && request.get("task") != null) {
                            entity.setTask(request.get("task"));
                        }
                        if (request.containsKey("oracleExpected") && request.get("oracleExpected") != null) {
                            entity.setOracleExpected(request.get("oracleExpected"));
                        }
                        entity.setStatus("active");
                        entity.setReviewedAt(Instant.now());
                    } else if ("discard".equals(action)) {
                        entity.setStatus("discarded");
                        entity.setReviewedAt(Instant.now());
                    } else {
                        return ResponseEntity.badRequest()
                                .body(Map.<String, Object>of("error", "action must be 'approve' or 'discard'"));
                    }

                    evalScenarioDraftRepository.save(entity);
                    return ResponseEntity.ok(toMap(entity));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMap(EvalScenarioEntity entity) {
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
        map.put("extractionRationale", entity.getExtractionRationale());
        map.put("createdAt", entity.getCreatedAt());
        map.put("reviewedAt", entity.getReviewedAt());
        return map;
    }
}
