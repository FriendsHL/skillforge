package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryRolloutEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.3: REST surface for canary rollout
 * lifecycle + read endpoints.
 *
 * <p>Seven endpoints (tech-design §7.1):
 * <ol>
 *   <li>{@code POST /api/canary/rollouts} — start a new canary (HTTP 201)</li>
 *   <li>{@code PATCH /api/canary/rollouts/{id}/step-up} — raise rollout %</li>
 *   <li>{@code POST /api/canary/rollouts/{id}/publish} — promote to production</li>
 *   <li>{@code POST /api/canary/rollouts/{id}/rollback} — manual rollback</li>
 *   <li>{@code GET /api/canary/rollouts/{id}} — detail</li>
 *   <li>{@code GET /api/canary/rollouts/{id}/metrics?limit=24} — last N hourly snapshots</li>
 *   <li>{@code GET /api/canary/rollouts?agentId=&surfaceType=&stage=} — list/filter</li>
 * </ol>
 *
 * <p>HTTP status mapping:
 * <ul>
 *   <li>{@link IllegalArgumentException} → {@code 400 Bad Request}</li>
 *   <li>{@link NoSuchElementException} → {@code 404 Not Found}</li>
 *   <li>{@link CanaryStateException} → {@code 409 Conflict}</li>
 * </ul>
 *
 * <p>Auth: V1 single-tenant dogfood (same as InsightsController / TracesController).
 */
@RestController
@RequestMapping("/api/canary/rollouts")
public class CanaryRolloutController {

    private static final Logger log = LoggerFactory.getLogger(CanaryRolloutController.class);

    /** Default metrics page size for {@code GET /{id}/metrics?limit=}. */
    static final int DEFAULT_METRICS_LIMIT = 24;
    /** Hard cap on metrics page size to keep the response bounded. */
    static final int MAX_METRICS_LIMIT = 168; // 7 days × 24 hours

    private final CanaryRolloutService canaryRolloutService;

    public CanaryRolloutController(CanaryRolloutService canaryRolloutService) {
        this.canaryRolloutService = canaryRolloutService;
    }

    /** Wire schema for {@code POST /api/canary/rollouts}. */
    public record StartCanaryRequest(Long agentId,
                                     String surfaceType,
                                     String baselineSkillName,
                                     String candidateSkillName,
                                     Integer percentage) {}

    /** Wire schema for {@code PATCH /api/canary/rollouts/{id}/step-up}. */
    public record StepUpRequest(Integer percentage) {}

    /** Wire schema for {@code POST /api/canary/rollouts/{id}/rollback}. */
    public record RollbackRequest(String reason) {}

    // ───────────────────────── mutations ─────────────────────────

    @PostMapping
    public ResponseEntity<?> startCanary(@RequestBody StartCanaryRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "request body is required"));
        }
        try {
            int pct = req.percentage() == null ? 0 : req.percentage();
            CanaryRolloutEntity created = canaryRolloutService.startCanary(
                    req.agentId(),
                    req.surfaceType(),
                    req.baselineSkillName(),
                    req.candidateSkillName(),
                    pct);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(CanaryRolloutResponse.from(created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (CanaryStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            // Phase 1.3 r1 review W1: covers the concurrent-startCanary race where two
            // requests both pass the application-side `findActiveCanaryByAgentAndSurface`
            // pre-check (both see empty), and the second write trips the Postgres
            // `uq_canary_active` partial UNIQUE index. Without this catch Spring's
            // default handler returns 500 — semantically wrong, the client just lost
            // a race they could retry; 409 is the right signal.
            log.info("CanaryRolloutController.startCanary: DB unique violation (likely concurrent start race) — returning 409");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Active canary already exists for this agent and surface"));
        }
    }

    @PatchMapping("/{id}/step-up")
    public ResponseEntity<?> stepUp(@PathVariable Long id,
                                    @RequestBody StepUpRequest req) {
        if (req == null || req.percentage() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "percentage is required"));
        }
        try {
            CanaryRolloutEntity updated = canaryRolloutService.stepUp(id, req.percentage());
            return ResponseEntity.ok(CanaryRolloutResponse.from(updated));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (CanaryStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id) {
        try {
            CanaryRolloutEntity updated = canaryRolloutService.publish(id);
            return ResponseEntity.ok(CanaryRolloutResponse.from(updated));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (CanaryStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/rollback")
    public ResponseEntity<?> rollback(@PathVariable Long id,
                                      @RequestBody(required = false) RollbackRequest req) {
        String reason = (req != null && req.reason() != null) ? req.reason() : "manual";
        try {
            CanaryRolloutEntity updated = canaryRolloutService.rollback(id, reason);
            return ResponseEntity.ok(CanaryRolloutResponse.from(updated));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (CanaryStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    // ───────────────────────── reads ─────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        try {
            CanaryRolloutEntity entity = canaryRolloutService.findById(id);
            return ResponseEntity.ok(CanaryRolloutResponse.from(entity));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/metrics")
    public ResponseEntity<?> metrics(@PathVariable Long id,
                                     @RequestParam(value = "limit", required = false) Integer limitParam) {
        int limit = clampMetricsLimit(limitParam);
        try {
            List<MetricSnapshotResponse> result = canaryRolloutService
                    .findMetricSnapshots(id, limit).stream()
                    .map(MetricSnapshotResponse::from)
                    .toList();
            return ResponseEntity.ok(result);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List/filter rollouts. {@code agentId} is required; {@code surfaceType}
     * defaults to {@code skill}; {@code stage} (optional) filters to a single
     * lifecycle stage.
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam Long agentId,
                                  @RequestParam(value = "surfaceType", required = false) String surfaceType,
                                  @RequestParam(value = "stage", required = false) String stage) {
        try {
            List<CanaryRolloutResponse> result = canaryRolloutService
                    .listByAgent(agentId, surfaceType, stage).stream()
                    .map(CanaryRolloutResponse::from)
                    .toList();
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static int clampMetricsLimit(Integer raw) {
        if (raw == null || raw <= 0) {
            return DEFAULT_METRICS_LIMIT;
        }
        return Math.min(raw, MAX_METRICS_LIMIT);
    }
}
