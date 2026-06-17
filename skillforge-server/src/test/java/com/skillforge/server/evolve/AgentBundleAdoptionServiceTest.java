package com.skillforge.server.evolve;

import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.evolve.AgentBundleAdoptionService.AdoptResult;
import com.skillforge.server.evolve.dto.CandidateBundle;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.improve.PromptPromotionService;
import com.skillforge.server.improve.PromotionResult;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.improve.SkillNameConflictException;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — unit tests for {@link AgentBundleAdoptionService}:
 * three-surface orchestration with per-surface isolation (a failure on one
 * surface does not block another), null surfaces skipped, ownership fail-fast.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentBundleAdoptionService")
class AgentBundleAdoptionServiceTest {

    @Mock private PromptPromotionService promptPromotionService;
    @Mock private BehaviorRulePromotionService behaviorRulePromotionService;
    @Mock private SkillDraftService skillDraftService;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private BehaviorRuleVersionRepository behaviorRuleVersionRepository;
    @Mock private SkillDraftRepository skillDraftRepository;

    private AgentBundleAdoptionService service;

    private static final String AGENT_ID = "7";

    @BeforeEach
    void setUp() {
        service = new AgentBundleAdoptionService(
                promptPromotionService, behaviorRulePromotionService, skillDraftService,
                promptVersionRepository, behaviorRuleVersionRepository, skillDraftRepository);
    }

    private PromptVersionEntity promptVersion(String id, String agentId) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId(id);
        v.setAgentId(agentId);
        v.setStatus("candidate");
        return v;
    }

    private BehaviorRuleVersionEntity ruleVersion(String id, String agentId, String status) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(id);
        v.setAgentId(agentId);
        v.setStatus(status);
        return v;
    }

    private SkillDraftEntity skillDraft(String id, Long targetAgentId) {
        SkillDraftEntity d = new SkillDraftEntity();
        d.setId(id);
        d.setTargetAgentId(targetAgentId);
        return d;
    }

    @Test
    @DisplayName("all three surfaces succeed → ok/ok/ok, anyFailed=false")
    void adopt_threeSurfaces_allSucceed() {
        CandidateBundle bundle = new CandidateBundle("pv-1", "rv-1", "sd-1");
        // ownership validation lookups
        when(promptVersionRepository.findById("pv-1")).thenReturn(Optional.of(promptVersion("pv-1", AGENT_ID)));
        when(behaviorRuleVersionRepository.findById("rv-1"))
                .thenReturn(Optional.of(ruleVersion("rv-1", AGENT_ID, "candidate")));
        when(skillDraftRepository.findById("sd-1")).thenReturn(Optional.of(skillDraft("sd-1", 7L)));
        when(promptPromotionService.promoteByHuman("pv-1", AGENT_ID, 42L))
                .thenReturn(PromotionResult.promoted("pv-1"));

        AdoptResult result = service.adopt(bundle, AGENT_ID, 42L);

        assertThat(result.prompt().status()).isEqualTo("ok");
        assertThat(result.rule().status()).isEqualTo("ok");
        assertThat(result.skill().status()).isEqualTo("ok");
        assertThat(result.anyFailed()).isFalse();
        verify(behaviorRulePromotionService).promote(any(BehaviorRuleVersionEntity.class));
        verify(skillDraftService).approveDraft("sd-1", 42L, true);
    }

    @Test
    @DisplayName("null pointers → that surface result is null and the service is not called")
    void adopt_nullSurfaces_skipped() {
        CandidateBundle bundle = new CandidateBundle("pv-1", null, null);
        when(promptVersionRepository.findById("pv-1")).thenReturn(Optional.of(promptVersion("pv-1", AGENT_ID)));
        when(promptPromotionService.promoteByHuman("pv-1", AGENT_ID, 42L))
                .thenReturn(PromotionResult.promoted("pv-1"));

        AdoptResult result = service.adopt(bundle, AGENT_ID, 42L);

        assertThat(result.prompt().status()).isEqualTo("ok");
        assertThat(result.rule()).isNull();
        assertThat(result.skill()).isNull();
        assertThat(result.anyFailed()).isFalse();
        verify(behaviorRulePromotionService, never()).promote(any());
        verify(skillDraftService, never()).approveDraft(any(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("skill surface fails → prompt+rule still committed, anyFailed=true (isolation)")
    void adopt_skillFails_othersIsolated() {
        CandidateBundle bundle = new CandidateBundle("pv-1", "rv-1", "sd-1");
        when(promptVersionRepository.findById("pv-1")).thenReturn(Optional.of(promptVersion("pv-1", AGENT_ID)));
        when(behaviorRuleVersionRepository.findById("rv-1"))
                .thenReturn(Optional.of(ruleVersion("rv-1", AGENT_ID, "candidate")));
        when(skillDraftRepository.findById("sd-1")).thenReturn(Optional.of(skillDraft("sd-1", 7L)));
        when(promptPromotionService.promoteByHuman("pv-1", AGENT_ID, 42L))
                .thenReturn(PromotionResult.promoted("pv-1"));
        when(skillDraftService.approveDraft("sd-1", 42L, true))
                .thenThrow(new SkillNameConflictException("dup", "my-skill"));

        AdoptResult result = service.adopt(bundle, AGENT_ID, 42L);

        assertThat(result.prompt().status()).isEqualTo("ok");
        assertThat(result.rule().status()).isEqualTo("ok");
        assertThat(result.skill().status()).isEqualTo("failed");
        assertThat(result.skill().reason()).contains("Skill name conflict");
        assertThat(result.anyFailed()).isTrue();
        // prompt + rule still ran (committed in their own tx) despite skill failure
        verify(behaviorRulePromotionService).promote(any());
    }

    @Test
    @DisplayName("prompt surface fails → recorded as failed, rule+skill still attempted")
    void adopt_promptFails_othersIsolated() {
        CandidateBundle bundle = new CandidateBundle("pv-1", "rv-1", null);
        when(promptVersionRepository.findById("pv-1")).thenReturn(Optional.of(promptVersion("pv-1", AGENT_ID)));
        when(behaviorRuleVersionRepository.findById("rv-1"))
                .thenReturn(Optional.of(ruleVersion("rv-1", AGENT_ID, "candidate")));
        when(promptPromotionService.promoteByHuman("pv-1", AGENT_ID, 42L))
                .thenThrow(new RuntimeException("boom"));

        AdoptResult result = service.adopt(bundle, AGENT_ID, 42L);

        assertThat(result.prompt().status()).isEqualTo("failed");
        assertThat(result.prompt().reason()).contains("boom");
        assertThat(result.rule().status()).isEqualTo("ok");
        assertThat(result.skill()).isNull();
        assertThat(result.anyFailed()).isTrue();
        verify(behaviorRulePromotionService).promote(any());
    }

    @Test
    @DisplayName("rule already active → noop (not failed), promote not called")
    void adopt_ruleAlreadyActive_noop() {
        CandidateBundle bundle = new CandidateBundle(null, "rv-1", null);
        when(behaviorRuleVersionRepository.findById("rv-1"))
                .thenReturn(Optional.of(ruleVersion("rv-1", AGENT_ID, BehaviorRuleVersionEntity.STATUS_ACTIVE)));

        AdoptResult result = service.adopt(bundle, AGENT_ID, 42L);

        assertThat(result.rule().status()).isEqualTo("noop");
        assertThat(result.anyFailed()).isFalse();
        verify(behaviorRulePromotionService, never()).promote(any());
    }

    @Test
    @DisplayName("ownership: a cross-agent pointer throws IAE before ANY surface is adopted")
    void adopt_ownershipMismatch_failsFast() {
        CandidateBundle bundle = new CandidateBundle("pv-1", "rv-1", null);
        // prompt belongs to a different agent → validateOwnership throws before any write
        when(promptVersionRepository.findById("pv-1")).thenReturn(Optional.of(promptVersion("pv-1", "9")));

        assertThatThrownBy(() -> service.adopt(bundle, AGENT_ID, 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to agent 9");

        verify(promptPromotionService, never()).promoteByHuman(any(), any(), anyLong());
        verify(behaviorRulePromotionService, never()).promote(any());
        verify(skillDraftService, never()).approveDraft(any(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("ownership: skill draft with null targetAgentId is tolerated (system-driven)")
    void adopt_skillDraftNullTargetAgent_tolerated() {
        CandidateBundle bundle = new CandidateBundle(null, null, "sd-1");
        when(skillDraftRepository.findById("sd-1")).thenReturn(Optional.of(skillDraft("sd-1", null)));

        AdoptResult result = service.adopt(bundle, AGENT_ID, 42L);

        assertThat(result.skill().status()).isEqualTo("ok");
        verify(skillDraftService).approveDraft("sd-1", 42L, true);
    }
}
