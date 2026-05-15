package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.improve.event.BehaviorRulePromotedEvent;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * V4 MULTI-SURFACE-FLYWHEEL Phase 1.3 — unit tests for the
 * {@link com.skillforge.server.improve.event.BehaviorRulePromotedEvent}
 * listener added to {@link AttributionStageListener}. Mirrors the prompt /
 * skill listener coverage in {@link AttributionStageListenerTest}.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Happy: BR promoted event matches an event in {@code ab_passed} →
 *       stage→{@code promoted} + broadcast fires.</li>
 *   <li>Happy: BR promoted event matches an event in {@code candidate_ready}
 *       (operator skipped A/B, promoted directly) → stage→{@code promoted}.</li>
 *   <li>No-op: event in unexpected stage (e.g. {@code ab_running}) → skip mirror,
 *       no save, no broadcast.</li>
 *   <li>No-op: blank / null versionId → skip entirely (defensive).</li>
 *   <li>No-op: no matching event row → skip silently (non-attribution-originated
 *       behavior_rule promote — operator hand-edited / direct dashboard).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttributionStageListener — BehaviorRulePromotedEvent (V4 Phase 1.3)")
class AttributionStageListenerBehaviorRuleTest {

    @Mock private OptimizationEventRepository eventRepository;
    @Mock private AttributionEventBroadcaster broadcaster;

    private AttributionStageListener listener;

    @BeforeEach
    void setUp() {
        listener = new AttributionStageListener(eventRepository, broadcaster);
    }

    private OptimizationEventEntity behaviorRuleEventInStage(Long id, String versionId, String stage) {
        OptimizationEventEntity e = new OptimizationEventEntity();
        e.setId(id);
        e.setSurfaceType(OptimizationEventEntity.SURFACE_BEHAVIOR_RULE);
        e.setStage(stage);
        e.setCandidateBehaviorRuleVersionId(versionId);
        return e;
    }

    @Test
    @DisplayName("BR promoted from ab_passed → stage=promoted, broadcaster fires with previousStage=ab_passed")
    void brPromoted_fromAbPassed_writesPromoted() {
        OptimizationEventEntity matched = behaviorRuleEventInStage(
                601L, "br-uuid-1", OptimizationEventEntity.STAGE_AB_PASSED);
        when(eventRepository.findByCandidateBehaviorRuleVersionId("br-uuid-1"))
                .thenReturn(List.of(matched));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onBehaviorRulePromoted(new BehaviorRulePromotedEvent(
                /*agentId*/ "7", /*versionId*/ "br-uuid-1",
                /*versionNumber*/ 3, /*userId*/ 99L));

        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROMOTED);
        ArgumentCaptor<String> prevStage = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).broadcastStageTransition(any(), prevStage.capture());
        assertThat(prevStage.getValue()).isEqualTo(OptimizationEventEntity.STAGE_AB_PASSED);
    }

    @Test
    @DisplayName("BR promoted from candidate_ready (operator skipped A/B) → stage=promoted")
    void brPromoted_fromCandidateReady_writesPromoted() {
        OptimizationEventEntity matched = behaviorRuleEventInStage(
                602L, "br-uuid-2", OptimizationEventEntity.STAGE_CANDIDATE_READY);
        when(eventRepository.findByCandidateBehaviorRuleVersionId("br-uuid-2"))
                .thenReturn(List.of(matched));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onBehaviorRulePromoted(new BehaviorRulePromotedEvent(
                "7", "br-uuid-2", 1, 99L));

        verify(eventRepository).save(any());
        assertThat(matched.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROMOTED);
        verify(broadcaster).broadcastStageTransition(any(),
                org.mockito.ArgumentMatchers.eq(OptimizationEventEntity.STAGE_CANDIDATE_READY));
    }

    @Test
    @DisplayName("BR promoted while event is in ab_running → skip mirror, no save (defensive)")
    void brPromoted_unexpectedStage_skipsMirror() {
        OptimizationEventEntity matched = behaviorRuleEventInStage(
                603L, "br-uuid-3", OptimizationEventEntity.STAGE_AB_RUNNING);
        when(eventRepository.findByCandidateBehaviorRuleVersionId("br-uuid-3"))
                .thenReturn(List.of(matched));

        listener.onBehaviorRulePromoted(new BehaviorRulePromotedEvent("7", "br-uuid-3", 1, 99L));

        // Defensive: don't advance from ab_running directly to promoted (skip the ab_passed gate)
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(broadcaster);
        assertThat(matched.getStage()).isEqualTo(OptimizationEventEntity.STAGE_AB_RUNNING);
    }

    @Test
    @DisplayName("BR promoted with blank versionId → early skip (no repo lookup)")
    void brPromoted_blankVersionId_skipsImmediately() {
        listener.onBehaviorRulePromoted(new BehaviorRulePromotedEvent("7", "  ", 1, 99L));
        listener.onBehaviorRulePromoted(new BehaviorRulePromotedEvent("7", null, 1, 99L));

        verify(eventRepository, never()).findByCandidateBehaviorRuleVersionId(anyString());
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("BR promoted with no matching event → silent skip (non-attribution promote — operator manual)")
    void brPromoted_noMatchingEvent_silentSkip() {
        when(eventRepository.findByCandidateBehaviorRuleVersionId("br-uuid-unknown"))
                .thenReturn(List.of());

        listener.onBehaviorRulePromoted(new BehaviorRulePromotedEvent("7", "br-uuid-unknown", 1, 99L));

        verify(eventRepository, never()).save(any());
        verifyNoInteractions(broadcaster);
    }
}
