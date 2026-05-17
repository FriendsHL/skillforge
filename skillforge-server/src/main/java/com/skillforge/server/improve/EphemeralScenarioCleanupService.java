package com.skillforge.server.improve;

import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.4d (2026-05-16) — sibling helper that deletes
 * ephemeral {@code EvalScenarioEntity} rows produced by
 * {@code PromptImproverService.runAbTestAgainst} / {@code SkillDraftService
 * .startAbTestFromDraft} fallback path (Ratify #7-E).
 *
 * <p><b>Why a separate bean (W5 fix)</b>: cleanup runs inside an async
 * {@code coordinatorExecutor.submit(...)} {@code finally} block, where the
 * publisher transaction has long since committed. A direct
 * {@code @Transactional} on a method in the same {@code PromptImproverService}
 * class would not take effect because Spring's AOP proxy doesn't intercept
 * self-invocation. Calling through a separately-injected bean forces the
 * proxy boundary, which is what makes {@code REQUIRES_NEW} actually open a
 * fresh transaction so {@code deleteAllById} commits (instead of silently
 * no-op'ing under no active tx, the reviewer-W5 footgun).
 *
 * <p>Sibling bean also keeps the cleanup logic discoverable for the
 * symmetric {@code SkillDraftService.startAbTestFromDraft} path (Phase 1.4d
 * sub-task 3 uses the same helper).
 */
@Service
public class EphemeralScenarioCleanupService {

    private static final Logger log = LoggerFactory.getLogger(EphemeralScenarioCleanupService.class);

    private final EvalScenarioDraftRepository scenarioRepository;

    public EphemeralScenarioCleanupService(EvalScenarioDraftRepository scenarioRepository) {
        this.scenarioRepository = scenarioRepository;
    }

    /**
     * Delete ephemeral scenarios in their own committed transaction. Safe to
     * call with {@code null} / empty list — both are treated as no-op so
     * callers don't need to guard.
     *
     * <p>Wrapped in try/catch so a failing cleanup never propagates back into
     * the calling async runnable's {@code finally} block (which would mask
     * the original A/B failure cause). Failed cleanups log at WARN — Phase
     * 1.6 dogfood will catch any accumulation on the
     * {@code t_eval_scenario} status='ephemeral' filter.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupEphemerals(List<String> ephemeralIds) {
        if (ephemeralIds == null || ephemeralIds.isEmpty()) {
            return;
        }
        try {
            scenarioRepository.deleteAllById(ephemeralIds);
            log.info("[EphemeralCleanup] deleted {} ephemeral EvalScenarios: {}",
                    ephemeralIds.size(), ephemeralIds);
        } catch (RuntimeException e) {
            log.warn("[EphemeralCleanup] failed to delete ephemeral EvalScenarios {}: {} "
                            + "— Phase 1.6 dogfood should query t_eval_scenario where status='ephemeral' "
                            + "and stale.", ephemeralIds, e.getMessage());
        }
    }
}
