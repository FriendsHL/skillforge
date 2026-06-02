package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.memory.context.MemoryContextProvider;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 4 (§10 #3) — unit tests for the
 * skill-surface hill-climb carry-forward
 * {@link SkillDraftService#createDraftFromBaseDraft}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftService.createDraftFromBaseDraft")
class SkillDraftServiceCarryForwardTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private LlmProvider llmProvider;
    @Mock private UserWebSocketHandler userWebSocketHandler;
    @Mock private SkillCreatorService skillCreatorService;
    @Mock private SkillPackageLoader skillPackageLoader;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillStorageService skillStorageService;
    @Mock private MemoryContextProvider memoryContextProvider;

    private SkillDraftService service;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("test");
        service = new SkillDraftService(
                sessionRepository, skillDraftRepository, skillRepository, llmProviderFactory,
                new ObjectMapper(), props, userWebSocketHandler, skillCreatorService,
                skillPackageLoader, skillRegistry, skillStorageService,
                org.mockito.Mockito.mock(com.skillforge.server.repository.EvalScenarioDraftRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.OptimizationEventRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.PatternSessionMemberRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.SessionScenarioExtractorService.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.EphemeralScenarioCleanupService.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.SkillAbEvalService.class),
                memoryContextProvider);
    }

    private void stubLlmReturns(String content) {
        when(llmProviderFactory.getProvider("xiaomi-mimo")).thenReturn(llmProvider);
        LlmResponse resp = new LlmResponse();
        resp.setContent(content);
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(resp);
    }

    private SkillDraftEntity baseDraft() {
        SkillDraftEntity base = new SkillDraftEntity();
        base.setId("base-1");
        base.setOwnerId(7L);
        base.setName("MySkill");
        base.setTargetAgentId(42L);
        base.setTriggers("old-trigger");
        base.setRequiredTools("Read");
        base.setPromptHint("old body");
        base.setStatus("draft");
        return base;
    }

    @Test
    @DisplayName("happy path: loads base draft, fills from LLM, inherits name + targetAgentId")
    void createDraftFromBaseDraft_happyPath_inheritsIdentity() {
        when(skillDraftRepository.findById("base-1")).thenReturn(Optional.of(baseDraft()));
        stubLlmReturns("""
                ---
                triggers: [new-trigger, refined]
                required_tools: [Read, Bash]
                ---
                Refined body building on the old one.""");
        ArgumentCaptor<SkillDraftEntity> captor = ArgumentCaptor.forClass(SkillDraftEntity.class);
        when(skillDraftRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        SkillDraftEntity result = service.createDraftFromBaseDraft(
                99L, "base-1", "Make it handle retries", 7L);

        assertThat(result).isNotNull();
        SkillDraftEntity saved = captor.getValue();
        // identity inherited from the base for hill-climb stability
        assertThat(saved.getName()).isEqualTo("MySkill");
        assertThat(saved.getTargetAgentId()).isEqualTo(42L);
        assertThat(saved.getOwnerId()).isEqualTo(7L);
        assertThat(saved.getStatus()).isEqualTo("draft");
        // LLM-filled content
        assertThat(saved.getTriggers()).isEqualTo("new-trigger,refined");
        assertThat(saved.getRequiredTools()).isEqualTo("Read,Bash");
        assertThat(saved.getPromptHint()).isEqualTo("Refined body building on the old one.");
        // audit pivot
        assertThat(saved.getExtractionRationale())
                .contains("[carry-forward:eventId=99")
                .contains("baseDraftId=base-1");
    }

    @Test
    @DisplayName("base draft from a DIFFERENT owner fails loud (W7 ownership)")
    void createDraftFromBaseDraft_crossOwner_throws() {
        SkillDraftEntity base = baseDraft();
        base.setOwnerId(8L);   // different owner than caller (7L)
        when(skillDraftRepository.findById("base-1")).thenReturn(Optional.of(base));

        assertThatThrownBy(() -> service.createDraftFromBaseDraft(99L, "base-1", "desc", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to owner 8");
        verify(skillDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("unknown baseDraftId fails loud")
    void createDraftFromBaseDraft_unknownBase_throws() {
        when(skillDraftRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDraftFromBaseDraft(99L, "ghost", "desc", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDraftId not found");
    }

    @Test
    @DisplayName("reflection editor appends prior-change / prior-report blocks to the LLM prompt")
    void createDraftFromBaseDraft_withEditor_appendsReflectionBlocks() {
        when(skillDraftRepository.findById("base-1")).thenReturn(Optional.of(baseDraft()));
        stubLlmReturns("""
                ---
                triggers: [t]
                required_tools: []
                ---
                Body.""");
        when(skillDraftRepository.save(any(SkillDraftEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);

        EvolveEditorContext editor = new EvolveEditorContext(
                "last round added a dry-run step", "{\"delta\":\"+5pp\"}");
        service.createDraftFromBaseDraft(99L, "base-1", "improve further", 7L, editor);

        verify(llmProvider).chat(reqCaptor.capture());
        String userPrompt = reqCaptor.getValue().getMessages().get(0).getTextContent();
        assertThat(userPrompt)
                .contains("上一轮改动")
                .contains("last round added a dry-run step")
                .contains("上一轮评测报告")
                .contains("+5pp")
                // base SKILL.md is fed as the starting point
                .contains("Current SKILL.md");
    }

    @Test
    @DisplayName("invalid args fail before the LLM call")
    void createDraftFromBaseDraft_invalidArgs_throwsBeforeLlm() {
        assertThatThrownBy(() -> service.createDraftFromBaseDraft(null, "base-1", "d", 7L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> service.createDraftFromBaseDraft(99L, " ", "d", 7L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baseDraftId");
        assertThatThrownBy(() -> service.createDraftFromBaseDraft(99L, "base-1", " ", 7L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attributedDescription");
        assertThatThrownBy(() -> service.createDraftFromBaseDraft(99L, "base-1", "d", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ownerId");
    }
}
