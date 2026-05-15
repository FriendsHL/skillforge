package com.skillforge.server.attribution;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.2: hourly cron entry point that scans
 * {@code t_session_pattern} for candidate failure clusters, applies the four
 * ratify-locked filters (surface allowlist / member-count threshold / 24h
 * cooldown / no in-flight event), and dispatches the {@code attribution-curator}
 * agent for each survivor.
 *
 * <p>Spec: {@code docs/requirements/active/ATTRIBUTION-AGENT/tech-design.md §1}
 * (dispatcher flow) + {@code prd.md §用户流程 step 2} + ratify #2 (24h cooldown)
 * + ratify #6 (surface ∈ \{skill, prompt\}).
 *
 * <p>Per-pattern dispatch: creates a fresh system-owned session bound to the
 * {@code attribution-curator} agent and submits a per-pattern user-message
 * carrying the {@code patternId}. The agent then runs its 4-STEP system-prompt
 * pipeline (PatternRead → SessionAnnotationRead+GetTrace × member sessions →
 * LLM reasoning → ProposeOptimization). Cooldown is enforced by the
 * {@code ProposeOptimization} tool setting
 * {@code t_optimization_event.cooldown_expires_at = NOW() + 24h} at the end of
 * a successful run; the dispatcher reads that column to gate future scans.
 *
 * <p>Wiring note: this service exposes {@link #dispatchPendingPatterns(int)} as
 * a plain method. Phase 1.4 / Bootstrap can attach it to either a Spring
 * {@code @Scheduled} cron or to the V81 ScheduledTask
 * {@code attribution-dispatcher-hourly} via a dispatcher tool — Phase 1.2 only
 * delivers the service itself + the 4 agent-facing tools.
 *
 * <p>Concurrency: not {@code @Transactional}'d at the method level — each
 * sub-call (repository scan / per-pattern dispatch) uses its own short
 * transaction so a single pattern's failure doesn't block the rest. {@link
 * DataAccessException} is caught per-pattern (per V1 W2 + V2 W2 lessons:
 * narrow catch, never swallow generic {@code Exception}).
 */
@Service
public class AttributionDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(AttributionDispatcherService.class);

    public static final String CURATOR_AGENT_NAME = "attribution-curator";
    public static final int DEFAULT_MAX_DISPATCH_PER_RUN = 5;
    public static final int MIN_MEMBER_COUNT = 3;
    public static final int SCAN_PAGE_SIZE = 100;
    /** SYSTEM user marker — same convention as V69 / V75 / V79 ScheduledTask creator_user_id. */
    public static final long SYSTEM_USER_ID = 0L;

    /**
     * Surfaces V3 auto-dispatches (ratify #6). {@code behavior_rule} is V4,
     * {@code other / unclear} are recorded but never approved.
     */
    static final Set<String> ELIGIBLE_SURFACES = Set.of(
            OptimizationEventEntity.SURFACE_SKILL,
            OptimizationEventEntity.SURFACE_PROMPT);

    /**
     * Pre-terminal event stages — having any of these for a pattern means an
     * earlier optimization is still in flight, so the dispatcher must not start
     * a competing curator run. Phase 1.3 added {@code dispatch_initiated} (the
     * sentinel row written before chatAsync — closes the race window where two
     * concurrent dispatcher ticks could both see "no event") and
     * {@code candidate_generating} (set by AttributionApprovalService.approve).
     *
     * <p>Phase 1.3 reviewer fix: also includes {@code candidate_failed}.
     * Per tech-design.md §6, {@code candidate_failed} is terminal until the
     * operator manually retries via the Phase 1.4 REST endpoint — auto-retrying
     * by re-dispatching would burn LLM budget on systematic failures (e.g. the
     * curator's proposal contradicts a hard constraint of the surface).
     * Other terminal stages ({@code proposal_rejected}, {@code ab_failed},
     * {@code rolled_back}, {@code verified}) are NOT in this set because
     * either (a) operator already explicitly closed the loop, or (b)
     * downstream Phase 1.4+ wiring will re-evaluate the pattern in the
     * normal scan after enough new evidence accumulates.
     */
    static final Set<String> ACTIVE_STAGES = Set.of(
            OptimizationEventEntity.STAGE_DISPATCH_INITIATED,
            OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
            OptimizationEventEntity.STAGE_PROPOSAL_APPROVED,
            OptimizationEventEntity.STAGE_CANDIDATE_GENERATING,
            OptimizationEventEntity.STAGE_CANDIDATE_READY,
            OptimizationEventEntity.STAGE_CANDIDATE_FAILED,
            OptimizationEventEntity.STAGE_CANDIDATE_CREATED,
            OptimizationEventEntity.STAGE_AB_RUNNING,
            OptimizationEventEntity.STAGE_AB_PASSED,
            OptimizationEventEntity.STAGE_CANARY_STARTED);

    private final SessionPatternRepository patternRepository;
    private final OptimizationEventRepository eventRepository;
    private final AgentRepository agentRepository;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final Clock clock;

    public AttributionDispatcherService(SessionPatternRepository patternRepository,
                                        OptimizationEventRepository eventRepository,
                                        AgentRepository agentRepository,
                                        SessionService sessionService,
                                        ChatService chatService,
                                        Clock clock) {
        this.patternRepository = patternRepository;
        this.eventRepository = eventRepository;
        this.agentRepository = agentRepository;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.clock = clock;
    }

    /**
     * Outcome counts of a single dispatcher run. Useful for log lines + future
     * dashboard surfaces; not persisted.
     *
     * @param scanned         total pattern rows seen this run
     * @param dispatched      patterns that passed all filters AND for which the
     *                        attribution-curator session was successfully spawned
     * @param skippedSurface  surface ∉ \{skill, prompt\} (ratify #6)
     * @param skippedCooldown latest event row's {@code cooldown_expires_at} > now
     * @param skippedActive   non-empty pre-terminal stage event for this pattern
     */
    public record DispatchResult(int scanned,
                                 int dispatched,
                                 int skippedSurface,
                                 int skippedCooldown,
                                 int skippedActive) {}

    /**
     * Scan candidate patterns and dispatch the curator agent for the top-N
     * eligible. {@code maxDispatchPerRun ≤ 0} falls back to
     * {@link #DEFAULT_MAX_DISPATCH_PER_RUN}.
     *
     * <p>Intentionally NOT {@code @Transactional} (Phase 1.2 reviewer fix):
     * sub-calls each carry their own short transaction. Wrapping the whole
     * scan would (a) make {@code chatAsync}'s pool worker race the outer
     * commit (silent "session not found" while we report dispatched++), and
     * (b) let one pattern's {@code DataAccessException} mark the outer tx
     * rollback-only, killing the entire batch on commit even though we
     * caught the exception and intended to continue.
     */
    public DispatchResult dispatchPendingPatterns(int maxDispatchPerRun) {
        int cap = maxDispatchPerRun > 0 ? maxDispatchPerRun : DEFAULT_MAX_DISPATCH_PER_RUN;

        Optional<AgentEntity> curatorOpt = agentRepository.findFirstByName(CURATOR_AGENT_NAME);
        if (curatorOpt.isEmpty()) {
            log.warn("[AttributionDispatcher] {} agent missing — V81 migration not applied? Skipping dispatch.",
                    CURATOR_AGENT_NAME);
            return new DispatchResult(0, 0, 0, 0, 0);
        }
        Long curatorAgentId = curatorOpt.get().getId();

        // Wide scan: filter null on all dims so we see every pattern. Page size 100
        // is a defensive ceiling — V1 dogfood produces <20 distinct patterns per
        // recompute cycle in current data. SCAN_PAGE_SIZE bump is a one-line
        // change if observed pattern count rises.
        List<SessionPatternEntity> patterns = patternRepository.findWithFilters(
                null, null, null, PageRequest.of(0, SCAN_PAGE_SIZE));

        int scanned = patterns.size();
        int dispatched = 0;
        int skippedSurface = 0;
        int skippedCooldown = 0;
        int skippedActive = 0;
        Instant now = clock.instant();

        for (SessionPatternEntity p : patterns) {
            if (dispatched >= cap) {
                break;
            }

            // Filter 1: surface allowlist (ratify #6).
            if (!ELIGIBLE_SURFACES.contains(p.getSuspectSurface())) {
                skippedSurface++;
                continue;
            }
            // Filter 2: member count threshold (defensive duplicate of V1
            // cluster recompute rule — clusters with <3 members are rarely
            // signal; skip without counting in a specific bucket).
            if (p.getMemberCount() < MIN_MEMBER_COUNT) {
                continue;
            }
            // Filter 3: 24h cooldown (ratify #2). Any event row for this
            // pattern whose cooldown_expires_at is still in the future blocks
            // dispatch.
            List<OptimizationEventEntity> activeCool = eventRepository
                    .findByPatternIdAndCooldownExpiresAtAfter(p.getId(), now);
            if (!activeCool.isEmpty()) {
                skippedCooldown++;
                continue;
            }
            // Filter 4: pre-terminal event already in flight (defensive — the
            // 24h cooldown row should catch this in practice, but if cooldown
            // expired while the prior event is mid-pipeline, we still skip).
            // Single COUNT(...) > 0 query (Phase 1.2 reviewer fix — collapsed
            // a 6-stage for-loop that was N+1 against SCAN_PAGE_SIZE).
            if (eventRepository.existsByPatternIdAndStageIn(p.getId(), ACTIVE_STAGES)) {
                skippedActive++;
                continue;
            }

            // Survive all 4 filters → dispatch.
            try {
                dispatchOne(p, curatorAgentId);
                dispatched++;
            } catch (DataAccessException dae) {
                // V1 W2 / V2 W2 lesson: narrow catch on persistence errors so
                // one pattern's DB hiccup doesn't poison the rest of the scan.
                log.error("[AttributionDispatcher] dispatch failed for pattern {} (DataAccessException): {}",
                        p.getId(), dae.getMessage(), dae);
            } catch (RuntimeException e) {
                // Non-DB runtime failure (e.g. ChatService rejected execution,
                // pattern row mutated mid-flight). Log + continue.
                log.error("[AttributionDispatcher] dispatch failed for pattern {}: {}",
                        p.getId(), e.getMessage(), e);
            }
        }

        log.info("[AttributionDispatcher] scan complete: scanned={} dispatched={} "
                        + "skippedSurface={} skippedCooldown={} skippedActive={} cap={}",
                scanned, dispatched, skippedSurface, skippedCooldown, skippedActive, cap);
        return new DispatchResult(scanned, dispatched, skippedSurface, skippedCooldown, skippedActive);
    }

    /**
     * Spawn a fresh attribution-curator session and submit the per-pattern
     * user-message that the system prompt expects. The agent's first tool call
     * (PatternRead) consumes the patternId; the rest of its 4-STEP pipeline
     * follows from there.
     *
     * <p>Phase 1.3 ratify: writes a {@code dispatch_initiated} sentinel
     * {@link OptimizationEventEntity} BEFORE invoking chatAsync. This closes
     * the race window where two concurrent dispatcher ticks could both observe
     * "no event for this pattern" and double-fire the curator. The sentinel is
     * later UPDATEd into {@code proposal_pending} (or {@code proposal_rejected})
     * by {@code ProposeOptimizationTool} once the curator finishes.
     *
     * <p>If the sentinel write fails the exception propagates to the caller
     * loop's {@code DataAccessException} handler — chatAsync is intentionally
     * NOT invoked on a failed sentinel write (would orphan a curator session
     * with no event row to anchor it).
     */
    void dispatchOne(SessionPatternEntity pattern, Long curatorAgentId) {
        OptimizationEventEntity sentinel = new OptimizationEventEntity();
        sentinel.setPatternId(pattern.getId());
        // pattern.agentId may be null on legacy V1 rows pre-V75; substitute the
        // curator agent id rather than violate NOT NULL — Phase 1.3 reviewers
        // can re-evaluate if observability data shows null pattern agentIds in
        // production.
        sentinel.setAgentId(pattern.getAgentId() != null ? pattern.getAgentId() : curatorAgentId);
        sentinel.setSurfaceType(pattern.getSuspectSurface());
        sentinel.setStage(OptimizationEventEntity.STAGE_DISPATCH_INITIATED);
        // All other fields (changeType / description / expectedImpact / confidence
        // / risk / cooldownExpiresAt / candidate*Id / abRunId / canaryId /
        // attributionSessionId) intentionally left null — ProposeOptimizationTool
        // populates them when the curator finishes. createdAt / updatedAt are
        // auto-populated by @PrePersist.
        OptimizationEventEntity persistedSentinel = eventRepository.save(sentinel);
        log.debug("[AttributionDispatcher] sentinel written for patternId={} eventId={}",
                pattern.getId(), persistedSentinel.getId());

        SessionEntity session = sessionService.createSession(SYSTEM_USER_ID, curatorAgentId);
        String prompt = composeDispatchPrompt(pattern);
        chatService.chatAsync(session.getId(), prompt, SYSTEM_USER_ID);
        log.info("[AttributionDispatcher] dispatched curator for patternId={} sessionId={} sentinelEventId={}",
                pattern.getId(), session.getId(), persistedSentinel.getId());
    }

    /**
     * Compose the dispatch prompt. Package-private so tests can pin the wire
     * format without going through chatAsync.
     */
    static String composeDispatchPrompt(SessionPatternEntity pattern) {
        return String.format(
                "Run the 4-STEP attribution pipeline for patternId=%d "
                        + "(agentId=%s, suspectSurface=%s, memberCount=%d). "
                        + "STEP 1: PatternRead(%d); STEP 2-4 follow your system prompt.",
                pattern.getId(),
                pattern.getAgentId() == null ? "null" : pattern.getAgentId().toString(),
                pattern.getSuspectSurface(),
                pattern.getMemberCount(),
                pattern.getId());
    }
}
