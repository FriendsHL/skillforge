package com.skillforge.server.controller;

import com.skillforge.server.entity.EvalAnnotationEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.EvalTaskItemEntity;
import com.skillforge.server.repository.EvalAnnotationRepository;
import com.skillforge.server.repository.EvalTaskItemRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/eval/annotations")
public class EvalAnnotationController {

    private final EvalAnnotationRepository evalAnnotationRepository;
    private final EvalTaskItemRepository evalTaskItemRepository;
    private final EvalTaskRepository evalTaskRepository;

    public EvalAnnotationController(EvalAnnotationRepository evalAnnotationRepository,
                                    EvalTaskItemRepository evalTaskItemRepository,
                                    EvalTaskRepository evalTaskRepository) {
        this.evalAnnotationRepository = evalAnnotationRepository;
        this.evalTaskItemRepository = evalTaskItemRepository;
        this.evalTaskRepository = evalTaskRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAnnotations(
            @RequestParam(value = "status", required = false) String status) {
        List<EvalAnnotationEntity> rows = (status == null || status.isBlank())
                ? evalAnnotationRepository.findAllByOrderByCreatedAtDesc()
                : evalAnnotationRepository.findByStatusOrderByCreatedAtDesc(status);
        return ResponseEntity.ok(rows.stream().map(this::toMap).collect(Collectors.toList()));
    }

    @PostMapping
    public ResponseEntity<?> createAnnotation(@RequestBody Map<String, Object> request) {
        Long taskItemId = asLong(request.get("taskItemId"));
        Long annotatorId = asLong(request.get("annotatorId"));
        if (taskItemId == null || annotatorId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "taskItemId and annotatorId are required"));
        }
        Optional<EvalTaskItemEntity> itemOpt = evalTaskItemRepository.findById(taskItemId);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        EvalAnnotationEntity entity = new EvalAnnotationEntity();
        entity.setTaskItemId(taskItemId);
        entity.setAnnotatorId(annotatorId);
        entity.setOriginalScore(itemOpt.get().getCompositeScore());
        entity.setCorrectedScore(asBigDecimal(request.get("correctedScore")));
        entity.setCorrectedExpected(asString(request.get("correctedExpected")));
        entity.setStatus(EvalAnnotationEntity.STATUS_PENDING);
        EvalAnnotationEntity saved = evalAnnotationRepository.save(entity);
        return ResponseEntity.status(201).body(toMap(saved));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> updateAnnotation(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        Optional<EvalAnnotationEntity> opt = evalAnnotationRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        EvalAnnotationEntity entity = opt.get();
        String status = asString(request.get("status"));
        if (status == null || status.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }
        if (!EvalAnnotationEntity.STATUS_PENDING.equals(status) && !EvalAnnotationEntity.STATUS_APPLIED.equals(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "status must be pending or applied"));
        }
        entity.setStatus(status);
        entity.setAppliedAt(EvalAnnotationEntity.STATUS_APPLIED.equals(status) ? Instant.now() : null);
        if (request.containsKey("correctedScore")) {
            entity.setCorrectedScore(asBigDecimal(request.get("correctedScore")));
        }
        if (request.containsKey("correctedExpected")) {
            entity.setCorrectedExpected(asString(request.get("correctedExpected")));
        }
        return ResponseEntity.ok(toMap(evalAnnotationRepository.save(entity)));
    }

    private Map<String, Object> toMap(EvalAnnotationEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("taskItemId", entity.getTaskItemId());
        map.put("annotatorId", entity.getAnnotatorId());
        map.put("originalScore", entity.getOriginalScore());
        map.put("correctedScore", entity.getCorrectedScore());
        map.put("correctedExpected", entity.getCorrectedExpected());
        map.put("status", entity.getStatus());
        map.put("createdAt", entity.getCreatedAt());
        map.put("appliedAt", entity.getAppliedAt());

        evalTaskItemRepository.findById(entity.getTaskItemId()).ifPresent(item -> {
            map.put("taskId", item.getTaskId());
            map.put("scenarioId", item.getScenarioId());
            map.put("scenarioSource", item.getScenarioSource());
            map.put("itemStatus", item.getStatus());
            map.put("attribution", item.getAttribution());
            map.put("rootTraceId", item.getRootTraceId());
            map.put("judgeRationale", item.getJudgeRationale());
            map.put("agentFinalOutput", item.getAgentFinalOutput());
            EvalTaskEntity task = evalTaskRepository.findById(item.getTaskId()).orElse(null);
            if (task != null) {
                map.put("agentDefinitionId", task.getAgentDefinitionId());
                map.put("taskStatus", task.getStatus());
            }
        });
        return map;
    }

    private static Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asString(Object value) {
        if (value == null) return null;
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }
}
