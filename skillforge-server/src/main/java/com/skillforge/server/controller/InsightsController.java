package com.skillforge.server.controller;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PatternSessionMemberEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.4): read-side API for the dashboard
 * {@code /insights/patterns} page.
 *
 * <p>Two endpoints, both read-only:
 * <ul>
 *   <li>{@code GET /api/insights/patterns} — list patterns sorted by
 *       member_count DESC, last_seen_at DESC. Optional filters: outcome /
 *       surface (suspect_surface) / agent (agent_id).</li>
 *   <li>{@code GET /api/insights/patterns/{id}/members} — sessions in one
 *       pattern, newest-added first.</li>
 * </ul>
 *
 * <p>Auth posture: V1 dogfood is single-tenant — no per-user gating here, just
 * read-only access to the cluster materialisations. Mirrors the existing
 * {@code TracesController} which is also pre-multitenant. V2+ will need a
 * user-context filter once we onboard external tenants.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private static final Logger log = LoggerFactory.getLogger(InsightsController.class);

    static final int PATTERN_LIST_DEFAULT_LIMIT = 50;
    static final int PATTERN_LIST_MAX_LIMIT = 200;
    static final int MEMBER_LIST_DEFAULT_LIMIT = 100;
    static final int MEMBER_LIST_MAX_LIMIT = 500;
    static final int RUNTIME_ERROR_TRUNCATE_LEN = 200;

    private final SessionPatternRepository sessionPatternRepository;
    private final PatternSessionMemberRepository patternSessionMemberRepository;
    private final SessionRepository sessionRepository;
    private final AgentRepository agentRepository;

    public InsightsController(SessionPatternRepository sessionPatternRepository,
                              PatternSessionMemberRepository patternSessionMemberRepository,
                              SessionRepository sessionRepository,
                              AgentRepository agentRepository) {
        this.sessionPatternRepository = sessionPatternRepository;
        this.patternSessionMemberRepository = patternSessionMemberRepository;
        this.sessionRepository = sessionRepository;
        this.agentRepository = agentRepository;
    }

    @GetMapping("/patterns")
    public ResponseEntity<List<PatternListItem>> listPatterns(
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String surface,
            @RequestParam(required = false) Long agent,
            @RequestParam(required = false) Integer limit) {
        int effLimit = clamp(limit, PATTERN_LIST_DEFAULT_LIMIT, 1, PATTERN_LIST_MAX_LIMIT);
        // Treat blank string params as "no filter" — easier for FE not to gate on
        // optional fields and the SQL path already handles null cleanly.
        String effOutcome = blankToNull(outcome);
        String effSurface = blankToNull(surface);

        List<SessionPatternEntity> rows = sessionPatternRepository.findWithFilters(
                effOutcome, effSurface, agent, PageRequest.of(0, effLimit));
        List<PatternListItem> out = new ArrayList<>(rows.size());
        for (SessionPatternEntity p : rows) {
            out.add(new PatternListItem(
                    p.getId(),
                    p.getSignature(),
                    p.getOutcome(),
                    p.getSuspectSurface(),
                    p.getTopFailingTool(),
                    p.getAgentId(),
                    p.getMemberCount(),
                    p.getSuggestedSurface(),
                    p.getFirstSeenAt(),
                    p.getLastSeenAt()
            ));
        }
        log.debug("listPatterns outcome={} surface={} agent={} limit={} returned={}",
                effOutcome, effSurface, agent, effLimit, out.size());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/patterns/{id}/members")
    public ResponseEntity<List<PatternMemberItem>> listPatternMembers(
            @PathVariable("id") Long patternId,
            @RequestParam(required = false) Integer limit) {
        // 404 when the pattern does not exist; we don't pretend members are an
        // empty list for missing patterns because that hides typos in the URL.
        if (sessionPatternRepository.findById(patternId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        int effLimit = clamp(limit, MEMBER_LIST_DEFAULT_LIMIT, 1, MEMBER_LIST_MAX_LIMIT);
        List<PatternSessionMemberEntity> members = patternSessionMemberRepository
                .findByPatternIdOrderByAddedAtDesc(patternId, PageRequest.of(0, effLimit));
        if (members.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<String> sessionIds = members.stream()
                .map(PatternSessionMemberEntity::getSessionId)
                .toList();
        Map<String, SessionEntity> sessionById = sessionRepository.findAllById(sessionIds).stream()
                .collect(Collectors.toMap(SessionEntity::getId, s -> s, (a, b) -> a));

        // Batch agent-name lookup once across all sessions in the page.
        List<Long> agentIds = sessionById.values().stream()
                .map(SessionEntity::getAgentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> agentNameById = new HashMap<>();
        if (!agentIds.isEmpty()) {
            for (AgentEntity a : agentRepository.findAllById(agentIds)) {
                agentNameById.put(a.getId(), a.getName());
            }
        }

        List<PatternMemberItem> out = new ArrayList<>(members.size());
        for (PatternSessionMemberEntity m : members) {
            SessionEntity s = sessionById.get(m.getSessionId());
            String agentName = null;
            Instant completedAt = null;
            String runtimeError = null;
            if (s != null) {
                if (s.getAgentId() != null) {
                    agentName = agentNameById.get(s.getAgentId());
                }
                completedAt = s.getCompletedAt();
                runtimeError = truncate(s.getRuntimeError(), RUNTIME_ERROR_TRUNCATE_LEN);
            }
            out.add(new PatternMemberItem(m.getSessionId(), agentName, completedAt, runtimeError));
        }
        log.debug("listPatternMembers patternId={} limit={} returned={}",
                patternId, effLimit, out.size());
        return ResponseEntity.ok(out);
    }

    /**
     * Pattern list row. Field names mirror tech-design §6.1 / PRD §"Dashboard"
     * so the FE can render columns directly off the JSON.
     */
    public record PatternListItem(
            Long id,
            String signature,
            String outcome,
            String suspectSurface,
            String topFailingTool,
            Long agentId,
            int memberCount,
            String suggestedSurface,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {}

    /**
     * Pattern member row. {@code runtimeError} is truncated to
     * {@link #RUNTIME_ERROR_TRUNCATE_LEN} chars in the drawer; the FE links
     * out to {@code /traces?sessionId=...} for the full trace.
     */
    public record PatternMemberItem(
            String sessionId,
            String agentName,
            Instant completedAt,
            String runtimeError
    ) {}

    private static int clamp(Integer value, int defaultValue, int min, int max) {
        int v = value == null ? defaultValue : value;
        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
