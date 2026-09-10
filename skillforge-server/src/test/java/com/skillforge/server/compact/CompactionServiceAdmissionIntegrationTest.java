package com.skillforge.server.compact;

import com.skillforge.core.compact.CompactResult;
import com.skillforge.core.compact.CompactableToolRegistry;
import com.skillforge.core.compact.ContextCompactorCallback.CompactCallbackResult;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.CompactionEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.CompactionEventRepository;
import com.skillforge.server.repository.SessionCompactionCheckpointRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.CompactAdmissionService;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompactionServiceAdmissionIntegrationTest {

    private static final String SESSION_ID = "compact-admission-integration";

    private SessionRepository sessionRepository;
    private CompactionEventRepository eventRepository;
    private SessionCompactionCheckpointRepository checkpointRepository;
    private SessionService sessionService;
    private LightCompactStrategy lightStrategy;
    private FullCompactStrategy fullStrategy;
    private LlmProviderFactory providerFactory;
    private LlmProperties llmProperties;
    private CompactAdmissionService admissionService;
    private CompactionService service;
    private SessionEntity session;
    private LlmProvider provider;
    private List<Message> durablePrefix;
    private FullCompactStrategy.PreparedCompact preparedCompact;
    private CompactResult fullResult;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        eventRepository = mock(CompactionEventRepository.class);
        checkpointRepository = mock(SessionCompactionCheckpointRepository.class);
        sessionService = mock(SessionService.class);
        lightStrategy = mock(LightCompactStrategy.class);
        fullStrategy = mock(FullCompactStrategy.class);
        providerFactory = mock(LlmProviderFactory.class);
        llmProperties = mock(LlmProperties.class);
        admissionService = mock(CompactAdmissionService.class);
        provider = mock(LlmProvider.class);

        session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(7L);
        session.setAgentId(3L);
        session.setMessageCount(30);
        session.setLastCompactedAtMessageCount(0);
        session.setRuntimeStatus("idle");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(SessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(llmProperties.getDefaultProvider()).thenReturn("mock");
        when(providerFactory.getProvider("mock")).thenReturn(provider);
        when(sessionService.appendMessages(eq(SESSION_ID), anyList())).thenReturn(100L);
        when(sessionService.countMessageRows(SESSION_ID)).thenReturn(30L);
        when(sessionService.findTailTraceIds(eq(SESSION_ID), anyInt())).thenReturn(List.of());
        when(eventRepository.save(any(CompactionEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(checkpointRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(checkpointRepository.findSidecarWatermarkById(anyString()))
                .thenReturn(Optional.of(1L));

        durablePrefix = messages(25, "durable-");
        preparedCompact = new FullCompactStrategy.PreparedCompact(
                5,
                List.copyOf(durablePrefix.subList(0, 5)),
                List.copyOf(durablePrefix.subList(5, durablePrefix.size())),
                1_000,
                durablePrefix.size(),
                32_000);
        List<Message> compacted = new ArrayList<>();
        compacted.add(Message.user("durable summary"));
        compacted.addAll(durablePrefix.subList(5, durablePrefix.size()));
        fullResult = new CompactResult(
                compacted, 1_000, 100, durablePrefix.size(), compacted.size(),
                List.of("llm-summary"));
        when(fullStrategy.prepareCompact(anyList(), anyInt())).thenReturn(preparedCompact);
        when(fullStrategy.applyPrepared(
                eq(preparedCompact), eq(provider), isNull(), isNull()))
                .thenReturn(fullResult);

        service = new CompactionService(
                sessionRepository,
                eventRepository,
                checkpointRepository,
                sessionService,
                lightStrategy,
                fullStrategy,
                providerFactory,
                llmProperties,
                mock(ChatEventBroadcaster.class),
                null);
        service.setCompactAdmissionService(admissionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "user-manual", "agent-tool", "engine-hard", "engine-preemptive", "post-overflow"
    })
    @DisplayName("all five Full entry sources share capture and Phase-3 revalidation")
    void compactFull_allSupportedSources_useSameAdmissionSnapshot(String source) {
        CompactAdmissionService.AdmissionSnapshot snapshot = snapshot(source, false);
        when(admissionService.capture(SESSION_ID, "full", source)).thenReturn(snapshot);
        when(admissionService.prepareFullInput(snapshot, durablePrefix))
                .thenReturn(new CompactAdmissionService.PreparedMessages(durablePrefix, List.of()));

        CompactCallbackResult result = service.compactFull(
                SESSION_ID, durablePrefix, source, "threshold");

        assertThat(result.performed).isTrue();
        verify(admissionService).capture(SESSION_ID, "full", source);
        verify(admissionService).revalidate(snapshot);
    }

    @Test
    @DisplayName("agent-tool sends only the admitted durable prefix to Full Compact and preserves intent")
    void compactFull_agentTool_excludesCurrentIntentFromModelInputAndRestoresItToCallback() {
        Message assistantIntent = assistantCompactIntent();
        List<Message> engineMessages = new ArrayList<>(durablePrefix);
        engineMessages.add(assistantIntent);
        CompactAdmissionService.AdmissionSnapshot snapshot = snapshot("agent-tool", true);
        when(admissionService.capture(SESSION_ID, "full", "agent-tool")).thenReturn(snapshot);
        when(admissionService.prepareFullInput(snapshot, engineMessages))
                .thenReturn(new CompactAdmissionService.PreparedMessages(
                        durablePrefix, List.of(assistantIntent)));

        CompactCallbackResult result = service.compactFull(
                SESSION_ID, engineMessages, "agent-tool", "agent requested");

        assertThat(result.performed).isTrue();
        assertThat(result.messages).endsWith(assistantIntent);
        Message compactResult = Message.toolResult("compact-call", "done", false);
        result.messages.add(compactResult);
        assertThat(result.messages).endsWith(assistantIntent, compactResult);
        verify(fullStrategy).prepareCompact(eq(durablePrefix), anyInt());
        verify(admissionService).revalidate(snapshot);
    }

    @Test
    @DisplayName("a stale Phase-3 snapshot aborts before any destructive Compact persistence")
    void compactFull_stalePhase3_doesNotPersistResult() {
        CompactAdmissionService.AdmissionSnapshot snapshot = snapshot("engine-hard", false);
        when(admissionService.capture(SESSION_ID, "full", "engine-hard")).thenReturn(snapshot);
        when(admissionService.prepareFullInput(snapshot, durablePrefix))
                .thenReturn(new CompactAdmissionService.PreparedMessages(durablePrefix, List.of()));
        doThrow(new IllegalStateException("Compact admission snapshot is stale"))
                .when(admissionService).revalidate(snapshot);

        assertThatThrownBy(() -> service.compactFull(
                SESSION_ID, durablePrefix, "engine-hard", "threshold"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");

        verify(sessionService, never()).appendMessages(anyString(), anyList());
        verify(sessionService, never()).rewriteMessages(anyString(), anyList());
        verify(sessionService, never()).saveSessionMessages(anyString(), anyList());
        verify(checkpointRepository, never()).saveAndFlush(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unbound in-memory view is rejected before Full strategy preparation")
    void compactFull_unboundInMemoryView_rejectsBeforeProvider() {
        CompactAdmissionService.AdmissionSnapshot snapshot = snapshot("engine-hard", false);
        when(admissionService.capture(SESSION_ID, "full", "engine-hard")).thenReturn(snapshot);
        doThrow(new IllegalStateException(
                "Compact input does not match the authoritative durable model view"))
                .when(admissionService).prepareFullInput(snapshot, durablePrefix);

        assertThatThrownBy(() -> service.compactFull(
                SESSION_ID, durablePrefix, "engine-hard", "threshold"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritative durable model view");

        verify(fullStrategy, never()).prepareCompact(anyList(), anyInt());
    }

    @Test
    @DisplayName("normal Light Compact uses the same admission and revalidation barrier")
    void compactLight_normalSource_capturesAndRevalidatesBeforePersistence() {
        CompactAdmissionService.AdmissionSnapshot snapshot = snapshot("engine-soft", false);
        when(admissionService.capture(SESSION_ID, "light", "engine-soft")).thenReturn(snapshot);
        when(admissionService.prepareLightInput(snapshot, durablePrefix))
                .thenReturn(durablePrefix);
        CompactResult lightResult = new CompactResult(
                durablePrefix.subList(5, durablePrefix.size()),
                1_000,
                100,
                durablePrefix.size(),
                durablePrefix.size() - 5,
                List.of("truncate"));
        when(lightStrategy.apply(
                eq(durablePrefix), anyInt(), any(CompactableToolRegistry.class)))
                .thenReturn(lightResult);

        CompactCallbackResult result = service.compactLight(
                SESSION_ID, durablePrefix, "engine-soft", "threshold");

        assertThat(result.performed).isTrue();
        verify(admissionService).capture(SESSION_ID, "light", "engine-soft");
        verify(admissionService).prepareLightInput(snapshot, durablePrefix);
        verify(admissionService).revalidate(snapshot);
        verify(sessionService).saveSessionMessages(SESSION_ID, lightResult.getMessages());
    }

    private static CompactAdmissionService.AdmissionSnapshot snapshot(
            String source, boolean agentTool) {
        return new CompactAdmissionService.AdmissionSnapshot(
                true,
                SESSION_ID,
                "engine-soft".equals(source) ? "light" : "full",
                source,
                8L,
                new CompactAdmissionService.TranscriptToken(91L, 44L, "transcript"),
                "loop-1",
                12L,
                null,
                new CompactAdmissionService.InboxToken(0L, null, "inbox"),
                agentTool);
    }

    private static List<Message> messages(int count, String prefix) {
        List<Message> messages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            messages.add(Message.user(prefix + index));
        }
        return List.copyOf(messages);
    }

    private static Message assistantCompactIntent() {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(ContentBlock.toolUse(
                "compact-call",
                "compact_context",
                Map.of("level", "full", "reason", "agent requested"))));
        return assistant;
    }
}
