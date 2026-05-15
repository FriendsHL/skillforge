package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.improve.BehaviorRuleImproverService;
import com.skillforge.server.improve.ImprovementStartResult;
import com.skillforge.server.improve.PromptImproverService;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V4 MULTI-SURFACE-FLYWHEEL Phase 1.3 — unit tests for the behavior_rule
 * dispatch branch of {@link AttributionApprovalService}.
 *
 * <p>Mirrors {@link AttributionApprovalServiceTest}'s prompt-surface coverage:
 * <ol>
 *   <li>approve happy path → surface=behavior_rule →
 *       {@link BehaviorRuleImproverService#startImprovementFromAttribution} called,
 *       stage advances to {@code candidate_ready}, and
 *       {@code candidate_behavior_rule_version_id} is stamped on the event.</li>
 *   <li>BehaviorRuleImprover throws → stage=candidate_failed (catch-block path).</li>
 *   <li>approve with null agentId on behavior_rule surface → IllegalStateException
 *       (caught + recorded as candidate_failed by the runCandidateGeneration catch).</li>
 *   <li>retry happy path → candidate_failed → candidate_ready (matches the V3.1
 *       retry contract for prompt/skill).</li>
 * </ol>
 *
 * <p>Same tx-propagation caveat as {@link AttributionApprovalServiceTest}:
 * {@code @Transactional(REQUIRES_NEW)} is a Spring-context concern and is
 * not exercised at the unit-test layer — the in-memory state-machine writes
 * are what we verify here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttributionApprovalService — behavior_rule dispatch (V4 Phase 1.3)")
class AttributionApprovalServiceBehaviorRuleTest {

    @Mock private OptimizationEventRepository eventRepository;
    @Mock private SkillDraftService skillDraftService;
    @Mock private PromptImproverService promptImproverService;
    @Mock private BehaviorRuleImproverService behaviorRuleImproverService;
    @Mock private AttributionEventBroadcaster broadcaster;

    private AttributionApprovalService service;

    @BeforeEach
    void setUp() {
        service = new AttributionApprovalService(
                eventRepository, skillDraftService, promptImproverService,
                behaviorRuleImproverService, broadcaster);
        // save() echo so we can inspect the in-flight entity.
        org.mockito.Mockito.lenient().when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OptimizationEventEntity pendingBehaviorRuleEvent(long id) {
        OptimizationEventEntity e = new OptimizationEventEntity();
        e.setId(id);
        e.setPatternId(42L);
        e.setAgentId(7L);
        e.setSurfaceType(OptimizationEventEntity.SURFACE_BEHAVIOR_RULE);
        e.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        e.setDescription("curator rationale: foo");
        e.setExpectedImpact("reduce violation by 30%");
        e.setChangeType("add_safety_rule");
        return e;
    }

    @Test
    @DisplayName("approve happy path: surface=behavior_rule → improver called, stage→candidate_ready, "
            + "candidate_behavior_rule_version_id stamped")
    void approve_behaviorRuleSurface_invokesImproverAndStampsCandidateLink() {
        OptimizationEventEntity event = pendingBehaviorRuleEvent(500L);
        when(eventRepository.findById(500L)).thenReturn(Optional.of(event));
        // ImprovementStartResult holds the BR version id in its (misnamed)
        // promptVersionId field — the record is reused across V3 prompt and
        // V4 behavior_rule paths since the shape is identical.
        when(behaviorRuleImproverService.startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(new ImprovementStartResult("7", null,
                        "br-version-uuid-1", "PENDING"));

        OptimizationEventEntity returned = service.approve(500L, /*approverUserId*/ 7L);

        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_READY);
        assertThat(returned.getCandidateBehaviorRuleVersionId()).isEqualTo("br-version-uuid-1");

        verify(behaviorRuleImproverService).startImprovementFromAttribution(
                eq(500L),
                eq("7"),  // agentId stringified
                eq("curator rationale: foo"),
                eq(7L));
        verify(skillDraftService, never()).createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(promptImproverService, never()).startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("approve catch-block: behavior_rule improver throws → stage=candidate_failed, "
            + "candidate_behavior_rule_version_id NOT stamped (audit trail in improver tx)")
    void approve_behaviorRuleImproverThrows_persistsCandidateFailed() {
        OptimizationEventEntity event = pendingBehaviorRuleEvent(501L);
        when(eventRepository.findById(501L)).thenReturn(Optional.of(event));
        when(behaviorRuleImproverService.startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("LLM timeout on behavior_rule fill"));

        OptimizationEventEntity returned = service.approve(501L, 7L);

        // catch-block rewrites stage + prefixes description; the link column is
        // left null because the outer entity's setter wasn't reached.
        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
        assertThat(returned.getCandidateBehaviorRuleVersionId()).isNull();
        assertThat(returned.getDescription())
                .contains("[candidate_failed]")
                .contains("LLM timeout on behavior_rule fill");
    }

    @Test
    @DisplayName("approve: behavior_rule event with null agentId → dispatch throws, "
            + "outer catch converts to candidate_failed")
    void approve_behaviorRuleNullAgentId_recordedAsCandidateFailed() {
        OptimizationEventEntity event = pendingBehaviorRuleEvent(502L);
        event.setAgentId(null);
        when(eventRepository.findById(502L)).thenReturn(Optional.of(event));

        OptimizationEventEntity returned = service.approve(502L, 7L);

        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
        assertThat(returned.getDescription()).contains("null agentId");
        verify(behaviorRuleImproverService, never()).startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("retry happy path: candidate_failed → candidate_generating → candidate_ready "
            + "(behavior_rule retry mirrors V3.1 prompt retry contract)")
    void retry_behaviorRule_resetsToCandidateGenerating_thenReady() {
        OptimizationEventEntity event = pendingBehaviorRuleEvent(503L);
        event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
        event.setDescription("[candidate_failed] earlier LLM down");
        when(eventRepository.findById(503L)).thenReturn(Optional.of(event));
        when(behaviorRuleImproverService.startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(new ImprovementStartResult("7", null,
                        "br-version-uuid-retry", "PENDING"));

        OptimizationEventEntity returned = service.retryCandidateGeneration(503L, 7L);

        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_READY);
        assertThat(returned.getCandidateBehaviorRuleVersionId()).isEqualTo("br-version-uuid-retry");
        verify(behaviorRuleImproverService).startImprovementFromAttribution(
                eq(503L), eq("7"), anyString(), eq(7L));
    }

    @Test
    @DisplayName("approve fires broadcaster on each stage transition (≥ 2 for behavior_rule happy path)")
    void approve_behaviorRule_firesBroadcasterPerTransition() {
        OptimizationEventEntity event = pendingBehaviorRuleEvent(504L);
        when(eventRepository.findById(504L)).thenReturn(Optional.of(event));
        when(behaviorRuleImproverService.startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(new ImprovementStartResult("7", null, "v1", "PENDING"));

        service.approve(504L, 7L);

        ArgumentCaptor<String> prev = ArgumentCaptor.forClass(String.class);
        verify(broadcaster, org.mockito.Mockito.atLeast(2))
                .broadcastStageTransition(any(OptimizationEventEntity.class), prev.capture());
        // At least one of the captured previousStage values must be
        // proposal_approved → candidate_generating, confirming the multi-step
        // happy path actually moved through interim states.
        assertThat(prev.getAllValues()).contains(OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
    }

    @Test
    @DisplayName("approve still rejects unknown surfaces (defensive — APPROVABLE_SURFACES whitelist)")
    void approve_unknownSurface_throws() {
        OptimizationEventEntity event = pendingBehaviorRuleEvent(505L);
        event.setSurfaceType(OptimizationEventEntity.SURFACE_OTHER);
        when(eventRepository.findById(505L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.approve(505L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported surface");
        verify(behaviorRuleImproverService, never()).startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong());
    }
}
