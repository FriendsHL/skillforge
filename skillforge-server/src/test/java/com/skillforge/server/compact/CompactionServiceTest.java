package com.skillforge.server.compact;

import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.CompactionEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CompactionEventRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.SessionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompactionServiceTest {

    private SessionRepository sessionRepository;
    private CompactionEventRepository eventRepository;
    private SessionService sessionService;
    private LlmProviderFactory llmProviderFactory;
    private LlmProperties llmProperties;
    private ChatEventBroadcaster broadcaster;
    private AgentRepository agentRepository;
    private CompactionService service;

    // in-memory storage
    private final Map<String, SessionEntity> sessionStore = new HashMap<>();
    private final Map<String, List<Message>> messagesStore = new HashMap<>();
    private final Map<Long, CompactionEventEntity> eventStore = new HashMap<>();
    private final AtomicLong eventIdSeq = new AtomicLong(0);

    private final LlmProvider mockProvider = new LlmProvider() {
        @Override public String getName() { return "mock"; }
        @Override public LlmResponse chat(LlmRequest request) {
            LlmResponse r = new LlmResponse();
            r.setContent("SUMMARY");
            return r;
        }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
    };

    @BeforeEach
    void setUp() {
        sessionStore.clear();
        messagesStore.clear();
        eventStore.clear();
        eventIdSeq.set(0);

        sessionRepository = mock(SessionRepository.class);
        eventRepository = mock(CompactionEventRepository.class);
        sessionService = mock(SessionService.class);
        llmProviderFactory = mock(LlmProviderFactory.class);
        llmProperties = mock(LlmProperties.class);
        broadcaster = mock(ChatEventBroadcaster.class);
        agentRepository = mock(AgentRepository.class);

        when(llmProperties.getDefaultProvider()).thenReturn("mock");
        when(llmProperties.getProviders()).thenReturn(new HashMap<>());
        when(llmProviderFactory.getProvider("mock")).thenReturn(mockProvider);

        when(sessionRepository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(sessionStore.get(inv.getArgument(0))));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity s = inv.getArgument(0);
            sessionStore.put(s.getId(), s);
            return s;
        });
        when(sessionService.getSessionMessages(anyString())).thenAnswer(inv ->
                new ArrayList<>(messagesStore.getOrDefault(inv.getArgument(0), new ArrayList<>())));
        org.mockito.Mockito.doAnswer(inv -> {
            String id = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            List<Message> msgs = (List<Message>) inv.getArgument(1);
            messagesStore.put(id, new ArrayList<>(msgs));
            SessionEntity s = sessionStore.get(id);
            if (s != null) s.setMessageCount(msgs.size());
            return null;
        }).when(sessionService).saveSessionMessages(anyString(), any());

        when(eventRepository.save(any(CompactionEventEntity.class))).thenAnswer(inv -> {
            CompactionEventEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(eventIdSeq.incrementAndGet());
            eventStore.put(e.getId(), e);
            return e;
        });

        service = new CompactionService(sessionRepository, eventRepository, sessionService,
                new LightCompactStrategy(), new FullCompactStrategy(),
                llmProviderFactory, llmProperties, broadcaster,
                null /* transactionManager — null OK in unit tests, runInTransaction runs directly */);
        service.setAgentRepository(agentRepository);
    }

    private SessionEntity seedSession(String id, int msgCount, int lastCompactAt, String runtimeStatus) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(1L);
        s.setMessageCount(msgCount);
        s.setLastCompactedAtMessageCount(lastCompactAt);
        s.setRuntimeStatus(runtimeStatus);
        sessionStore.put(id, s);
        return s;
    }

    /**
     * Produce 30 messages with a large tool_result so a light compact actually reclaims something.
     */
    private void seedMessages(String id) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) msgs.add(Message.user("front filler " + i));
        // big tool_result pair
        Message tu = new Message();
        tu.setRole(Message.Role.ASSISTANT);
        tu.setContent(List.of(ContentBlock.toolUse("t1", "Bash", Map.of("cmd", "cat huge"))));
        msgs.add(tu);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) big.append("l").append(i).append("\n");
        Message tr = new Message();
        tr.setRole(Message.Role.USER);
        tr.setContent(List.of(ContentBlock.toolResult("t1", big.toString(), false)));
        msgs.add(tr);
        for (int i = 0; i < 18; i++) msgs.add(Message.user("tail " + i));
        messagesStore.put(id, msgs);
    }

    // ============= tests =============

    @Test
    void user_manual_bypasses_idempotency_guard() {
        // session with lastCompactedAtMessageCount == messageCount (gap=0)
        seedSession("s1", 30, 30, "idle");
        seedMessages("s1");

        // user-manual full should proceed
        CompactionEventEntity event = service.compact("s1", "full", "user-manual", "I want it now");
        assertThat(event).isNotNull();
        assertThat(event.getSource()).isEqualTo("user-manual");
        assertThat(event.getLevel()).isEqualTo("full");

        // now the session has fresh lastCompactedAtMessageCount.
        // an agent-tool full request should be guarded (gap < 5)
        seedMessages("s1"); // re-seed messages so there's something to compact
        SessionEntity fresh = sessionStore.get("s1");
        fresh.setLastCompactedAtMessageCount(fresh.getMessageCount());
        CompactionEventEntity e2 = service.compact("s1", "full", "agent-tool", "too much");
        assertThat(e2).isNull();
    }

    @Test
    void a1_light_bypasses_idempotency_guard_and_blocks_subsequent_a2_full() {
        // Same gap=0 setup but call light via agent-tool twice, both should proceed
        seedSession("sA1", 30, 30, "idle");
        seedMessages("sA1");
        CompactionEventEntity e1 = service.compact("sA1", "light", "agent-tool", "first light");
        assertThat(e1).isNotNull();
        assertThat(e1.getLevel()).isEqualTo("light");

        // second A1 light within < 5 msg gap — should still pass
        seedMessages("sA1"); // re-seed to give strategy something to reclaim
        SessionEntity mid = sessionStore.get("sA1");
        mid.setLastCompactedAtMessageCount(mid.getMessageCount());
        CompactionEventEntity e2 = service.compact("sA1", "light", "agent-tool", "second light");
        assertThat(e2).isNotNull();

        // Now an A2 full within < 5 msg gap — must be BLOCKED by idempotency
        CompactionEventEntity e3 = service.compact("sA1", "full", "agent-tool", "now full");
        assertThat(e3).isNull();
    }

    @Test
    void user_manual_rejects_when_running() {
        seedSession("s2", 30, 0, "running");
        seedMessages("s2");
        assertThatThrownBy(() -> service.compact("s2", "full", "user-manual", "try"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("running");
    }

    @Test
    void records_event_with_agent_tool_source() {
        seedSession("s3", 30, 0, "idle");
        seedMessages("s3");
        CompactionEventEntity event = service.compact("s3", "light", "agent-tool", "llm says compact");
        assertThat(event).isNotNull();
        assertThat(event.getSource()).isEqualTo("agent-tool");
        assertThat(event.getLevel()).isEqualTo("light");
    }

    @Test
    void records_event_with_engine_soft_source() {
        seedSession("s4", 30, 0, "idle");
        seedMessages("s4");
        CompactionEventEntity event = service.compact("s4", "light", "engine-soft", "ratio > 0.4");
        assertThat(event).isNotNull();
        assertThat(event.getSource()).isEqualTo("engine-soft");
    }

    @Test
    void records_event_with_engine_hard_source() {
        seedSession("s5", 30, 0, "idle");
        seedMessages("s5");
        CompactionEventEntity event = service.compact("s5", "full", "engine-hard", "ratio > 0.8");
        assertThat(event).isNotNull();
        assertThat(event.getSource()).isEqualTo("engine-hard");
        assertThat(event.getLevel()).isEqualTo("full");
    }

    @Test
    void records_event_with_engine_gap_source() {
        seedSession("s6", 30, 0, "idle");
        seedMessages("s6");
        CompactionEventEntity event = service.compact("s6", "light", "engine-gap", "gap=13h");
        assertThat(event).isNotNull();
        assertThat(event.getSource()).isEqualTo("engine-gap");
    }

    @Test
    void records_event_with_user_manual_source() {
        seedSession("s7", 30, 30, "idle");
        seedMessages("s7");
        CompactionEventEntity event = service.compact("s7", "full", "user-manual", "click");
        assertThat(event).isNotNull();
        assertThat(event.getSource()).isEqualTo("user-manual");
    }

    /**
     * End-to-end: after a first successful compact, lastCompactedAtMessageCount should
     * be updated to the post-compact messageCount WITHOUT any manual test-side setup.
     */
    @Test
    void light_compact_updates_lastCompactedAtMessageCount_end_to_end() {
        // session starts with lastCompactedAtMessageCount = 0 and real gap big enough
        seedSession("sE", 30, 0, "idle");
        seedMessages("sE");

        CompactionEventEntity event = service.compact("sE", "light", "engine-soft", "first");
        assertThat(event).isNotNull();

        SessionEntity fresh = sessionStore.get("sE");
        // Post-compact: lastCompactedAtMessageCount should equal the new messageCount exactly
        assertThat(fresh.getLastCompactedAtMessageCount()).isEqualTo(fresh.getMessageCount());
        // And lastCompactedAt should have been written
        assertThat(fresh.getLastCompactedAt()).isNotNull();
        // Light counter incremented
        assertThat(fresh.getLightCompactCount()).isEqualTo(1);
        // Total reclaimed > 0
        assertThat(fresh.getTotalTokensReclaimed()).isGreaterThan(0);
    }

    /**
     * Verify that a persisted CompactionEventEntity has its strategiesApplied column populated
     * with the actual rules that fired (not empty, not null, not "llm-summary" for a light compact).
     */
    @Test
    void strategies_applied_column_is_populated_correctly() {
        seedSession("sSA", 30, 0, "idle");
        seedMessages("sSA");

        CompactionEventEntity event = service.compact("sSA", "light", "engine-soft", "strat check");
        assertThat(event).isNotNull();
        assertThat(event.getStrategiesApplied()).isNotBlank();
        // The large tool output always triggers truncate-large-tool-output
        assertThat(event.getStrategiesApplied()).contains("truncate-large-tool-output");
        // It must NOT contain llm-summary because this was a light compact
        assertThat(event.getStrategiesApplied()).doesNotContain("llm-summary");
    }

    /**
     * Full compact should record strategiesApplied = "llm-summary" verbatim.
     */
    @Test
    void full_compact_records_llm_summary_as_strategy() {
        seedSession("sFS", 30, 0, "idle");
        seedMessages("sFS");
        CompactionEventEntity event = service.compact("sFS", "full", "engine-hard", "full check");
        assertThat(event).isNotNull();
        assertThat(event.getStrategiesApplied()).isEqualTo("llm-summary");
    }

    /**
     * #12: A no-op (strategy produces zero reclaim) should NOT persist an event row.
     * Use a session with trivial messages so the light strategy can't reclaim anything.
     */
    @Test
    void noop_compact_does_not_persist_junk_event() {
        seedSession("sNO", 3, 0, "idle");
        // Only 3 tiny messages: strategies all fail to find anything to reclaim
        List<Message> tiny = new ArrayList<>();
        tiny.add(Message.user("hi"));
        tiny.add(Message.assistant("hello"));
        tiny.add(Message.user("bye"));
        messagesStore.put("sNO", tiny);

        CompactionEventEntity event = service.compact("sNO", "light", "user-manual", "trivial");
        assertThat(event).isNull();
        assertThat(eventStore).isEmpty();
    }

    /**
     * #9 / #13 stripe-lock race: while a simulated chatAsync holds the lock and flips
     * runtimeStatus to "running", a concurrent C1 user-manual compact must see the
     * "running" state (not the earlier "idle") and raise IllegalStateException.
     */
    @Test
    void stripe_lock_prevents_c1_from_compacting_a_running_session() throws Exception {
        seedSession("sRACE", 30, 0, "idle");
        seedMessages("sRACE");

        // Simulate ChatService.chatAsync path: take the same lock, flip status, hold briefly.
        CountDownLatch chatHasLock = new CountDownLatch(1);
        CountDownLatch c1MayProceed = new CountDownLatch(1);
        AtomicBoolean chatFlippedRunning = new AtomicBoolean(false);

        Thread chatThread = new Thread(() -> {
            synchronized (service.lockFor("sRACE")) {
                SessionEntity s = sessionStore.get("sRACE");
                s.setRuntimeStatus("running");
                sessionStore.put("sRACE", s);
                chatFlippedRunning.set(true);
                chatHasLock.countDown();
                try {
                    c1MayProceed.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "chat-thread");
        chatThread.start();

        // Wait for chat to hold the lock and flip to running
        assertThat(chatHasLock.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(chatFlippedRunning.get()).isTrue();

        // Now let C1 start racing; it will block on the same lock until chat releases
        AtomicBoolean c1Raised = new AtomicBoolean(false);
        Thread c1Thread = new Thread(() -> {
            try {
                service.compact("sRACE", "full", "user-manual", "racing");
            } catch (IllegalStateException e) {
                c1Raised.set(true);
            }
        }, "c1-thread");
        c1Thread.start();
        // Give c1 time to attempt lock acquisition
        Thread.sleep(100);

        // Let chat release
        c1MayProceed.countDown();
        chatThread.join(2000);
        c1Thread.join(2000);

        // C1 must have seen the running state and raised
        assertThat(c1Raised.get()).isTrue();
    }

    /**
     * #13: verify that listEvents returns stored events.
     */
    @Test
    void list_events_returns_persisted_rows() {
        seedSession("sLIST", 30, 0, "idle");
        seedMessages("sLIST");
        // Wire the eventRepository findBySessionId stub
        when(eventRepository.findBySessionIdOrderByIdDesc("sLIST")).thenAnswer(inv -> {
            List<CompactionEventEntity> out = new ArrayList<>();
            for (CompactionEventEntity e : eventStore.values()) {
                if ("sLIST".equals(e.getSessionId())) out.add(e);
            }
            return out;
        });
        service.compact("sLIST", "light", "engine-soft", "one");
        List<CompactionEventEntity> events = service.listEvents("sLIST");
        assertThat(events).isNotEmpty();
    }

    /**
     * Explicit YAML contextWindowTokens should win over the model map (step 1 > step 2).
     */
    @Test
    void resolveContextWindow_yaml_explicit_wins_over_model_map() throws Exception {
        LlmProperties.ProviderConfig pc = new LlmProperties.ProviderConfig();
        pc.setModel("claude-sonnet-4-20250514");
        pc.setContextWindowTokens(50_000);  // deliberate override — not a real value, just testing priority
        when(llmProperties.getProviders()).thenReturn(Map.of("claude", pc));
        when(llmProperties.getDefaultProvider()).thenReturn("claude");

        SessionEntity session = new SessionEntity();
        session.setId("s-yaml");
        session.setAgentId(97L);

        AgentEntity agent = new AgentEntity();
        agent.setId(97L);
        agent.setModelId("claude:claude-sonnet-4-20250514");
        when(agentRepository.findById(97L)).thenReturn(Optional.of(agent));

        java.lang.reflect.Method m = CompactionService.class.getDeclaredMethod(
                "resolveContextWindowForSession", SessionEntity.class);
        m.setAccessible(true);
        int result = (int) m.invoke(service, session);

        assertThat(result).isEqualTo(50_000);  // YAML wins, not 200000 from model map
    }

    /**
     * When YAML contextWindowTokens is unset, the known-model map should resolve
     * claude-sonnet-4-20250514 to 200_000.
     */
    @Test
    void resolveContextWindow_uses_known_model_map_when_yaml_unset() throws Exception {
        LlmProperties.ProviderConfig pc = new LlmProperties.ProviderConfig();
        pc.setModel("claude-sonnet-4-20250514");
        // contextWindowTokens NOT set → null
        when(llmProperties.getProviders()).thenReturn(Map.of("claude", pc));
        when(llmProperties.getDefaultProvider()).thenReturn("claude");

        SessionEntity session = new SessionEntity();
        session.setId("s-claude");
        session.setAgentId(99L);

        AgentEntity agent = new AgentEntity();
        agent.setId(99L);
        agent.setModelId("claude:claude-sonnet-4-20250514");
        when(agentRepository.findById(99L)).thenReturn(Optional.of(agent));

        java.lang.reflect.Method m = CompactionService.class.getDeclaredMethod(
                "resolveContextWindowForSession", SessionEntity.class);
        m.setAccessible(true);
        int result = (int) m.invoke(service, session);

        assertThat(result).isEqualTo(200_000);
    }

    /**
     * Unknown model names should fall through the known-model map to the 32000 constant.
     */
    @Test
    void resolveContextWindow_falls_back_to_32000_for_unknown_model() throws Exception {
        LlmProperties.ProviderConfig pc = new LlmProperties.ProviderConfig();
        pc.setModel("some-custom-llm-v1");
        when(llmProperties.getProviders()).thenReturn(Map.of("custom", pc));
        when(llmProperties.getDefaultProvider()).thenReturn("custom");

        SessionEntity session = new SessionEntity();
        session.setId("s-unknown");
        session.setAgentId(98L);

        AgentEntity agent = new AgentEntity();
        agent.setId(98L);
        agent.setModelId("custom:some-custom-llm-v1");
        when(agentRepository.findById(98L)).thenReturn(Optional.of(agent));

        java.lang.reflect.Method m = CompactionService.class.getDeclaredMethod(
                "resolveContextWindowForSession", SessionEntity.class);
        m.setAccessible(true);
        int result = (int) m.invoke(service, session);

        assertThat(result).isEqualTo(ModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS);
    }

    /**
     * P1-2: if a full compact is already in-flight for a session (Phase 2 LLM call in progress),
     * a concurrent second full compact request must be deduped and return null (no-op).
     */
    @Test
    void fullCompact_inFlight_deduplication() throws Exception {
        seedSession("sIF", 30, 0, "idle");
        seedMessages("sIF");

        // A slow LLM provider: blocks Phase 2 until we release the latch.
        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch llmRelease = new CountDownLatch(1);
        LlmProvider slowProvider = new LlmProvider() {
            @Override public String getName() { return "slow"; }
            @Override public LlmResponse chat(LlmRequest request) {
                llmStarted.countDown();
                try { llmRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                LlmResponse r = new LlmResponse();
                r.setContent("SLOW SUMMARY");
                return r;
            }
            @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) {}
        };
        when(llmProviderFactory.getProvider("mock")).thenReturn(slowProvider);

        // Thread 1: first full compact — will block in Phase 2
        AtomicBoolean t1Done = new AtomicBoolean(false);
        AtomicBoolean t1NonNull = new AtomicBoolean(false);
        Thread t1 = new Thread(() -> {
            CompactionEventEntity e = service.compact("sIF", "full", "engine-hard", "first");
            t1NonNull.set(e != null);
            t1Done.set(true);
        }, "full-compact-t1");
        t1.start();

        // Wait for Phase 2 to start (LLM call in progress, stripe lock released)
        assertThat(llmStarted.await(3, TimeUnit.SECONDS)).isTrue();

        // Thread 2: second full compact while t1 is in Phase 2 — must be deduped
        CompactionEventEntity e2 = service.compact("sIF", "full", "engine-hard", "second");
        assertThat(e2).isNull(); // deduped

        // Let t1 finish
        llmRelease.countDown();
        t1.join(5000);
        assertThat(t1Done.get()).isTrue();
        assertThat(t1NonNull.get()).isTrue(); // t1 should have succeeded
    }

    /**
     * Reference use of AtomicInteger to satisfy the import (keeps the test file clean).
     */
    @SuppressWarnings("unused")
    private AtomicInteger keepImport() { return new AtomicInteger(0); }
}
