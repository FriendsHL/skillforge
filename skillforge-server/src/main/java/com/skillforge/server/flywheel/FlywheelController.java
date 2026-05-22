package com.skillforge.server.flywheel;

import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FLYWHEEL-PER-RUN — REST surface for the per-run sidebar of the Flywheel
 * observability panel.
 *
 * <p>Mode toggle in the dashboard's {@code /insights} 5th tab switches between
 * "Aggregate" (existing topology + metric panels, unchanged) and "Per-Run"
 * which consumes this endpoint to render the recent attribution runs.
 *
 * <p>Auth: V1 single-tenant dogfood pattern (matches AttributionEventController /
 * InsightsController). Goes through the same Bearer-token AuthInterceptor as
 * every other {@code /api/**} endpoint. Phase 2 may introduce role-based
 * gating consistent with the rest of the attribution API.
 */
@RestController
@RequestMapping("/api/flywheel")
public class FlywheelController {

    private static final Logger log = LoggerFactory.getLogger(FlywheelController.class);

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int MIN_LIMIT = 1;

    /**
     * FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21) — defaults / clamps for the
     * on-demand per-agent loop trigger.
     */
    static final int DEFAULT_WINDOW_HOURS = 24;
    static final int MIN_WINDOW_HOURS = 1;
    static final int MAX_WINDOW_HOURS = 168;  // 7d defensive ceiling, matches DetectSignalAnnotationsTool.MAX_WINDOW_HOURS
    static final int DEFAULT_MAX = 10;
    static final int MIN_MAX = 1;
    static final int MAX_MAX = 20;
    /** SYSTEM user marker — mirrors {@code AttributionDispatcherService.SYSTEM_USER_ID}. */
    static final long SYSTEM_USER_ID = 0L;

    /**
     * Accepted values for the {@code agentType} query param — mirrors the
     * {@code chk_agent_type} DB CHECK constraint (V89). Unknown values fail
     * fast with 400 rather than silently returning {@code []}.
     */
    static final Set<String> ALLOWED_AGENT_TYPES = Set.of("user", "system");

    private final FlywheelRunsService runsService;
    private final AgentRepository agentRepository;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final FlywheelChainOrchestrator chainOrchestrator;
    private final Clock clock;

    public FlywheelController(FlywheelRunsService runsService,
                              AgentRepository agentRepository,
                              SessionService sessionService,
                              ChatService chatService,
                              FlywheelChainOrchestrator chainOrchestrator,
                              Clock clock) {
        this.runsService = runsService;
        this.agentRepository = agentRepository;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.chainOrchestrator = chainOrchestrator;
        this.clock = clock;
    }

    /**
     * Recent attribution runs (one row per {@code t_optimization_event}) with
     * agent + pattern context joined. {@code updated_at DESC} sort.
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code agentType} — {@code user} / {@code system} (optional;
     *       resolves to an agent-id set via {@code agent_type} column).
     *       Blank → no filter.</li>
     *   <li>{@code surface} — {@code skill} / {@code prompt} /
     *       {@code behavior_rule} (optional). Blank → no filter.</li>
     *   <li>{@code limit} — default {@value #DEFAULT_LIMIT}, clamped to
     *       {@code [{@value #MIN_LIMIT}, {@value #MAX_LIMIT}]}.</li>
     *   <li>{@code hideTerminal} — default {@code true}: exclude runs in
     *       {@link FlywheelRunsService#TERMINAL_HAPPY_STAGES}
     *       (promoted / verified / rolled_back). Failed terminals
     *       (proposal_rejected / candidate_failed / ab_failed) stay visible —
     *       operators want to see errors.</li>
     * </ul>
     */
    @GetMapping("/runs")
    public ResponseEntity<?> listRuns(@RequestParam(value = "agentType", required = false) String agentType,
                                      @RequestParam(value = "surface", required = false) String surface,
                                      @RequestParam(value = "limit", required = false) Integer limit,
                                      @RequestParam(value = "hideTerminal", required = false) Boolean hideTerminal) {
        String agentTypeNorm = blankToNull(agentType);
        // r2 W2 fix: fail-fast on unknown agentType rather than silently returning
        // [] (the service would have done so via empty agentIds, which masked
        // typo bugs at the FE).
        if (agentTypeNorm != null && !ALLOWED_AGENT_TYPES.contains(agentTypeNorm)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "agentType must be one of " + ALLOWED_AGENT_TYPES + " (got: " + agentTypeNorm + ")");
        }
        int safeLimit = clampLimit(limit);
        boolean safeHideTerminal = hideTerminal == null || hideTerminal;
        List<FlywheelRunDto> items = runsService.listRecentRuns(
                agentTypeNorm,
                blankToNull(surface),
                safeLimit,
                safeHideTerminal);

        // LinkedHashMap to keep the response field order stable in JSON output.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("limit", safeLimit);
        body.put("hideTerminal", safeHideTerminal);
        return ResponseEntity.ok(body);
    }

    private static int clampLimit(Integer raw) {
        if (raw == null) return DEFAULT_LIMIT;
        if (raw < MIN_LIMIT) return MIN_LIMIT;
        return Math.min(raw, MAX_LIMIT);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21) — on-demand "run my agent's
     * flywheel loop now" entry point for the dashboard's per-agent button.
     *
     * <p>Two sequential steps, both reusing the existing system-agent +
     * LLM-link orchestration:
     * <ol>
     *   <li>fire {@code session-annotator} chatAsync with a scoped user
     *       message containing the literal {@code agentId=<id>} keyword so
     *       the agent threads {@code agent_id} into
     *       {@code DetectSignalAnnotations} (see system prompt SCOPE block);</li>
     *   <li>register a one-shot hook with {@link FlywheelChainOrchestrator}
     *       that fires {@code attribution-dispatcher} chatAsync once the
     *       annotator session reaches a terminal {@code runtimeStatus}
     *       (Q1 of the brief: sequential, not parallel — dispatcher waiting
     *       on annotator avoids stale-pattern scans).</li>
     * </ol>
     *
     * <p>Returns 202 ACCEPTED immediately with the annotator session id —
     * the dashboard polls {@code GET /api/flywheel/runs} to observe the
     * downstream dispatcher / curator side-effects.
     *
     * <p>Path/query params:
     * <ul>
     *   <li>{@code agentId} — path variable; rejects 404 if no such agent.</li>
     *   <li>{@code windowHours} — optional, default {@value #DEFAULT_WINDOW_HOURS}
     *       (clamped to [{@value #MIN_WINDOW_HOURS}, {@value #MAX_WINDOW_HOURS}]).</li>
     *   <li>{@code max} — optional, default {@value #DEFAULT_MAX} (clamped to
     *       [{@value #MIN_MAX}, {@value #MAX_MAX}]). Forwarded to the dispatcher
     *       so the dispatcher's {@code ListAttributionCandidates} call caps
     *       sentinel rows produced per click.</li>
     * </ul>
     *
     * <p>Auth: V1 single-tenant dogfood pattern — goes through the existing
     * Bearer-token AuthInterceptor on all {@code /api/**} routes (see
     * AttributionEventController class javadoc for the broader auth posture).
     */
    @PostMapping("/agents/{agentId}/run-loop")
    public ResponseEntity<?> runLoop(@PathVariable("agentId") Long agentId,
                                     @RequestParam(value = "windowHours", required = false) Integer windowHoursRaw,
                                     @RequestParam(value = "max", required = false) Integer maxRaw) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        int windowHours = clampWindowHours(windowHoursRaw);
        int max = clampMax(maxRaw);

        // Pre-check: target agent exists. We don't validate agent_type=user
        // here on purpose — operators may legitimately run the flywheel on a
        // system agent during incident triage, and the downstream session-
        // annotator pipeline itself already filters origin=production at the
        // SessionRepository layer. Fail fast on truly missing rows.
        AgentEntity targetAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Agent not found: id=" + agentId));

        // Lookup the two system agents the chain delegates to. These rows are
        // seeded by V75 / V93 migrations + V95 inline prompt seed
        // (KILL-BOOTSTRAP-PROMPT-TO-DB 2026-05-22); missing rows would
        // indicate a partially-rolled-out instance — fail fast with 503 so
        // the dashboard can surface a clear "system agent missing" error
        // rather than firing chatAsync into a void.
        AgentEntity annotatorAgent = agentRepository.findFirstByName(SystemAgentNames.SESSION_ANNOTATOR)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "session-annotator system agent not seeded; check V75 migration"));
        AgentEntity dispatcherAgent = agentRepository.findFirstByName(SystemAgentNames.ATTRIBUTION_DISPATCHER)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "attribution-dispatcher system agent not seeded; check V93 migration"));

        // STEP 1: fire session-annotator. The "agentId=N" keyword in the user
        // message is the SCOPE marker the annotator's system prompt parses to
        // decide whether to thread agent_id to DetectSignalAnnotations.
        SessionEntity annotatorSession = sessionService.createSession(SYSTEM_USER_ID, annotatorAgent.getId());
        String annotatorPrompt = String.format(
                "请只处理 agentId=%d 在最近 %d 小时的 session：标注 + 重算聚类。跑完后停。",
                agentId, windowHours);
        chatService.chatAsync(annotatorSession.getId(), annotatorPrompt, SYSTEM_USER_ID);

        // STEP 2: register one-shot hook. Capture the dispatcher fan-out as a
        // Runnable so the orchestrator can invoke it once the annotator
        // session goes terminal (idle / error). The annotator's session id is
        // returned to the caller; the dispatcher's session id is logged + can
        // be queried via the /api/flywheel/runs sidebar once it lands.
        //
        // FLYWHEEL-CHAIN-VISIBILITY (2026-05-22): also capture agentName +
        // startedAt + annotatorSessionId so the dispatcher-hook registration
        // can carry full context for the eventual chain-completed WS
        // broadcast. startedAt = clock.instant() right here = opt-loop
        // wall-clock zero, used by the orchestrator to bound the
        // countByAgentIdAndCreatedAtAfter query window.
        final Long capturedAgentId = agentId;
        final int capturedMax = max;
        final Long dispatcherAgentId = dispatcherAgent.getId();
        final String capturedAgentName = targetAgent.getName();
        final String capturedAnnotatorSessionId = annotatorSession.getId();
        final Instant capturedStartedAt = clock.instant();
        chainOrchestrator.registerAnnotatorEndHook(
                annotatorSession.getId(),
                () -> fireDispatcher(capturedAgentId, dispatcherAgentId, capturedMax,
                        capturedAgentName, capturedAnnotatorSessionId, capturedStartedAt));

        // 202 ACCEPTED — annotator is queued; dispatcher will fire after it
        // completes. The note field is the user-facing latency hint matching
        // observed dogfood timings (~30s–2min for the annotator end-to-end).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", agentId);
        body.put("agentName", targetAgent.getName());
        body.put("annotatorSessionId", annotatorSession.getId());
        body.put("windowHours", windowHours);
        body.put("max", max);
        body.put("status", "triggered");
        body.put("note", "attribution-dispatcher will fire automatically after the "
                + "session-annotator session reaches a terminal runtimeStatus "
                + "(typically 30s-2min).");
        return ResponseEntity.accepted().body(body);
    }

    /**
     * Hook payload: fire the dispatcher chatAsync with a scoped user message
     * containing the {@code agentId=N} keyword. Package-private so the
     * controller test can verify the chained call without going through the
     * orchestrator's polling tick.
     *
     * <p>FLYWHEEL-CHAIN-VISIBILITY (2026-05-22): after firing chatAsync, also
     * register a dispatcher-hook with the orchestrator so the polling tick
     * can detect dispatcher terminal status + emit the chain-completed WS
     * broadcast. {@code annotatorStatus} is always {@code "idle"} on this
     * path — the annotator-end-hook only fires when the annotator session is
     * terminal, and reaching {@code error} would still execute the hook (the
     * orchestrator does not distinguish idle vs error when running the
     * Runnable). We default to "idle" here as the dominant case; a future
     * refactor that wants to surface annotator-error chains differently can
     * thread the status through the Runnable contract.
     */
    void fireDispatcher(Long agentId, Long dispatcherAgentId, int max,
                        String agentName, String annotatorSessionId, Instant startedAt) {
        SessionEntity dispatcherSession = sessionService.createSession(SYSTEM_USER_ID, dispatcherAgentId);
        String dispatcherPrompt = String.format(
                "请只 dispatch agentId=%d 的 pattern (max=%d)。",
                agentId, max);
        chatService.chatAsync(dispatcherSession.getId(), dispatcherPrompt, SYSTEM_USER_ID);
        // r2 fix (code-reviewer N1): make dispatcher session id traceable
        // from the BE log without operator having to filter the session list
        // by agent name.
        log.info("[FlywheelChain] dispatcher session created: sessionId={} targetAgentId={} dispatcherAgentId={} max={}",
                dispatcherSession.getId(), agentId, dispatcherAgentId, max);

        // FLYWHEEL-CHAIN-VISIBILITY: register the dispatcher half of the
        // chain so the orchestrator polling tick can detect dispatcher
        // terminal + broadcast chain-completed.
        try {
            chainOrchestrator.registerDispatcherHook(
                    dispatcherSession.getId(),
                    agentId,
                    agentName,
                    annotatorSessionId,
                    startedAt,
                    "idle");  // annotator was terminal-idle by the time this hook fired
        } catch (RuntimeException e) {
            // Don't propagate — the chatAsync already fired, the dispatcher
            // session is real; we just lose the chain-completed broadcast for
            // this click. Log so we can spot a recurring drift in dogfood.
            log.warn("[FlywheelChain] failed to register dispatcher hook: dispatcherSessionId={} agentId={}: {}",
                    dispatcherSession.getId(), agentId, e.getMessage());
        }
    }

    /**
     * FLYWHEEL-CHAIN-VISIBILITY (2026-05-22): in-memory snapshot of recent
     * chain runs (annotator + dispatcher pairs) — the dashboard's per-agent
     * progress panel reads this on mount to backfill state without waiting
     * for the next WS event. Empty list when no chain has completed yet
     * after the last server restart (the cache is process-local, by design).
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code agentId} — optional filter to a single target agent;
     *       omitted = all agents.</li>
     *   <li>{@code limit} — default 20, clamped to [1, 100] (matches
     *       {@link FlywheelChainOrchestrator#MAX_COMPLETED}).</li>
     * </ul>
     *
     * <p>Returns a JSON array of {@link FlywheelChainOrchestrator.ChainRunResult}
     * record components (Jackson auto-serializes record components by
     * accessor name). In-flight runs (dispatcher session still running) have
     * {@code dispatcherStatus=null} / {@code completedAt=null} so the FE can
     * render an "in progress" pill; completed runs have both fields filled.
     */
    @GetMapping("/chain-runs")
    public ResponseEntity<?> listChainRuns(
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        int safeLimit = (limit == null || limit < 1) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<FlywheelChainOrchestrator.ChainRunResult> runs =
                chainOrchestrator.getChainRuns(agentId, safeLimit);
        return ResponseEntity.ok(runs);
    }

    private static int clampWindowHours(Integer raw) {
        if (raw == null) return DEFAULT_WINDOW_HOURS;
        if (raw < MIN_WINDOW_HOURS) return MIN_WINDOW_HOURS;
        return Math.min(raw, MAX_WINDOW_HOURS);
    }

    private static int clampMax(Integer raw) {
        if (raw == null) return DEFAULT_MAX;
        if (raw < MIN_MAX) return MIN_MAX;
        return Math.min(raw, MAX_MAX);
    }
}
