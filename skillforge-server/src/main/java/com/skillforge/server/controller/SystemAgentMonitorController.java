package com.skillforge.server.controller;

import com.skillforge.server.dto.SystemAgentMonitorResponse;
import com.skillforge.server.service.SystemAgentMonitorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SYSTEM-AGENT-TYPING Phase 2.1: REST surface for the
 * {@code /api/system-agents/monitor} observability endpoint (PRD F4).
 *
 * <p>The thin pass-through delegates aggregation to
 * {@link SystemAgentMonitorService#monitorAll()}. The endpoint is unauthenticated
 * for now (no admin RBAC — see {@code requirements/active/SYSTEM-AGENT-TYPING/index.md}
 * "不在范围内: cross-tenant / RBAC"); the SECURITY-ADMIN-RBAC backlog item is the
 * place to add gating once the surface needs it.
 */
@RestController
@RequestMapping("/api/system-agents")
public class SystemAgentMonitorController {

    private final SystemAgentMonitorService monitorService;

    public SystemAgentMonitorController(SystemAgentMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/monitor")
    public ResponseEntity<List<SystemAgentMonitorResponse>> monitorAll() {
        return ResponseEntity.ok(monitorService.monitorAll());
    }
}
