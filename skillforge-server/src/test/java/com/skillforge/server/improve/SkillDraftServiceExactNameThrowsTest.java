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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-DASHBOARD-POLISH-V2 §H — pre-flight exact-name check in
 * {@link SkillDraftService#approveDraft}. Two cases:
 * <ul>
 *   <li>name collision → throws {@link SkillNameConflictException} containing
 *       {@code existingSkillId}; no artifact write, no DB save.</li>
 *   <li>no collision → normal flow proceeds.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftService.approveDraft — exact-name pre-flight gate")
class SkillDraftServiceExactNameThrowsTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private UserWebSocketHandler userWebSocketHandler;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillStorageService skillStorageService;

    private SkillDraftService service;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        SkillCreatorService creatorService = new SkillCreatorService();
        SkillPackageLoader packageLoader = new SkillPackageLoader();
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("claude");

        service = new SkillDraftService(
                sessionRepository, skillDraftRepository, skillRepository,
                llmProviderFactory, new ObjectMapper(), props,
                userWebSocketHandler, creatorService, packageLoader, skillRegistry,
                skillStorageService,
                // Phase 1.4e — 6 mock deps for startAbTestFromDraft path.
                org.mockito.Mockito.mock(com.skillforge.server.repository.EvalScenarioDraftRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.OptimizationEventRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.PatternSessionMemberRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.SessionScenarioExtractorService.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.EphemeralScenarioCleanupService.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.SkillAbEvalService.class));
        service.setSkillsDir(tmp.toString());
    }

    private SkillDraftEntity newDraft() {
        SkillDraftEntity d = new SkillDraftEntity();
        d.setId(UUID.randomUUID().toString());
        d.setOwnerId(7L);
        d.setName("MySkill");
        d.setDescription("does something");
        d.setRequiredTools("Read");
        d.setPromptHint("hint body");
        d.setStatus("draft");
        return d;
    }

    @Test
    @DisplayName("exact-name match → throws SkillNameConflictException with existingSkillId; no save")
    void exactNameMatch_throwsWithExistingId() {
        SkillDraftEntity draft = newDraft();
        SkillEntity existing = new SkillEntity();
        existing.setId(99L);
        existing.setName("MySkill");
        existing.setOwnerId(7L);
        existing.setEnabled(true);

        when(skillDraftRepository.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(skillRepository.findFirstByOwnerIdAndNameAndEnabledTrue(7L, "MySkill"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.approveDraft(draft.getId(), 11L))
                .isInstanceOf(SkillNameConflictException.class)
                .satisfies(e -> {
                    SkillNameConflictException nce = (SkillNameConflictException) e;
                    assertThat(nce.getExistingSkillId()).isEqualTo(99L);
                    assertThat(nce.getExistingSkillName()).isEqualTo("MySkill");
                });

        // No artifact write, no skill row, no draft flip.
        verify(skillRepository, never()).save(any(SkillEntity.class));
        verify(skillDraftRepository, never()).save(any(SkillDraftEntity.class));
        assertThat(draft.getStatus()).isEqualTo("draft");
    }

    @Test
    @DisplayName("no exact-name match → normal flow proceeds (draft approved)")
    void noMatch_normalFlow() {
        SkillDraftEntity draft = newDraft();

        when(skillDraftRepository.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(skillRepository.findFirstByOwnerIdAndNameAndEnabledTrue(7L, "MySkill"))
                .thenReturn(Optional.empty());
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> {
            SkillEntity e = inv.getArgument(0);
            e.setId(101L);
            return e;
        });
        when(skillDraftRepository.save(any(SkillDraftEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillDraftEntity result = service.approveDraft(draft.getId(), 11L);

        assertThat(result.getStatus()).isEqualTo("approved");
        assertThat(result.getSkillId()).isEqualTo(101L);
    }
}
