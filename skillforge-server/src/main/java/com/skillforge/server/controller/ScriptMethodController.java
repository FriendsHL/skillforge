package com.skillforge.server.controller;

import com.skillforge.server.code.ScriptMethodService;
import com.skillforge.server.entity.ScriptMethodEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD endpoints for dynamically registered script hook methods.
 *
 * <ul>
 *   <li>{@code GET    /api/script-methods}        — list all</li>
 *   <li>{@code GET    /api/script-methods/{id}}   — fetch one</li>
 *   <li>{@code POST   /api/script-methods}        — create + register</li>
 *   <li>{@code PUT    /api/script-methods/{id}}   — update + re-register (if enabled)</li>
 *   <li>{@code POST   /api/script-methods/{id}/enable} — toggle enabled</li>
 *   <li>{@code DELETE /api/script-methods/{id}}   — delete + unregister</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/script-methods")
public class ScriptMethodController {

    private static final Logger log = LoggerFactory.getLogger(ScriptMethodController.class);

    private final ScriptMethodService scriptMethodService;

    public ScriptMethodController(ScriptMethodService scriptMethodService) {
        this.scriptMethodService = scriptMethodService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> result = scriptMethodService.listAll().stream()
                .map(ScriptMethodController::toSummaryMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return scriptMethodService.findById(id)
                .<ResponseEntity<?>>map(e -> ResponseEntity.ok(toMap(e)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "script method not found: id=" + id)));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            ScriptMethodEntity saved = scriptMethodService.create(new ScriptMethodService.CreateRequest(
                    asString(body.get("ref")),
                    asString(body.get("displayName")),
                    asString(body.get("description")),
                    asString(body.get("lang")),
                    asString(body.get("scriptBody")),
                    asString(body.get("argsSchema")),
                    asLong(body.get("ownerId"))
            ));
            return ResponseEntity.status(201).body(toMap(saved));
        } catch (ScriptMethodService.ScriptMethodException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            ScriptMethodEntity saved = scriptMethodService.update(id, new ScriptMethodService.UpdateRequest(
                    asString(body.get("displayName")),
                    asString(body.get("description")),
                    asString(body.get("lang")),
                    asString(body.get("scriptBody")),
                    asString(body.get("argsSchema"))
            ));
            return ResponseEntity.ok(toMap(saved));
        } catch (ScriptMethodService.ScriptMethodException e) {
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<?> toggle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object enabledRaw = body.get("enabled");
        if (!(enabledRaw instanceof Boolean enabled)) {
            return ResponseEntity.badRequest().body(Map.of("error", "enabled must be boolean"));
        }
        try {
            ScriptMethodEntity saved = scriptMethodService.setEnabled(id, enabled);
            return ResponseEntity.ok(toMap(saved));
        } catch (ScriptMethodService.ScriptMethodException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            scriptMethodService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (ScriptMethodService.ScriptMethodException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toSummaryMap(ScriptMethodEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ref", e.getRef());
        m.put("displayName", e.getDisplayName());
        m.put("description", e.getDescription());
        m.put("lang", e.getLang());
        m.put("ownerId", e.getOwnerId());
        m.put("enabled", e.isEnabled());
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> toMap(ScriptMethodEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ref", e.getRef());
        m.put("displayName", e.getDisplayName());
        m.put("description", e.getDescription());
        m.put("lang", e.getLang());
        m.put("scriptBody", e.getScriptBody());
        m.put("argsSchema", e.getArgsSchema());
        m.put("ownerId", e.getOwnerId());
        m.put("enabled", e.isEnabled());
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
