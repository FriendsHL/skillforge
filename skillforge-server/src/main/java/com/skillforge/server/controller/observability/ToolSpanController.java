package com.skillforge.server.controller.observability;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.server.controller.observability.dto.EventSpanDetailDto;
import com.skillforge.server.controller.observability.dto.ToolSpanDetailDto;
import com.skillforge.server.service.observability.SubagentSessionResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * OBS-2 M3 — read path cut-over for tool / event span detail endpoints.
 *
 * <p>Pre-M3 ToolSpanController read {@code t_trace_span where span_type='TOOL_CALL'} via
 * {@code TraceSpanRepository}. After M3 it routes through {@link LlmTraceStore#readSpan}
 * and dispatches by {@code kind}:
 * <ul>
 *   <li>{@code GET /api/observability/tool-spans/{spanId}} → returns {@link ToolSpanDetailDto}
 *   when the underlying row has {@code kind='tool'}</li>
 *   <li>{@code GET /api/observability/event-spans/{spanId}} → returns {@link EventSpanDetailDto}
 *   when {@code kind='event'} (new endpoint for the four legacy event types)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/observability")
public class ToolSpanController {

    private final LlmTraceStore traceStore;
    private final SubagentSessionResolver subagentResolver;
    private final ObservabilityOwnershipGuard ownershipGuard;

    public ToolSpanController(LlmTraceStore traceStore,
                              SubagentSessionResolver subagentResolver,
                              ObservabilityOwnershipGuard ownershipGuard) {
        this.traceStore = traceStore;
        this.subagentResolver = subagentResolver;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/tool-spans/{spanId}")
    public ResponseEntity<ToolSpanDetailDto> getToolSpan(@PathVariable String spanId,
                                                         @RequestParam Long userId) {
        Optional<LlmSpan> opt = traceStore.readSpan(spanId);
        if (opt.isEmpty() || !"tool".equals(opt.get().kind())) {
            return ResponseEntity.notFound().build();
        }
        LlmSpan s = opt.get();
        // R3-W6: ownership check against the tool span's session.
        ownershipGuard.requireOwned(s.sessionId(), userId);
        String childSession = subagentResolver.resolve(s);
        boolean success = s.error() == null;
        ToolSpanDetailDto dto = new ToolSpanDetailDto(
                s.spanId(),
                s.traceId(),
                s.parentSpanId(),
                s.sessionId(),
                s.name(), s.toolUseId(),
                success, s.error(),
                s.inputSummary(), s.outputSummary(),
                s.startedAt(), s.endedAt(),
                s.latencyMs(),
                s.iterationIndex(),
                childSession);
        return ResponseEntity.ok(dto);
    }

    /**
     * OBS-2 M3 — event span detail endpoint. Returns the row from {@code t_llm_span} when
     * {@code kind='event'} (covering ask_user / install_confirm / compact / agent_confirm).
     */
    @GetMapping("/event-spans/{spanId}")
    public ResponseEntity<EventSpanDetailDto> getEventSpan(@PathVariable String spanId,
                                                           @RequestParam Long userId) {
        Optional<LlmSpan> opt = traceStore.readSpan(spanId);
        if (opt.isEmpty() || !"event".equals(opt.get().kind())) {
            return ResponseEntity.notFound().build();
        }
        LlmSpan s = opt.get();
        ownershipGuard.requireOwned(s.sessionId(), userId);
        boolean success = s.error() == null;
        EventSpanDetailDto dto = new EventSpanDetailDto(
                s.spanId(),
                s.traceId(),
                s.parentSpanId(),
                s.sessionId(),
                s.eventType(), s.name(),
                success, s.error(),
                s.inputSummary(), s.outputSummary(),
                s.startedAt(), s.endedAt(),
                s.latencyMs(),
                s.iterationIndex());
        return ResponseEntity.ok(dto);
    }
}
