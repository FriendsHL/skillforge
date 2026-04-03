package com.skillforge.server.controller;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentService;
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

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
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

    @PostMapping
    public ResponseEntity<AgentEntity> createAgent(@RequestBody AgentEntity agent) {
        AgentEntity created = agentService.createAgent(agent);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentEntity> updateAgent(@PathVariable Long id,
                                                    @RequestBody AgentEntity agent) {
        AgentEntity updated = agentService.updateAgent(id, agent);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAgent(@PathVariable Long id) {
        agentService.deleteAgent(id);
        return ResponseEntity.ok().build();
    }
}
