package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
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
 * ChatService model-override path coverage (P10 INV-4).
 *
 * <p>The override is applied <em>inline</em> in {@code runLoop} after building
 * {@code agentDef}: when {@code session.runtimeModelOverride != null}, the
 * AgentDefinition's modelId is replaced before being handed to
 * {@link AgentLoopEngine#run}. We assert this by capturing the AgentDefinition
 * passed to {@code agentLoopEngine.run} and checking {@code getModelId()}.
 *
 * <p>The chat-loop executor is replaced with a synchronous one so the
 * {@code chatAsync → runLoop → engine.run} chain completes in test thread.
 */
@DisplayName("ChatService — runtime model override (P10 INV-4)")
class ChatServiceModelOverrideTest {

    private AgentService agentService;
    private SessionService sessionService;
    private SkillRegistry skillRegistry;
    private AgentLoopEngine agentLoopEngine;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = buildChatService(null /* reminderBuilder — default tests don't exercise it */);
    }

    /**
     * Build a fresh {@link ChatService} with a synchronous chat-loop executor and the
     * provided {@link com.skillforge.core.reminder.ReminderBuilder}. Class-field mocks
     * ({@code agentService}, {@code sessionService}, {@code skillRegistry},
     * {@code agentLoopEngine}) are re-assigned so test wiring + verification target the
     * same instances embedded in the returned ChatService.
     */
    private ChatService buildChatService(com.skillforge.core.reminder.ReminderBuilder reminderBuilder) {
        agentService = mock(AgentService.class);
        sessionService = mock(SessionService.class);
        skillRegistry = mock(SkillRegistry.class);
        agentLoopEngine = mock(AgentLoopEngine.class);
        ModelUsageRepository modelUsageRepository = mock(ModelUsageRepository.class);
        ChatEventBroadcaster broadcaster = mock(ChatEventBroadcaster.class);
        SessionTitleService sessionTitleService = mock(SessionTitleService.class);
        SubAgentRegistry subAgentRegistry = mock(SubAgentRegistry.class);
        CancellationRegistry cancellationRegistry = mock(CancellationRegistry.class);
        CompactionService compactionService = mock(CompactionService.class);

        // Synchronous executor — runs runLoop on the test thread.
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
                reminderBuilder);
    }

    @Test
    @DisplayName("session.runtimeModelOverride != null → AgentDefinition.modelId is overridden")
    void overridePresent_overridesAgentDefModelId() {
        SessionEntity sess = newSession("sess-1", "openai:gpt-4o");
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4-20250514");
        AgentDefinition def = newDef("claude:claude-sonnet-4-20250514");

        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-1", "hello", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getModelId()).isEqualTo("openai:gpt-4o");
    }

    @Test
    @DisplayName("session.runtimeModelOverride is null → falls back to agent.modelId (no change)")
    void overrideAbsent_fallsBackToAgentModelId() {
        SessionEntity sess = newSession("sess-2", null);
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4-20250514");
        AgentDefinition def = newDef("claude:claude-sonnet-4-20250514");

        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-2", "hello", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getModelId()).isEqualTo("claude:claude-sonnet-4-20250514");
    }

    @Test
    @DisplayName("blank override is treated as 'no override' — does not zero out modelId")
    void blankOverride_isIgnored() {
        SessionEntity sess = newSession("sess-3", "  ");
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4-20250514");
        AgentDefinition def = newDef("claude:claude-sonnet-4-20250514");

        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-3", "hello", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getModelId()).isEqualTo("claude:claude-sonnet-4-20250514");
    }

    /**
     * Q2 reminder fix regression (2026-05-10): when {@link com.skillforge.core.reminder.ReminderBuilder}
     * emits a non-empty {@code <system-reminder>} block, ChatService must hand the engine the
     * <em>same</em> two-block {@code Message} it persisted (reminder block first, raw user text
     * second). Without the 7-arg engine.run wiring the engine would rebuild
     * {@code Message.user(userMessage)} from the raw string and silently drop the reminder.
     */
    @Test
    @DisplayName("reminder emits → 7-arg engine.run receives 2-block Message [reminder, raw user text]")
    void reminderEnabled_passesArrayShapeMessageToEngineRun() {
        // Build a real ReminderBuilder with a deterministic single source that always emits.
        com.skillforge.core.reminder.ReminderSource fixed = new com.skillforge.core.reminder.ReminderSource() {
            @Override public String getName() { return "test-source"; }
            @Override public boolean shouldEmit(com.skillforge.core.reminder.ReminderContext ctx) {
                return true;
            }
            @Override public com.skillforge.core.reminder.ReminderEntry emit(
                    com.skillforge.core.reminder.ReminderContext ctx) {
                return new com.skillforge.core.reminder.ReminderEntry("Context: 80% used (W/T tokens)", 5);
            }
        };
        com.skillforge.core.reminder.ReminderBuilder builder =
                new com.skillforge.core.reminder.ReminderBuilder(List.of(fixed), 5_000, true);
        chatService = buildChatService(builder);

        SessionEntity sess = newSession("sess-rmd", null);
        AgentEntity agent = newAgent(100L, "claude:claude-sonnet-4-20250514");
        AgentDefinition def = newDef("claude:claude-sonnet-4-20250514");
        // ReminderContext.maxTokens reads agentDef config; ensure non-null systemPrompt so
        // buildUserMessageWithReminder doesn't NPE on the path through to ReminderBuilder.build().
        def.setSystemPrompt("hi");
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-rmd", "hello world", 7L);

        // Capture the Message that ChatService passed as the 7-arg userMessageBlock.
        ArgumentCaptor<com.skillforge.core.model.Message> msgCaptor =
                ArgumentCaptor.forClass(com.skillforge.core.model.Message.class);
        org.mockito.Mockito.verify(agentLoopEngine).run(
                any(AgentDefinition.class), anyString(),
                msgCaptor.capture(),
                anyList(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class));

        com.skillforge.core.model.Message captured = msgCaptor.getValue();
        assertThat(captured).isNotNull();
        assertThat(captured.getContent()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<com.skillforge.core.model.ContentBlock> blocks =
                (List<com.skillforge.core.model.ContentBlock>) captured.getContent();
        assertThat(blocks).hasSize(2);
        // Block 0: reminder, text starts with the literal `<system-reminder>` tag.
        assertThat(blocks.get(0).getType()).isEqualTo("text");
        assertThat(blocks.get(0).getText()).startsWith("<system-reminder>");
        assertThat(blocks.get(0).getText()).contains("Context: 80% used");
        // Block 1: raw user input, untouched.
        assertThat(blocks.get(1).getType()).isEqualTo("text");
        assertThat(blocks.get(1).getText()).isEqualTo("hello world");
    }

    // -------------------------- helpers --------------------------

    private void wireBaseMocks(SessionEntity sess, AgentEntity agent, AgentDefinition def) {
        when(sessionService.getSession(sess.getId())).thenReturn(sess);
        when(agentService.getAgent(100L)).thenReturn(agent);
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getSessionMessages(sess.getId())).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages(sess.getId())).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory(sess.getId())).thenReturn(new ArrayList<>());

        // Engine returns a minimal LoopResult so the rest of runLoop's tail completes.
        LoopResult result = new LoopResult();
        result.setMessages(new ArrayList<>());
        result.setToolCalls(new ArrayList<>());
        // Q2 reminder fix (2026-05-10): engine.run now has a 7-arg overload accepting
        // a pre-constructed Message userMessageBlock. The legacy 6-arg overload still
        // delegates with null block, but ChatService now calls the 7-arg form directly.
        // Mockito needs typed matchers (`any(Message.class)`, `anyList()`) to
        // disambiguate from the 5/6-arg overloads — `any()` alone is `Object` and the
        // compiler can't pick the right method.
        // NULL-MATCHING NOTE: `any(Message.class)` is equivalent to `isA(Message.class)`
        // and does NOT match a null argument. This is currently safe because
        // ChatService.buildUserMessageWithReminder() always returns a non-null Message
        // (legacy path falls back to Message.user(userMessage) when reminderBuilder is
        // null or yields empty text). If a future ChatService path passes null as the
        // userMsgWithReminder argument to the 7-arg engine.run, this stub will silently
        // miss the call and the test will see "null" verify failures — switch to
        // `nullable(com.skillforge.core.model.Message.class)` here, or add a separate
        // stub branch for the null case.
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

    /**
     * Minimal hook dispatcher that lets sessions start without firing real hooks.
     */
    private static class NoopDispatcher implements LifecycleHookDispatcher {
        @Override
        public boolean dispatch(com.skillforge.core.engine.hook.HookEvent event,
                                java.util.Map<String, Object> input,
                                AgentDefinition agentDef,
                                String sessionId, Long userId) { return true; }

        @Override public boolean fireSessionStart(AgentDefinition d, String s, Long u) { return true; }
        @Override
        public boolean fireUserPromptSubmit(AgentDefinition d, String s, Long u, String m, int c) {
            return true;
        }
        @Override
        public void firePostToolUse(AgentDefinition d, String s, Long u, String name,
                                    java.util.Map<String, Object> in,
                                    com.skillforge.core.skill.SkillResult r, long ms) {}
        @Override
        public void fireStop(AgentDefinition d, String s, Long u, int loops,
                             long it, long ot, String response) {}
        @Override
        public void fireSessionEnd(AgentDefinition d, String s, Long u, int mc, String reason) {}
    }
}
