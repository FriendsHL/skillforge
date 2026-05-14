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
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.when;

/**
 * MULTIMODAL-MVP Task #3: effective-model resolution priority for multimodal turns.
 *
 * <p>Priority (PRD Ratify #7 / tech-design §"effective model"):
 * <ol>
 *   <li>{@code agent.multimodalModelId} (when this turn carries multimodal blocks
 *       AND the field is non-blank)</li>
 *   <li>{@code session.runtimeModelOverride} ({@code /model})</li>
 *   <li>{@code agent.modelId}</li>
 * </ol>
 *
 * <p>The check examines the user message {@code content} block list for
 * {@code image_ref} / {@code pdf_ref} / {@code image} blocks. ChatService must
 * NOT mutate the persisted {@code agentEntity} — it mutates the per-turn
 * {@link AgentDefinition} copy returned by {@code toAgentDefinition}. This is
 * what guarantees subsequent text-only turns automatically fall back to
 * {@code agent.modelId} without any explicit revert logic.
 */
@DisplayName("ChatService — multimodal effective model (MULTIMODAL-MVP Task #3)")
class ChatServiceMultimodalEffectiveModelTest {

    private AgentService agentService;
    private SessionService sessionService;
    private SkillRegistry skillRegistry;
    private AgentLoopEngine agentLoopEngine;
    private ChatAttachmentService chatAttachmentService;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = buildChatService();
    }

    private ChatService buildChatService() {
        agentService = mock(AgentService.class);
        sessionService = mock(SessionService.class);
        skillRegistry = mock(SkillRegistry.class);
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

        // r2 W7: ChatService now requires non-null LlmProperties on any multimodal
        // turn (otherwise IllegalStateException — see W7 guard). Wire an inclusive
        // visionModels allowlist so the priority tests in this file don't crash
        // on the capability check; the dedicated `ChatServiceMultimodalCapabilityCheckTest`
        // covers the supports / does-not-support branches.
        LlmProperties llmProperties = new LlmProperties();
        LlmProperties.ProviderConfig provider = new LlmProperties.ProviderConfig();
        provider.setType("openai");
        provider.setVisionModels(List.of(
                "mimo-v2-omni",                  // matches multimodalModelId in these tests
                "openai:gpt-4o",                 // /model fallback target in mm-unset case
                "claude:claude-sonnet-4"));     // never actually used on a mm turn here
        Map<String, LlmProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("test", provider);
        llmProperties.setProviders(providers);

        return new ChatService(agentService, sessionService, skillRegistry,
                agentLoopEngine, modelUsageRepository, broadcaster, sync,
                sessionTitleService, subAgentRegistry, cancellationRegistry, compactionService,
                null, null, new ObjectMapper(), null,
                new NoopDispatcher(),
                new SessionConfirmCache(), new PendingConfirmationRegistry(),
                sid -> sid, mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null /* reminderBuilder — keep userMsg as plain text block */,
                chatAttachmentService,
                llmProperties);
    }

    @Test
    @DisplayName("multimodal turn → effective model is agent.multimodalModelId")
    void multimodalTurn_usesMultimodalModelId() {
        SessionEntity sess = newSession("sess-mm", null /* no /model override */);
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4", "mimo-v2-omni");
        AgentDefinition def = newDef("claude:claude-sonnet-4");
        // ReferenceBlocks: caller asked for one attachmentId → image_ref block.
        when(chatAttachmentService.referenceBlocks(eq("sess-mm"), eq(7L), eq(List.of("att-1"))))
                .thenReturn(List.of(ContentBlock.imageRef("att-1", "image/png", "screen.png")));
        // materializeForProvider: pass-through (not exercised by the model-switch logic).
        when(chatAttachmentService.materializeForProvider(eq("sess-mm"), any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-mm", "look", 7L, List.of("att-1"));

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getModelId()).isEqualTo("mimo-v2-omni");
    }

    @Test
    @DisplayName("text-only turn after multimodal turn reverts to agent.modelId — no persistent state change")
    void textOnlyTurn_revertsToAgentModelId() {
        // Demonstrates the design: `agentDef` is freshly built per turn from
        // `agentService.toAgentDefinition(agentEntity)`, so a multimodal switch
        // on one turn cannot leak into the next turn. Verified by a single
        // text-only chatAsync call where multimodalModelId is configured but no
        // blocks are present — model must still come from agent.modelId.
        SessionEntity sess = newSession("sess-text", null);
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4", "mimo-v2-omni");
        AgentDefinition def = newDef("claude:claude-sonnet-4");
        wireBaseMocks(sess, agent, def);

        // No attachmentIds → no multimodal blocks.
        chatService.chatAsync("sess-text", "plain text", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getModelId()).isEqualTo("claude:claude-sonnet-4");
    }

    @Test
    @DisplayName("/model override + multimodal blocks → multimodalModelId still wins (Ratify #7)")
    void multimodalBeatsRuntimeOverride() {
        SessionEntity sess = newSession("sess-both", "openai:gpt-4o");
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4", "mimo-v2-omni");
        AgentDefinition def = newDef("claude:claude-sonnet-4");
        when(chatAttachmentService.referenceBlocks(eq("sess-both"), eq(7L), eq(List.of("att-2"))))
                .thenReturn(List.of(ContentBlock.imageRef("att-2", "image/png", "x.png")));
        when(chatAttachmentService.materializeForProvider(eq("sess-both"), any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-both", "look here", 7L, List.of("att-2"));

        AgentDefinition captured = captureAgentDef();
        // Multimodal switch is a structural decision and beats user's /model.
        assertThat(captured.getModelId()).isEqualTo("mimo-v2-omni");
    }

    @Test
    @DisplayName("multimodalModelId configured but no multimodal blocks → uses agent.modelId")
    void multimodalConfiguredButNoBlocks_usesAgentModelId() {
        SessionEntity sess = newSession("sess-noblocks", null);
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4", "mimo-v2-omni");
        AgentDefinition def = newDef("claude:claude-sonnet-4");
        wireBaseMocks(sess, agent, def);

        // No attachmentIds — multimodal switch must NOT trigger.
        chatService.chatAsync("sess-noblocks", "no images", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getModelId()).isEqualTo("claude:claude-sonnet-4");
    }

    @Test
    @DisplayName("B2 regression: engine.run receives image_ref form (NOT materialized image) — persistence shape unchanged")
    void engineReceivesImageRefNotImage() {
        // The Iron Law: providerUserMsg with base64 image blocks must NEVER reach
        // agentLoopEngine.run as the 3rd arg, because that becomes part of
        // result.getMessages() and triggers updateSessionMessages divergence guard.
        // ChatService r2 fix: pass userMsgWithReminder (image_ref form), wire
        // MessageMaterializer on LoopContext for engine-boundary expansion instead.
        SessionEntity sess = newSession("sess-b2", null);
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4", "mimo-v2-omni");
        AgentDefinition def = newDef("claude:claude-sonnet-4");
        when(chatAttachmentService.referenceBlocks(eq("sess-b2"), eq(7L), eq(List.of("att-b2"))))
                .thenReturn(List.of(ContentBlock.imageRef("att-b2", "image/png", "screen.png")));
        // CRITICAL: this stub MUST NOT be exercised on the pre-engine path anymore.
        // r2 (B2 fix) confines materialization to engine-boundary via LoopContext.
        // Leaving the stub with `lenient()` so an accidental pre-engine call would
        // still resolve but we can `verify(..., never())` on it.
        org.mockito.Mockito.lenient()
                .when(chatAttachmentService.materializeForProvider(eq("sess-b2"), any(Message.class)))
                .thenAnswer(inv -> {
                    Message in = inv.getArgument(1);
                    Message expanded = new Message();
                    expanded.setRole(in.getRole());
                    expanded.setContent(List.of(ContentBlock.image("image/png", "B64")));
                    return expanded;
                });
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-b2", "look", 7L, List.of("att-b2"));

        // Capture the 3rd-arg Message handed to engine.run.
        org.mockito.ArgumentCaptor<Message> msgCap = org.mockito.ArgumentCaptor.forClass(Message.class);
        org.mockito.Mockito.verify(agentLoopEngine).run(
                any(AgentDefinition.class), anyString(),
                msgCap.capture(),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class));
        Message engineInput = msgCap.getValue();
        assertThat(engineInput).isNotNull();
        assertThat(engineInput.getContent()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<ContentBlock> blocks = (List<ContentBlock>) engineInput.getContent();
        // Iron Law: at least one image_ref block reaches engine, NO image (materialized) block.
        boolean hasImageRef = blocks.stream().anyMatch(b -> "image_ref".equals(b.getType()));
        boolean hasMaterializedImage = blocks.stream().anyMatch(b -> "image".equals(b.getType()));
        assertThat(hasImageRef).as("engine should receive image_ref form").isTrue();
        assertThat(hasMaterializedImage)
                .as("engine must NOT receive materialized image (would corrupt DB via divergence guard)")
                .isFalse();
        // Pre-engine materializeForProvider call must be gone (engine-boundary path now).
        org.mockito.Mockito.verify(chatAttachmentService, org.mockito.Mockito.never())
                .materializeForProvider(eq("sess-b2"), any(Message.class));
    }

    @Test
    @DisplayName("multimodalModelId is null but blocks present → fall back to runtime override / agent.modelId")
    void multimodalUnsetWithBlocks_fallsBackThroughChain() {
        // Edge case: this path is normally blocked by the upload-endpoint 409 gate,
        // but if the gate is bypassed (e.g. attachment uploaded under a different
        // session config that was later cleared), the in-flight chatAsync should
        // fall back gracefully through the priority chain, not NPE.
        SessionEntity sess = newSession("sess-cleared", "openai:gpt-4o");
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4", null);
        AgentDefinition def = newDef("claude:claude-sonnet-4");
        when(chatAttachmentService.referenceBlocks(eq("sess-cleared"), eq(7L), eq(List.of("att-3"))))
                .thenReturn(List.of(ContentBlock.imageRef("att-3", "image/png", "y.png")));
        when(chatAttachmentService.materializeForProvider(eq("sess-cleared"), any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-cleared", "x", 7L, List.of("att-3"));

        AgentDefinition captured = captureAgentDef();
        // multimodalModelId null → drop to /model runtime override.
        assertThat(captured.getModelId()).isEqualTo("openai:gpt-4o");
    }

    // -------------------------- helpers --------------------------

    private void wireBaseMocks(SessionEntity sess, AgentEntity agent, AgentDefinition def) {
        when(sessionService.getSession(sess.getId())).thenReturn(sess);
        when(agentService.getAgent(100L)).thenReturn(agent);
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getSessionMessages(sess.getId())).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages(sess.getId())).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory(sess.getId())).thenReturn(new ArrayList<>());
        // Default: materializeForProvider is a pass-through unless a test overrides it.
        // Without this default, text-only chatAsync calls would receive null from the mock
        // and propagate null into agentLoopEngine.run, hiding the effective-model assertion
        // under a NullPointerException-shaped failure.
        lenient().when(chatAttachmentService.materializeForProvider(anyString(), any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        LoopResult result = new LoopResult();
        result.setMessages(new ArrayList<>());
        result.setToolCalls(new ArrayList<>());
        when(agentLoopEngine.run(any(AgentDefinition.class), anyString(),
                any(com.skillforge.core.model.Message.class),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class)))
                .thenReturn(result);
    }

    private AgentDefinition captureAgentDef() {
        ArgumentCaptor<AgentDefinition> cap = ArgumentCaptor.forClass(AgentDefinition.class);
        org.mockito.Mockito.verify(agentLoopEngine).run(
                cap.capture(), anyString(),
                any(com.skillforge.core.model.Message.class),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class));
        return cap.getValue();
    }

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

    private AgentEntity newAgent(Long id, String modelId, String multimodalModelId) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName("test-agent");
        a.setModelId(modelId);
        a.setMultimodalModelId(multimodalModelId);
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
