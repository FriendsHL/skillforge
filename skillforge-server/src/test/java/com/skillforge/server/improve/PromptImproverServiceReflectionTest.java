package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL reflection (config + history aware evolve-editor) —
 * unit tests for the {@link EvolveEditorContext}-gated branch of
 * {@link PromptImproverService}'s prompt-candidate generation.
 *
 * <p>Three load-bearing properties:
 * <ul>
 *   <li><b>editor != null</b> uses the SEEDED {@code evolve-editor} agent's
 *       system prompt (not the hardcoded "expert prompt engineer" one) and
 *       appends the target-agent config + priorChange + priorEvalReport to the
 *       user message.</li>
 *   <li><b>editor != null</b> with the seed absent → defensive fallback to the
 *       hardcoded system prompt (still produces a candidate), but the user-side
 *       reflection blocks are still appended.</li>
 *   <li><b>editor == null</b> (the shared non-evolve attribution path) is
 *       UNCHANGED — hardcoded system prompt, NO config / reflection markers in
 *       the user message.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromptImproverService evolve-editor reflection (EvolveEditorContext gating)")
class PromptImproverServiceReflectionTest {

    private static final String SEED_PROMPT =
            "你是 evolve-editor：在 agent 自动进化中决定下一步怎么改 prompt。SEED-MARKER.";
    private static final String LEGACY_MARKER = "You are an expert prompt engineer";
    private static final String CONFIG_LABEL = "目标 agent 当前配置（仅供参考，本次只改 prompt）";
    private static final String PRIOR_CHANGE = "上一轮收紧了输出格式要求";
    private static final String PRIOR_REPORT = "{\"overallDelta\":3.0,\"perCase\":[]}";

    @Mock private AgentRepository agentRepository;
    @Mock private EvalTaskRepository evalTaskRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private AbEvalPipeline abEvalPipeline;
    @Mock private PromptPromotionService promotionService;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private ExecutorService coordinatorExecutor;
    @Mock private MemoryContextProvider memoryContextProvider;

    private PromptImproverService service;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("test");
        service = new PromptImproverService(
                agentRepository,
                evalTaskRepository,
                promptVersionRepository,
                promptAbRunRepository,
                abEvalPipeline,
                promotionService,
                llmProviderFactory,
                new ObjectMapper(),
                coordinatorExecutor,
                props,
                org.mockito.Mockito.mock(com.skillforge.server.improve.surface.PromptSurface.class),
                org.mockito.Mockito.mock(PromptEvalService.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.EvalScenarioDraftRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.OptimizationEventRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.PatternSessionMemberRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.SessionRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.SessionScenarioExtractorService.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.EphemeralScenarioCleanupService.class),
                null,
                memoryContextProvider);
    }

    private AgentEntity targetAgent(long id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOwnerId(7L);
        a.setSystemPrompt("ORIGINAL active prompt.");
        a.setBehaviorRules("{\"customRules\":[{\"severity\":\"MUST\",\"text\":\"BR-MARKER\"}]}");
        a.setSkillIds("SKILL-MARKER");
        a.setToolIds("TOOL-MARKER");
        a.setModelId("MODEL-MARKER");
        a.setAutoImprovePaused(false);
        return a;
    }

    private AgentEntity editorAgent() {
        AgentEntity e = new AgentEntity();
        e.setId(999L);
        e.setName("evolve-editor");
        e.setSystemPrompt(SEED_PROMPT);
        return e;
    }

    private LlmProvider stubLlm() {
        LlmProvider provider = org.mockito.Mockito.mock(LlmProvider.class);
        LlmResponse response = new LlmResponse();
        response.setContent("improved candidate prompt");
        when(provider.chat(any(LlmRequest.class))).thenReturn(response);
        when(llmProviderFactory.getProvider("test")).thenReturn(provider);
        return provider;
    }

    private void stubGenesisAttributionWrites(long agentId) {
        when(agentRepository.findByIdForUpdate(agentId)).thenReturn(Optional.of(targetAgent(agentId)));
        // No existing versions → genesis-baseline path materializes v1 then v2
        // candidate. The save() lambda just echoes the arg back.
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc(String.valueOf(agentId)))
                .thenReturn(List.of());
        when(promptVersionRepository.save(any(PromptVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // agentRepository.save called for the genesis baseline active-version link
        lenient().when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(memoryContextProvider.load(any(), any())).thenReturn(null);
    }

    private LlmRequest captureRequest(LlmProvider provider) {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(provider).chat(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("editor != null: uses seeded evolve-editor system prompt + appends config + priorChange + priorEvalReport")
    void editorPresent_usesSeededPrompt_andAppendsReflectionBlocks() {
        stubGenesisAttributionWrites(10L);
        when(agentRepository.findFirstByName("evolve-editor")).thenReturn(Optional.of(editorAgent()));
        LlmProvider provider = stubLlm();

        service.startImprovementFromAttribution(
                200L, "10", "Address the failure pattern", 7L,
                new EvolveEditorContext(PRIOR_CHANGE, PRIOR_REPORT));

        LlmRequest request = captureRequest(provider);
        // (a) seeded evolve-editor system prompt, NOT the legacy hardcoded one
        assertThat(request.getSystemPrompt()).contains("SEED-MARKER");
        assertThat(request.getSystemPrompt()).doesNotContain(LEGACY_MARKER);
        // (b) user message carries config summary + reflection sections
        String user = request.getMessages().get(0).getTextContent();
        assertThat(user).contains(CONFIG_LABEL);
        assertThat(user).contains("BR-MARKER");      // behaviorRules
        assertThat(user).contains("SKILL-MARKER");   // skills
        assertThat(user).contains("TOOL-MARKER");    // tools
        assertThat(user).contains("MODEL-MARKER");   // modelId
        assertThat(user).contains(PRIOR_CHANGE);     // what changed last round
        assertThat(user).contains(PRIOR_REPORT);     // last round eval report
        assertThat(user).contains("综合上述");        // guidance line
    }

    @Test
    @DisplayName("editor != null with first-round nulls: still evolve-editor mode (seed prompt + config + guidance), no prior sections")
    void editorPresent_firstRoundNulls_stillEvolveEditorMode() {
        stubGenesisAttributionWrites(11L);
        when(agentRepository.findFirstByName("evolve-editor")).thenReturn(Optional.of(editorAgent()));
        LlmProvider provider = stubLlm();

        // Presence of the (null,null) context signals evolve-editor mode even on round 1.
        service.startImprovementFromAttribution(
                200L, "11", "Address the failure pattern", 7L,
                new EvolveEditorContext(null, null));

        LlmRequest request = captureRequest(provider);
        assertThat(request.getSystemPrompt()).contains("SEED-MARKER");
        String user = request.getMessages().get(0).getTextContent();
        assertThat(user).contains(CONFIG_LABEL);
        assertThat(user).contains("综合上述");
        // No prior-round sections when both are null
        assertThat(user).doesNotContain("上一轮改动");
        assertThat(user).doesNotContain("上一轮评测报告");
    }

    @Test
    @DisplayName("editor != null but seed absent: defensive fallback to legacy system prompt, reflection blocks still appended")
    void editorPresent_seedAbsent_fallsBackToLegacySystemPrompt() {
        stubGenesisAttributionWrites(12L);
        when(agentRepository.findFirstByName("evolve-editor")).thenReturn(Optional.empty());
        LlmProvider provider = stubLlm();

        service.startImprovementFromAttribution(
                200L, "12", "Address the failure pattern", 7L,
                new EvolveEditorContext(PRIOR_CHANGE, PRIOR_REPORT));

        LlmRequest request = captureRequest(provider);
        // Fallback: hardcoded system prompt (defensive when V135 not applied)
        assertThat(request.getSystemPrompt()).contains(LEGACY_MARKER);
        // ...but the user-side reflection still rides along (the gate is the context object)
        String user = request.getMessages().get(0).getTextContent();
        assertThat(user).contains(CONFIG_LABEL);
        assertThat(user).contains(PRIOR_CHANGE);
        assertThat(user).contains(PRIOR_REPORT);
    }

    @Test
    @DisplayName("editor == null (shared non-evolve path): byte-identical legacy — hardcoded system prompt, NO config/reflection markers")
    void editorNull_legacyPath_unchanged() {
        stubGenesisAttributionWrites(13L);
        LlmProvider provider = stubLlm();

        // The 4-arg form (what AttributionApprovalService calls) → editor=null.
        service.startImprovementFromAttribution(200L, "13", "Address the failure pattern", 7L);

        LlmRequest request = captureRequest(provider);
        assertThat(request.getSystemPrompt()).contains(LEGACY_MARKER);
        String user = request.getMessages().get(0).getTextContent();
        // None of the evolve-editor reflection markers leak into the legacy path
        assertThat(user).doesNotContain(CONFIG_LABEL);
        assertThat(user).doesNotContain("上一轮改动");
        assertThat(user).doesNotContain("上一轮评测报告");
        assertThat(user).doesNotContain("综合上述");
        // And the evolve-editor agent is never even looked up on the legacy path.
        verify(agentRepository, never()).findFirstByName(any());
    }

    @Test
    @DisplayName("improveFromBasePrompt (iter 2+ path) editor != null: seeded prompt + base-version content as 'Current system prompt' + reflection blocks")
    void improveFromBasePrompt_editorPresent_usesSeededPromptAndBaseContent() {
        // iter 2+ hill-climb routes here (basePromptVersionId = current-best winner).
        when(agentRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(targetAgent(20L)));
        PromptVersionEntity best = new PromptVersionEntity();
        best.setId("v-best");
        best.setAgentId("20");
        best.setContent("WINNER-CONTENT-MARKER prompt from a prior round.");
        when(promptVersionRepository.findById("v-best")).thenReturn(Optional.of(best));
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("20"))
                .thenReturn(List.of(best));
        when(promptVersionRepository.save(any(PromptVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(memoryContextProvider.load(any(), any())).thenReturn(null);
        when(agentRepository.findFirstByName("evolve-editor")).thenReturn(Optional.of(editorAgent()));
        LlmProvider provider = stubLlm();

        service.improveFromBasePrompt(
                200L, "20", "v-best", "Address the failure pattern", 7L,
                new EvolveEditorContext(PRIOR_CHANGE, PRIOR_REPORT));

        LlmRequest request = captureRequest(provider);
        // Seeded evolve-editor system prompt reaches the iter-2+ path too.
        assertThat(request.getSystemPrompt()).contains("SEED-MARKER");
        assertThat(request.getSystemPrompt()).doesNotContain(LEGACY_MARKER);
        String user = request.getMessages().get(0).getTextContent();
        // The BASE (winner) version's content is what gets improved, not the agent's active prompt.
        assertThat(user).contains("WINNER-CONTENT-MARKER");
        assertThat(user).doesNotContain("ORIGINAL active prompt.");
        // Reflection blocks present on the iter-2+ path.
        assertThat(user).contains(CONFIG_LABEL);
        assertThat(user).contains(PRIOR_CHANGE);
        assertThat(user).contains(PRIOR_REPORT);
    }
}
