package com.skillforge.server.improve.surface;

import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.improve.BehaviorRuleImproverService;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — third surface (behavior_rule)
 * implementation. Unlike {@code SkillSurface} / {@code PromptSurface} (thin
 * adapters over existing V2 / V3 services), this one is built from scratch
 * because there was no behavior_rule A/B service before V4.
 *
 * <p>Active-version lookup:
 * <ol>
 *   <li>Hit in-memory TTL cache (5 min — tech-design §4.3) → return.</li>
 *   <li>Query {@code t_behavior_rule_version WHERE status='active'} → cache + return.</li>
 *   <li>Otherwise, return {@code null}; the {@code BehaviorRuleImproverService}
 *       caller handles the no-baseline case by feeding {@code rulesJson="[]"}
 *       into the LLM prompt. <b>We do NOT</b> synthesize an ad-hoc entity
 *       wrapping a startup-loaded baseline, because downstream
 *       {@code promote(candidate)} would then attempt to retire a phantom row
 *       that doesn't exist in the DB, violating V82's partial UNIQUE
 *       invariant. (Implementation judgment vs tech-design §4.3's "fallback
 *       to startup baseline" sketch — see the §4.3 dev-judgment note.) Phase
 *       1.2 may inject {@code BehaviorRuleRegistry} (core) here if sandbox
 *       evaluation needs the built-in baseline rules; Phase 1.1 deliberately
 *       does not depend on it.</li>
 * </ol>
 *
 * <p>Cache implementation: lightweight {@link ConcurrentHashMap} with
 * timestamp-based TTL on read (not Caffeine — the project doesn't have
 * Caffeine on the classpath today, and Phase 1.1 doesn't justify adding a
 * new dependency for a single 5-min cache).
 */
@Component
public class BehaviorRuleSurface implements OptimizableSurface<BehaviorRuleVersionEntity> {

    public static final String SURFACE_TYPE = "behavior_rule";

    private static final Logger log = LoggerFactory.getLogger(BehaviorRuleSurface.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final BehaviorRuleVersionRepository versionRepository;
    private final BehaviorRuleImproverService improverService;
    private final BehaviorRulePromotionService promotionService;

    /**
     * agentId → cached active-version entry. Updated on every miss in
     * {@link #loadActive} and invalidated by {@link #promote} / {@link #rollback}
     * for the affected agent. Cross-instance staleness is bounded by
     * {@link #CACHE_TTL}; we don't try to coordinate across instances (a 5-min
     * window of stale data after promote on another node is acceptable for
     * Phase 1.1 — V5+ may revisit if multi-node deploys become routine).
     */
    private final ConcurrentHashMap<Long, CachedEntry> activeCache = new ConcurrentHashMap<>();

    /**
     * Phase 1.3 — session-scoped registry of which {@link BehaviorRuleVersionEntity}
     * is currently injected for which sandbox session. Same shape + semantics as
     * {@code SkillSurface.injectedBySession} / {@code PromptSurface.injectedBySession}:
     * populated by {@link #injectForSandbox}, queryable by
     * {@link #getInjectedVersion}, removed when a {@code version=null} inject
     * arrives. Concurrent because two A/B runs (different agents) may inject
     * disjoint sessionId keys in parallel.
     *
     * <p>This map is the **inject side** of the cache; {@link #activeCache}
     * is the **loadActive side** (per-agent active version). They're separate
     * concerns and intentionally not unified — sandbox state is per-session,
     * production active is per-agent.
     *
     * <p>Phase 1.2 callers (Skill / Prompt subclass orchestrators) pass the
     * version directly to {@code runEvalSet}, so this map is NOT consumed by
     * the template's run() path today. Reserved for Phase 1.3+ surface-aware
     * dispatch (e.g. a sandbox-scoped {@code BehaviorRuleRegistry} override
     * that wants to see the candidate rules during sandbox eval).
     */
    private final ConcurrentMap<String, BehaviorRuleVersionEntity> injectedBySession =
            new ConcurrentHashMap<>();

    public BehaviorRuleSurface(BehaviorRuleVersionRepository versionRepository,
                                BehaviorRuleImproverService improverService,
                                BehaviorRulePromotionService promotionService) {
        // Phase 1.1 reviewer fix (W-QUAL-4 / W1): no startupRegistry dependency
        // — the field was unused and the class javadoc previously misled
        // readers into thinking BehaviorRuleSurface owns the startup-baseline
        // fallback (it does not; BehaviorRuleImproverService handles the
        // null-baseline case with rulesJson="[]"). Phase 1.2 may add
        // BehaviorRuleRegistry injection back if sandbox evaluation needs the
        // built-in rules.
        this.versionRepository = versionRepository;
        this.improverService = improverService;
        this.promotionService = promotionService;
    }

    @Override
    public String surfaceType() {
        return SURFACE_TYPE;
    }

    @Override
    public BehaviorRuleVersionEntity loadActive(Long agentId) {
        if (agentId == null) return null;

        CachedEntry cached = activeCache.get(agentId);
        if (cached != null && !cached.isExpired()) {
            return cached.entity();
        }

        BehaviorRuleVersionEntity active = versionRepository
                .findByAgentIdAndStatus(String.valueOf(agentId), BehaviorRuleVersionEntity.STATUS_ACTIVE)
                .orElse(null);

        // Cache positive AND negative results — a "no DB row" answer is just
        // as valid and we don't want to hammer the DB for agents that have
        // never promoted a candidate. Negative cache (null entity) still
        // respects the 5-min TTL so a freshly-promoted version surfaces
        // within 5 min on this node.
        activeCache.put(agentId, new CachedEntry(active, Instant.now()));
        return active;
    }

    @Override
    public BehaviorRuleVersionEntity loadVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) return null;
        return versionRepository.findById(versionId).orElse(null);
    }

    @Override
    public BehaviorRuleVersionEntity createCandidate(BehaviorRuleVersionEntity baseline,
                                                      String improvementContext) {
        if (baseline == null) {
            throw new IllegalArgumentException(
                    "BehaviorRuleSurface.createCandidate: baseline must be non-null. "
                            + "For first-attribution-for-agent flows use BehaviorRuleImproverService."
                            + "startImprovementFromAttribution directly (it tolerates null DB baseline).");
        }
        if (improvementContext == null || improvementContext.isBlank()) {
            throw new IllegalArgumentException("improvementContext required");
        }

        // This entry point is used by AbstractAbEvalRunner (Phase 1.2) — for
        // attribution flows callers go through BehaviorRuleImproverService
        // directly. Persist a minimal candidate row tagged source='manual'
        // so the Phase 1.2 runner doesn't depend on attribution metadata.
        BehaviorRuleVersionEntity candidate = new BehaviorRuleVersionEntity();
        candidate.setId(UUID.randomUUID().toString());
        candidate.setAgentId(baseline.getAgentId());
        candidate.setVersionNumber(baseline.getVersionNumber() + 1);
        candidate.setStatus(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        candidate.setSource(BehaviorRuleVersionEntity.SOURCE_MANUAL);
        candidate.setBaselineVersionId(baseline.getId());
        candidate.setImprovementRationale(improvementContext.trim());

        // Delegate to the improver's LLM helper for the actual rules-JSON
        // synthesis. Phase 1.1 surface is the only non-attribution caller of
        // that helper; Phase 1.2 will revisit if a second call site appears.
        //
        // Phase 1.1 reviewer fix (W-QUAL-1): match the V3.1
        // commit 91c3108 audit-trail pattern from
        // BehaviorRuleImproverService.startImprovementFromAttribution — on
        // LLM failure, save a placeholder row with rulesJson="[]" then
        // RETHROW so the upstream catch (Phase 1.3 AttributionApprovalService)
        // writes stage='candidate_failed' at the optimization_event layer.
        // Silently returning a '[]' candidate would let Phase 1.2's A/B
        // runner compare empty-vs-empty and false-positively promote.
        String improvedRules;
        try {
            improvedRules = improverService.generateCandidateRulesFromAttribution(
                    baseline.getRulesJson() != null ? baseline.getRulesJson() : "[]",
                    improvementContext);
        } catch (RuntimeException llmEx) {
            candidate.setRulesJson("[]");
            versionRepository.save(candidate);
            log.error("BehaviorRuleSurface.createCandidate LLM fill failed, persisted empty audit row: "
                            + "agentId={} versionId={}: {}",
                    candidate.getAgentId(), candidate.getId(), llmEx.getMessage());
            throw llmEx;
        }
        candidate.setRulesJson(improvedRules);

        return versionRepository.save(candidate);
    }

    @Override
    public void injectForSandbox(SandboxContext ctx, BehaviorRuleVersionEntity version) {
        // Phase 1.3: stash the version under the sandbox session id (same shape
        // as SkillSurface / PromptSurface Phase 1.2 r1 inject pattern). Full
        // sandbox-scoped BehaviorRuleRegistry override remains future work — the
        // map is registry-only, no I/O. The Phase 1.2 r1 design judgment was
        // that surfaces own the per-session inject state (rather than the
        // AbstractAbEvalRunner template), so behavior_rule must hold the same
        // contract even though no Phase 1.3 caller consumes it yet (Phase 1.4
        // dashboard surface-aware dispatch is the first consumer).
        //
        // Passing version=null deletes the entry (used by external callers to
        // tear down after a sandbox session ends).
        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            throw new IllegalArgumentException(
                    "SandboxContext.sessionId is required for injectForSandbox");
        }
        if (version == null) {
            injectedBySession.remove(ctx.sessionId());
        } else {
            injectedBySession.put(ctx.sessionId(), version);
        }
    }

    /**
     * Phase 1.3 helper — return the {@link BehaviorRuleVersionEntity} most
     * recently injected for the given sandbox session, or {@code null} when no
     * entry exists. Mirrors {@code SkillSurface.getInjectedVersion} /
     * {@code PromptSurface.getInjectedVersion} so dashboard surface-aware
     * dispatch (Phase 1.4) can read sandbox version state uniformly across
     * the three surfaces via {@link OptimizableSurface} pattern.
     */
    public BehaviorRuleVersionEntity getInjectedVersion(String sessionId) {
        return sessionId == null ? null : injectedBySession.get(sessionId);
    }

    @Override
    public void promote(BehaviorRuleVersionEntity candidate) {
        promotionService.promote(candidate);
        invalidateCache(candidate);
    }

    @Override
    public void rollback(BehaviorRuleVersionEntity candidate) {
        promotionService.rollback(candidate);
        invalidateCache(candidate);
    }

    /**
     * Invalidate the loadActive cache for the affected agent so a subsequent
     * read sees the post-promote / post-rollback state. Tolerates non-numeric
     * agent ids (legacy / test fixtures) — those just skip cache eviction.
     */
    private void invalidateCache(BehaviorRuleVersionEntity v) {
        if (v == null || v.getAgentId() == null) return;
        try {
            activeCache.remove(Long.parseLong(v.getAgentId()));
        } catch (NumberFormatException nfe) {
            // Non-numeric agent id — clear the whole cache as a defensive
            // fallback. In production agent ids are always numeric Longs;
            // this branch only triggers in tests with synthetic UUID-style ids.
            activeCache.clear();
        }
    }

    /**
     * V4 Phase 1.1 alternate entry — attribution-derived candidate creation.
     * Delegates to {@link BehaviorRuleImproverService} so the same path is
     * usable from {@code AttributionApprovalService.dispatchBehaviorRuleSurface}
     * (Phase 1.3) without coupling that service to the surface adapter.
     *
     * <p>Why not push this through {@link #createCandidate}: the attribution
     * flow has explicit {@code eventId} + {@code ownerId} audit fields that the
     * generic surface {@link #createCandidate} doesn't accept. Mixing the two
     * would either widen the interface signature (breaking the locked 6-method
     * shape) or hide the audit fields inside {@code improvementContext} as
     * encoded strings (V3 SkillDraftService's
     * {@code [attribution:eventId=...]} prefix trick — works but harder to
     * filter on).
     */
    public ImprovementStartResult startImprovementFromAttribution(Long eventId,
                                                                  String agentId,
                                                                  String attributedDescription,
                                                                  Long ownerId) {
        return improverService.startImprovementFromAttribution(
                eventId, agentId, attributedDescription, ownerId);
    }

    /** Visible for tests — force cache invalidation without going through promote/rollback. */
    void evictCacheForTests(Long agentId) {
        activeCache.remove(agentId);
    }

    private record CachedEntry(BehaviorRuleVersionEntity entity, Instant loadedAt) {
        boolean isExpired() {
            return Duration.between(loadedAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
}
