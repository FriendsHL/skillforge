package com.skillforge.server.controller.observability;

import com.skillforge.server.controller.observability.dto.ToolSpanDetailDto;
import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.TraceSpanRepository;
import com.skillforge.server.service.observability.SubagentSessionResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** Plan §7.1 R2-B1 + R3-W6 — Tool span detail with ownership enforcement. */
@RestController
@RequestMapping("/api/observability")
public class ToolSpanController {

    private final TraceSpanRepository repository;
    private final SubagentSessionResolver subagentResolver;
    private final ObservabilityOwnershipGuard ownershipGuard;

    public ToolSpanController(TraceSpanRepository repository,
                              SubagentSessionResolver subagentResolver,
                              ObservabilityOwnershipGuard ownershipGuard) {
        this.repository = repository;
        this.subagentResolver = subagentResolver;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/tool-spans/{spanId}")
    public ResponseEntity<ToolSpanDetailDto> getToolSpan(@PathVariable String spanId,
                                                         @RequestParam Long userId) {
        Optional<TraceSpanEntity> opt = repository.findById(spanId);
        if (opt.isEmpty() || !"TOOL_CALL".equals(opt.get().getSpanType())) {
            return ResponseEntity.notFound().build();
        }
        TraceSpanEntity e = opt.get();
        // R3-W6: ownership check against the tool span's session.
        ownershipGuard.requireOwned(e.getSessionId(), userId);
        String childSession = subagentResolver.resolve(e);
        // BE-W4: TOOL_CALL spans currently hang directly under the AGENT_LOOP root,
        // so parentSpanId == traceId is correct under the current architecture.
        // TODO: when TOOL_CALL nests under LLM_CALL (planned), resolve traceId from
        // t_trace_span.trace_id (currently absent column) instead of reusing parentSpanId.
        ToolSpanDetailDto dto = new ToolSpanDetailDto(
                e.getId(),
                e.getParentSpanId(),  // traceId — see BE-W4 note above
                e.getParentSpanId(),  // parentSpanId
                e.getSessionId(),
                e.getName(), e.getToolUseId(),
                e.isSuccess(), e.getError(),
                e.getInput(), e.getOutput(),
                e.getStartTime(), e.getEndTime(),
                e.getDurationMs(),
                e.getIterationIndex(),
                childSession);
        return ResponseEntity.ok(dto);
    }
}
