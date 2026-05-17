package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.skill.SkillCreatorService;
import com.skillforge.server.skill.SkillStorageService;
import com.skillforge.server.websocket.UserWebSocketHandler;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.4c sub-task 2 (Ratify #7-B path (a),
 * 2026-05-16) — focused unit test for
 * {@link SkillDraftService#promoteDraftToTransientSkill(String)}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftService.promoteDraftToTransientSkill (Phase 1.4c sub-task 2)")
class SkillDraftServiceTransientPromoteTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private UserWebSocketHandler userWebSocketHandler;
    @Mock private SkillCreatorService skillCreatorService;
    @Mock private SkillPackageLoader skillPackageLoader;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillStorageService skillStorageService;

    private SkillDraftService service;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("test");
        service = new SkillDraftService(
                sessionRepository, skillDraftRepository, skillRepository,
                llmProviderFactory, new ObjectMapper(), props,
                userWebSocketHandler, skillCreatorService,
                skillPackageLoader, skillRegistry, skillStorageService,
                // Phase 1.4e — 6 mock deps for startAbTestFromDraft path
                // (not exercised by this test class but constructor requires them).
                org.mockito.Mockito.mock(com.skillforge.server.repository.EvalScenarioDraftRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.OptimizationEventRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.PatternSessionMemberRepository.class),
                org.mockito.Mockito.mock(SessionScenarioExtractorService.class),
                org.mockito.Mockito.mock(EphemeralScenarioCleanupService.class),
                org.mockito.Mockito.mock(SkillAbEvalService.class));
    }

    @Test
    @DisplayName("creates disabled SkillEntity with name suffix _candidate_<8-uuid> and "
            + "carries description/triggers/requiredTools/ownerId from draft")
    void promoteDraftToTransientSkill_creates_disabled_skill_with_name_suffix() throws Exception {
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-uuid-phase14c");
        draft.setName("MyImprovedSkill");
        draft.setDescription("Auto-improved from attribution event #99");
        draft.setTriggers("trig-a,trig-b");
        draft.setRequiredTools("Bash,Read");
        draft.setOwnerId(7L);
        draft.setPromptHint("---\ntriggers: [trig-a, trig-b]\n---\nUse Bash first, then Read.");
        when(skillDraftRepository.findById("draft-uuid-phase14c"))
                .thenReturn(Optional.of(draft));

        // R3 fix: stub skillStorageService.allocate to return a fake path so
        // skillCreatorService.render gets called, then verify the chain wired.
        java.nio.file.Path fakeTargetDir = java.nio.file.Path.of(
                "/tmp/skillforge-test/skills/evolution-fork/7/x/y");
        when(skillStorageService.allocate(any(), any())).thenReturn(fakeTargetDir);

        ArgumentCaptor<SkillEntity> captor = ArgumentCaptor.forClass(SkillEntity.class);
        when(skillRepository.save(captor.capture())).thenAnswer(inv -> {
            SkillEntity arg = inv.getArgument(0);
            arg.setId(404L);
            return arg;
        });

        SkillEntity transientSkill = service.promoteDraftToTransientSkill("draft-uuid-phase14c");

        SkillEntity saved = captor.getValue();
        // Ratify #7-B (a) — name suffix is the transient identity marker.
        assertThat(saved.getName())
                .startsWith("MyImprovedSkill_candidate_")
                .matches("MyImprovedSkill_candidate_[0-9a-f]{8}");
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getOwnerId()).isEqualTo(7L);
        assertThat(saved.getDescription()).isEqualTo("Auto-improved from attribution event #99");
        assertThat(saved.getTriggers()).isEqualTo("trig-a,trig-b");
        assertThat(saved.getRequiredTools()).isEqualTo("Bash,Read");
        assertThat(saved.getSource()).isEqualTo("attribution_ab_transient");
        assertThat(saved.isSystem()).isFalse();
        // R3 fix: skillStorageService.allocate called + render called +
        // skillPath set to the allocated target dir.
        org.mockito.Mockito.verify(skillStorageService).allocate(any(), any());
        org.mockito.Mockito.verify(skillCreatorService).render(eq(draft), eq(fakeTargetDir));
        assertThat(saved.getSkillPath()).isEqualTo(fakeTargetDir.toString());
        assertThat(transientSkill).isSameAs(saved);
        assertThat(transientSkill.getId()).isEqualTo(404L);
    }

    @Test
    @DisplayName("draftId null/blank → IllegalArgumentException")
    void promoteDraftToTransientSkill_blankDraftId_throws() {
        assertThatThrownBy(() -> service.promoteDraftToTransientSkill(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draftId");
        assertThatThrownBy(() -> service.promoteDraftToTransientSkill("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draftId");
    }

    @Test
    @DisplayName("draft not found → IllegalArgumentException with draftId in message")
    void promoteDraftToTransientSkill_draftNotFound_throws() {
        when(skillDraftRepository.findById("missing-draft"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.promoteDraftToTransientSkill("missing-draft"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing-draft");
    }
}
