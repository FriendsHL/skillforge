package com.skillforge.server.controller.observability;

import com.skillforge.server.controller.observability.dto.SpanSummaryDto;
import com.skillforge.server.service.observability.SessionSpansService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan §7.1 R2-B1 + R3-WN4 — merged session/spans endpoint.
 *
 * <p>{@code GET /api/observability/sessions/{sessionId}/spans?userId=&since=&limit=&kinds=llm,tool}
 *
 * <p>Plan §6.3 / §7.3 R3-W6: {@code userId} is required and validated against
 * {@code SessionEntity.user_id} before any data is returned.
 */
@RestController
@RequestMapping("/api/observability")
public class SessionSpansController {

    private final SessionSpansService service;
    private final ObservabilityOwnershipGuard ownershipGuard;

    public SessionSpansController(SessionSpansService service,
                                  ObservabilityOwnershipGuard ownershipGuard) {
        this.service = service;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/sessions/{sessionId}/spans")
    public Map<String, Object> listSpans(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) Instant since,
            @RequestParam(required = false, defaultValue = "200") int limit,
            @RequestParam(required = false) Set<String> kinds) {

        // R3-W6: throws 400/403/404 before touching any data.
        ownershipGuard.requireOwned(sessionId, userId);

        if (limit < 1) limit = 1;
        if (limit > 1000) limit = 1000;

        List<SpanSummaryDto> spans = service.listMergedSpans(
                sessionId, userId, traceId, since, limit, kinds);
        boolean hasMore = spans.size() >= limit;
        return Map.of("spans", spans, "hasMore", hasMore);
    }
}
