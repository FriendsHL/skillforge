package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.exception.MultimodalNoVisionException;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTIMODAL-MVP redesign (2026-05-14): vision capability defense-in-depth.
 *
 * <p>The agent now has a single {@code modelId} (the separate
 * {@code multimodalModelId} field was dropped). When a turn carries multimodal
 * blocks and the resolved effective model (runtimeOverride or agent.modelId)
 * is NOT in any provider's {@code visionModels} allowlist, ChatService must
 * throw {@link MultimodalNoVisionException} BEFORE handing the message to the
 * provider. The existing catch block writes runtimeError + broadcasts
 * sessionStatus("error"), so the FE can surface an actionable "switch model"
 * hint via the stable wire code.</p>
 *
 * <p>FE upload-button gate + BE upload-endpoint gate
 * ({@code ChatController.requireVisionCapableModel}) block the common path
 * upstream; this check is the runtime guard for race conditions (agent.modelId
 * swapped between upload and send) and replayed / stale-FE requests.</p>
 */
@DisplayName("ChatService — multimodal vision capability check (defense-in-depth)")
class ChatServiceMultimodalCapabilityCheckTest {

    private AgentService agentService;
    private SessionService sessionService;
    private AgentLoopEngine agentLoopEngine;
    private ChatAttachmentService chatAttachmentService;
    private SessionService sessionServiceSpy;
    private ChatService chatService;

    /**
     * Wire ChatService with the supplied LlmProperties. visionModels can be
     * empty to model "no model supports vision yet".
     */
    private ChatService buildChatService(LlmProperties llmProperties) {
        return buildChatService(llmProperties, null);
    }

    private ChatService buildChatService(
            LlmProperties llmProperties,
            ArtifactWorkspaceService artifactWorkspaceService) {
        agentService = mock(AgentService.class);
        sessionService = mock(SessionService.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        agentLoopEngine = mock(AgentLoopEngine.class);
        chatAttachmentService = mock(ChatAttachmentService.class);
        ModelUsageRepository modelUsageRepository = mock(ModelUsageRepository.class);
        ChatEventBroadcaster broadcaster = mock(ChatEventBroadcaster.class);
        SessionTitleService sessionTitleService = mock(SessionTitleService.class);
        SubAgentRegistry subAgentRegistry = mock(SubAgentRegistry.class);
        CancellationRegistry cancellationRegistry = mock(CancellationRegistry.class);
        CompactionService compactionService = mock(CompactionService.class);

        ThreadPoolExecutor sync = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16)) {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        lenient().when(compactionService.lockFor(anyString())).thenAnswer(inv -> new Object());

        return new ChatService(agentService, sessionService, skillRegistry,
                agentLoopEngine, modelUsageRepository, broadcaster, sync,
                sessionTitleService, subAgentRegistry, cancellationRegistry, compactionService,
                null, null, new ObjectMapper(), null,
                new NoopDispatcher(),
                new SessionConfirmCache(), new PendingConfirmationRegistry(),
                sid -> sid, mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null /* reminderBuilder */,
                chatAttachmentService,
                llmProperties,
                artifactWorkspaceService);
    }

    @Test
    @DisplayName("multimodal turn + agent.modelId NOT in visionModels → runtimeError set, provider never called")
    void unsupportedVisionMainModel_setsRuntimeErrorAndSkipsProvider() {
        // visionModels has only "real-vision-model"; agent uses a text model.
        LlmProperties props = new LlmProperties();
        LlmProperties.ProviderConfig provider = new LlmProperties.ProviderConfig();
        provider.setType("openai");
        provider.setVisionModels(List.of("real-vision-model"));
        Map<String, LlmProperties.ProviderConfig> map = new LinkedHashMap<>();
        map.put("openai", provider);
        props.setProviders(map);

        chatService = buildChatService(props);

        SessionEntity sess = newSession("sess-no-vision", null);
        // agent.modelId is text-only ("non-vision-model" not in any visionModels list)
        AgentEntity agent = newAgent(100L, "non-vision-model");
        AgentDefinition def = newDef("non-vision-model");
        when(chatAttachmentService.referenceBlocks(eq("sess-no-vision"), eq(7L), eq(List.of("att-1"))))
                .thenReturn(List.of(ContentBlock.imageRef("att-1", "image/png", "screen.png")));
        when(chatAttachmentService.materializeForProvider(eq("sess-no-vision"), any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sessionService.getSession("sess-no-vision")).thenReturn(sess);
        when(agentService.getAgent(100L)).thenReturn(agent);
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getSessionMessages("sess-no-vision")).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages("sess-no-vision")).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory("sess-no-vision")).thenReturn(new ArrayList<>());
        // SessionEntity reloads inside runLoop / catch block: returning the same `sess`
        // keeps state consistent across saveSession round-trips.
        lenient().when(sessionService.getSession(anyString())).thenReturn(sess);

        chatService.chatAsync("sess-no-vision", "describe", 7L, List.of("att-1"));

        // Iron Law: provider must never have been called when capability check fails.
        verify(agentLoopEngine, never()).run(
                any(AgentDefinition.class), anyString(),
                any(Message.class),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class));
        // runtimeError contains the stable wire code so the FE can detect + prompt.
        assertThat(sess.getRuntimeError())
                .isNotNull()
                .contains(MultimodalNoVisionException.CODE)
                .contains("non-vision-model");
        assertThat(sess.getRuntimeStatus()).isEqualTo("error");
    }

    @Test
    @DisplayName("multimodal turn + agent.modelId IS in visionModels → provider call proceeds normally")
    void visionCapableMainModel_proceedsToProvider() {
        LlmProperties props = new LlmProperties();
        LlmProperties.ProviderConfig provider = new LlmProperties.ProviderConfig();
        provider.setType("openai");
        provider.setVisionModels(List.of("mimo-v2-omni"));
        Map<String, LlmProperties.ProviderConfig> map = new LinkedHashMap<>();
        map.put("xiaomi-mimo", provider);
        props.setProviders(map);

        chatService = buildChatService(props);

        SessionEntity sess = newSession("sess-vision-ok", null);
        AgentEntity agent = newAgent(100L, "mimo-v2-omni");
        AgentDefinition def = newDef("mimo-v2-omni");
        when(chatAttachmentService.referenceBlocks(eq("sess-vision-ok"), eq(7L), eq(List.of("att-2"))))
                .thenReturn(List.of(ContentBlock.imageRef("att-2", "image/png", "x.png")));
        when(chatAttachmentService.materializeForProvider(eq("sess-vision-ok"), any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(sessionService.getSession("sess-vision-ok")).thenReturn(sess);
        when(agentService.getAgent(100L)).thenReturn(agent);
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getSessionMessages("sess-vision-ok")).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages("sess-vision-ok")).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory("sess-vision-ok")).thenReturn(new ArrayList<>());

        LoopResult result = new LoopResult();
        result.setMessages(new ArrayList<>());
        result.setToolCalls(new ArrayList<>());
        when(agentLoopEngine.run(any(AgentDefinition.class), anyString(),
                any(Message.class),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class)))
                .thenReturn(result);

        chatService.chatAsync("sess-vision-ok", "describe", 7L, List.of("att-2"));

        verify(agentLoopEngine).run(any(AgentDefinition.class), anyString(),
                any(Message.class),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class));
    }

    @Test
    @DisplayName("text-only turn skips capability check entirely (no multimodal blocks)")
    void textOnlyTurn_skipsCapabilityCheck() {
        // Empty visionModels — would refuse ANY multimodal turn — but a text-only turn
        // must pass through without consulting the allowlist at all.
        LlmProperties props = new LlmProperties();
        props.setProviders(new LinkedHashMap<>());

        chatService = buildChatService(props);

        SessionEntity sess = newSession("sess-text", null);
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4");
        AgentDefinition def = newDef("claude:claude-sonnet-4");
        when(sessionService.getSession("sess-text")).thenReturn(sess);
        when(agentService.getAgent(100L)).thenReturn(agent);
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getSessionMessages("sess-text")).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages("sess-text")).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory("sess-text")).thenReturn(new ArrayList<>());
        lenient().when(chatAttachmentService.materializeForProvider(eq("sess-text"), any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        LoopResult result = new LoopResult();
        result.setMessages(new ArrayList<>());
        result.setToolCalls(new ArrayList<>());
        when(agentLoopEngine.run(any(AgentDefinition.class), anyString(),
                any(Message.class),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class)))
                .thenReturn(result);

        chatService.chatAsync("sess-text", "hello", 7L);

        verify(agentLoopEngine).run(any(AgentDefinition.class), anyString(),
                any(Message.class),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class));
    }

    @Test
    void artifactWorkspaceUsesDedicatedContextFieldWithoutReplacingWorkingDirectory() {
        LlmProperties props = new LlmProperties();
        props.setProviders(new LinkedHashMap<>());
        ArtifactWorkspaceService workspaceService = mock(ArtifactWorkspaceService.class);
        Path artifactDirectory = Path.of("/artifact-output/session-1/trace-1");
        when(workspaceService.create(eq(7L), eq("sess-artifact"), anyString()))
                .thenReturn(artifactDirectory);
        when(workspaceService.promptInstruction(artifactDirectory)).thenReturn("artifact prompt");
        chatService = buildChatService(props, workspaceService);

        SessionEntity session = newSession("sess-artifact", null);
        AgentEntity agent = newAgent(100L, "text-model");
        AgentDefinition definition = newDef("text-model");
        when(sessionService.getSession("sess-artifact")).thenReturn(session);
        when(agentService.getAgent(100L)).thenReturn(agent);
        when(agentService.toAgentDefinition(agent)).thenReturn(definition);
        when(sessionService.getSessionMessages("sess-artifact")).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages("sess-artifact")).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory("sess-artifact")).thenReturn(new ArrayList<>());
        LoopResult result = new LoopResult();
        result.setMessages(new ArrayList<>());
        result.setToolCalls(new ArrayList<>());
        when(agentLoopEngine.run(any(), anyString(), any(), anyList(), anyString(), anyLong(), any()))
                .thenReturn(result);

        chatService.chatAsync("sess-artifact", "create report", 7L);

        ArgumentCaptor<com.skillforge.core.engine.LoopContext> contextCaptor =
                ArgumentCaptor.forClass(com.skillforge.core.engine.LoopContext.class);
        verify(agentLoopEngine).run(any(), anyString(), any(), anyList(), anyString(), anyLong(),
                contextCaptor.capture());
        assertThat(contextCaptor.getValue().getWorkingDirectory()).isNull();
        assertThat(contextCaptor.getValue().getArtifactOutputDirectory())
                .isEqualTo(artifactDirectory.toString());
    }

    // -------------------------- helpers --------------------------

    private SessionEntity newSession(String id, String runtimeOverride) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(100L);
        s.setMessageCount(0);
        s.setLastUserMessageAt(java.time.Instant.now());
        s.setRuntimeStatus("idle");
        s.setMessagesJson("[]");
        s.setRuntimeModelOverride(runtimeOverride);
        return s;
    }

    private AgentEntity newAgent(Long id, String modelId) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName("test-agent");
        a.setModelId(modelId);
        return a;
    }

    private AgentDefinition newDef(String modelId) {
        AgentDefinition def = new AgentDefinition();
        def.setId("100");
        def.setName("test-agent");
        def.setModelId(modelId);
        return def;
    }

    private static class NoopDispatcher implements LifecycleHookDispatcher {
        @Override
        public boolean dispatch(HookEvent event, Map<String, Object> input,
                                AgentDefinition agentDef, String sessionId, Long userId) {
            return true;
        }
        @Override public boolean fireSessionStart(AgentDefinition d, String s, Long u) { return true; }
        @Override
        public boolean fireUserPromptSubmit(AgentDefinition d, String s, Long u, String m, int c) {
            return true;
        }
        @Override
        public void firePostToolUse(AgentDefinition d, String s, Long u, String name,
                                    Map<String, Object> in,
                                    com.skillforge.core.skill.SkillResult r, long ms) {}
        @Override
        public void fireStop(AgentDefinition d, String s, Long u, int loops,
                             long it, long ot, String response) {}
        @Override
        public void fireSessionEnd(AgentDefinition d, String s, Long u, int mc, String reason) {}
    }
}
