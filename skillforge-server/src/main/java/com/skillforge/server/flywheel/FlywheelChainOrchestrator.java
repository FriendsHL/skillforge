package com.skillforge.server.flywheel;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21) — on-demand per-agent loop trigger
 * step-chaining helper.
 *
 * <p>The controller endpoint
 * {@code POST /api/flywheel/agents/{agentId}/run-loop} fires the
 * {@code session-annotator} agent first; the {@code attribution-dispatcher}
 * must wait until the annotator's chat-loop reaches a terminal state
 * ({@code idle} / {@code error}) before it runs, otherwise the dispatcher
 * scans stale {@code t_session_pattern} rows (Q1 of the brief).
 *
 * <p>Design choice (Q2 of the brief, b option — polling fallback over
 * Spring-event-listener wiring): {@link Scheduled} polls a
 * {@code ConcurrentHashMap<sessionId, PendingHook>} every
 * {@value #POLL_INTERVAL_MS} ms. When the registered annotator session's
 * {@link SessionEntity#getRuntimeStatus()} is terminal the hook is removed +
 * its {@link Runnable} runs. Reasons polling beats the alternatives:
 * <ul>
 *   <li><b>No new event on ChatService</b> — the brief flags ChatService as a
 *       Iron-Law-protected core file (touching it risks the 4-byte
 *       persistence-shape invariant on the Message JSON path); a polling
 *       helper lives entirely inside this new component;</li>
 *   <li><b>Bounded fan-out</b> — the polling map is keyed by sessionId so even
 *       under rapid clicks (operator hammering the button) we never spawn
 *       runaway scheduler work; the map size = number of concurrent in-flight
 *       per-agent triggers, naturally bounded by operator UI cadence;</li>
 *   <li><b>Crash-safe</b> — orphan hooks expire after {@link #HOOK_TTL} so a
 *       server restart between annotator-fire and dispatcher-fire doesn't
 *       leak the hook map forever. The dispatcher then never fires for that
 *       click, but the user can re-click; the annotator session itself is
 *       persisted normally.</li>
 * </ul>
 *
 * <p>FLYWHEEL-CHAIN-VISIBILITY (2026-05-22): extended to also track the
 * dispatcher half of the chain. After the annotator-end hook fires the
 * dispatcher chatAsync, the orchestrator gets a follow-up
 * {@link #registerDispatcherHook} call so the same polling tick can detect
 * dispatcher terminal too, then emit:
 * <ol>
 *   <li>a {@code flywheel_chain_completed} WebSocket payload broadcast to
 *       every connected operator (single-tenant dogfood — see
 *       {@link UserWebSocketHandler#broadcastAll}); and</li>
 *   <li>a bounded LIFO {@link #completedRuns} cache (capped at
 *       {@value #MAX_COMPLETED} entries) so a freshly-loaded dashboard can
 *       backfill the per-agent chain status via
 *       {@code GET /api/flywheel/chain-runs} without depending on the WS
 *       arrival timing.</li>
 * </ol>
 *
 * <p>Concurrency:
 * <ul>
 *   <li>{@link #pending} / {@link #pendingDispatcher} are
 *       {@link ConcurrentHashMap} — registrations from the controller thread
 *       vs reads from the scheduler thread are safe;</li>
 *   <li>each tick uses {@link Map#computeIfPresent} (atomic check-and-remove)
 *       to ensure a hook fires <b>exactly once</b> even if two scheduler ticks
 *       race against a slow {@code Runnable};</li>
 *   <li>{@link #completedRuns} is guarded by its own monitor — only the
 *       scheduler thread writes; the endpoint reader takes a snapshot under
 *       the same monitor before sorting.</li>
 * </ul>
 */
@Component
public class FlywheelChainOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FlywheelChainOrchestrator.class);

    /**
     * Polling cadence in milliseconds. Chosen as 2s — annotator chat-loops in
     * production take 30s–2min, so a 2s tick gives ~1s expected dispatcher
     * delay after annotator completion (acceptable for on-demand operator UX)
     * without burning CPU on idle ticks. Lower values would not help (annotator
     * runtime is the bottleneck); higher values would noticeably delay the
     * dispatcher fire from the operator's perspective.
     */
    static final long POLL_INTERVAL_MS = 2_000L;

    /**
     * Hook TTL — drop any registration that has been waiting longer than this
     * for its annotator session to reach a terminal state. Picked to comfortably
     * exceed the worst-case annotator run (~2 min observed in dogfood +
     * generous headroom for LLM provider 5xx retries / network blips). Once
     * exceeded the hook is removed without firing — the operator can re-click
     * "Run loop now" if they still want a dispatcher fan-out.
     *
     * <p>FLYWHEEL-CHAIN-VISIBILITY: reused as the dispatcher-hook TTL too so
     * a hung dispatcher session can't pin the {@link #pendingDispatcher} map
     * indefinitely.
     */
    static final Duration HOOK_TTL = Duration.ofMinutes(10);

    /**
     * FLYWHEEL-CHAIN-VISIBILITY: cap on the LIFO completed-runs cache.
     * Sized for a single operator clicking "Run loop" at most a few times
     * per minute — 100 entries covers the most recent ~30min–1h of activity
     * which is plenty for the dashboard's "recent chains" section without
     * unbounded memory growth.
     */
    static final int MAX_COMPLETED = 100;

    /**
     * Set of session terminal-state runtimeStatus values. Stays in sync with
     * {@code SessionEntity.runtimeStatus} = {@code idle} / {@code running} /
     * {@code waiting_user} / {@code error}. Terminal = {@code idle} (success
     * exit) or {@code error} (engine threw / LLM provider failure). {@code
     * waiting_user} is NOT terminal — the annotator never asks the user, so
     * if it ends up there something is wrong, but we don't fire the dispatcher
     * either (treat as still-running until either the operator intervenes or
     * the TTL sweeps the hook). Hard-coded constants vs an enum to mirror
     * {@code ChatService}'s string-based state machine.
     */
    private static final String STATUS_IDLE = "idle";
    private static final String STATUS_ERROR = "error";
    /**
     * FLYWHEEL-CHAIN-VISIBILITY: synthetic dispatcher status used when the
     * annotator's terminal status was {@code error} and the original
     * controller logic did not fire the dispatcher — in this implementation
     * we still fire-and-track, but the constant is reserved for the case
     * where a future refactor decides to skip dispatcher on annotator error.
     */
    static final String DISPATCHER_NOT_FIRED = "not_fired";

    private final SessionService sessionService;
    private final OptimizationEventRepository optimizationEventRepository;
    private final UserWebSocketHandler userWebSocketHandler;
    private final Clock clock;

    /**
     * Pending per-annotator-session hooks. Keyed by sessionId so re-registering
     * for the same session is a no-op (operator double-click safety; the
     * controller already creates a fresh session per click, but a defensive
     * upsert costs nothing).
     */
    private final Map<String, PendingHook> pending = new ConcurrentHashMap<>();

    /**
     * FLYWHEEL-CHAIN-VISIBILITY: pending dispatcher hooks, keyed by
     * dispatcher session id. Populated by
     * {@link #registerDispatcherHook(String, Long, String, String, Instant, String)}
     * after the controller's hook callback successfully fires the dispatcher
     * chatAsync. Drained by the same polling tick that drains
     * {@link #pending}.
     */
    private final Map<String, DispatcherHook> pendingDispatcher = new ConcurrentHashMap<>();

    /**
     * FLYWHEEL-CHAIN-VISIBILITY: bounded LIFO of completed chain runs.
     * {@code addFirst} on completion + {@code removeLast} when size exceeds
     * {@link #MAX_COMPLETED} gives newest-first iteration for the
     * {@code /api/flywheel/chain-runs} endpoint.
     */
    private final Deque<ChainRunResult> completedRuns = new ArrayDeque<>();

    public FlywheelChainOrchestrator(SessionService sessionService,
                                     OptimizationEventRepository optimizationEventRepository,
                                     UserWebSocketHandler userWebSocketHandler,
                                     Clock clock) {
        this.sessionService = sessionService;
        this.optimizationEventRepository = optimizationEventRepository;
        this.userWebSocketHandler = userWebSocketHandler;
        this.clock = clock;
    }

    /**
     * Pending hook record. {@code registeredAt} drives the TTL sweep;
     * {@code onComplete} is the {@link Runnable} the scheduler fires once the
     * annotator session reaches a terminal {@code runtimeStatus}.
     */
    record PendingHook(String annotatorSessionId, Instant registeredAt, Runnable onComplete) {}

    /**
     * FLYWHEEL-CHAIN-VISIBILITY: dispatcher half of a chain. Captures
     * everything the WS broadcast / endpoint response needs so the polling
     * tick can build a {@link ChainRunResult} without re-querying the
     * controller closure.
     *
     * @param agentId             target user-agent id (the operator's agent)
     * @param agentName           target agent display name (denormalized once
     *                            at register-time so we don't re-query)
     * @param annotatorSessionId  id of the annotator session that ran first
     * @param dispatcherSessionId id of the dispatcher session being tracked
     * @param startedAt           opt loop start (annotator register-time)
     * @param dispatcherFiredAt   when the dispatcher chatAsync was launched
     * @param annotatorStatus     annotator's terminal runtimeStatus
     *                            ({@code idle} / {@code error})
     */
    public record DispatcherHook(
            Long agentId,
            String agentName,
            String annotatorSessionId,
            String dispatcherSessionId,
            Instant startedAt,
            Instant dispatcherFiredAt,
            String annotatorStatus) {}

    /**
     * FLYWHEEL-CHAIN-VISIBILITY: a chain run that has reached its terminal
     * state (dispatcher session reached {@code idle} / {@code error}, or
     * the dispatcher never fired because the annotator errored before the
     * hook). Stored newest-first in {@link #completedRuns} and serialized to
     * the {@code /chain-runs} endpoint via Jackson record-component
     * introspection (no custom serializer needed).
     *
     * @param optEventCount {@code -1} signals "count query failed" — the FE
     *                      should render "?" rather than "0".
     */
    public record ChainRunResult(
            Long agentId,
            String agentName,
            String annotatorSessionId,
            String dispatcherSessionId,
            Instant startedAt,
            Instant completedAt,
            String annotatorStatus,
            String dispatcherStatus,
            int optEventCount) {}

    /**
     * Register a one-shot {@link Runnable} to fire after the
     * {@code annotatorSessionId} session reaches a terminal
     * {@code runtimeStatus}. Non-blocking: returns immediately; the polling
     * scheduler will detect completion and run the hook. Re-registering the
     * same {@code annotatorSessionId} silently replaces the prior hook
     * (operator double-click safety).
     *
     * <p>Visible-for-test: the unit test directly invokes {@link #tick()} so
     * polling latency does not interfere with assertions.
     */
    public void registerAnnotatorEndHook(String annotatorSessionId, Runnable onComplete) {
        if (annotatorSessionId == null || annotatorSessionId.isBlank()) {
            throw new IllegalArgumentException("annotatorSessionId must be non-blank");
        }
        if (onComplete == null) {
            throw new IllegalArgumentException("onComplete Runnable must be non-null");
        }
        PendingHook hook = new PendingHook(annotatorSessionId, clock.instant(), onComplete);
        PendingHook prior = pending.put(annotatorSessionId, hook);
        if (prior != null) {
            log.info("[FlywheelChain] replaced existing hook for annotatorSessionId={}", annotatorSessionId);
        } else {
            log.info("[FlywheelChain] registered hook for annotatorSessionId={} (pendingSize={})",
                    annotatorSessionId, pending.size());
        }
    }

    /**
     * FLYWHEEL-CHAIN-VISIBILITY: register a dispatcher hook after the
     * controller's annotator-end hook callback successfully fires the
     * dispatcher chatAsync. The polling tick will subsequently detect the
     * dispatcher session's terminal {@code runtimeStatus} and emit the
     * chain-completed WS event + cache the {@link ChainRunResult}.
     *
     * <p>Inputs are validated narrowly — the controller is the only caller
     * and we've already done deeper validation there (agent exists, system
     * agents seeded). Re-registering the same dispatcher session id silently
     * replaces (same defensive double-click stance as the annotator hook).
     *
     * @param dispatcherSessionId id of the freshly-created dispatcher
     *                            session (chatAsync already fired)
     * @param agentId             target user-agent id (the operator's agent)
     * @param agentName           target agent display name (controller
     *                            captures from AgentEntity)
     * @param annotatorSessionId  id of the annotator session that just
     *                            finished
     * @param startedAt           when the operator clicked Run-Loop (i.e.
     *                            annotator register-time, NOT dispatcher
     *                            fire-time — opt loop wall-clock)
     * @param annotatorStatus     terminal status the annotator reached
     */
    public void registerDispatcherHook(String dispatcherSessionId,
                                       Long agentId,
                                       String agentName,
                                       String annotatorSessionId,
                                       Instant startedAt,
                                       String annotatorStatus) {
        if (dispatcherSessionId == null || dispatcherSessionId.isBlank()) {
            throw new IllegalArgumentException("dispatcherSessionId must be non-blank");
        }
        if (agentId == null) {
            throw new IllegalArgumentException("agentId must be non-null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must be non-null");
        }
        DispatcherHook hook = new DispatcherHook(
                agentId,
                agentName,
                annotatorSessionId,
                dispatcherSessionId,
                startedAt,
                clock.instant(),
                annotatorStatus);
        DispatcherHook prior = pendingDispatcher.put(dispatcherSessionId, hook);
        if (prior != null) {
            log.info("[FlywheelChain] replaced existing dispatcher hook for dispatcherSessionId={}",
                    dispatcherSessionId);
        } else {
            log.info("[FlywheelChain] registered dispatcher hook: dispatcherSessionId={} targetAgentId={} (pendingDispatcherSize={})",
                    dispatcherSessionId, agentId, pendingDispatcher.size());
        }
    }

    /**
     * Scheduler tick: walk {@link #pending} once, fire hooks whose annotator
     * session has reached terminal {@code runtimeStatus}, drop expired hooks.
     * Public visibility for unit tests.
     *
     * <p>FLYWHEEL-CHAIN-VISIBILITY: after the annotator-half sweep we also
     * walk {@link #pendingDispatcher} the same way, calling
     * {@link #completeChainRun} when the dispatcher session goes terminal.
     *
     * <p>Per-hook isolation: each hook's lookup / run is wrapped in try/catch
     * so a single bad session (deleted / DB hiccup) does not poison the rest
     * of the tick — same per-iteration narrow-catch shape as
     * {@code AttributionDispatcherService.dispatchPendingPatterns}.
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void tick() {
        Instant now = clock.instant();
        Instant ttlCutoff = now.minus(HOOK_TTL);

        // ─── annotator half ───────────────────────────────────────────────
        if (!pending.isEmpty()) {
            Iterator<Map.Entry<String, PendingHook>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, PendingHook> entry = it.next();
                String sessionId = entry.getKey();
                PendingHook hook = entry.getValue();

                // TTL sweep: drop without firing.
                if (hook.registeredAt().isBefore(ttlCutoff)) {
                    pending.remove(sessionId, hook);
                    log.warn("[FlywheelChain] hook for annotatorSessionId={} expired after TTL={}; "
                            + "annotator never reached terminal runtimeStatus", sessionId, HOOK_TTL);
                    continue;
                }

                try {
                    SessionEntity session = sessionService.getSession(sessionId);
                    String runtimeStatus = session.getRuntimeStatus();
                    if (STATUS_IDLE.equals(runtimeStatus) || STATUS_ERROR.equals(runtimeStatus)) {
                        // Atomic compare-and-remove: if the hook reference is still
                        // the one we matched, swap it out + fire. If another thread
                        // (e.g. a re-registration) replaced it, leave the new hook
                        // alone — next tick will handle it.
                        PendingHook[] firedRef = new PendingHook[1];
                        pending.computeIfPresent(sessionId, (k, current) -> {
                            if (current == hook) {
                                firedRef[0] = current;
                                return null;  // remove
                            }
                            return current;
                        });
                        if (firedRef[0] != null) {
                            log.info("[FlywheelChain] annotator terminal (status={}); firing hook for sessionId={}",
                                    runtimeStatus, sessionId);
                            try {
                                firedRef[0].onComplete().run();
                            } catch (RuntimeException e) {
                                log.error("[FlywheelChain] onComplete hook threw for annotatorSessionId={}: {}",
                                        sessionId, e.getMessage(), e);
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    // SessionNotFoundException / DataAccessException — drop the
                    // hook because we can't ever fire it. Same narrow-catch shape
                    // as AttributionDispatcherService.dispatchPendingPatterns
                    // (per-pattern try/catch lesson: one bad row must not poison
                    // the rest of the tick).
                    log.warn("[FlywheelChain] dropping hook for annotatorSessionId={} (lookup failed): {}",
                            sessionId, e.getMessage());
                    pending.remove(sessionId, hook);
                }
            }
        }

        // ─── dispatcher half (FLYWHEEL-CHAIN-VISIBILITY) ──────────────────
        if (!pendingDispatcher.isEmpty()) {
            Iterator<Map.Entry<String, DispatcherHook>> it = pendingDispatcher.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, DispatcherHook> entry = it.next();
                String sessionId = entry.getKey();
                DispatcherHook hook = entry.getValue();

                // TTL sweep — anchored to dispatcherFiredAt so the dispatcher
                // gets a fresh full HOOK_TTL window independent of how long
                // the annotator took. r1 W2 fix (2026-05-22): originally
                // anchored to startedAt to "bound the total opt loop window",
                // but a slow annotator (~9.9min) would leave the dispatcher
                // with only seconds before silent TTL drop — undercutting the
                // whole point of tracking dispatcher completion.
                if (hook.dispatcherFiredAt().isBefore(ttlCutoff)) {
                    pendingDispatcher.remove(sessionId, hook);
                    log.warn("[FlywheelChain] dispatcher hook for dispatcherSessionId={} (agentId={}) "
                            + "expired after TTL={}; dispatcher never reached terminal runtimeStatus",
                            sessionId, hook.agentId(), HOOK_TTL);
                    continue;
                }

                try {
                    SessionEntity session = sessionService.getSession(sessionId);
                    String runtimeStatus = session.getRuntimeStatus();
                    if (STATUS_IDLE.equals(runtimeStatus) || STATUS_ERROR.equals(runtimeStatus)) {
                        DispatcherHook[] firedRef = new DispatcherHook[1];
                        pendingDispatcher.computeIfPresent(sessionId, (k, current) -> {
                            if (current == hook) {
                                firedRef[0] = current;
                                return null;  // remove
                            }
                            return current;
                        });
                        if (firedRef[0] != null) {
                            log.info("[FlywheelChain] dispatcher terminal (status={}); completing chain for sessionId={} agentId={}",
                                    runtimeStatus, sessionId, firedRef[0].agentId());
                            try {
                                completeChainRun(firedRef[0], runtimeStatus);
                            } catch (RuntimeException e) {
                                log.error("[FlywheelChain] completeChainRun threw for dispatcherSessionId={}: {}",
                                        sessionId, e.getMessage(), e);
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("[FlywheelChain] dropping dispatcher hook for dispatcherSessionId={} (lookup failed): {}",
                            sessionId, e.getMessage());
                    pendingDispatcher.remove(sessionId, hook);
                }
            }
        }
    }

    /**
     * FLYWHEEL-CHAIN-VISIBILITY: build the terminal {@link ChainRunResult},
     * stash in the bounded LIFO, and broadcast to every connected operator.
     * Failure isolation: a DB / WS hiccup on any step is logged but does not
     * propagate (the caller is the scheduler thread — must keep ticking).
     */
    private void completeChainRun(DispatcherHook hook, String dispatcherStatus) {
        // 1) query opt event count since the opt loop started — best-effort.
        int count;
        try {
            long raw = optimizationEventRepository.countByAgentIdAndCreatedAtAfter(
                    hook.agentId(), hook.startedAt());
            count = (raw > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) raw;
        } catch (RuntimeException e) {
            log.warn("[FlywheelChain] failed to query opt event count for agentId={}: {}",
                    hook.agentId(), e.getMessage());
            count = -1;
        }

        // 2) build result.
        ChainRunResult result = new ChainRunResult(
                hook.agentId(),
                hook.agentName(),
                hook.annotatorSessionId(),
                hook.dispatcherSessionId(),
                hook.startedAt(),
                clock.instant(),
                hook.annotatorStatus(),
                dispatcherStatus,
                count);

        // 3) stash bounded LIFO.
        synchronized (completedRuns) {
            completedRuns.addFirst(result);
            while (completedRuns.size() > MAX_COMPLETED) {
                completedRuns.removeLast();
            }
        }

        // 4) broadcast WS payload. LinkedHashMap to keep field order stable
        // for FE devtools inspection.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "flywheel_chain_completed");
        payload.put("agentId", result.agentId());
        payload.put("agentName", result.agentName());
        payload.put("annotatorSessionId", result.annotatorSessionId());
        payload.put("dispatcherSessionId", result.dispatcherSessionId());
        payload.put("annotatorStatus", result.annotatorStatus());
        payload.put("dispatcherStatus", result.dispatcherStatus());
        payload.put("optEventCount", result.optEventCount());
        payload.put("hasResults", result.optEventCount() > 0);
        payload.put("startedAt", result.startedAt().toString());
        payload.put("completedAt", result.completedAt().toString());
        try {
            userWebSocketHandler.broadcastAll(payload);
        } catch (RuntimeException e) {
            log.warn("[FlywheelChain] WS broadcast failed for agentId={}: {}",
                    hook.agentId(), e.getMessage());
        }

        log.info("[FlywheelChain] chain completed: agentId={} agentName={} optEventCount={} dispatcherStatus={}",
                result.agentId(), result.agentName(), result.optEventCount(), result.dispatcherStatus());
    }

    /**
     * FLYWHEEL-CHAIN-VISIBILITY: snapshot of chain runs for the
     * {@code GET /api/flywheel/chain-runs} endpoint. Merges:
     * <ul>
     *   <li>{@link #pendingDispatcher} → projected as "still-running"
     *       {@link ChainRunResult}s with {@code dispatcherStatus=null} and
     *       {@code completedAt=null} so the FE can render a spinner badge;
     *   </li>
     *   <li>{@link #completedRuns} → terminal entries (newest-first by
     *       construction).</li>
     * </ul>
     *
     * <p>Sort: {@code startedAt DESC} across the merged set so an in-flight
     * run that started 10s ago sorts above a completed run from 5min ago.
     * Filtering by {@code agentId} happens post-merge — at dogfood scale
     * the merged set is at most ~100 entries so the linear filter is fine.
     */
    public List<ChainRunResult> getChainRuns(Long agentId, int limit) {
        if (limit < 1) limit = 1;
        if (limit > MAX_COMPLETED) limit = MAX_COMPLETED;

        List<ChainRunResult> merged = new ArrayList<>();

        // in-flight dispatcher hooks → partial ChainRunResult projections.
        for (DispatcherHook hook : pendingDispatcher.values()) {
            merged.add(new ChainRunResult(
                    hook.agentId(),
                    hook.agentName(),
                    hook.annotatorSessionId(),
                    hook.dispatcherSessionId(),
                    hook.startedAt(),
                    null,  // completedAt unknown — still in flight
                    hook.annotatorStatus(),
                    null,  // dispatcherStatus unknown — still in flight
                    -1));  // optEventCount unknown
        }

        synchronized (completedRuns) {
            merged.addAll(completedRuns);
        }

        // Filter + sort + truncate.
        merged.sort(Comparator.comparing(ChainRunResult::startedAt).reversed());
        if (agentId != null) {
            merged.removeIf(r -> !Objects.equals(r.agentId(), agentId));
        }
        if (merged.size() > limit) {
            return new ArrayList<>(merged.subList(0, limit));
        }
        return merged;
    }

    /**
     * Visible-for-test: snapshot the current pending map size. Public so unit
     * tests can assert "hook was registered" and "hook was fired+removed"
     * without poking the internal field reflectively.
     */
    public int pendingSize() {
        return pending.size();
    }

    /**
     * Visible-for-test: a sessionId is currently registered. Used by tests
     * to assert pre/post-tick state transitions.
     */
    public boolean isPending(String annotatorSessionId) {
        return pending.containsKey(annotatorSessionId);
    }

    /** Visible-for-test: FLYWHEEL-CHAIN-VISIBILITY dispatcher hook map size. */
    public int pendingDispatcherSize() {
        return pendingDispatcher.size();
    }

    /** Visible-for-test: a dispatcherSessionId is currently registered. */
    public boolean isDispatcherPending(String dispatcherSessionId) {
        return pendingDispatcher.containsKey(dispatcherSessionId);
    }

    /** Visible-for-test: completed-runs cache size. */
    public int completedRunsSize() {
        synchronized (completedRuns) {
            return completedRuns.size();
        }
    }

    /**
     * Visible-for-test only: snapshot of the entries via the supplied
     * {@link java.util.function.Function}; not exposed in production paths. Kept off public API
     * because the {@link PendingHook} record is package-private.
     */
    Map<String, PendingHook> snapshotForTest() {
        return new HashMap<>(pending);
    }
}
