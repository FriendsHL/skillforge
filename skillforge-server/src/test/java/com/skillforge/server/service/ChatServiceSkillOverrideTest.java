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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18) — ChatService skill-override
 * path coverage (V92 + java.md footgun audit support).
 *
 * <p>Mirrors {@link ChatServiceModelOverrideTest}'s structure (P10 INV-4) for
 * the parallel runtime-override added by this requirement: when
 * {@code session.skillOverridesJson} is non-null, the runtime should use the
 * override list (parsed as {@code List<String>}) instead of
 * {@code agent.skillIds} when populating the engine's available skills.
 *
 * <p>Iron Law audit: ChatService is a core-7+1 file; the override branch is
 * ~7 lines surrounded by review-mandatory commentary. These tests pin the
 * three behaviours that the branch promises so accidental regressions are
 * caught the next time someone refactors the area:
 * <ul>
 *   <li>override == null → agent.skillIds untouched (legacy path)</li>
 *   <li>override == "[]" → agent.skillIds replaced with empty list (without_skill baseline)</li>
 *   <li>override == ["a","b"] → agent.skillIds replaced with [a,b] (with_skill override)</li>
 * </ul>
 */
@DisplayName("ChatService — runtime skill override (SKILL-CREATOR-WITH-EVAL Phase 1.1)")
class ChatServiceSkillOverrideTest {

    private AgentService agentService;
    private SessionService sessionService;
    private SkillRegistry skillRegistry;
    private AgentLoopEngine agentLoopEngine;
    private ChatService chatService;

    private ChatService buildChatService() {
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
                null /* reminderBuilder */);
    }

    @Test
    @DisplayName("session.skillOverridesJson == null → agent.skillIds untouched (legacy)")
    void overrideAbsent_legacyAgentSkillsUsed() {
        chatService = buildChatService();
        SessionEntity sess = newSession("sess-1", null);
        AgentEntity agent = newAgent(100L);
        AgentDefinition def = newDef(Arrays.asList("agent-skill-a", "agent-skill-b"));
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-1", "hi", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getSkillIds())
                .as("null override → agent.skillIds round-tripped unchanged")
                .containsExactly("agent-skill-a", "agent-skill-b");
    }

    @Test
    @DisplayName("session.skillOverridesJson == \"[]\" → agent.skillIds replaced with empty (without_skill baseline)")
    void overrideEmpty_replacedWithEmptyList() {
        chatService = buildChatService();
        SessionEntity sess = newSession("sess-2", "[]");
        AgentEntity agent = newAgent(100L);
        AgentDefinition def = newDef(Arrays.asList("agent-skill-a", "agent-skill-b"));
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-2", "hi", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getSkillIds())
                .as("explicit empty-array override → without_skill baseline, no agent skills available")
                .isEmpty();
    }

    @Test
    @DisplayName("session.skillOverridesJson == [\"x\",\"y\"] → agent.skillIds replaced (with_skill override)")
    void overridePresent_replacesAgentSkills() {
        chatService = buildChatService();
        SessionEntity sess = newSession("sess-3", "[\"override-x\",\"override-y\"]");
        AgentEntity agent = newAgent(100L);
        AgentDefinition def = newDef(Arrays.asList("agent-skill-a", "agent-skill-b"));
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-3", "hi", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getSkillIds())
                .as("non-empty override → replaces agent.skillIds completely (NOT merged)")
                .containsExactly("override-x", "override-y");
    }

    @Test
    @DisplayName("malformed skillOverridesJson → falls back to agent.skillIds (no NPE / no exception)")
    void overrideMalformed_fallsBackToAgent() {
        chatService = buildChatService();
        SessionEntity sess = newSession("sess-4", "{not-a-list");
        AgentEntity agent = newAgent(100L);
        AgentDefinition def = newDef(Arrays.asList("agent-skill-a"));
        wireBaseMocks(sess, agent, def);

        chatService.chatAsync("sess-4", "hi", 7L);

        AgentDefinition captured = captureAgentDef();
        assertThat(captured.getSkillIds())
                .as("malformed JSON must not crash the loop; defense-in-depth falls back to legacy")
                .containsExactly("agent-skill-a");
    }

    // -------------------------- helpers --------------------------

    private void wireBaseMocks(SessionEntity sess, AgentEntity agent, AgentDefinition def) {
        when(sessionService.getSession(sess.getId())).thenReturn(sess);
        when(agentService.getAgent(100L)).thenReturn(agent);
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getSessionMessages(sess.getId())).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages(sess.getId())).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory(sess.getId())).thenReturn(new ArrayList<>());

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

    private SessionEntity newSession(String id, String skillOverrides) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(100L);
        s.setMessageCount(0);
        s.setLastUserMessageAt(java.time.Instant.now());
        s.setRuntimeStatus("idle");
        s.setMessagesJson("[]");
        s.setSkillOverridesJson(skillOverrides);
        return s;
    }

    private AgentEntity newAgent(Long id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName("test-agent");
        a.setModelId("claude:claude-sonnet-4-20250514");
        return a;
    }

    private AgentDefinition newDef(List<String> skillIds) {
        AgentDefinition def = new AgentDefinition();
        def.setId("100");
        def.setName("test-agent");
        def.setModelId("claude:claude-sonnet-4-20250514");
        def.setSkillIds(new ArrayList<>(skillIds));
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
