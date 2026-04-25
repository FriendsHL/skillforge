package com.skillforge.server.controller;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.exception.AgentNotFoundException;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.AgentYamlMapper;
import com.skillforge.server.service.LifecycleHookViewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;
    private final LifecycleHookViewService lifecycleHookViewService;

    @Autowired
    public AgentController(AgentService agentService,
                           LifecycleHookViewService lifecycleHookViewService) {
        this.agentService = agentService;
        this.lifecycleHookViewService = lifecycleHookViewService;
    }

    /** Test-only convenience constructor for controller tests that don't exercise hook views. */
    AgentController(AgentService agentService) {
        this(agentService, null);
    }

    @GetMapping
    public ResponseEntity<List<AgentEntity>> listAgents(
            @RequestParam(required = false) Long ownerId) {
        List<AgentEntity> agents = agentService.listAgents(ownerId);
        return ResponseEntity.ok(agents);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentEntity> getAgent(@PathVariable Long id) {
        AgentEntity agent = agentService.getAgent(id);
        return ResponseEntity.ok(agent);
    }

    @GetMapping("/{id}/hooks")
    public ResponseEntity<?> getAgentHooks(@PathVariable Long id) {
        try {
            if (lifecycleHookViewService == null) {
                return ResponseEntity.status(501).body(Map.of("error", "lifecycle hook view service unavailable"));
            }
            return ResponseEntity.ok(lifecycleHookViewService.getAgentHooks(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/hooks/user")
    public ResponseEntity<?> updateUserHooks(@PathVariable Long id,
                                             @RequestBody Map<String, Object> body) {
        try {
            if (lifecycleHookViewService == null) {
                return ResponseEntity.status(501).body(Map.of("error", "lifecycle hook view service unavailable"));
            }
            Object raw = body.get("rawJson");
            agentService.updateLifecycleHooks(id, raw != null ? raw.toString() : null);
            return ResponseEntity.ok(lifecycleHookViewService.getAgentHooks(id).get("user"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createAgent(@RequestBody AgentEntity agent) {
        try {
            AgentEntity created = agentService.createAgent(agent);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateAgent(@PathVariable Long id,
                                          @RequestBody AgentEntity agent) {
        try {
            AgentEntity updated = agentService.updateAgent(id, agent);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAgent(@PathVariable Long id) {
        agentService.deleteAgent(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Import an agent from YAML. Accepts application/x-yaml, text/yaml or
     * application/yaml. Parses the body into an AgentEntity via
     * AgentYamlMapper and delegates persistence to AgentService.
     */
    @PostMapping(value = "/import",
            consumes = {"application/x-yaml", "text/yaml", "application/yaml"})
    public ResponseEntity<?> importAgent(@RequestBody String yamlBody) {
        try {
            AgentEntity entity = AgentYamlMapper.fromYaml(yamlBody);
            AgentEntity created = agentService.createAgent(entity);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Export an agent as YAML text matching the CLI schema (list-valued
     * {@code skills:} instead of the JSON-string {@code skillIds}).
     */
    @GetMapping(value = "/{id}/export", produces = "application/yaml; charset=utf-8")
    public ResponseEntity<String> exportAgent(@PathVariable Long id) {
        try {
            AgentEntity agent = agentService.getAgent(id);
            String yaml = AgentYamlMapper.toYaml(agent);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/yaml; charset=utf-8"))
                    .body(yaml);
        } catch (AgentNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
