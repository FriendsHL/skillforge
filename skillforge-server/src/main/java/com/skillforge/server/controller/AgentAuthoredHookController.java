package com.skillforge.server.controller;

import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.service.AgentAuthoredHookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/agent-authored-hooks")
public class AgentAuthoredHookController {

    private final AgentAuthoredHookService service;

    public AgentAuthoredHookController(AgentAuthoredHookService service) {
        this.service = service;
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        try {
            AgentAuthoredHookEntity saved = service.approve(id, null, reviewNote(body));
            return ResponseEntity.ok(toMap(saved));
        } catch (IllegalArgumentException e) {
            return respond(e);
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        try {
            AgentAuthoredHookEntity saved = service.reject(id, null, reviewNote(body));
            return ResponseEntity.ok(toMap(saved));
        } catch (IllegalArgumentException e) {
            return respond(e);
        }
    }

    @PostMapping("/{id}/retire")
    public ResponseEntity<?> retire(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        try {
            AgentAuthoredHookEntity saved = service.retire(id, null, reviewNote(body));
            return ResponseEntity.ok(toMap(saved));
        } catch (IllegalArgumentException e) {
            return respond(e);
        }
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        try {
            Object raw = body.get("enabled");
            if (!(raw instanceof Boolean enabled)) {
                return ResponseEntity.badRequest().body(Map.of("error", "enabled boolean is required"));
            }
            AgentAuthoredHookEntity saved = service.setEnabled(id, enabled);
            return ResponseEntity.ok(toMap(saved));
        } catch (IllegalArgumentException e) {
            return respond(e);
        }
    }

    private static ResponseEntity<?> respond(IllegalArgumentException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("not found")) {
            return ResponseEntity.status(404).body(Map.of("error", msg));
        }
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static String reviewNote(Map<String, Object> body) {
        Object value = body != null ? body.get("reviewNote") : null;
        return value != null ? value.toString() : null;
    }

    private static Map<String, Object> toMap(AgentAuthoredHookEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("targetAgentId", e.getTargetAgentId());
        map.put("authorAgentId", e.getAuthorAgentId());
        map.put("authorSessionId", e.getAuthorSessionId());
        map.put("event", e.getEvent());
        map.put("methodKind", e.getMethodKind());
        map.put("methodId", e.getMethodId());
        map.put("methodRef", e.getMethodRef());
        map.put("displayName", e.getDisplayName());
        map.put("reviewState", e.getReviewState());
        map.put("reviewNote", e.getReviewNote());
        map.put("reviewedByUserId", e.getReviewedByUserId());
        map.put("reviewedAt", e.getReviewedAt());
        map.put("enabled", e.isEnabled());
        map.put("createdAt", e.getCreatedAt());
        map.put("updatedAt", e.getUpdatedAt());
        return map;
    }
}
