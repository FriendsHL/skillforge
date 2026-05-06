package com.skillforge.server.improve;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.eval.attribution.FailureAttribution;
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

import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PromptImproverService")
class PromptImproverServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private EvalTaskRepository evalTaskRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private AbEvalPipeline abEvalPipeline;
    @Mock private PromptPromotionService promotionService;
    @Mock private ExecutorService coordinatorExecutor;

    private LlmProviderFactory llmProviderFactory;
    private PromptImproverService service;
    private CapturingProvider provider;
    private final Map<String, PromptVersionEntity> versions = new HashMap<>();
    private final Map<String, PromptAbRunEntity> abRuns = new HashMap<>();

    @BeforeEach
    void setUp() {
        llmProviderFactory = new LlmProviderFactory();
        provider = new CapturingProvider();
        llmProviderFactory.registerProvider("test", provider);

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
                new com.fasterxml.jackson.databind.ObjectMapper(),
                coordinatorExecutor,
                props
        );

        when(promptVersionRepository.findMaxVersionNumber("10")).thenReturn(Optional.of(3));
        when(promptVersionRepository.save(any(PromptVersionEntity.class)))
                .thenAnswer(inv -> {
                    PromptVersionEntity entity = inv.getArgument(0);
                    versions.put(entity.getId(), entity);
                    return entity;
                });
        when(promptVersionRepository.findById(any(String.class)))
                .thenAnswer(inv -> Optional.ofNullable(versions.get(inv.getArgument(0))));
        when(promptAbRunRepository.save(any(PromptAbRunEntity.class)))
                .thenAnswer(inv -> {
                    PromptAbRunEntity entity = inv.getArgument(0);
                    abRuns.put(entity.getId(), entity);
                    return entity;
                });
        when(promptAbRunRepository.findById(any(String.class)))
                .thenAnswer(inv -> Optional.ofNullable(abRuns.get(inv.getArgument(0))));
        doAnswer(inv -> {
            Runnable runnable = inv.getArgument(0);
            runnable.run();
            return null;
        }).when(coordinatorExecutor).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("startImprovement: suggestion is stored on version and injected into LLM prompt")
    void startImprovement_suggestionStoredAndInjectedIntoPrompt() {
        AgentEntity agent = new AgentEntity();
        agent.setId(10L);
        agent.setSystemPrompt("Be concise.");
        agent.setAutoImprovePaused(false);
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent));

        EvalTaskEntity task = new EvalTaskEntity();
        task.setId("eval-1");
        task.setStatus("COMPLETED");
        task.setPrimaryAttribution(FailureAttribution.PROMPT_QUALITY);
        task.setOverallPassRate(42.0);
        task.setScenarioResultsJson("[]");
        when(evalTaskRepository.findById("eval-1")).thenReturn(Optional.of(task));

        ArgumentCaptor<PromptVersionEntity> versionCaptor = ArgumentCaptor.forClass(PromptVersionEntity.class);

        ImprovementStartResult result = service.startImprovement(
                "10",
                "eval-1",
                7L,
                "Ask clarifying questions before using tools."
        );

        assertThat(result.agentId()).isEqualTo("10");
        assertThat(provider.lastUserMessage)
                .contains("Analysis improvement suggestion:")
                .contains("Ask clarifying questions before using tools.");
        assertThat(provider.lastResponseContent).isEqualTo("Improved prompt text");
        org.mockito.Mockito.verify(promptVersionRepository, org.mockito.Mockito.atLeastOnce()).save(versionCaptor.capture());
        assertThat(versionCaptor.getAllValues().get(0).getImprovementRationale())
                .isEqualTo("Ask clarifying questions before using tools.");
    }

    private static final class CapturingProvider implements LlmProvider {
        private String lastUserMessage;
        private String lastResponseContent;

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastUserMessage = request.getMessages().isEmpty()
                    ? null
                    : String.valueOf(request.getMessages().get(0).getContent());
            lastResponseContent = "Improved prompt text";
            LlmResponse response = new LlmResponse();
            response.setContent(lastResponseContent);
            return response;
        }

        @Override
        public void chatStream(LlmRequest request, com.skillforge.core.llm.LlmStreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
