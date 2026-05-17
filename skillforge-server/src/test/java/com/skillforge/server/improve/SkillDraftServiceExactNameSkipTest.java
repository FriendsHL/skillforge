package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SessionEntity;
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

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-DASHBOARD-POLISH F — verify {@code extractFromRecentSessions} skips
 * any LLM-extracted draft whose name already case-insensitively matches an
 * existing skill row for this owner. Without the skip, the cron extractor
 * would produce a "dead" draft that {@code approveDraft()} can never satisfy
 * (V64 partial unique would fire as soon as the new row flips to enabled).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillDraftService.extractFromRecentSessions: exact-name skip")
class SkillDraftServiceExactNameSkipTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SkillDraftRepository skillDraftRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private LlmProvider llmProvider;
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

    /** Build an LLM response containing a single draft with the given name. */
    private static LlmResponse llmDraftJson(String... names) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(names[i]).append("\",")
              .append("\"description\":\"some skill description\",")
              .append("\"triggers\":\"trigA, trigB\",")
              .append("\"requiredTools\":\"Bash\",")
              .append("\"promptHint\":\"do this then that\",")
              .append("\"extractionRationale\":\"because\"}");
        }
        sb.append("]");
        LlmResponse resp = new LlmResponse();
        resp.setContent(sb.toString());
        return resp;
    }

    private static SessionEntity sessionWith(String messagesJson) {
        SessionEntity s = new SessionEntity();
        s.setMessagesJson(messagesJson);
        s.setTitle("test");
        return s;
    }

    private static SkillEntity existingSkill(Long id, String name) {
        SkillEntity skill = new SkillEntity();
        skill.setId(id);
        skill.setName(name);
        skill.setOwnerId(7L);
        return skill;
    }

    @Test
    @DisplayName("draft with exact-name match is skipped (case-insensitive)")
    void exactNameSkipped_caseInsensitive() throws Exception {
        when(sessionRepository.findRecentEligibleSessionsForSkillDraft(any(), any()))
                .thenReturn(List.of(sessionWith("[]")));
        when(llmProviderFactory.getProvider(anyString())).thenReturn(llmProvider);
        // LLM extracts "MySkill" — but ownerId=7L already has "myskill" (different case)
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(llmDraftJson("MySkill"));
        when(skillRepository.findByOwnerId(7L)).thenReturn(List.of(existingSkill(99L, "myskill")));
        when(skillDraftRepository.findByOwnerIdAndStatus(7L, "draft")).thenReturn(Collections.emptyList());

        int saved = service.extractFromRecentSessions(101L, 7L);

        assertThat(saved).isEqualTo(0);
        // saveAll is still called with an empty list (the loop simply produces no items),
        // so verify the captured list is empty.
        ArgumentCaptor<List<SkillDraftEntity>> cap = ArgumentCaptor.forClass(List.class);
        verify(skillDraftRepository).saveAll(cap.capture());
        assertThat(cap.getValue()).isEmpty();
    }

    @Test
    @DisplayName("draft with non-conflicting name is preserved")
    void nonConflictingName_preserved() throws Exception {
        when(sessionRepository.findRecentEligibleSessionsForSkillDraft(any(), any()))
                .thenReturn(List.of(sessionWith("[]")));
        when(llmProviderFactory.getProvider(anyString())).thenReturn(llmProvider);
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(llmDraftJson("BrandNewSkill"));
        when(skillRepository.findByOwnerId(7L))
                .thenReturn(List.of(existingSkill(99L, "SomeOtherSkill")));
        when(skillDraftRepository.findByOwnerIdAndStatus(7L, "draft")).thenReturn(Collections.emptyList());

        int saved = service.extractFromRecentSessions(101L, 7L);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<List<SkillDraftEntity>> cap = ArgumentCaptor.forClass(List.class);
        verify(skillDraftRepository).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).getName()).isEqualTo("BrandNewSkill");
    }

    @Test
    @DisplayName("blank name from LLM is skipped (existing guard, sanity check)")
    void blankName_skipped() throws Exception {
        when(sessionRepository.findRecentEligibleSessionsForSkillDraft(any(), any()))
                .thenReturn(List.of(sessionWith("[]")));
        when(llmProviderFactory.getProvider(anyString())).thenReturn(llmProvider);
        // Two items: one blank-named, one valid.
        LlmResponse resp = new LlmResponse();
        resp.setContent("[" +
                "{\"name\":\"\",\"description\":\"x\",\"triggers\":\"\",\"requiredTools\":\"\",\"promptHint\":\"\",\"extractionRationale\":\"\"}," +
                "{\"name\":\"Good\",\"description\":\"y\",\"triggers\":\"\",\"requiredTools\":\"\",\"promptHint\":\"\",\"extractionRationale\":\"\"}" +
                "]");
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(resp);
        when(skillRepository.findByOwnerId(7L)).thenReturn(Collections.emptyList());
        when(skillDraftRepository.findByOwnerIdAndStatus(7L, "draft")).thenReturn(Collections.emptyList());

        int saved = service.extractFromRecentSessions(101L, 7L);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<List<SkillDraftEntity>> cap = ArgumentCaptor.forClass(List.class);
        verify(skillDraftRepository).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).getName()).isEqualTo("Good");
    }
}
