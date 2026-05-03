package com.skillforge.server.service;

import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.controller.observability.dto.TraceNodeDto;
import com.skillforge.server.controller.observability.dto.TraceSpanDto;
import com.skillforge.server.controller.observability.dto.TraceTreeDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OBS-4 M2: assemble a cross-session trace tree by {@code root_trace_id}.
 *
 * <p>One investigation = one root_trace_id = potentially many traces across many
 * sessions (parent agent + spawned subagents + recursive child of child). The
 * data model from M1 makes this a 3-SQL operation:
 * <ol>
 *   <li>{@code SELECT * FROM t_llm_trace WHERE root_trace_id = ? ORDER BY started_at}</li>
 *   <li>{@code SELECT * FROM t_llm_span WHERE trace_id IN (?...) ORDER BY started_at}</li>
 *   <li>{@code SELECT * FROM t_session WHERE id IN (?...)} (batch PK lookup for
 *   {@code parent_session_id} chain — used to compute depth)</li>
 * </ol>
 *
 * <p>Depth (0 = root agent's session, 1 = spawned children, ...) is computed in
 * memory by walking {@code t_session.parent_session_id} from each trace's session
 * up to the root. All sessions in the tree are loaded once via batch lookup;
 * sessions outside the investigation (ancestors of root) terminate the walk so we
 * don't blow up memory.
 *
 * <p>Performance target: 6f18ecca-shape investigation (6 sessions, 138 spans)
 * &lt; 200ms per PRD §3 acceptance #6. Two SQL round trips + linear in-memory
 * grouping is comfortably under that.
 */
@Service
public class TraceTreeService {

    private static final Logger log = LoggerFactory.getLogger(TraceTreeService.class);

    private final LlmTraceRepository traceRepository;
    private final LlmSpanRepository spanRepository;
    private final SessionRepository sessionRepository;

    public TraceTreeService(LlmTraceRepository traceRepository,
                            LlmSpanRepository spanRepository,
                            SessionRepository sessionRepository) {
        this.traceRepository = traceRepository;
        this.spanRepository = spanRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Returns the full trace tree for the given investigation root, or an empty
     * Optional if no traces share that root_trace_id (caller maps to 404).
     */
    @Transactional(readOnly = true)
    public Optional<TraceTreeDto> getTree(String rootTraceId) {
        if (rootTraceId == null || rootTraceId.isBlank()) {
            return Optional.empty();
        }

        // §B.1: one query for all traces in the investigation
        List<LlmTraceEntity> traces = traceRepository.findByRootTraceIdOrderByStartedAtAsc(rootTraceId);
        if (traces.isEmpty()) {
            return Optional.empty();
        }

        // §B.2: one query for all their spans, grouped by traceId in memory
        List<String> traceIds = new ArrayList<>(traces.size());
        for (LlmTraceEntity t : traces) traceIds.add(t.getTraceId());
        List<LlmSpanEntity> spans = spanRepository.findByTraceIdInOrderByStartedAtAsc(traceIds);

        Map<String, List<TraceSpanDto>> spansByTraceId = new HashMap<>();
        for (LlmSpanEntity s : spans) {
            spansByTraceId.computeIfAbsent(s.getTraceId(), k -> new ArrayList<>()).add(toSpanDto(s));
        }

        // §B.3: depth via t_session.parent_session_id walk. Load all sessions referenced
        // by the traces in one batch; missing sessions (deleted / pre-existing legacy)
        // get depth=0 fallback.
        Set<String> sessionIds = new HashSet<>();
        for (LlmTraceEntity t : traces) sessionIds.add(t.getSessionId());
        Map<String, SessionEntity> sessionById = new HashMap<>();
        for (SessionEntity se : sessionRepository.findAllById(sessionIds)) {
            sessionById.put(se.getId(), se);
        }
        Map<String, Integer> depthBySession = computeDepthBySession(sessionIds, sessionById);

        // §B.4: assemble DTOs
        List<TraceNodeDto> nodeDtos = new ArrayList<>(traces.size());
        for (LlmTraceEntity t : traces) {
            SessionEntity se = sessionById.get(t.getSessionId());
            int depth = depthBySession.getOrDefault(t.getSessionId(), 0);
            String parentSessionId = se != null ? se.getParentSessionId() : null;
            List<TraceSpanDto> traceSpans = spansByTraceId.getOrDefault(
                    t.getTraceId(), Collections.emptyList());
            int llmCount = 0;
            for (TraceSpanDto sp : traceSpans) {
                if ("llm".equals(sp.kind())) llmCount++;
            }
            nodeDtos.add(new TraceNodeDto(
                    t.getTraceId(),
                    t.getSessionId(),
                    t.getAgentId(),
                    t.getAgentName() != null ? t.getAgentName() : t.getRootName(),
                    depth,
                    parentSessionId,
                    t.getStatus(),
                    t.getStartedAt(),
                    t.getEndedAt(),
                    t.getTotalDurationMs(),
                    llmCount,
                    t.getToolCallCount(),
                    t.getEventCount(),
                    t.getTotalInputTokens(),
                    t.getTotalOutputTokens(),
                    t.getError(),
                    traceSpans));
        }
        return Optional.of(new TraceTreeDto(rootTraceId, nodeDtos));
    }

    /**
     * For each session in {@code sessionIds}, walk parent_session_id chain until we hit
     * a session that's not in the set (the spawn boundary out of this investigation) or
     * a null parent. Depth = number of hops to that boundary. Sessions whose parent is
     * not in the set get depth=0 (they are roots of the investigation).
     *
     * <p>Cycles in {@code parent_session_id} (a data integrity bug) are detected by
     * capping the walk at investigation size; if hit, the session falls back to depth=0
     * (safer for FE rendering than the previously-returned maxHops value, which would
     * indent absurdly deep) and a warning is logged so the bug surfaces in ops.
     */
    private static Map<String, Integer> computeDepthBySession(Set<String> investigationSessionIds,
                                                              Map<String, SessionEntity> sessionById) {
        Map<String, Integer> depthBySession = new HashMap<>();
        int maxHops = investigationSessionIds.size() + 1;
        for (String sid : investigationSessionIds) {
            int depth = 0;
            String cursor = sid;
            boolean cycleDetected = true;
            for (int i = 0; i < maxHops; i++) {
                SessionEntity se = sessionById.get(cursor);
                if (se == null) { cycleDetected = false; break; }
                String parent = se.getParentSessionId();
                if (parent == null || !investigationSessionIds.contains(parent)) {
                    cycleDetected = false;
                    break;
                }
                depth++;
                cursor = parent;
            }
            if (cycleDetected) {
                log.warn("OBS-4 trace tree depth walk hit maxHops={} for session={} — "
                        + "likely a parent_session_id cycle (data integrity bug); "
                        + "falling back depth=0", maxHops, sid);
                depth = 0;
            }
            depthBySession.put(sid, depth);
        }
        return depthBySession;
    }

    private static TraceSpanDto toSpanDto(LlmSpanEntity s) {
        return new TraceSpanDto(
                s.getSpanId(),
                s.getParentSpanId(),
                s.getKind(),
                s.getEventType(),
                s.getName() != null ? s.getName() : s.getModel(),
                s.getModel(),
                s.getInputSummary(),
                s.getOutputSummary(),
                s.getStartedAt(),
                s.getEndedAt(),
                s.getLatencyMs(),
                s.getIterationIndex(),
                s.getInputTokens(),
                s.getOutputTokens(),
                TraceSpanDto.deriveStatus(s.getError()),
                s.getError());
    }
}
