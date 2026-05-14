package com.skillforge.server.attribution;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V3 ATTRIBUTION-AGENT — Phase 1.0 red placeholder.
 *
 * <p>Pins the two highest-stakes branches of the future
 * {@code AttributionDispatcherService.dispatchPendingPatterns()} method that
 * the tech-design pegs as ratify-locked policy:
 *
 * <ul>
 *   <li><b>24h cooldown</b> — same pattern must not produce a second
 *       attribution-curator run within 24 hours of the previous
 *       {@code t_optimization_event} row (per
 *       {@code docs/requirements/active/ATTRIBUTION-AGENT/prd.md} ratify #2
 *       and {@code tech-design.md} §6 cooldown_expires_at row).</li>
 *   <li><b>Surface allow-list</b> — V3 dispatch only fires for
 *       {@code suspect_surface ∈ {skill, prompt}} (per ratify #6;
 *       {@code behavior_rule} = V4, {@code other / unclear} not actionable).</li>
 * </ul>
 *
 * <p>References:
 * <ul>
 *   <li>tech-design.md §1 (dispatcher flow + 24h cooldown gate)</li>
 *   <li>tech-design.md §5 (Phase 1.0 — 5-item grep checklist + red test placeholder)</li>
 *   <li>prd.md §用户流程 step 2 (cooldown + member≥3 + suspect_surface filter)</li>
 * </ul>
 *
 * <p>This file is intentionally {@link Disabled} until Phase 1.2 ships
 * {@code AttributionDispatcherService} + {@code OptimizationEventEntity} +
 * {@code SessionPatternRepository.findUnattributedPatterns(...)}. Once those
 * exist, Phase 1.2 BE-Dev removes the {@code @Disabled} annotation, wires the
 * real Mockito setup described in each test's TODO, and flips the bodies green.
 *
 * <p>Per V3 Phase 1.0 task contract: <b>no production code is written in this
 * phase</b> — Entity / Service / Repository / Tool / Bootstrap / migration all
 * land in Phase 1.1+.
 */
@Disabled("V3 Phase 1.0 red test — AttributionDispatcherService not yet written; "
        + "will turn green at Phase 1.2")
class AttributionDispatcherRedTest {

    @Test
    @DisplayName("dispatcher_skipsPattern_whenInCooldown — pattern with unexpired "
            + "cooldown is not re-dispatched")
    void dispatcher_skipsPattern_whenInCooldown() {
        // TODO[Phase 1.2] Turn green once AttributionDispatcherService exists.
        //
        // Given:
        //   - 1 row in t_session_pattern (id=42, suspect_surface='skill',
        //     member_count=5) — passes basic eligibility.
        //   - 1 prior row in t_optimization_event (pattern_id=42,
        //     stage='proposal_pending', cooldown_expires_at = NOW() + INTERVAL '1h')
        //     — the previous attribution run is still inside its 24h window.
        //
        // When:
        //   - attributionDispatcherService.dispatchPendingPatterns() runs.
        //
        // Then:
        //   - subAgentRegistry.dispatch(...) is NEVER called for pattern_id=42.
        //   - No new t_optimization_event row is appended (pattern still has
        //     exactly 1 prior event).
        //   - log.info contains "skip cooldown patternId=42" (sanity check;
        //     not a hard assertion, just diagnostic verification).
        //
        // Implementation sketch (Phase 1.2):
        //   AttributionDispatcherService svc = new AttributionDispatcherService(
        //       sessionPatternRepo, optimizationEventRepo, subAgentRegistry, clock);
        //   when(sessionPatternRepo.findUnattributedPatterns(any()))
        //       .thenReturn(List.of(patternId42));
        //   when(optimizationEventRepo.findLatestByPatternId(42L))
        //       .thenReturn(Optional.of(eventWithCooldownExpiringIn1h));
        //   svc.dispatchPendingPatterns();
        //   verify(subAgentRegistry, never()).dispatch(any(), eq("attribution-curator"), any());
    }

    @Test
    @DisplayName("dispatcher_skipsPattern_whenSurfaceNotInAllowList — suspect_surface "
            + "outside {skill, prompt} is filtered out per V3 ratify #6")
    void dispatcher_skipsPattern_whenSurfaceNotInAllowList() {
        // TODO[Phase 1.2] Turn green once AttributionDispatcherService exists.
        //
        // Given:
        //   - 1 row in t_session_pattern (id=99, suspect_surface='unclear',
        //     member_count=5) — V1 annotator couldn't pin the surface, so V3
        //     scope (ratify #6) rules it out.
        //   - No prior t_optimization_event row (cooldown gate not relevant).
        //
        // When:
        //   - attributionDispatcherService.dispatchPendingPatterns() runs.
        //
        // Then:
        //   - subAgentRegistry.dispatch(...) is NEVER called for pattern_id=99.
        //   - No t_optimization_event row written.
        //   - The query path should ideally filter at the repository
        //     (suspect_surface IN ('skill', 'prompt') in the WHERE clause), so
        //     the assertion may be that the candidate list is empty rather
        //     than that the dispatcher post-filters. Either implementation is
        //     acceptable as long as the observed effect (no dispatch) holds.
        //
        // Cross-check cases the same test class should add at Phase 1.2:
        //   - suspect_surface='behavior_rule' → skipped (V4 scope, ratify #6)
        //   - suspect_surface='other' → skipped (not actionable)
        //   - suspect_surface='skill' + member_count < 3 → skipped (§6 risk row)
        //   - suspect_surface='prompt' + no prior event → dispatched (positive
        //     path, verifies cooldown gate doesn't false-positive on first run)
    }
}
