package com.skillforge.server.mcp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.mcp.dto.McpServerRequest;
import com.skillforge.server.mcp.dto.McpServerResponse;
import com.skillforge.server.mcp.entity.McpServerEntity;
import com.skillforge.server.mcp.exception.McpServerInUseException;
import com.skillforge.server.mcp.exception.McpServerNotFoundException;
import com.skillforge.server.mcp.service.McpServerLifecycle;
import com.skillforge.server.mcp.service.McpServerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST surface for {@code /api/mcp-servers}.
 *
 * <p>{@code userId} is required on every endpoint per the project ownership pattern
 * (matches ScheduledTaskController / SkillDraftController). Service layer accepts
 * the id; the actual admin-only enforcement is currently a no-op TODO (Q9 ratify
 * said "global resource, admin only" — this MVP relies on the dashboard hiding
 * the page from non-admin users; a future hardening pass will add a real check).
 */
@RestController
@RequestMapping("/api/mcp-servers")
public class McpServerController {

    private final McpServerService service;
    private final McpServerLifecycle lifecycle;
    private final ObjectMapper objectMapper;

    public McpServerController(McpServerService service,
                               McpServerLifecycle lifecycle,
                               ObjectMapper objectMapper) {
        this.service = service;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestParam Long userId, @RequestBody McpServerRequest req) {
        try {
            McpServerEntity created = service.create(userId, req);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(McpServerResponse.from(created, objectMapper,
                            lifecycle.runtimeStatus(created),
                            lifecycle.liveTools(created)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<McpServerResponse>> list(@RequestParam Long userId) {
        List<McpServerResponse> rows = service.list().stream()
                .map(e -> McpServerResponse.from(e, objectMapper,
                        lifecycle.runtimeStatus(e), lifecycle.liveTools(e)))
                .collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id, @RequestParam Long userId) {
        try {
            McpServerEntity entity = service.get(id);
            return ResponseEntity.ok(McpServerResponse.from(entity, objectMapper,
                    lifecycle.runtimeStatus(entity), lifecycle.liveTools(entity)));
        } catch (McpServerNotFoundException nf) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", nf.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestParam Long userId,
                                    @RequestBody McpServerRequest req) {
        try {
            McpServerEntity updated = service.update(id, userId, req);
            return ResponseEntity.ok(McpServerResponse.from(updated, objectMapper,
                    lifecycle.runtimeStatus(updated), lifecycle.liveTools(updated)));
        } catch (McpServerNotFoundException nf) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", nf.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam Long userId) {
        try {
            service.delete(id, userId);
            return ResponseEntity.noContent().build();
        } catch (McpServerInUseException inUse) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", inUse.getMessage(),
                    "referencingAgents", inUse.getReferencingAgentNames()
            ));
        } catch (McpServerNotFoundException nf) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", nf.getMessage()));
        }
    }

    @PostMapping("/{id}/test-connection")
    public ResponseEntity<?> testConnection(@PathVariable Long id, @RequestParam Long userId) {
        try {
            List<Map<String, Object>> tools = lifecycle.testConnection(id);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "tools", tools
            ));
        } catch (McpServerNotFoundException nf) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", nf.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", "error",
                    "error", e.getMessage()
            ));
        }
    }
}
