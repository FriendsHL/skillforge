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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL BUG-1 (winner-carry-forward / true hill-climb) —
 * unit tests for {@link PromptImproverService#improveFromBasePrompt}.
 *
 * <p>The bug: every evolve iteration re-improved the agent's <em>original</em>
 * active prompt and re-measured the baseline fresh, so a 3-iteration run never
 * actually climbed — each candidate was independent of the last winner. The fix
 * adds this entry point: the candidate is generated from an explicit
 * {@code basePromptVersionId} (the current-best prompt) so iterations accumulate.
 *
 * <p>The load-bearing assertion is {@code generatesFromBaseContent_notAgentActivePrompt}:
 * it proves the LLM is shown the BASE version's content, not the agent's active
 * {@code system_prompt} — i.e. iteration N+1 builds on iteration N's winner.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromptImproverService.improveFromBasePrompt (BUG-1 hill-climb)")
class PromptImproverServiceHillClimbTest {

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

    private AgentEntity agent(long id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOwnerId(7L);
        a.setSystemPrompt("ORIGINAL active prompt — must NOT seed the hill-climb candidate.");
        a.setAutoImprovePaused(false);
        return a;
    }

    private LlmProvider stubLlmReturning(String responseContent) {
        LlmProvider provider = org.mockito.Mockito.mock(LlmProvider.class);
        LlmResponse response = new LlmResponse();
        response.setContent(responseContent);
        when(provider.chat(any(LlmRequest.class))).thenReturn(response);
        when(llmProviderFactory.getProvider("test")).thenReturn(provider);
        return provider;
    }

    private PromptVersionEntity versionRow(String agentId, int versionNumber, String status, String content) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId("v-" + agentId + "-" + versionNumber);
        v.setAgentId(agentId);
        v.setVersionNumber(versionNumber);
        v.setStatus(status);
        v.setContent(content);
        return v;
    }

    @Test
    @DisplayName("happy path: builds candidate from the supplied base version, source=attribution, next versionNumber")
    void improveFromBasePrompt_happyPath_persistsCumulativeCandidate() {
        when(agentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(agent(10L)));
        PromptVersionEntity best = versionRow("10", 2, "candidate",
                "WINNER prompt from iteration 1 — the current best.");
        when(promptVersionRepository.findById("v-10-2")).thenReturn(Optional.of(best));
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("10"))
                .thenReturn(List.of(
                        versionRow("10", 2, "candidate", "WINNER prompt from iteration 1 — the current best."),
                        versionRow("10", 1, "active", "ORIGINAL active prompt — must NOT seed the hill-climb candidate.")));
        stubLlmReturning("WINNER prompt + iteration-2 refinement.");
        ArgumentCaptor<PromptVersionEntity> captor = ArgumentCaptor.forClass(PromptVersionEntity.class);
        when(promptVersionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ImprovementStartResult result = service.improveFromBasePrompt(
                /*eventId*/ 200L,
                /*agentId*/ "10",
                /*basePromptVersionId*/ "v-10-2",
                /*attributedDescription*/ "Tighten the rule added in iteration 1",
                /*ownerId*/ 7L);

        assertThat(result).isNotNull();
        assertThat(result.agentId()).isEqualTo("10");
        assertThat(result.abRunId()).isNull();
        assertThat(result.promptVersionId()).isNotBlank();
        assertThat(result.status()).isEqualTo("PENDING");

        PromptVersionEntity saved = captor.getValue();
        assertThat(saved.getAgentId()).isEqualTo("10");
        assertThat(saved.getStatus()).isEqualTo("candidate");
        assertThat(saved.getSource()).isEqualTo("attribution");
        assertThat(saved.getVersionNumber()).isEqualTo(3);  // max(2) + 1
        assertThat(saved.getContent()).isEqualTo("WINNER prompt + iteration-2 refinement.");
        assertThat(saved.getImprovementRationale()).isEqualTo("Tighten the rule added in iteration 1");
        // No A/B run created here (mirrors startImprovementFromAttribution contract).
        verify(promptAbRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("load-bearing: the LLM sees the BASE version content, not the agent's active prompt")
    void improveFromBasePrompt_generatesFromBaseContent_notAgentActivePrompt() {
        when(agentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(agent(10L)));
        PromptVersionEntity best = versionRow("10", 2, "candidate",
                "WINNER prompt from iteration 1 — the current best.");
        when(promptVersionRepository.findById("v-10-2")).thenReturn(Optional.of(best));
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("10"))
                .thenReturn(List.of(best));
        LlmProvider provider = stubLlmReturning("improved");
        when(promptVersionRepository.save(any(PromptVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);

        service.improveFromBasePrompt(200L, "10", "v-10-2", "Refine further", 7L);

        verify(provider).chat(requestCaptor.capture());
        String userPrompt = requestCaptor.getValue().getMessages().get(0).getTextContent();
        // Builds on the winner...
        assertThat(userPrompt).contains("WINNER prompt from iteration 1 — the current best.");
        // ...NOT on the agent's original active prompt (that would defeat the hill-climb).
        assertThat(userPrompt).doesNotContain("ORIGINAL active prompt — must NOT seed the hill-climb candidate.");
    }

    @Test
    @DisplayName("base version not found → RuntimeException, no version saved")
    void improveFromBasePrompt_baseMissing_throws() {
        when(agentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(agent(10L)));
        when(promptVersionRepository.findById("v-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.improveFromBasePrompt(
                200L, "10", "v-missing", "desc", 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Base prompt version not found");
        verify(promptVersionRepository, never()).save(any());
    }

    @Test
    @DisplayName("cross-agent base version → IllegalArgumentException (ownership guard), no version saved")
    void improveFromBasePrompt_baseBelongsToOtherAgent_throws() {
        when(agentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(agent(10L)));
        // Base version owned by agent 99, but caller targets agent 10.
        PromptVersionEntity foreign = versionRow("99", 5, "candidate", "someone else's prompt");
        when(promptVersionRepository.findById("v-99-5")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.improveFromBasePrompt(
                200L, "10", "v-99-5", "desc", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to agent");
        verify(promptVersionRepository, never()).save(any());
    }

    @Test
    @DisplayName("missing args: null eventId / null agentId / blank basePromptVersionId / blank description → IllegalArgumentException")
    void improveFromBasePrompt_invalidArgs_throws() {
        assertThatThrownBy(() -> service.improveFromBasePrompt(
                null, "10", "v-10-2", "desc", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> service.improveFromBasePrompt(
                200L, null, "v-10-2", "desc", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
        assertThatThrownBy(() -> service.improveFromBasePrompt(
                200L, "10", "  ", "desc", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basePromptVersionId");
        assertThatThrownBy(() -> service.improveFromBasePrompt(
                200L, "10", "v-10-2", "  ", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributedDescription");
        verify(promptVersionRepository, never()).save(any());
    }
}
