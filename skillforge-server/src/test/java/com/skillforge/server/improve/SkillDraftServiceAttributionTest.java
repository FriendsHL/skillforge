package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SkillDraftEntity;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.3 — unit tests for the new
 * {@link SkillDraftService#createDraftFromAttribution} entry point.
 *
 * <p>Existing {@code extractFromRecentSessions} path covered by
 * {@link SkillDraftServiceApproveDraftTest} / {@link SkillDraftScheduledExtractorTest}
 * — these tests verify the attribution-aware path doesn't disturb that one.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftService.createDraftFromAttribution (V3 Phase 1.3)")
class SkillDraftServiceAttributionTest {

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
                sessionRepository,
                skillDraftRepository,
                skillRepository,
                llmProviderFactory,
                new ObjectMapper(),
                props,
                userWebSocketHandler,
                skillCreatorService,
                skillPackageLoader,
                skillRegistry,
                skillStorageService);
    }

    @Test
    @DisplayName("happy path: persists draft tagged with attribution metadata")
    void createDraftFromAttribution_happyPath_persistsTaggedDraft() {
        ArgumentCaptor<SkillDraftEntity> captor = ArgumentCaptor.forClass(SkillDraftEntity.class);
        when(skillDraftRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        SkillDraftEntity draft = service.createDraftFromAttribution(
                /*eventId*/ 99L,
                /*patternId*/ 42L,
                /*description*/ "Add Bash pre-validation step before retries",
                /*expectedImpact*/ "Reduce failure rate by ~30% on this pattern",
                /*changeType*/ "rewrite_skill_md",
                /*ownerId*/ 7L,
                /*suggestedSkillName*/ "AttrSkill42_99");

        assertThat(draft).isNotNull();
        SkillDraftEntity saved = captor.getValue();
        assertThat(saved.getOwnerId()).isEqualTo(7L);
        assertThat(saved.getName()).isEqualTo("AttrSkill42_99");
        assertThat(saved.getStatus()).isEqualTo("draft");
        // Attribution metadata pivot — Phase 1.3 brief: prefer field reuse.
        assertThat(saved.getExtractionRationale())
                .contains("[attribution:eventId=99")
                .contains("patternId=42")
                .contains("changeType=rewrite_skill_md")
                .contains("Add Bash pre-validation step");
        // Description carries the curator's prose verbatim for FE display.
        assertThat(saved.getDescription()).isEqualTo("Add Bash pre-validation step before retries");
        // promptHint pre-loaded with structured "[attribution-derived ...]" prefix.
        assertThat(saved.getPromptHint())
                .contains("[attribution-derived skill draft]")
                .contains("Expected impact: Reduce failure rate by ~30%")
                .contains("optimization event #99")
                .contains("pattern #42");
        // sourceSessionId left null intentionally — attribution generalises.
        assertThat(saved.getSourceSessionId()).isNull();
    }

    @Test
    @DisplayName("missing eventId / ownerId / blank description → IllegalArgumentException")
    void createDraftFromAttribution_invalidArgs_throws() {
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                null, 42L, "desc", "impact", "rewrite", 7L, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                99L, 42L, "desc", "impact", "rewrite", null, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerId");
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                99L, 42L, "  ", "impact", "rewrite", 7L, "name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributedDescription");
        assertThatThrownBy(() -> service.createDraftFromAttribution(
                99L, 42L, "desc", "impact", "rewrite", 7L, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suggestedSkillName");
        verify(skillDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("expectedImpact null/blank → omitted from promptHint, no NPE")
    void createDraftFromAttribution_blankExpectedImpact_skipsImpactSection() {
        ArgumentCaptor<SkillDraftEntity> captor = ArgumentCaptor.forClass(SkillDraftEntity.class);
        when(skillDraftRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.createDraftFromAttribution(
                99L, 42L,
                "desc only",
                null,  // null expectedImpact
                "rewrite",
                7L,
                "AttrName");

        SkillDraftEntity saved = captor.getValue();
        assertThat(saved.getPromptHint()).doesNotContain("Expected impact");
        // Still contains the description + pivot footer.
        assertThat(saved.getPromptHint()).contains("Proposed change: desc only");
        assertThat(saved.getPromptHint()).contains("optimization event #99");
    }
}
