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
import static org.mockito.Mockito.atLeastOnce;
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

    /** Last request the provider received — lets tests assert what system prompt the LLM got. */
    private volatile LlmRequest lastLlmRequest;

    private final LlmProvider mockProvider = new LlmProvider() {
        @Override public String getName() { return "mock"; }
        @Override public LlmResponse chat(LlmRequest request) {
            lastLlmRequest = request;
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
        lastLlmRequest = null;

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
        when(summaryRepository.existsBySessionIdAndSupersededByIsNull(anyString()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(0);
                    return summaryStore.values().stream()
                            .anyMatch(s -> id.equals(s.getSessionId()) && s.getSupersededBy() == null);
                });
        // INCREMENTAL-SUMMARY: the latest active summary (highest start_seq) is the rolling summary
        // CompactionService reads under the lock and threads into Phase 2. Mirror real semantics.
        when(summaryRepository.findTopBySessionIdAndSupersededByIsNullOrderByStartSeqDesc(anyString()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(0);
                    return summaryStore.values().stream()
                            .filter(s -> id.equals(s.getSessionId()) && s.getSupersededBy() == null)
                            .max(java.util.Comparator.comparingLong(SessionSummaryEntity::getStartSeq));
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

    private void seedLightCompactModelViewWithInjectedSummary(String id) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user("[Context summary from 84 messages compacted at 2026-06-29T07:58:03Z]\n"
                + "prior task state"));
        for (int i = 0; i < 3; i++) {
            msgs.add(Message.user("front filler " + i));
        }
        Message tu = new Message();
        tu.setRole(Message.Role.ASSISTANT);
        tu.setContent(List.of(ContentBlock.toolUse("lt1", "Edit", Map.of("path", "/tmp/huge"))));
        msgs.add(tu);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 7000; i++) {
            big.append("line ").append(i).append('\n');
        }
        Message tr = new Message();
        tr.setRole(Message.Role.USER);
        tr.setContent(List.of(ContentBlock.toolResult("lt1", big.toString(), false)));
        msgs.add(tr);
        for (int i = 0; i < 6; i++) {
            msgs.add(Message.user("tail " + i));
        }
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
    @DisplayName("flag ON: prior active summary is threaded into Phase 2 → LLM gets the INCREMENTAL prompt")
    void rangeModel_priorSummary_drivesIncrementalLlmCall() {
        seedSession("sINC", 30, 0, "idle");

        // Pre-existing active rolling summary the service must read under the lock and feed to Phase 2.
        String priorSummaryText = "PRIOR ROLLING SUMMARY: user wants the migration finished.";
        SessionSummaryEntity prior = new SessionSummaryEntity();
        prior.setId(summaryIdSeq.incrementAndGet());
        prior.setSessionId("sINC");
        prior.setStartSeq(0L);
        prior.setEndSeq(SEQ_BASE - 1);
        prior.setSummaryText(priorSummaryText);
        prior.setLevel("full");
        prior.setSource("engine-hard");
        summaryStore.put(prior.getId(), prior);

        // Derived range-model view = [prior summary as user head] + new turns + tool pair + tail,
        // mirroring SessionService.getContextMessagesWithProvenance output.
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.user(priorSummaryText)); // the injected active-summary head
        for (int i = 0; i < 7; i++) msgs.add(Message.user("new turn " + i));
        Message tu = new Message();
        tu.setRole(Message.Role.ASSISTANT);
        tu.setContent(List.of(ContentBlock.toolUse("t1", "Bash", Map.of("cmd", "echo"))));
        msgs.add(tu);
        Message tr = new Message();
        tr.setRole(Message.Role.USER);
        tr.setContent(List.of(ContentBlock.toolResult("t1", "out", false)));
        msgs.add(tr);
        for (int i = 0; i < 20; i++) msgs.add(Message.user("tail " + i));
        messagesStore.put("sINC", msgs);

        CompactionEventEntity event = service.compact("sINC", "full", "engine-hard", "incremental wiring");
        assertThat(event).isNotNull();

        // WIRING PROOF: the service read the prior summary and routed Phase 2 to the incremental path
        // (without the stub, findTop returns empty → from-scratch prompt, and this assertion fails).
        assertThat(lastLlmRequest).as("LLM must have been called").isNotNull();
        assertThat(lastLlmRequest.getSystemPrompt())
                .as("prior active summary must drive the INCREMENTAL summary prompt")
                .contains("INCREMENTAL update")
                .contains("Do NOT drop task state from the EXISTING summary");

        // The existing summary is handed to the LLM as the labeled existing block + not duplicated
        // (stripped from the serialized window since the head equals it exactly).
        String userText = (String) lastLlmRequest.getMessages().get(0).getContent();
        assertThat(userText).contains("## EXISTING SUMMARY");
        assertThat(userText).contains(priorSummaryText);
        assertThat(userText.indexOf(priorSummaryText))
                .as("prior summary not duplicated in the window")
                .isEqualTo(userText.lastIndexOf(priorSummaryText));
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

        // Full range-model compaction must restamp markers from the active ranges. Merely marking
        // NULL rows leaves rows covered by superseded summaries pointing at stale ids, which can
        // re-expose already-summarized history in the derived model view.
        verify(messageRepository, atLeastOnce()).clearCompactedMarkers(eq("sRM2"));
        verify(messageRepository, atLeastOnce())
                .markCompactedBySummary(eq("sRM2"), eq(0L), anyLong(), anyLong());
    }

    @Test
    @DisplayName("flag ON: compact with prior summary never moves the active frontier backwards")
    void rangeModel_compactWithPriorSummaryDoesNotMoveFrontierBackwards() {
        seedSession("sREGRESS", 30, 0, "idle");
        seedMessages("sREGRESS");

        SessionSummaryEntity prior = new SessionSummaryEntity();
        prior.setId(summaryIdSeq.incrementAndGet());
        prior.setSessionId("sREGRESS");
        prior.setStartSeq(0L);
        prior.setEndSeq(SEQ_BASE + 20);
        prior.setSummaryText("WIDE ACTIVE SUMMARY");
        prior.setLevel("full");
        prior.setSource("engine-hard");
        summaryStore.put(prior.getId(), prior);

        CompactionEventEntity event = service.compact("sREGRESS", "full", "engine-hard", "regression");

        assertThat(event).isNotNull();
        assertThat(summaryStore).hasSize(2);
        SessionSummaryEntity priorAfter = summaryStore.get(prior.getId());
        assertThat(priorAfter.getSupersededBy()).isNotNull();
        SessionSummaryEntity activeAfter = summaryStore.get(priorAfter.getSupersededBy());
        assertThat(activeAfter.getEndSeq())
                .as("new active summary must include at least the prior frontier")
                .isGreaterThanOrEqualTo(prior.getEndSeq());
    }

    @Test
    @DisplayName("flag ON: light compact does not rewrite derived model-view summaries into message rows")
    void rangeModel_lightCompactDoesNotPersistDerivedSummaryView() {
        seedSession("sLIGHT", 12, 0, "idle");
        seedLightCompactModelViewWithInjectedSummary("sLIGHT");

        SessionSummaryEntity active = new SessionSummaryEntity();
        active.setId(summaryIdSeq.incrementAndGet());
        active.setSessionId("sLIGHT");
        active.setStartSeq(0L);
        active.setEndSeq(83L);
        active.setSummaryText("prior task state");
        active.setLevel("full");
        active.setSource("engine-hard");
        summaryStore.put(active.getId(), active);

        CompactionEventEntity event = service.compact("sLIGHT", "light", "engine-soft", "range light");

        assertThat(event).isNull();
        verify(sessionService, never()).saveSessionMessages(eq("sLIGHT"), any());
        verify(sessionService, never()).rewriteMessages(eq("sLIGHT"), any());
        verify(sessionService, never()).appendMessages(eq("sLIGHT"), any());
    }
}
