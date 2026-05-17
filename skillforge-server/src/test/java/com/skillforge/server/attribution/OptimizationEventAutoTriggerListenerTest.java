package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.3 (2026-05-16) — unit tests for
 * {@link OptimizationEventAutoTriggerListener}.
 *
 * <p>Replaces the Phase 1.0 baseline test
 * {@code OptimizationEventAutoTriggerListenerCurrentStateTest} (which
 * asserted listener + event classes did NOT exist — locking the pre-fix
 * gap). With Phase 1.3 wiring the listener, that file became obsolete and
 * was deleted; this file is its replacement.
 *
 * <p><b>Phase 1.3 dispatch* methods are placeholder logs</b> per ratify
 * (post-review Concern 6 path (a)): the service stubs in
 * {@code PromptImproverService.runAbTestAgainst} /
 * {@code SkillDraftService.startAbTestFromDraft} throw
 * {@code IllegalStateException} on null {@code evalScenarioIds}, which the
 * attribution path always passes. Calling them from the listener would flood
 * dogfood with false-positive {@code ab_failed} broadcasts on every
 * candidate_ready. Phase 1.4 replaces the placeholder logs with real service
 * calls after wiring the ephemeral-scenario fallback.
 *
 * <p><b>Coverage</b> (6 cases):
 * <ul>
 *   <li>prompt path → log placeholder fires; no service call (no service
 *       fields exist yet); no broadcast (placeholder isn't a failure)</li>
 *   <li>skill path → same</li>
 *   <li>behavior_rule → V5.1 backlog skip log; no broadcast</li>
 *   <li>null UUID sidecar → safe-skip log; no broadcast</li>
 *   <li>defensive: non-candidate_ready transition → no-op</li>
 *   <li>defensive: null eventId → no-op without NPE</li>
 *   <li>defensive: unknown surface → warn log + no-op</li>
 * </ul>
 *
 * <p>The {@code dispatch-throws → broadcastAbFailed} test that was here in
 * the earlier Phase 1.3 sketch is intentionally <b>removed</b>: with
 * dispatch* methods being pure log calls, there's no realistic throw path
 * to exercise. The outer {@code try / catch + broadcastAbFailed} in
 * {@link OptimizationEventAutoTriggerListener#onStageCandidateReady} stays
 * (defensive code for Phase 1.4); Phase 1.4 will add the
 * {@code service throws → broadcastAbFailed} regression test when the real
 * service calls land and can fail meaningfully.
 *
 * <p>Kill-switch ({@code skillforge.flywheel.auto-trigger-ab-on-candidate-ready=false})
 * is realised via {@code @ConditionalOnProperty(matchIfMissing = true)} on
 * the listener bean — when the flag is false, Spring skips bean creation
 * entirely, so no per-call gate exists to unit-test. Spring-context
 * integration covered by full {@code @SpringBootTest} suite.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OptimizationEventAutoTriggerListener (Phase 1.3 — candidate_ready auto-trigger)")
class OptimizationEventAutoTriggerListenerTest {

    @Mock private PromptImproverService promptImproverService;
    @Mock private SkillDraftService skillDraftService;
    @Mock private AttributionEventBroadcaster broadcaster;

    private OptimizationEventAutoTriggerListener listener;

    @BeforeEach
    void setUp() {
        listener = new OptimizationEventAutoTriggerListener(
                promptImproverService, skillDraftService, broadcaster);
    }

    private OptimizationEventStageChangeEvent candidateReadyEvent(String surface,
                                                                   String promptUuid,
                                                                   String skillDraftUuid,
                                                                   String behaviorRuleVersionId) {
        return new OptimizationEventStageChangeEvent(
                /*eventId*/ 99L,
                /*fromStage*/ OptimizationEventEntity.STAGE_CANDIDATE_GENERATING,
                /*toStage*/ OptimizationEventEntity.STAGE_CANDIDATE_READY,
                surface,
                /*agentId*/ 7L,
                /*patternId*/ 42L,
                promptUuid, skillDraftUuid, behaviorRuleVersionId);
    }

    @Test
    @DisplayName("prompt surface → real call to PromptImproverService.runAbTestAgainst "
            + "(agentId String, baseline=null, candidate=UUID, scenarios=null)")
    void onCandidateReady_promptSurface_callsRunAbTestAgainst() {
        String promptUuid = "phase14-prompt-uuid-1234-5678-90abcdef0001";
        when(promptImproverService.runAbTestAgainst(
                anyString(), isNull(), anyString(), isNull()))
                .thenReturn("ab-run-id-phase14-prompt");

        listener.onStageCandidateReady(candidateReadyEvent(
                OptimizationEventEntity.SURFACE_PROMPT, promptUuid, null, null));

        verify(promptImproverService).runAbTestAgainst(
                eq("7"),
                isNull(),
                eq(promptUuid),
                isNull());
        verify(skillDraftService, never()).startAbTestFromDraft(anyString(), any());
        verifyNoInteractions(broadcaster);  // happy path: no ab_failed mirror
    }

    @Test
    @DisplayName("skill surface → real call to SkillDraftService.startAbTestFromDraft "
            + "(draftUuid, scenarios=null)")
    void onCandidateReady_skillSurface_callsStartAbTestFromDraft() {
        String draftUuid = "phase14-skill-draft-uuid-1234-5678-90abcdef0002";
        when(skillDraftService.startAbTestFromDraft(anyString(), isNull()))
                .thenReturn("ab-run-id-phase14-skill");

        listener.onStageCandidateReady(candidateReadyEvent(
                OptimizationEventEntity.SURFACE_SKILL, null, draftUuid, null));

        verify(skillDraftService).startAbTestFromDraft(eq(draftUuid), isNull());
        verify(promptImproverService, never()).runAbTestAgainst(
                anyString(), any(), anyString(), any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("behavior_rule surface → V5.1 backlog skip log; no broadcast")
    void onCandidateReady_behaviorRuleSurface_skipsCleanly() {
        assertThatCode(() -> listener.onStageCandidateReady(candidateReadyEvent(
                OptimizationEventEntity.SURFACE_BEHAVIOR_RULE,
                null, null, "br-version-uuid-phase13")))
                .doesNotThrowAnyException();

        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("prompt surface with null candidatePromptVersionUuid → safe skip "
            + "(log warn; no broadcast — this is a Phase 1.2 sidecar diagnostic, not an A/B failure)")
    void onCandidateReady_promptSurface_nullSidecar_safeSkip() {
        assertThatCode(() -> listener.onStageCandidateReady(candidateReadyEvent(
                OptimizationEventEntity.SURFACE_PROMPT,
                /*candidatePromptVersionUuid*/ null, null, null)))
                .doesNotThrowAnyException();

        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("defensive: non-candidate_ready transition (e.g. proposal_approved) → no dispatch")
    void onCandidateReady_nonCandidateReadyStage_noOp() {
        OptimizationEventStageChangeEvent event = new OptimizationEventStageChangeEvent(
                99L,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                OptimizationEventEntity.STAGE_PROPOSAL_APPROVED,
                OptimizationEventEntity.SURFACE_PROMPT,
                7L, 42L, "irrelevant-uuid", null, null);

        listener.onStageCandidateReady(event);

        // Listener short-circuits on the toStage filter — no dispatch, no broadcast.
        verify(broadcaster, never()).broadcastStageTransition(
                anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("defensive: null eventId → skip without NPE")
    void onCandidateReady_nullEventId_skipsWithoutNpe() {
        OptimizationEventStageChangeEvent event = new OptimizationEventStageChangeEvent(
                /*eventId*/ null,
                "x", OptimizationEventEntity.STAGE_CANDIDATE_READY,
                OptimizationEventEntity.SURFACE_PROMPT,
                7L, 42L, "uuid", null, null);

        assertThatCode(() -> listener.onStageCandidateReady(event))
                .doesNotThrowAnyException();

        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("defensive: unknown surface (e.g. typo \"prompts\") → warn log, no broadcast")
    void onCandidateReady_unknownSurface_noOp() {
        listener.onStageCandidateReady(candidateReadyEvent(
                "prompts" /* typo */, "uuid", null, null));

        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("service throws → catch + broadcaster.broadcastStageTransition with "
            + "stage=ab_failed (dashboard shows failure; operator can retry)")
    void onCandidateReady_serviceThrows_broadcastsAbFailed() {
        String promptUuid = "phase14-prompt-uuid-fails-here";
        when(promptImproverService.runAbTestAgainst(
                anyString(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("simulated runAbTestAgainst failure"));

        // Listener swallows the exception inside its catch block (@Async +
        // AFTER_COMMIT means a thrown exception has nowhere useful to propagate);
        // ab_failed mirror is the user-visible signal.
        listener.onStageCandidateReady(candidateReadyEvent(
                OptimizationEventEntity.SURFACE_PROMPT, promptUuid, null, null));

        verify(broadcaster).broadcastStageTransition(
                eq(99L),
                eq(42L),
                eq(OptimizationEventEntity.STAGE_AB_FAILED),
                eq(OptimizationEventEntity.STAGE_CANDIDATE_READY),
                any(Instant.class));
    }
}
