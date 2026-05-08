package com.skillforge.server.controller;

import com.skillforge.server.memory.MemoryConsolidationScheduler;
import com.skillforge.server.memory.MemoryConsolidationScheduler.ConsolidationSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MEMORY-DREAM-CONSOLIDATION — temporary admin endpoint to manually trigger the
 * {@link MemoryConsolidationScheduler#runOnce(Long)} cron without waiting for the
 * 03:30 schedule. Useful for E2E / Phase Final verification in dev.
 *
 * <p>Mirrors {@link AdminSkillEvolveLoopController}'s pattern. Replace with proper
 * admin auth + UI button when the dashboard surface is added (V2 in
 * MEMORY-DREAM-CONSOLIDATION/index.md).
 */
@RestController
@RequestMapping("/api/admin/memory")
public class AdminMemoryConsolidationController {

    private static final Logger log = LoggerFactory.getLogger(AdminMemoryConsolidationController.class);

    private final MemoryConsolidationScheduler scheduler;

    public AdminMemoryConsolidationController(MemoryConsolidationScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * {@code POST /api/admin/memory/consolidation/run-once} — runs consolidation now.
     *
     * @param userId optional; when present, restricts the run to that single user.
     *               When omitted, scans every recently-active user.
     */
    @PostMapping("/consolidation/run-once")
    public ResponseEntity<?> triggerConsolidation(@RequestParam(value = "userId", required = false) Long userId) {
        log.info("Admin manual trigger: MemoryConsolidationScheduler.runOnce(userId={})", userId);
        try {
            ConsolidationSummary summary = scheduler.runOnce(userId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("ran", "memory-consolidation");
            body.put("at", Instant.now().toString());
            body.put("userIdFilter", userId);
            body.put("eligible", summary.eligible());
            body.put("succeeded", summary.succeeded());
            body.put("failed", summary.failed());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("Admin trigger memory-consolidation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage() == null ? "unknown" : e.getMessage()));
        }
    }
}
