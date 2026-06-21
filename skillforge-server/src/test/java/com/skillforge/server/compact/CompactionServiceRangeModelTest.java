package com.skillforge.server.compact;

import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.CompactionEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionSummaryEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CompactionEventRepository;
import com.skillforge.server.repository.SessionCompactionCheckpointRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.SessionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P1 — range-model write path (flag ON).
 *
 * <p>Asserts that when {@code skillforge.compact.range-model.enabled=true} a full compact:
 * writes exactly one {@code t_session_summary} row with the correct covered range,
 * marks the covered rows, supersedes a prior active summary (Q3 merge), and appends/deletes
 * ZERO message rows — repeatedly, with no row growth.
 */
class CompactionServiceRangeModelTest {

    /** Non-zero, non-1 seq base so the range mapping is exercised against production-shaped seq_nos. */
    private static final long SEQ_BASE = 100L;

    private SessionRepository sessionRepository;
    private CompactionEventRepository eventRepository;
    private SessionCompactionCheckpointRepository checkpointRepository;
    private SessionService sessionService;
    private LlmProviderFactory llmProviderFactory;
    private LlmProperties llmProperties;
    private ChatEventBroadcaster broadcaster;
    private AgentRepository agentRepository;
    private SessionSummaryRepository summaryRepository;
    private SessionMessageRepository messageRepository;
    private CompactionService service;

    private final Map<String, SessionEntity> sessionStore = new HashMap<>();
    private final Map<String, List<Message>> messagesStore = new HashMap<>();
    private final Map<Long, SessionSummaryEntity> summaryStore = new HashMap<>();
    private final AtomicLong summaryIdSeq = new AtomicLong(0);
    private final AtomicLong eventIdSeq = new AtomicLong(0);

    private final LlmProvider mockProvider = new LlmProvider() {
        @Override public String getName() { return "mock"; }
        @Override public LlmResponse chat(LlmRequest request) {
            LlmResponse r = new LlmResponse();
            r.setContent("RANGE SUMMARY");
            return r;
        }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
    };

    @BeforeEach
    void setUp() {
        sessionStore.clear();
        messagesStore.clear();
        summaryStore.clear();
        summaryIdSeq.set(0);
        eventIdSeq.set(0);

        sessionRepository = mock(SessionRepository.class);
        eventRepository = mock(CompactionEventRepository.class);
        checkpointRepository = mock(SessionCompactionCheckpointRepository.class);
        sessionService = mock(SessionService.class);
        llmProviderFactory = mock(LlmProviderFactory.class);
        llmProperties = mock(LlmProperties.class);
        broadcaster = mock(ChatEventBroadcaster.class);
        agentRepository = mock(AgentRepository.class);
        summaryRepository = mock(SessionSummaryRepository.class);
        messageRepository = mock(SessionMessageRepository.class);

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
        when(sessionService.getContextMessages(anyString())).thenAnswer(inv ->
                new ArrayList<>(messagesStore.getOrDefault(inv.getArgument(0), new ArrayList<>())));
        // Range model maps window → real seq via getFullHistoryRecords. Use a NON-zero, non-1
        // base (SEQ_BASE) so the mapping is proven against production-shaped seq_nos (real seq_nos
        // are base+1, not 0-based) rather than the trivial 0-based case.
        when(sessionService.getFullHistoryRecords(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            List<Message> msgs = messagesStore.getOrDefault(id, new ArrayList<>());
            List<SessionService.StoredMessage> out = new ArrayList<>();
            for (int i = 0; i < msgs.size(); i++) {
                out.add(new SessionService.StoredMessage(
                        SEQ_BASE + i, SessionService.MSG_TYPE_NORMAL,
                        java.util.Collections.emptyMap(), msgs.get(i)));
            }
            return out;
        });
        when(sessionService.countMessageRows(anyString())).thenAnswer(inv ->
                (long) messagesStore.getOrDefault(inv.getArgument(0), new ArrayList<>()).size());

        when(eventRepository.save(any(CompactionEventEntity.class))).thenAnswer(inv -> {
            CompactionEventEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(eventIdSeq.incrementAndGet());
            return e;
        });

        when(summaryRepository.save(any(SessionSummaryEntity.class))).thenAnswer(inv -> {
            SessionSummaryEntity s = inv.getArgument(0);
            if (s.getId() == null) s.setId(summaryIdSeq.incrementAndGet());
            summaryStore.put(s.getId(), s);
            return s;
        });
        when(summaryRepository.findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(anyString()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(0);
                    List<SessionSummaryEntity> out = new ArrayList<>();
                    for (SessionSummaryEntity s : summaryStore.values()) {
                        if (id.equals(s.getSessionId()) && s.getSupersededBy() == null) out.add(s);
                    }
                    out.sort(java.util.Comparator.comparingLong(SessionSummaryEntity::getStartSeq));
                    return out;
                });
        when(summaryRepository.markSuperseded(anyLong(), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long newId = inv.getArgument(1);
            SessionSummaryEntity s = summaryStore.get(id);
            if (s != null) { s.setSupersededBy(newId); return 1; }
            return 0;
        });
        when(messageRepository.markCompactedBySummary(anyString(), anyLong(), anyLong(), anyLong()))
                .thenReturn(0);

        service = new CompactionService(sessionRepository, eventRepository, checkpointRepository, sessionService,
                new LightCompactStrategy(), new FullCompactStrategy(),
                llmProviderFactory, llmProperties, broadcaster, null);
        service.setAgentRepository(agentRepository);
        service.setSessionSummaryRepository(summaryRepository);
        service.setSessionMessageRepository(messageRepository);
        service.setRangeModelEnabled(true);
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

    /** 30 messages: 28 plain + a tool_use/tool_result pair, so full compact finds a safe boundary. */
    private void seedMessages(String id) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 8; i++) msgs.add(Message.user("front filler " + i));
        Message tu = new Message();
        tu.setRole(Message.Role.ASSISTANT);
        tu.setContent(List.of(ContentBlock.toolUse("t1", "Bash", Map.of("cmd", "echo"))));
        msgs.add(tu);
        Message tr = new Message();
        tr.setRole(Message.Role.USER);
        tr.setContent(List.of(ContentBlock.toolResult("t1", "out", false)));
        msgs.add(tr);
        for (int i = 0; i < 20; i++) msgs.add(Message.user("tail " + i));
        messagesStore.put(id, msgs);
    }

    @Test
    @DisplayName("flag ON: full compact writes 1 summary row with correct range, appends 0 message rows")
    void rangeModel_fullCompact_writesSummaryRow_appendsZeroRows() {
        seedSession("sRM", 30, 0, "idle");
        seedMessages("sRM");

        CompactionEventEntity event = service.compact("sRM", "full", "engine-hard", "range model");
        assertThat(event).isNotNull();

        // Exactly one summary row, covering [0, endSeq]. window = 30 - young(20) = 10 rows
        // (model-view indices 0..9). With SEQ_BASE=100 the last window row's real seq_no is
        // 100 + 9 = 109 → endSeq = 109, compactedCount = 10. startSeq stays 0 (§2.6 Q3 rolling
        // merge: the new summary covers [0, endSeq] regardless of the physical base).
        long expectedEndSeq = SEQ_BASE + 9; // 109
        assertThat(summaryStore).hasSize(1);
        SessionSummaryEntity summary = summaryStore.values().iterator().next();
        assertThat(summary.getSessionId()).isEqualTo("sRM");
        assertThat(summary.getStartSeq()).isEqualTo(0L);
        assertThat(summary.getEndSeq()).isEqualTo(expectedEndSeq);
        assertThat(summary.getLevel()).isEqualTo("full");
        assertThat(summary.getSource()).isEqualTo("engine-hard");
        assertThat(summary.getSummaryText()).contains("RANGE SUMMARY");
        assertThat(summary.getCompactedMessageCount()).isEqualTo(10);
        assertThat(summary.getSupersededBy()).isNull(); // newest is active

        // Marked covered rows via the targeted UPDATE.
        verify(messageRepository).markCompactedBySummary(eq("sRM"), eq(0L), eq(expectedEndSeq), eq(summary.getId()));

        // NO message rows appended / deleted / re-appended.
        verify(sessionService, never()).appendMessages(eq("sRM"), any());
        verify(sessionService, never()).rewriteMessages(eq("sRM"), any());
        verify(sessionService, never()).saveSessionMessages(eq("sRM"), any());

        // Checkpoint still written, pointing at the summary id.
        verify(checkpointRepository).save(any());
    }

    @Test
    @DisplayName("flag ON: repeated full compact supersedes prior active summary, still 0 message rows")
    void rangeModel_repeatedCompact_supersedesPrior_noRowGrowth() {
        seedSession("sRM2", 30, 0, "idle");
        seedMessages("sRM2");

        // First compaction.
        service.compact("sRM2", "full", "engine-hard", "round 1");
        assertThat(summaryStore).hasSize(1);
        Long firstId = summaryStore.keySet().iterator().next();

        // Second compaction: allow the idempotency guard to pass (bump gap), re-seed messages.
        SessionEntity s = sessionStore.get("sRM2");
        s.setLastCompactedAtMessageCount(0);
        seedMessages("sRM2");
        service.compact("sRM2", "full", "engine-hard", "round 2");

        // Two summary rows now exist; the first is superseded by the second.
        assertThat(summaryStore).hasSize(2);
        SessionSummaryEntity first = summaryStore.get(firstId);
        assertThat(first.getSupersededBy()).as("prior active summary must be superseded").isNotNull();

        // Exactly one active (non-superseded) summary remains.
        long active = summaryStore.values().stream().filter(x -> x.getSupersededBy() == null).count();
        assertThat(active).isEqualTo(1);

        // Across both rounds: zero message-row appends/rewrites (no unbounded growth).
        verify(sessionService, never()).appendMessages(eq("sRM2"), any());
        verify(sessionService, never()).rewriteMessages(eq("sRM2"), any());
    }
}
