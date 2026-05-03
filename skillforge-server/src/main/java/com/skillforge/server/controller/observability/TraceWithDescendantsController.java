package com.skillforge.server.controller.observability;

import com.skillforge.server.controller.observability.dto.TraceWithDescendantsDto;
import com.skillforge.server.service.observability.TraceDescendantsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OBS-3 — unified trace tree endpoint.
 *
 * <p>{@code GET /api/traces/{traceId}/with_descendants?max_depth=3&max_descendants=20&user_id={uid}}
 *
 * <p>Returns the root trace summary, descendant child trace metadata (DFS order), and a
 * single time-sorted span timeline so the dashboard can render parent + child trace spans
 * in a unified waterfall. Ownership is enforced against the root trace's session at the
 * service layer (see {@link TraceDescendantsService#fetch}).
 */
@RestController
@RequestMapping("/api/traces")
public class TraceWithDescendantsController {

    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final int DEFAULT_MAX_DESCENDANTS = 20;

    private final TraceDescendantsService service;

    public TraceWithDescendantsController(TraceDescendantsService service) {
        this.service = service;
    }

    @GetMapping("/{traceId}/with_descendants")
    public ResponseEntity<TraceWithDescendantsDto> getWithDescendants(
            @PathVariable String traceId,
            @RequestParam(name = "user_id") Long userId,
            @RequestParam(name = "max_depth", defaultValue = "" + DEFAULT_MAX_DEPTH) int maxDepth,
            @RequestParam(name = "max_descendants", defaultValue = "" + DEFAULT_MAX_DESCENDANTS) int maxDescendants) {
        TraceWithDescendantsDto dto = service.fetch(traceId, maxDepth, maxDescendants, userId);
        return ResponseEntity.ok(dto);
    }
}
