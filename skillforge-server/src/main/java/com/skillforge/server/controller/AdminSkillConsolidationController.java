package com.skillforge.server.controller;

import com.skillforge.server.skill.curate.SkillConsolidator;
import com.skillforge.server.skill.curate.SkillConsolidator.ConsolidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SKILL-CURATOR V1 — admin endpoint to manually trigger the skill curator without
 * waiting for the 03:45 cron. Useful for E2E / Phase Final verification of the
 * dry-run behaviour. Mirrors {@code AdminMemoryConsolidationController}; sits behind
 * the existing {@code AuthInterceptor} which gates {@code /api/**}.
 */
@RestController
@RequestMapping("/api/admin/skill-consolidation")
public class AdminSkillConsolidationController {

    private static final Logger log = LoggerFactory.getLogger(AdminSkillConsolidationController.class);

    private final SkillConsolidator consolidator;

    public AdminSkillConsolidationController(SkillConsolidator consolidator) {
        this.consolidator = consolidator;
    }

    /** {@code POST /api/admin/skill-consolidation/run} — runs the curator now. */
    @PostMapping("/run")
    public ResponseEntity<?> run() {
        log.info("Admin manual trigger: SkillConsolidator.consolidate()");
        try {
            ConsolidationResult result = consolidator.consolidate();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Admin trigger skill-consolidation failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage() == null ? "unknown" : e.getMessage()));
        }
    }
}
