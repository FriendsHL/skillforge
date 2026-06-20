package com.skillforge.server.controller;

import com.skillforge.server.dto.SkillCuratorCandidateDto;
import com.skillforge.server.skill.curate.SkillConsolidator;
import com.skillforge.server.skill.curate.SkillConsolidator.ConsolidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    /** {@code POST /api/admin/skill-consolidation/run} — runs the curator now (honors dry-run). */
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

    /**
     * SKILL-CURATOR human-in-loop preview.
     * {@code GET /api/admin/skill-consolidation/candidates} — returns the rows the
     * curator <em>would</em> archive, WITHOUT mutating anything. Drives the dashboard
     * "技能整理" candidate table. Returns a bare JSON array (matches the listSkills
     * bare-array contract the FE expects).
     */
    @GetMapping("/candidates")
    public ResponseEntity<?> candidates() {
        try {
            List<SkillCuratorCandidateDto> dtos = consolidator.findCandidates().stream()
                    .map(SkillCuratorCandidateDto::from)
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("Admin skill-consolidation candidates failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage() == null ? "unknown" : e.getMessage()));
        }
    }

    /**
     * SKILL-CURATOR human-in-loop manual apply.
     * {@code POST /api/admin/skill-consolidation/apply} — archives the current
     * candidates for REAL (bypasses the dry-run prop; the operator explicitly clicked
     * "归档这些"). The cron still defaults to dry-run via {@code /run} / the scheduler.
     */
    @PostMapping("/apply")
    public ResponseEntity<?> apply() {
        log.info("Admin manual apply: SkillConsolidator.applyArchival() (real archival)");
        try {
            ConsolidationResult result = consolidator.applyArchival();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Admin skill-consolidation apply failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage() == null ? "unknown" : e.getMessage()));
        }
    }
}
