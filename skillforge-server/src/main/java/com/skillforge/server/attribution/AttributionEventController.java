package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.4 — REST surface for the optimization-event
 * timeline + operator approve/reject/retry endpoints.
 *
 * <p>Five endpoints (per Phase 1.4 brief):
 * <ol>
 *   <li>{@code GET  /api/attribution/events} — paginated list with
 *       stage / agentId / surfaceType filters; {@code created_at DESC}.</li>
 *   <li>{@code GET  /api/attribution/events/{id}} — detail by id.</li>
 *   <li>{@code POST /api/attribution/events/{id}/approve} — operator approve
 *       a {@code proposal_pending} event; triggers candidate generation.</li>
 *   <li>{@code POST /api/attribution/events/{id}/reject} — operator reject
 *       a {@code proposal_pending} event; reason folded into description.</li>
 *   <li>{@code POST /api/attribution/events/{id}/retry} — operator retry
 *       a {@code candidate_failed} event (Phase 1.4 manual-retry per
 *       tech-design §6).</li>
 * </ol>
 *
 * <p>HTTP status mapping (matches {@code CanaryRolloutController} idiom):
 * <ul>
 *   <li>{@link IllegalArgumentException} → {@code 400 Bad Request}</li>
 *   <li>{@link NoSuchElementException} → {@code 404 Not Found}</li>
 *   <li>{@link IllegalStateException} → {@code 409 Conflict}
 *       (stage-machine violations, unsupported surface)</li>
 * </ul>
 *
 * <p>Auth: V1 single-tenant dogfood pattern (matches InsightsController /
 * CanaryRolloutController). Phase 2 will introduce role-based gating.
 */
@RestController
@RequestMapping("/api/attribution/events")
public class AttributionEventController {

    private static final Logger log = LoggerFactory.getLogger(AttributionEventController.class);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 200;

    private final OptimizationEventRepository eventRepository;
    private final AttributionApprovalService approvalService;

    public AttributionEventController(OptimizationEventRepository eventRepository,
                                      AttributionApprovalService approvalService) {
        this.eventRepository = eventRepository;
        this.approvalService = approvalService;
    }

    /** Wire schema for {@code POST /{id}/approve} and {@code POST /{id}/retry}. */
    public record ApproveOrRetryRequest(Long approverUserId) {}

    /** Wire schema for {@code POST /{id}/reject}. */
    public record RejectRequest(Long approverUserId, String reason) {}

    // ───────────────────────── reads ─────────────────────────

    /**
     * Paginated list with optional stage / agentId / surfaceType filters.
     * Defaults: page=0, size={@value DEFAULT_PAGE_SIZE}, sort=created_at DESC.
     * Page size is clamped to [1, {@value MAX_PAGE_SIZE}].
     *
     * <p>Phase 1.4 reviewer fix (W2/W3): single repository finder
     * {@code findFiltered} with optional null binding handles every filter
     * combination, returning a true {@link Page} so {@code total} reflects
     * actual matching row count (previously stream-post-filtered, breaking
     * pagination math). Blank-string filter params are normalised to null
     * before dispatch so {@code ?stage=} (empty) doesn't mismatch every row.
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "stage", required = false) String stage,
                                  @RequestParam(value = "agentId", required = false) Long agentId,
                                  @RequestParam(value = "surfaceType", required = false) String surfaceType,
                                  @RequestParam(value = "page", required = false) Integer page,
                                  @RequestParam(value = "size", required = false) Integer size) {
        int safePage = (page == null || page < 0) ? 0 : page;
        int safeSize = clampPageSize(size);
        // Pageable's sort is intentionally not set — the @Query embeds
        // ORDER BY createdAt DESC for plan stability.
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<OptimizationEventEntity> result = eventRepository.findFiltered(
                blankToNull(stage),
                agentId,
                blankToNull(surfaceType),
                pageable);

        List<OptimizationEventDto> dtos = result.getContent().stream()
                .map(OptimizationEventDto::from)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", dtos);
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("total", result.getTotalElements());
        return ResponseEntity.ok(body);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        return eventRepository.findById(id)
                .<ResponseEntity<?>>map(e -> ResponseEntity.ok(OptimizationEventDto.from(e)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "optimization event not found: " + id)));
    }

    // ───────────────────────── mutations ─────────────────────────

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id,
                                     @RequestBody(required = false) ApproveOrRetryRequest req) {
        Long approverUserId = req == null ? null : req.approverUserId();
        try {
            OptimizationEventEntity updated = approvalService.approve(id, approverUserId);
            return ResponseEntity.ok(OptimizationEventDto.from(updated));
        } catch (IllegalArgumentException e) {
            // ApprovalService throws IllegalArgumentException for both null id
            // (impossible here — @PathVariable rejects null) and "not found".
            // Map "not found" message-content to 404; everything else to 400.
            String msg = e.getMessage() == null ? "" : e.getMessage();
            HttpStatus status = msg.contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Map.of("error", msg));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id,
                                    @RequestBody(required = false) RejectRequest req) {
        Long approverUserId = req == null ? null : req.approverUserId();
        String reason = req == null ? null : req.reason();
        try {
            OptimizationEventEntity updated = approvalService.reject(id, approverUserId, reason);
            return ResponseEntity.ok(OptimizationEventDto.from(updated));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            HttpStatus status = msg.contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Map.of("error", msg));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id,
                                   @RequestBody(required = false) ApproveOrRetryRequest req) {
        Long approverUserId = req == null ? null : req.approverUserId();
        try {
            OptimizationEventEntity updated = approvalService.retryCandidateGeneration(id, approverUserId);
            return ResponseEntity.ok(OptimizationEventDto.from(updated));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            HttpStatus status = msg.contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Map.of("error", msg));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    // ───────────────────────── helpers ───────────────────────

    private static int clampPageSize(Integer raw) {
        if (raw == null || raw <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(raw, MAX_PAGE_SIZE);
    }
}
