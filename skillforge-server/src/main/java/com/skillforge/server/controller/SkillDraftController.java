package com.skillforge.server.controller;

import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.SkillDraftService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class SkillDraftController {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftController.class);

    private final SkillDraftService skillDraftService;
    private final ExecutorService coordinatorExecutor;

    public SkillDraftController(SkillDraftService skillDraftService,
                                @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor) {
        this.skillDraftService = skillDraftService;
        this.coordinatorExecutor = coordinatorExecutor;
    }

    @PostMapping("/agents/{agentId}/skill-drafts")
    public ResponseEntity<Map<String, Object>> triggerExtraction(
            @PathVariable Long agentId,
            @RequestParam(name = "ownerId", required = false, defaultValue = "0") Long ownerId) {

        if (skillDraftService.hasPendingDrafts(ownerId)) {
            return ResponseEntity.ok(Map.of(
                    "status", "already_has_drafts",
                    "message", "Review or discard existing drafts first"
            ));
        }

        coordinatorExecutor.submit(() -> {
            try {
                skillDraftService.extractFromRecentSessions(agentId, ownerId);
            } catch (Exception e) {
                log.error("Async skill draft extraction failed for agent {}: {}", agentId, e.getMessage(), e);
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "extracting",
                "agentId", agentId
        ));
    }

    @GetMapping("/skill-drafts")
    public ResponseEntity<List<Map<String, Object>>> listDrafts(
            @RequestParam(name = "ownerId", required = false, defaultValue = "0") Long ownerId) {
        List<Map<String, Object>> result = skillDraftService.getDrafts(ownerId)
                .stream().map(this::toMap).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/skill-drafts/{id}")
    public ResponseEntity<Map<String, Object>> reviewDraft(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {

        Object actionObj = body.get("action");
        String action = actionObj != null ? actionObj.toString() : null;
        Long reviewedBy = body.containsKey("reviewedBy") && body.get("reviewedBy") != null
                ? Long.parseLong(body.get("reviewedBy").toString())
                : 0L;

        try {
            SkillDraftEntity result;
            if ("approve".equals(action)) {
                result = skillDraftService.approveDraft(id, reviewedBy);
            } else if ("discard".equals(action)) {
                result = skillDraftService.discardDraft(id, reviewedBy);
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "action must be 'approve' or 'discard'"));
            }
            return ResponseEntity.ok(toMap(result));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> toMap(SkillDraftEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("sourceSessionId", entity.getSourceSessionId());
        map.put("ownerId", entity.getOwnerId());
        map.put("name", entity.getName());
        map.put("description", entity.getDescription());
        map.put("triggers", entity.getTriggers());
        map.put("requiredTools", entity.getRequiredTools());
        map.put("promptHint", entity.getPromptHint());
        map.put("extractionRationale", entity.getExtractionRationale());
        map.put("status", entity.getStatus());
        map.put("skillId", entity.getSkillId());
        map.put("createdAt", entity.getCreatedAt());
        map.put("reviewedAt", entity.getReviewedAt());
        map.put("reviewedBy", entity.getReviewedBy());
        return map;
    }
}
