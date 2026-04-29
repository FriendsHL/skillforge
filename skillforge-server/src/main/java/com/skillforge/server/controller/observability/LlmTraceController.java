package com.skillforge.server.controller.observability;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmTrace;
import com.skillforge.server.controller.observability.dto.LlmSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.LlmTraceDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** Plan §7.1 + R3-W6 — OBS-1 trace 详情 with ownership enforcement. */
@RestController
@RequestMapping("/api/observability")
public class LlmTraceController {

    private final LlmTraceStore traceStore;
    private final ObservabilityOwnershipGuard ownershipGuard;

    public LlmTraceController(LlmTraceStore traceStore,
                              ObservabilityOwnershipGuard ownershipGuard) {
        this.traceStore = traceStore;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<LlmTraceDto> getTrace(@PathVariable String traceId,
                                                @RequestParam Long userId) {
        Optional<LlmTraceStore.TraceWithSpans> opt = traceStore.readByTraceId(traceId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        LlmTrace t = opt.get().trace();
        // R3-W6: ownership check against the trace's session before exposing any spans.
        ownershipGuard.requireOwned(t.sessionId(), userId);
        List<LlmSpan> spans = opt.get().spans();
        List<LlmSpanSummaryDto> summaries = spans.stream()
                .map(s -> new LlmSpanSummaryDto(
                        "llm", s.spanId(), s.traceId(), s.parentSpanId(),
                        s.startedAt(), s.endedAt(), s.latencyMs(),
                        s.provider(), s.model(),
                        s.inputTokens(), s.outputTokens(),
                        s.source().wireValue(), s.stream(),
                        s.inputBlobRef() != null,
                        s.outputBlobRef() != null,
                        s.rawSseBlobRef() != null,
                        s.blobStatus(),
                        s.finishReason(), s.error(), s.errorType()))
                .toList();
        LlmTraceDto dto = new LlmTraceDto(
                t.traceId(), t.sessionId(), t.agentId(), t.userId(), t.rootName(),
                t.startedAt(), t.endedAt(),
                t.totalInputTokens(), t.totalOutputTokens(),
                t.totalCostUsd(), t.source().wireValue(),
                summaries);
        return ResponseEntity.ok(dto);
    }
}
