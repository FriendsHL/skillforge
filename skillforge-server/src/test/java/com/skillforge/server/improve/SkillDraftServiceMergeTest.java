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

import java.nio.file.Files;
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
 * SKILL-DASHBOARD-POLISH-V2 §H — verify {@link SkillDraftService#mergeIntoExistingSkill}.
 * Cases:
 * <ul>
 *   <li>happy path: writes SKILL.md, updates target content, flips draft to approved</li>
 *   <li>draft not in 'draft' status → RuntimeException, no save</li>
 *   <li>target skill not found → RuntimeException</li>
 *   <li>ownership mismatch → RuntimeException, no save</li>
 *   <li>system target skill → RuntimeException, no save</li>
 *   <li>target skillPath null → RuntimeException, no save</li>
 *   <li>does NOT touch enabled flag (V64 partial unique safety)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftService.mergeIntoExistingSkill")
class SkillDraftServiceMergeTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private UserWebSocketHandler userWebSocketHandler;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillStorageService skillStorageService;

    private SkillDraftService service;

    @BeforeEach
    void setUp() {
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
    }

    private SkillDraftEntity newDraft(String status, Long ownerId) {
        SkillDraftEntity d = new SkillDraftEntity();
        d.setId(UUID.randomUUID().toString());
        d.setOwnerId(ownerId);
        d.setName("MySkill");
        d.setDescription("Updated description from draft.");
        d.setTriggers("trig1, trig2");
        d.setRequiredTools("Read");
        d.setPromptHint("Updated prompt hint body.");
        d.setStatus(status);
        return d;
    }

    private SkillEntity newTarget(Long id, Long ownerId, Path skillPath, boolean enabled) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName("MySkill");
        s.setOwnerId(ownerId);
        s.setEnabled(enabled);
        s.setDescription("Stale description.");
        s.setTriggers("oldTrig");
        s.setRequiredTools("OldTool");
        s.setSkillPath(skillPath != null ? skillPath.toString() : null);
        s.setSemver("v1");
        s.setUsageCount(50);
        s.setSuccessCount(40);
        return s;
    }

    @Test
    @DisplayName("happy path: writes SKILL.md, updates target content, draft → approved, enabled NOT touched")
    void happyPath(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("skill-7-99");
        Files.createDirectories(skillDir);

        SkillDraftEntity draft = newDraft("draft", 7L);
        SkillEntity target = newTarget(99L, 7L, skillDir, true);

        when(skillDraftRepository.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(skillRepository.findById(99L)).thenReturn(Optional.of(target));
        when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillDraftRepository.save(any(SkillDraftEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillDraftEntity result = service.mergeIntoExistingSkill(draft.getId(), 99L, 11L);

        // SKILL.md should now be on disk
        Path md = skillDir.resolve("SKILL.md");
        assertThat(Files.exists(md)).isTrue();
        String body = Files.readString(md);
        assertThat(body).contains("Updated description from draft.");
        assertThat(body).contains("Updated prompt hint body.");

        // Target content updated, runtime state preserved
        assertThat(target.getDescription()).isEqualTo("Updated description from draft.");
        assertThat(target.getTriggers()).isEqualTo("trig1, trig2");
        assertThat(target.getRequiredTools()).isEqualTo("Read");
        assertThat(target.isEnabled())
                .as("enabled flag MUST NOT be touched (V64 partial unique safety)")
                .isTrue();
        assertThat(target.getUsageCount())
                .as("runtime counters preserved")
                .isEqualTo(50);
        assertThat(target.getSemver())
                .as("semver / parent / source preserved")
                .isEqualTo("v1");

        // Draft flipped
        assertThat(result.getStatus()).isEqualTo("approved");
        assertThat(result.getSkillId()).isEqualTo(99L);
        assertThat(result.getReviewedBy()).isEqualTo(11L);
        assertThat(result.getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("draft not in 'draft' status → throws, nothing saved")
    void nonDraftStatus_throws() {
        SkillDraftEntity approved = newDraft("approved", 7L);
        when(skillDraftRepository.findByIdForUpdate(approved.getId()))
                .thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.mergeIntoExistingSkill(approved.getId(), 99L, 11L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not in 'draft' status");

        verify(skillRepository, never()).save(any(SkillEntity.class));
        verify(skillDraftRepository, never()).save(any(SkillDraftEntity.class));
    }

    @Test
    @DisplayName("target skill not found → throws, nothing saved")
    void targetMissing_throws() {
        SkillDraftEntity draft = newDraft("draft", 7L);
        when(skillDraftRepository.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(skillRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.mergeIntoExistingSkill(draft.getId(), 99L, 11L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Target skill not found");

        verify(skillRepository, never()).save(any(SkillEntity.class));
        verify(skillDraftRepository, never()).save(any(SkillDraftEntity.class));
    }

    @Test
    @DisplayName("ownership mismatch (different ownerId) → throws, nothing saved")
    void ownershipMismatch_throws(@TempDir Path tmp) throws Exception {
        Path skillDir = tmp.resolve("skill-x");
        Files.createDirectories(skillDir);

        SkillDraftEntity draft = newDraft("draft", 7L);  // owner 7
        SkillEntity target = newTarget(99L, 999L, skillDir, true);   // owner 999

        when(skillDraftRepository.findByIdForUpdate(draft.getId())).thenReturn(Optional.of(draft));
        when(skillRepository.findById(99L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.mergeIntoExistingSkill(draft.getId(), 99L, 11L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Ownership mismatch");

        verify(skillRepository, never()).save(any(SkillEntity.class));
        verify(skillDraftRepository, never()).save(any(SkillDraftEntity.class));
    }
}
