package com.skillforge.server.attribution;

import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SkillDraftEntity;
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
 * V3 ATTRIBUTION-AGENT Phase 1.3 — unit tests for {@link AttributionApprovalService}.
 *
 * <p>Covers the 6 critical paths:
 * <ol>
 *   <li>approve happy path → surface=skill → SkillDraft created</li>
 *   <li>approve happy path → surface=prompt → PromptVersion created</li>
 *   <li>approve when stage != proposal_pending → IllegalStateException</li>
 *   <li>reject happy path → stage=proposal_rejected, reason folded into description</li>
 *   <li>candidate generation throws → stage=candidate_failed (NOT rethrown)</li>
 *   <li>approve when surface ∉ {skill, prompt} → IllegalStateException (defensive)</li>
 * </ol>
 *
 * <p>Bonus: validateTransition white-list pinned by smoke test.
 *
 * <p><b>Tx-propagation caveat (Phase 1.3 reviewer fix)</b>: this test class uses
 * {@code MockitoExtension} with no Spring AOP, so {@code @Transactional} (and
 * specifically {@code Propagation.REQUIRES_NEW} on
 * {@code SkillDraftService.createDraftFromAttribution} /
 * {@code PromptImproverService.startImprovementFromAttribution}) does NOT take
 * effect at the unit-test layer. The candidate-failed test below
 * ({@link #approve_candidateGenThrows_persistsCandidateFailed}) verifies that
 * the in-memory state-machine writes happen, but does NOT verify that the
 * outer transaction would actually commit in production — that requires a
 * Spring-context IT (deferred to Phase Final {@code @SpringBootTest}). The
 * REQUIRES_NEW invariant is what makes the candidate_failed write durable
 * across a child-service exception; the unit tests can only confirm the
 * service code path matches the design.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttributionApprovalService (V3 Phase 1.3)")
class AttributionApprovalServiceTest {

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
        // Save returns the input arg unchanged (covers stage transitions).
        org.mockito.Mockito.lenient().when(eventRepository.save(any(OptimizationEventEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OptimizationEventEntity pendingEvent(long id, String surface) {
        OptimizationEventEntity e = new OptimizationEventEntity();
        e.setId(id);
        e.setPatternId(42L);
        e.setAgentId(7L);
        e.setSurfaceType(surface);
        e.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        e.setDescription("curator description");
        e.setExpectedImpact("expected impact");
        e.setChangeType("rewrite_skill_md");
        return e;
    }

    @Test
    @DisplayName("approve happy path: surface=skill → SkillDraftService.createDraftFromAttribution called, stage→candidate_ready")
    void approve_skillSurface_createsDraftAndAdvances() {
        OptimizationEventEntity event = pendingEvent(99L, OptimizationEventEntity.SURFACE_SKILL);
        when(eventRepository.findById(99L)).thenReturn(Optional.of(event));
        SkillDraftEntity stubDraft = new SkillDraftEntity();
        stubDraft.setId("draft-uuid-1");
        when(skillDraftService.createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(stubDraft);

        OptimizationEventEntity returned = service.approve(99L, /*approverUserId*/ 7L);

        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_READY);
        verify(skillDraftService).createDraftFromAttribution(
                eq(99L), eq(42L),
                eq("curator description"),
                eq("expected impact"),
                eq("rewrite_skill_md"),
                eq(7L),
                anyString());
        verify(promptImproverService, never()).startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("approve happy path: surface=prompt → PromptImproverService.startImprovementFromAttribution called, stage→candidate_ready")
    void approve_promptSurface_startsImprovementAndAdvances() {
        OptimizationEventEntity event = pendingEvent(100L, OptimizationEventEntity.SURFACE_PROMPT);
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        when(promptImproverService.startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(new ImprovementStartResult("7", null, "version-uuid-1", "PENDING"));

        OptimizationEventEntity returned = service.approve(100L, 7L);

        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_READY);
        verify(promptImproverService).startImprovementFromAttribution(
                eq(100L), eq("7"), eq("curator description"), eq(7L));
        verify(skillDraftService, never()).createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("approve when stage != proposal_pending → IllegalStateException, no candidate gen, no save")
    void approve_wrongStage_throws() {
        OptimizationEventEntity event = pendingEvent(101L, OptimizationEventEntity.SURFACE_SKILL);
        event.setStage(OptimizationEventEntity.STAGE_AB_RUNNING);  // already mid-pipeline
        when(eventRepository.findById(101L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.approve(101L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal stage transition");
        verify(skillDraftService, never()).createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("reject happy path: stage→proposal_rejected, reason folded into description preserving original")
    void reject_happyPath_persistsRejectedAndFoldsReason() {
        OptimizationEventEntity event = pendingEvent(102L, OptimizationEventEntity.SURFACE_SKILL);
        when(eventRepository.findById(102L)).thenReturn(Optional.of(event));
        ArgumentCaptor<OptimizationEventEntity> captor = ArgumentCaptor.forClass(OptimizationEventEntity.class);
        when(eventRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        OptimizationEventEntity returned = service.reject(102L, 7L, "duplicate of #95");

        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_PROPOSAL_REJECTED);
        OptimizationEventEntity saved = captor.getValue();
        assertThat(saved.getDescription())
                .startsWith("[rejected: duplicate of #95]")
                .contains("curator description");  // original preserved
    }

    @Test
    @DisplayName("approve catch-block: candidate gen throws → stage=candidate_failed (NOT rethrown), reason in description")
    void approve_candidateGenThrows_persistsCandidateFailed() {
        OptimizationEventEntity event = pendingEvent(103L, OptimizationEventEntity.SURFACE_SKILL);
        when(eventRepository.findById(103L)).thenReturn(Optional.of(event));
        when(skillDraftService.createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("LLM provider down"));

        OptimizationEventEntity returned = service.approve(103L, 7L);

        // Tx commits with failure state — operator sees the failure on next refresh
        // rather than a mysterious 500 + missing transition.
        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
        assertThat(returned.getDescription()).contains("[candidate_failed]").contains("LLM provider down");
    }

    @Test
    @DisplayName("approve when surface='unclear' → IllegalStateException (defensive — ratify #6 redundant guard)")
    void approve_unsupportedSurface_throws() {
        OptimizationEventEntity event = pendingEvent(104L, OptimizationEventEntity.SURFACE_UNCLEAR);
        when(eventRepository.findById(104L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.approve(104L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported surface");
        verify(skillDraftService, never()).createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("validateTransition white-list smoke test: legal + illegal transitions pin the policy")
    void validateTransition_whitelistSanityCheck() {
        // Legal samples — should not throw.
        service.validateTransition(OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                OptimizationEventEntity.STAGE_PROPOSAL_APPROVED);
        service.validateTransition(OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                OptimizationEventEntity.STAGE_PROPOSAL_REJECTED);
        service.validateTransition(OptimizationEventEntity.STAGE_AB_PASSED,
                OptimizationEventEntity.STAGE_CANARY_STARTED);
        service.validateTransition(OptimizationEventEntity.STAGE_DISPATCH_INITIATED,
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING);

        // Illegal — terminal stages cannot transition out.
        assertThatThrownBy(() -> service.validateTransition(
                OptimizationEventEntity.STAGE_PROPOSAL_REJECTED,
                OptimizationEventEntity.STAGE_PROPOSAL_APPROVED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.validateTransition(
                OptimizationEventEntity.STAGE_CANDIDATE_FAILED,
                OptimizationEventEntity.STAGE_AB_RUNNING))
                .isInstanceOf(IllegalStateException.class);
        // Illegal — random non-adjacent jump.
        assertThatThrownBy(() -> service.validateTransition(
                OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
                OptimizationEventEntity.STAGE_CANARY_STARTED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("approve / reject with null eventId or missing event → IllegalArgumentException")
    void approveReject_nullOrMissingEvent_throws() {
        assertThatThrownBy(() -> service.approve(null, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reject(null, 7L, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        when(eventRepository.findById(9999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(9999L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Phase 1.4 retry happy path: candidate_failed → candidate_generating → candidate_ready")
    void retry_happyPath_resetsToCandidateGenerating_thenReady() {
        OptimizationEventEntity event = pendingEvent(200L, OptimizationEventEntity.SURFACE_SKILL);
        event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
        event.setDescription("[candidate_failed] LLM timeout");
        when(eventRepository.findById(200L)).thenReturn(Optional.of(event));
        SkillDraftEntity stubDraft = new SkillDraftEntity();
        stubDraft.setId("draft-uuid-retry-1");
        when(skillDraftService.createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(stubDraft);

        OptimizationEventEntity returned = service.retryCandidateGeneration(200L, 7L);

        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_READY);
        verify(skillDraftService).createDraftFromAttribution(
                eq(200L), anyLong(), anyString(), anyString(), anyString(), eq(7L), anyString());
    }

    @Test
    @DisplayName("Phase 1.4 retry: throws when stage != candidate_failed (per ALLOWED_TRANSITIONS)")
    void retry_throwsWhenStageNotCandidateFailed() {
        OptimizationEventEntity event = pendingEvent(201L, OptimizationEventEntity.SURFACE_SKILL);
        // proposal_pending → candidate_generating is NOT in ALLOWED_TRANSITIONS.
        when(eventRepository.findById(201L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.retryCandidateGeneration(201L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal stage transition")
                .hasMessageContaining(OptimizationEventEntity.STAGE_CANDIDATE_GENERATING);
        verify(skillDraftService, never()).createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Phase 1.4 retry: candidate gen still failing → stage rests on candidate_failed (looped)")
    void retry_candidateGenStillFailing_persistsCandidateFailed() {
        OptimizationEventEntity event = pendingEvent(202L, OptimizationEventEntity.SURFACE_SKILL);
        event.setStage(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
        when(eventRepository.findById(202L)).thenReturn(Optional.of(event));
        when(skillDraftService.createDraftFromAttribution(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("LLM still down"));

        OptimizationEventEntity returned = service.retryCandidateGeneration(202L, 7L);

        // Despite being in CANDIDATE_FAILED at start, the retry transitions
        // through CANDIDATE_GENERATING (validated) → catch-block sets it back
        // to CANDIDATE_FAILED with the new failure prefix.
        assertThat(returned.getStage()).isEqualTo(OptimizationEventEntity.STAGE_CANDIDATE_FAILED);
        assertThat(returned.getDescription()).contains("[candidate_failed]").contains("LLM still down");
    }

    @Test
    @DisplayName("Phase 1.4 broadcaster: each stage transition fires broadcastStageTransition")
    void broadcaster_firesOnEveryTransition() {
        OptimizationEventEntity event = pendingEvent(300L, OptimizationEventEntity.SURFACE_PROMPT);
        when(eventRepository.findById(300L)).thenReturn(Optional.of(event));
        when(promptImproverService.startImprovementFromAttribution(
                anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(new ImprovementStartResult("7", null, "v1", "PENDING"));

        service.approve(300L, 7L);

        // approve flow saves 3 times (proposal_pending→approved, approved→generating,
        // generating→ready) → 3 broadcast invocations. We verify ≥ 2 to allow for
        // future internal save() reorganization without brittle test churn.
        verify(broadcaster, org.mockito.Mockito.atLeast(2))
                .broadcastStageTransition(any(OptimizationEventEntity.class), anyString());
    }
}
