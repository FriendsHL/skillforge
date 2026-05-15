package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.2: per-session skill-version allocator.
 *
 * <p>V4 MULTI-SURFACE-FLYWHEEL Phase 1.3 — generalized to accept arbitrary
 * surface types via {@link #allocate(String, Long, String, String)}. The legacy
 * 3-arg {@link #allocate(String, Long, String)} entry point is preserved as a
 * thin wrapper (zero behavior drift for V2 {@code DefaultSessionSkillResolver}
 * callers) and forwards with {@code surfaceType="skill"} hardcoded.
 *
 * <p>Called by {@code DefaultSessionSkillResolver} for each user-bound skill
 * name in {@code agentDef.skillIds}. Default (no active canary) returns the
 * baseline name unchanged, preserving today's "一刀切" behaviour — this is the
 * safe path that every existing skill takes until an operator explicitly opens
 * a canary.
 *
 * <p>When a canary is active, the algorithm (tech-design.md §5) is:
 * <ol>
 *   <li>Find the single active rollout for (agentId, surface_type,
 *       baseline_identity).</li>
 *   <li>Short-circuit on degenerate percentages: 100 → candidate, 0 → baseline.</li>
 *   <li>Otherwise check {@code t_session_annotation} for a prior
 *       {@code canary_group} row pinning this session to a version (per
 *       "session 锁版本" ratify decision — a session never switches version
 *       mid-flight).</li>
 *   <li>For brand-new sessions, hash the sessionId into a 0..99 bucket and
 *       pick candidate if bucket &lt; pct, then persist the assignment via
 *       {@link SessionAnnotationRepository#upsertSkipDuplicate} so subsequent
 *       turns of the same session read the pinned value.</li>
 * </ol>
 *
 * <p><b>Annotation discrimination (Phase 1.3 design judgment)</b>:
 * {@code annotation_type} stays the constant {@code "canary_group"} across all
 * surfaces; the {@code annotation_value} prefix {@code "<surface>:<identity>"}
 * already discriminates by surface (skill rows write {@code "skill:my-skill"},
 * behavior_rule rows write {@code "behavior_rule:<versionId>"} etc.). This is
 * the V2 contract — {@link SessionAnnotationRepository#findCanaryGroup} already
 * takes a {@code surfaceType} arg and filters by the value prefix — so generic
 * support requires zero migration to existing V2 rows.
 *
 * <p><b>Idempotency</b>: persistence relies on {@code ON CONFLICT DO NOTHING}
 * (per V1 W2 PG aborted-tx footgun fix). A second concurrent allocate for
 * the same sessionId may race, but both writes converge to one row (UNIQUE
 * constraint) and the follow-up reads use {@code findCanaryGroup} which
 * resolves deterministically.
 *
 * <p><b>Hash stability</b>: {@code (sessionId.hashCode() &amp; 0x7FFFFFFF) % 100}
 * is stable per JVM. {@code String.hashCode()} is JLS-defined so behaviour is
 * identical across JVM versions — safe to use without a custom hash.
 */
@Component
public class CanaryAllocator {

    private static final Logger log = LoggerFactory.getLogger(CanaryAllocator.class);

    /** Surface namespace prefix for the canary_group annotation_value. */
    static final String SURFACE_SKILL = CanaryRolloutEntity.SURFACE_SKILL;
    static final String ANNOTATION_TYPE = "canary_group";
    static final String ANNOTATION_SOURCE = "system";

    private final CanaryRolloutRepository canaryRepository;
    private final SessionAnnotationRepository sessionAnnotationRepository;

    public CanaryAllocator(CanaryRolloutRepository canaryRepository,
                           SessionAnnotationRepository sessionAnnotationRepository) {
        this.canaryRepository = canaryRepository;
        this.sessionAnnotationRepository = sessionAnnotationRepository;
    }

    /**
     * V2 legacy 3-arg entry point — skill surface only. Preserved as a thin
     * wrapper so {@code DefaultSessionSkillResolver.allocateForSkill} (and any
     * other V2 callers) keep working with zero source diff. Delegates to the
     * generic 4-arg overload with {@code surfaceType="skill"} and continues to
     * use the V2 {@link CanaryRolloutRepository#findActiveCanaryForSkill}
     * query (hard-coded skill filter in JPQL) — this keeps existing test mocks
     * (which stub {@code findActiveCanaryForSkill}) green.
     *
     * @param sessionId         current session id (must be non-null and stable
     *                          across the session's lifetime)
     * @param agentId           id of the agent owning this skill binding;
     *                          {@code null} short-circuits to baseline
     *                          (legacy / test paths)
     * @param baselineSkillName the skill name the resolver was originally
     *                          going to mount (never {@code null})
     * @return the skill name to mount: either {@code baselineSkillName}
     *         unchanged or the rollout's candidate name
     */
    public String allocate(String sessionId, Long agentId, String baselineSkillName) {
        if (baselineSkillName == null) {
            return null;
        }
        if (sessionId == null || agentId == null) {
            // No session / no agent context → behave like today (baseline).
            return baselineSkillName;
        }

        Optional<CanaryRolloutEntity> activeOpt;
        try {
            activeOpt = canaryRepository.findActiveCanaryForSkill(agentId, baselineSkillName);
        } catch (DataAccessException e) {
            log.warn("CanaryAllocator: repository lookup failed for agent={}, skill={}; falling back to baseline: {}",
                    agentId, baselineSkillName, e.getMessage());
            return baselineSkillName;
        }
        if (activeOpt.isEmpty()) {
            return baselineSkillName;
        }
        return allocateFromCanary(sessionId, activeOpt.get(), SURFACE_SKILL, baselineSkillName,
                activeOpt.get().getCandidateSkillName());
    }

    /**
     * V4 MULTI-SURFACE-FLYWHEEL Phase 1.3: generic per-session canary allocator.
     * Same algorithm as the V2 3-arg form, but the active-canary lookup is
     * parameterized by {@code surfaceType} so behavior_rule / prompt surfaces
     * can plug in once Phase 1.4 wires their canary creation paths.
     *
     * <p>The session-pin annotation is stored as
     * {@code annotation_type='canary_group'} +
     * {@code annotation_value='<surfaceType>:<identity>'} — identical layout to
     * V2 skill rows. {@link SessionAnnotationRepository#findCanaryGroup}
     * already filters by surfaceType-prefix, so multi-surface coexistence on
     * the same session is naturally supported.
     *
     * <p>{@code baselineIdentity} semantics by surface:
     * <ul>
     *   <li>{@code surfaceType="skill"}: skill name (V2 contract)</li>
     *   <li>{@code surfaceType="prompt"}: prompt version id (UUID)</li>
     *   <li>{@code surfaceType="behavior_rule"}: behavior_rule version id (UUID)</li>
     * </ul>
     *
     * @param sessionId         current session id; {@code null} short-circuits
     * @param agentId           agent owning the surface binding; {@code null} short-circuits
     * @param surfaceType       {@code "skill"} / {@code "prompt"} / {@code "behavior_rule"}
     * @param baselineIdentity  the surface-specific baseline identifier; {@code null} short-circuits
     * @return baseline or candidate identity per the rollout's percentage hash
     */
    public String allocate(String sessionId, Long agentId, String surfaceType, String baselineIdentity) {
        if (baselineIdentity == null) {
            return null;
        }
        if (surfaceType == null || surfaceType.isBlank()) {
            // Defensive: empty surface degrades to baseline rather than throwing
            // — keeps caller paths fail-safe (V2-style).
            return baselineIdentity;
        }
        if (sessionId == null || agentId == null) {
            return baselineIdentity;
        }

        Optional<CanaryRolloutEntity> activeOpt;
        try {
            activeOpt = canaryRepository.findActiveCanaryByAgentSurfaceBaseline(
                    agentId, surfaceType, baselineIdentity);
        } catch (DataAccessException e) {
            log.warn("CanaryAllocator: repository lookup failed for agent={}, surface={}, baseline={}; falling back to baseline: {}",
                    agentId, surfaceType, baselineIdentity, e.getMessage());
            return baselineIdentity;
        }
        if (activeOpt.isEmpty()) {
            return baselineIdentity;
        }
        return allocateFromCanary(sessionId, activeOpt.get(), surfaceType, baselineIdentity,
                activeOpt.get().getCandidateSkillName());
    }

    /**
     * Shared hash-bucket + session-pin path used by both 3-arg (skill-only) and
     * 4-arg (generic) entry points. Pulled out so both paths share the
     * pct&gt;=100 / pct&lt;=0 short-circuits, pre-existing canary_group lookup,
     * fresh hash assignment, and {@code upsertSkipDuplicate} persistence.
     *
     * <p>{@code candidateIdentity} is read from
     * {@link CanaryRolloutEntity#getCandidateSkillName()} for now — the V77
     * column name is skill-specific but the values are surface-agnostic
     * identifiers (a candidate's surface-specific id). A future migration may
     * rename this column to {@code candidate_identity} once Phase 1.4 wires
     * non-skill canary creation; until then we accept the leaky column name.
     */
    private String allocateFromCanary(String sessionId,
                                      CanaryRolloutEntity canary,
                                      String surfaceType,
                                      String baselineIdentity,
                                      String candidateIdentity) {
        int pct = canary.getRolloutPercentage() == null ? 0 : canary.getRolloutPercentage();

        if (pct >= 100) {
            // Fully rolled out — every session sees candidate. (Note: the
            // promote-to-100 path normally transitions stage='canary' →
            // 'production' so this branch is defensive against operator
            // races where pct was nudged to 100 but stage hasn't flipped yet.)
            return candidateIdentity;
        }
        if (pct <= 0) {
            return baselineIdentity;
        }

        // Session already pinned? — honour the prior assignment so the same
        // session never sees two versions during its lifetime.
        Optional<String> existingGroup;
        try {
            existingGroup = sessionAnnotationRepository.findCanaryGroup(sessionId, surfaceType);
        } catch (DataAccessException e) {
            log.warn("CanaryAllocator: findCanaryGroup failed for session={}, surface={}; falling back to baseline: {}",
                    sessionId, surfaceType, e.getMessage());
            return baselineIdentity;
        }
        if (existingGroup.isPresent()) {
            String prior = parseIdentityFromGroupValue(existingGroup.get(), surfaceType);
            if (prior != null) {
                return prior;
            }
            // Malformed value — fall through to fresh allocation; we still try
            // to persist below so the next call has a clean row.
            log.warn("CanaryAllocator: malformed canary_group value '{}' for session={}, surface={}, falling through",
                    existingGroup.get(), sessionId, surfaceType);
        }

        // Fresh allocation — deterministic hash bucket.
        int bucket = (sessionId.hashCode() & 0x7FFFFFFF) % 100;
        String picked = bucket < pct ? candidateIdentity : baselineIdentity;

        // Persist the assignment so subsequent turns of the same session
        // converge. V1 W2 fix: ON CONFLICT DO NOTHING (never saveAndFlush+catch).
        try {
            sessionAnnotationRepository.upsertSkipDuplicate(
                    sessionId,
                    ANNOTATION_TYPE,
                    surfaceType + ":" + picked,
                    ANNOTATION_SOURCE,
                    BigDecimal.ONE,
                    null);
        } catch (DataAccessException e) {
            // Persistence failure must NOT block the user turn — log and
            // proceed with the allocated name. Worst case the next turn
            // re-hashes (same sessionId → same bucket → same result).
            log.warn("CanaryAllocator: upsertSkipDuplicate failed for session={}, surface={}, picked={}: {}",
                    sessionId, surfaceType, picked, e.getMessage());
        }
        return picked;
    }

    /**
     * Parse {@code "<surfaceType>:<identity>"} back into {@code "<identity>"}.
     * Defensive against unexpected formats — returns {@code null} so the
     * caller can fall back to a fresh allocation rather than mounting a bogus
     * identifier.
     */
    static String parseIdentityFromGroupValue(String value, String surfaceType) {
        if (value == null || surfaceType == null) return null;
        String prefix = surfaceType + ":";
        if (!value.startsWith(prefix)) {
            return null;
        }
        String name = value.substring(prefix.length());
        return name.isEmpty() ? null : name;
    }

    /**
     * V2 legacy parser for skill-only callers. Kept as a thin alias for
     * compile-time test references that pre-date Phase 1.3. New code should
     * use {@link #parseIdentityFromGroupValue(String, String)} with the
     * explicit surface type.
     */
    static String parseSkillNameFromGroupValue(String value) {
        return parseIdentityFromGroupValue(value, SURFACE_SKILL);
    }
}
