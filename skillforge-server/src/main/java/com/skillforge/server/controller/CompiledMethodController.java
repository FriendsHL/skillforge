package com.skillforge.server.controller;

import com.skillforge.server.code.CompiledMethodService;
import com.skillforge.server.entity.CompiledMethodEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for dynamically compiled Java hook methods.
 *
 * <ul>
 *   <li>{@code GET    /api/compiled-methods}            — list (summary, no sourceCode)</li>
 *   <li>{@code GET    /api/compiled-methods/{id}}       — full details</li>
 *   <li>{@code POST   /api/compiled-methods}            — submit (pending_review)</li>
 *   <li>{@code POST   /api/compiled-methods/{id}/compile} — compile source to bytecode</li>
 *   <li>{@code POST   /api/compiled-methods/{id}/approve} — load + register (active)</li>
 *   <li>{@code POST   /api/compiled-methods/{id}/reject}  — reject</li>
 *   <li>{@code DELETE /api/compiled-methods/{id}}       — delete + unregister</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/compiled-methods")
public class CompiledMethodController {

    private static final Logger log = LoggerFactory.getLogger(CompiledMethodController.class);

    private final CompiledMethodService service;

    public CompiledMethodController(CompiledMethodService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> result = service.listAll().stream()
                .map(CompiledMethodController::toSummaryMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return service.findById(id)
                .<ResponseEntity<?>>map(e -> ResponseEntity.ok(toMap(e)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "compiled method not found: id=" + id)));
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody Map<String, Object> body) {
        try {
            CompiledMethodEntity saved = service.submit(new CompiledMethodService.SubmitRequest(
                    asString(body.get("ref")),
                    asString(body.get("displayName")),
                    asString(body.get("description")),
                    asString(body.get("sourceCode")),
                    asString(body.get("argsSchema")),
                    asString(body.get("sessionId")),
                    asLong(body.get("agentId"))
            ));
            return ResponseEntity.status(201).body(toMap(saved));
        } catch (CompiledMethodService.CompiledMethodException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/compile")
    public ResponseEntity<?> compile(@PathVariable Long id) {
        try {
            CompiledMethodEntity saved = service.compile(id);
            return ResponseEntity.ok(toMap(saved));
        } catch (CompiledMethodService.CompiledMethodException e) {
            return respondForException(e);
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long reviewerUserId = body == null ? null : asLong(body.get("reviewerUserId"));
        try {
            CompiledMethodEntity saved = service.approve(id, reviewerUserId);
            return ResponseEntity.ok(toMap(saved));
        } catch (CompiledMethodService.CompiledMethodException e) {
            return respondForException(e);
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long reviewerUserId = body == null ? null : asLong(body.get("reviewerUserId"));
        try {
            CompiledMethodEntity saved = service.reject(id, reviewerUserId);
            return ResponseEntity.ok(toMap(saved));
        } catch (CompiledMethodService.CompiledMethodException e) {
            return respondForException(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (CompiledMethodService.CompiledMethodException e) {
            return respondForException(e);
        }
    }

    private static ResponseEntity<?> respondForException(CompiledMethodService.CompiledMethodException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("not found")) {
            return ResponseEntity.status(404).body(Map.of("error", msg));
        }
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private static Map<String, Object> toSummaryMap(CompiledMethodEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ref", e.getRef());
        m.put("displayName", e.getDisplayName());
        m.put("description", e.getDescription());
        m.put("status", e.getStatus());
        m.put("compileError", e.getCompileError());
        m.put("generatedBySessionId", e.getGeneratedBySessionId());
        m.put("generatedByAgentId", e.getGeneratedByAgentId());
        m.put("reviewedByUserId", e.getReviewedByUserId());
        m.put("hasCompiledBytes", e.getCompiledClassBytes() != null && e.getCompiledClassBytes().length > 0);
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> toMap(CompiledMethodEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ref", e.getRef());
        m.put("displayName", e.getDisplayName());
        m.put("description", e.getDescription());
        m.put("sourceCode", e.getSourceCode());
        m.put("argsSchema", e.getArgsSchema());
        m.put("status", e.getStatus());
        m.put("compileError", e.getCompileError());
        m.put("generatedBySessionId", e.getGeneratedBySessionId());
        m.put("generatedByAgentId", e.getGeneratedByAgentId());
        m.put("reviewedByUserId", e.getReviewedByUserId());
        m.put("hasCompiledBytes", e.getCompiledClassBytes() != null && e.getCompiledClassBytes().length > 0);
        m.put("compiledBytesLength",
                e.getCompiledClassBytes() == null ? 0 : e.getCompiledClassBytes().length);
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
