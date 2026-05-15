package com.skillforge.server.improve;

import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.improve.event.BehaviorRulePromotedEvent;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — promote a behavior_rule candidate
 * to active. Mirrors {@code PromptPromotionService.evaluateAndPromote}'s
 * atomic-promote section but without the V3 gates (delta threshold / 24h
 * cooldown / decline counter) — those gates belong to {@code
 * AbstractAbEvalRunner.shouldPromote} (Phase 1.2) and are surface-specific.
 *
 * <p>This service does the V82 invariant-safe state transition:
 * <ol>
 *   <li>Retire the prior active row ({@code status='retired'}) so the partial
 *       UNIQUE {@code uq_brv_one_active} can accept the new active row.</li>
 *   <li>Promote candidate ({@code status='active'} + {@code promotedAt=now}).</li>
 *   <li>Publish {@link BehaviorRulePromotedEvent} (best-effort — listener
 *       failure must not roll back the promotion).</li>
 * </ol>
 *
 * <p>NB: the PG partial UNIQUE catches the "two actives at once" race even if
 * caller order is wrong; we still retire-first to keep the failure mode loud
 * (caller sees a clear violation on save() rather than silent overlap).
 */
@Service
public class BehaviorRulePromotionService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorRulePromotionService.class);

    private final BehaviorRuleVersionRepository versionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BehaviorRulePromotionService(BehaviorRuleVersionRepository versionRepository,
                                         ApplicationEventPublisher eventPublisher) {
        this.versionRepository = versionRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Atomic promote of a candidate version. Caller is responsible for the
     * gate decision (delta threshold / canary outcome / operator override) —
     * this method only performs the state transition once the gate has said
     * "yes".
     *
     * @throws IllegalArgumentException if candidate is null or already active
     *                                  / retired (no-op guard)
     */
    @Transactional
    public void promote(BehaviorRuleVersionEntity candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        if (BehaviorRuleVersionEntity.STATUS_ACTIVE.equals(candidate.getStatus())) {
            throw new IllegalArgumentException("Candidate is already active: " + candidate.getId());
        }
        if (BehaviorRuleVersionEntity.STATUS_RETIRED.equals(candidate.getStatus())) {
            throw new IllegalArgumentException("Candidate has been retired: " + candidate.getId());
        }

        Instant now = Instant.now();
        String agentId = candidate.getAgentId();

        // STEP 1: retire current active (if any) BEFORE flipping candidate.
        // The V82 partial UNIQUE uq_brv_one_active is on (agent_id) WHERE
        // status='active'; without retire-first, both rows would briefly
        // satisfy the predicate within the same tx and Hibernate's flush
        // order could fire the constraint. Saving the old row first +
        // forcing a flush would also work; doing it in this order is
        // clearer and survives JPA flush-ordering changes.
        versionRepository.findByAgentIdAndStatus(agentId, BehaviorRuleVersionEntity.STATUS_ACTIVE)
                .ifPresent(old -> {
                    old.setStatus(BehaviorRuleVersionEntity.STATUS_RETIRED);
                    versionRepository.saveAndFlush(old);
                });

        // STEP 2: promote candidate.
        candidate.setStatus(BehaviorRuleVersionEntity.STATUS_ACTIVE);
        candidate.setPromotedAt(now);
        versionRepository.save(candidate);

        log.info("Behavior rule promoted: agentId={} versionId={} versionNumber={}",
                agentId, candidate.getId(), candidate.getVersionNumber());

        // STEP 3: publish event. Wrapped in try/catch — a downstream listener
        // throwing must not undo the promote (the DB transition is the
        // contract; the broadcast is a notification, not a guard).
        try {
            eventPublisher.publishEvent(new BehaviorRulePromotedEvent(
                    agentId, candidate.getId(), candidate.getVersionNumber(), null));
        } catch (Exception e) {
            log.warn("Failed to publish BehaviorRulePromotedEvent for versionId={}: {}",
                    candidate.getId(), e.getMessage());
        }
    }

    /**
     * Roll back the given active version. Restores no specific prior version
     * automatically (Phase 1.1 keeps rollback simple — the operator picks the
     * target via a future UI; for now we just mark current as retired so the
     * registry falls back to the startup baseline on next lookup).
     */
    @Transactional
    public void rollback(BehaviorRuleVersionEntity active) {
        if (active == null) {
            throw new IllegalArgumentException("active must not be null");
        }
        if (!BehaviorRuleVersionEntity.STATUS_ACTIVE.equals(active.getStatus())) {
            throw new IllegalArgumentException(
                    "rollback target is not active: id=" + active.getId() + " status=" + active.getStatus());
        }
        active.setStatus(BehaviorRuleVersionEntity.STATUS_RETIRED);
        versionRepository.save(active);
        log.info("Behavior rule rolled back: agentId={} versionId={}",
                active.getAgentId(), active.getId());
    }
}
