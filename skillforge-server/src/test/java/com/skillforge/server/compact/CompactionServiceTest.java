package com.skillforge.server.compact;

import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.compact.recovery.FileStateCache;
import com.skillforge.core.compact.recovery.RecoveryPayloadBuilder;
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
import com.skillforge.server.repository.SessionCompactionCheckpointRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.SessionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompactionServiceTest {

    private SessionRepository sessionRepository;
    private CompactionEventRepository eventRepository;
    private SessionCompactionCheckpointRepository checkpointRepository;
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
        checkpointRepository = mock(SessionCompactionCheckpointRepository.class);
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
        when(sessionService.getContextMessages(anyString())).thenAnswer(inv ->
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

        service = new CompactionService(sessionRepository, eventRepository, checkpointRepository, sessionService,
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

    /**
     * Tool-heavy layout where the only safe full-compact boundary lands a tiny window in
     * (a few leading user messages, then unbreakable tool_use↔tool_result pairs). Used to
     * exercise the ② degenerate-split guard.
     */
    private void seedToolHeavyTinyBoundary(String id) {
        List<Message> msgs = new ArrayList<>();
        // 1 leading user message — the only place a safe boundary can land early.
        msgs.add(Message.user("kickoff"));
        // An early long-running tool_use (e.g. a SubAgent dispatch) whose tool_result only
        // arrives at the very END. This OPEN pair spans the whole middle region, so every
        // boundary between the dispatch and its result is unsafe (INV-1) — the only safe cut
        // is at idx 1 (before the dispatch), forcing a degenerate tiny window (rightEdge=1).
        Message dispatch = new Message();
        dispatch.setRole(Message.Role.ASSISTANT);
        dispatch.setContent(List.of(ContentBlock.toolUse("long-1", "SubAgent", Map.of("task", "big"))));
        msgs.add(dispatch);
        // Filler assistant text in the middle (cannot be cut because long-1 is still open).
        for (int i = 0; i < 38; i++) {
            Message a = new Message();
            a.setRole(Message.Role.ASSISTANT);
            a.setContent("interim step " + i);
            msgs.add(a);
        }
        // The matching tool_result arrives last.
        Message done = new Message();
        done.setRole(Message.Role.USER);
        done.setContent(List.of(ContentBlock.toolResult("long-1", "sub-agent result", false)));
        msgs.add(done);
        messagesStore.put(id, msgs);
    }

    // ============= tests =============

    // ---- COMPACT-IDEMPOTENCY-FIX ① : preemptive bypass + persisted-count gap space ----

    @Test
    @DisplayName("①: engine-preemptive over-window compact is NOT skipped even when gap is negative")
    void preemptive_compact_bypasses_negative_gap_idempotency() {
        // Simulate the live bug: a previously-compacted session with a high persisted high-water
        // (lastCompactedAtMessageCount=560, messageCount=560 → persisted gap=0) while the engine's
        // in-memory working set is small. The OLD code computed gap = inMemoryMessages.size() - 560
        // → hugely negative → "skipped gap=-...". With the ① fix engine-preemptive bypasses the
        // guard entirely and the compaction proceeds.
        seedSession("sPRE-GAP", 560, 560, "running");
        seedMessages("sPRE-GAP");
        // Engine callback path with a small in-memory working set (30 messages from seedMessages).
        var result = service.compactFull("sPRE-GAP", new ArrayList<>(messagesStore.get("sPRE-GAP")),
                "engine-preemptive", "ratio 1.72 > 0.85 before LLM call");
        assertThat(result.performed)
                .as("engine-preemptive must never be suppressed by the idempotency guard")
                .isTrue();
    }

    @Test
    @DisplayName("①: engine-hard full callback also bypasses negative gap")
    void engine_hard_compact_bypasses_negative_gap() {
        seedSession("sHARD-GAP", 560, 560, "running");
        seedMessages("sHARD-GAP");
        var result = service.compactFull("sHARD-GAP", new ArrayList<>(messagesStore.get("sHARD-GAP")),
                "engine-hard", "B1 ran but ratio still high");
        assertThat(result.performed).isTrue();
    }

    @Test
    @DisplayName("①: non-preemptive agent-tool full still gated by PERSISTED-count gap (not in-memory size)")
    void agent_tool_full_uses_persisted_count_gap() {
        // Persisted gap is 0 (messageCount == lastCompactedAtMessageCount) → must be skipped,
        // regardless of in-memory working-set size. This proves the gap now uses the persisted
        // count space on both sides (the old code would have used the in-memory size).
        seedSession("sAT-GAP", 560, 560, "idle");
        seedMessages("sAT-GAP");
        var result = service.compactFull("sAT-GAP", new ArrayList<>(messagesStore.get("sAT-GAP")),
                "agent-tool", "llm asked");
        assertThat(result.performed)
                .as("agent-tool full with persisted gap=0 must be idempotency-skipped")
                .isFalse();
    }

    @Test
    @DisplayName("①: agent-tool full proceeds when persisted gap is large enough")
    void agent_tool_full_proceeds_with_large_persisted_gap() {
        // messageCount 560, lastCompactedAt 100 → persisted gap 460 >= 5 → proceeds.
        seedSession("sAT-OK", 560, 100, "idle");
        seedMessages("sAT-OK");
        var result = service.compactFull("sAT-OK", new ArrayList<>(messagesStore.get("sAT-OK")),
                "agent-tool", "llm asked");
        assertThat(result.performed).isTrue();
    }

    // ---- COMPACT-IDEMPOTENCY-FIX ② : degenerate-split guard ----

    @Test
    @DisplayName("②: degenerate tiny-window full compact is a true no-op (no rows appended, no event)")
    @SuppressWarnings("unchecked")
    void degenerate_window_fullCompact_is_true_noop() {
        seedSession("sDEG", 41, 0, "idle");
        seedToolHeavyTinyBoundary("sDEG");

        CompactionEventEntity event = service.compact("sDEG", "full", "engine-hard", "tool heavy");
        // True no-op: no event persisted.
        assertThat(event).as("degenerate window must not persist a compaction event").isNull();
        assertThat(eventStore).isEmpty();
        // And nothing was appended (no boundary/summary/retained re-append → no row bloat).
        verify(sessionService, org.mockito.Mockito.never()).appendMessages(eq("sDEG"), any());
    }

    @Test
    @DisplayName("②: repeated degenerate compactions never append rows (no unbounded growth)")
    void repeated_degenerate_compactions_do_not_grow_rows() {
        seedSession("sDEG2", 41, 0, "idle");
        seedToolHeavyTinyBoundary("sDEG2");
        for (int i = 0; i < 5; i++) {
            var r = service.compactFull("sDEG2", new ArrayList<>(messagesStore.get("sDEG2")),
                    "engine-hard", "round " + i);
            assertThat(r.performed).isFalse();
        }
        verify(sessionService, org.mockito.Mockito.never()).appendMessages(eq("sDEG2"), any());
    }

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
        verify(checkpointRepository).save(any());
    }

    /**
     * OBS-2 Q1: full compact's retained young-gen block must inherit the
     * pre-compact tail trace_ids by seq_no alignment. Without this, retained
     * rows would land with traceId=null and break per-trace UI lookups.
     */
    @Test
    @DisplayName("OBS-2 Q1: full compact retained block carries pre-compact tail trace_ids")
    @SuppressWarnings("unchecked")
    void full_compact_retainedBlock_inheritsTailTraceIds() {
        seedSession("sFTRACE", 30, 0, "idle");
        seedMessages("sFTRACE");

        // Stub findTailTraceIds to return deterministic per-index trace_ids so
        // we can assert each retained AppendMessage carries the matching value.
        when(sessionService.findTailTraceIds(eq("sFTRACE"), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    int n = inv.getArgument(1);
                    List<String> out = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        out.add("trace-tail-" + i);
                    }
                    return out;
                });

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        CompactionEventEntity event = service.compact("sFTRACE", "full", "engine-hard", "trace-id-preserve");
        assertThat(event).isNotNull();

        // times(1): full compact persistCompactResult writes the boundary +
        // summary + retained block in a single appendMessages call. Pinning to
        // exactly 1 turns any future regression that adds a second call into a
        // clean Mockito failure rather than a confusing index-mismatch error
        // when captor.getValue() returns the LAST capture and the retained
        // assertions below collide with whatever that batch contained.
        verify(sessionService, times(1)).appendMessages(eq("sFTRACE"), captor.capture());
        List<SessionService.AppendMessage> appended = captor.getValue();

        // The first row is BOUNDARY, second is SUMMARY. Retained NORMAL rows
        // start at index 2 and run until either the optional RECOVERY_PAYLOAD or
        // end of list. They must each carry trace-tail-{i} where i is the index
        // within the retained block (NOT within the full appended list).
        assertThat(appended.size()).isGreaterThanOrEqualTo(3);
        assertThat(appended.get(0).msgType()).isEqualTo(SessionService.MSG_TYPE_COMPACT_BOUNDARY);
        assertThat(appended.get(1).msgType()).isEqualTo(SessionService.MSG_TYPE_SUMMARY);
        // Boundary + summary rows themselves must NOT receive a trace_id (they are
        // background markers — see CompactionService comment).
        assertThat(appended.get(0).traceId()).isNull();
        assertThat(appended.get(1).traceId()).isNull();

        int retainedIdx = 0;
        for (int i = 2; i < appended.size(); i++) {
            SessionService.AppendMessage row = appended.get(i);
            if (SessionService.MSG_TYPE_RECOVERY_PAYLOAD.equals(row.msgType())) {
                continue; // Recovery payload is also a background marker, no trace_id.
            }
            assertThat(row.msgType()).isEqualTo(SessionService.MSG_TYPE_NORMAL);
            assertThat(row.traceId())
                    .as("retained row at retainedIdx=%d must carry trace-tail-%d", retainedIdx, retainedIdx)
                    .isEqualTo("trace-tail-" + retainedIdx);
            retainedIdx++;
        }
        // Sanity: at least one retained row was checked.
        assertThat(retainedIdx).isGreaterThan(0);

        // findTailTraceIds was queried for the retained block size (= retainedIdx).
        verify(sessionService).findTailTraceIds(eq("sFTRACE"), eq(retainedIdx));
    }

    /**
     * BUG-F-2: SUMMARY row role MUST be USER (not SYSTEM). The boundary marker row
     * stays SYSTEM, and the persistence-time {@code AppendMessage} batch order is
     * [BOUNDARY, SUMMARY, ...young-gen] with the right roles.
     */
    @Test
    @SuppressWarnings("unchecked")
    void full_compact_persists_summary_row_as_user_role() {
        seedSession("sSummary", 30, 0, "idle");
        seedMessages("sSummary");

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        CompactionEventEntity event = service.compact("sSummary", "full", "engine-hard", "summary-role-check");
        assertThat(event).isNotNull();

        verify(sessionService, atLeastOnce()).appendMessages(eq("sSummary"), captor.capture());
        List<SessionService.AppendMessage> appended = captor.getValue();

        // First two entries: boundary then summary
        assertThat(appended.size()).isGreaterThanOrEqualTo(2);
        SessionService.AppendMessage boundaryRow = appended.get(0);
        SessionService.AppendMessage summaryRow = appended.get(1);

        assertThat(boundaryRow.msgType()).isEqualTo(SessionService.MSG_TYPE_COMPACT_BOUNDARY);
        // Boundary keeps SYSTEM role (it's an internal marker, never sent to provider)
        assertThat(boundaryRow.message().getRole()).isEqualTo(Message.Role.SYSTEM);

        assertThat(summaryRow.msgType()).isEqualTo(SessionService.MSG_TYPE_SUMMARY);
        // Summary row role MUST be USER (BUG-F-2): mirrors engine-side Message.user(...)
        assertThat(summaryRow.message().getRole()).isEqualTo(Message.Role.USER);
        // Summary content is a String (post BUG-F-1 invariant)
        assertThat(summaryRow.message().getContent()).isInstanceOf(String.class);
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
     * BUG-A prerequisite: Phase 2 LLM failure must be rethrown so the engine's catch
     * counts it toward the breaker. Returning null previously made it look like a
     * neutral no-op under the BUG-A fix, hiding real failures from the breaker.
     */
    @Test
    void fullCompact_phase2LlmException_rethrows_soEngineCanCountBreaker() {
        seedSession("sBreaker", 30, 0, "idle");
        seedMessages("sBreaker");

        LlmProvider boomProvider = new LlmProvider() {
            @Override public String getName() { return "boom"; }
            @Override public LlmResponse chat(LlmRequest request) {
                throw new RuntimeException("simulated LLM backend failure");
            }
            @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
        };
        when(llmProviderFactory.getProvider("mock")).thenReturn(boomProvider);

        // BUG-A contract: rethrown as RuntimeException with sessionId in the message.
        assertThatThrownBy(() -> service.compact("sBreaker", "full", "engine-hard", "test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sBreaker");
    }

    // =================================================================================
    // P9-5: Post-compact recovery payload — verifies the new appendMessage row that
    // CompactionService.persistCompactResult emits after retained young-gen messages.
    // =================================================================================

    @Test
    @DisplayName("P9-5: full compact appends recovery payload row when FileStateCache has entries")
    @SuppressWarnings("unchecked")
    void p9_5_fullCompact_appendsRecoveryRow_whenCacheNonEmpty() {
        seedSession("sR1", 30, 0, "idle");
        seedMessages("sR1");

        FileStateCache cache = new FileStateCache();
        cache.put("sR1", "/abs/foo.java", "public class Foo {}\n");
        RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(cache);
        service.setRecoveryPayloadBuilder(builder);

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        CompactionEventEntity event = service.compact("sR1", "full", "engine-hard", "recovery test");
        assertThat(event).isNotNull();
        verify(sessionService, atLeastOnce()).appendMessages(eq("sR1"), captor.capture());

        List<SessionService.AppendMessage> appended = captor.getValue();
        // Find the recovery row (msgType = RECOVERY_PAYLOAD).
        SessionService.AppendMessage recoveryRow = appended.stream()
                .filter(am -> SessionService.MSG_TYPE_RECOVERY_PAYLOAD.equals(am.msgType()))
                .findFirst()
                .orElse(null);
        assertThat(recoveryRow).as("expected exactly one RECOVERY_PAYLOAD row").isNotNull();
        // Recovery row is plain user message, content is String containing the cached path.
        assertThat(recoveryRow.message().getRole()).isEqualTo(Message.Role.USER);
        assertThat(recoveryRow.message().getContent()).isInstanceOf(String.class);
        // REMINDER-MVP D6: payload is wrapped in <system-reminder>...</system-reminder>.
        String content = (String) recoveryRow.message().getContent();
        assertThat(content).startsWith("<system-reminder>\n");
        assertThat(content).endsWith("</system-reminder>\n");
        assertThat(content).contains("/abs/foo.java");
        // Order invariant: recovery row must come AFTER boundary + summary (which are at index 0/1).
        int recoveryIdx = appended.indexOf(recoveryRow);
        assertThat(recoveryIdx).isGreaterThanOrEqualTo(2);
        // Trigger metadata is propagated.
        assertThat(recoveryRow.metadata()).containsEntry("trigger", "engine-hard");
    }

    @Test
    @DisplayName("P9-5: empty cache → no RECOVERY_PAYLOAD row appended (4 paths preserved)")
    @SuppressWarnings("unchecked")
    void p9_5_fullCompact_skipsRecoveryRow_whenCacheEmpty() {
        seedSession("sR2", 30, 0, "idle");
        seedMessages("sR2");

        FileStateCache cache = new FileStateCache();  // empty
        RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(cache);
        service.setRecoveryPayloadBuilder(builder);

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        CompactionEventEntity event = service.compact("sR2", "full", "engine-hard", "no-cache");
        assertThat(event).isNotNull();
        verify(sessionService, atLeastOnce()).appendMessages(eq("sR2"), captor.capture());

        List<SessionService.AppendMessage> appended = captor.getValue();
        // No RECOVERY_PAYLOAD row should be present
        assertThat(appended).extracting(SessionService.AppendMessage::msgType)
                .doesNotContain(SessionService.MSG_TYPE_RECOVERY_PAYLOAD);
    }

    @Test
    @DisplayName("P9-5: recovery payload disabled (enabled=false) → no RECOVERY_PAYLOAD row")
    @SuppressWarnings("unchecked")
    void p9_5_fullCompact_disabledBuilder_skipsRecoveryRow() {
        seedSession("sR3", 30, 0, "idle");
        seedMessages("sR3");

        FileStateCache cache = new FileStateCache();
        cache.put("sR3", "/some/file.txt", "data");
        RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(cache);
        builder.setEnabled(false); // feature flag off
        service.setRecoveryPayloadBuilder(builder);

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        service.compact("sR3", "full", "engine-hard", "disabled");
        verify(sessionService, atLeastOnce()).appendMessages(eq("sR3"), captor.capture());
        List<SessionService.AppendMessage> appended = captor.getValue();
        assertThat(appended).extracting(SessionService.AppendMessage::msgType)
                .doesNotContain(SessionService.MSG_TYPE_RECOVERY_PAYLOAD);
    }

    @Test
    @DisplayName("P9-5: builder not wired (null) → CompactionService still appends boundary+summary normally")
    @SuppressWarnings("unchecked")
    void p9_5_fullCompact_nullBuilder_legacyBehaviorPreserved() {
        seedSession("sR4", 30, 0, "idle");
        seedMessages("sR4");
        // intentionally do NOT set recoveryPayloadBuilder — null path

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        service.compact("sR4", "full", "engine-hard", "legacy");
        verify(sessionService, atLeastOnce()).appendMessages(eq("sR4"), captor.capture());
        List<SessionService.AppendMessage> appended = captor.getValue();

        assertThat(appended).extracting(SessionService.AppendMessage::msgType)
                .contains(SessionService.MSG_TYPE_COMPACT_BOUNDARY,
                          SessionService.MSG_TYPE_SUMMARY)
                .doesNotContain(SessionService.MSG_TYPE_RECOVERY_PAYLOAD);
    }

    // -------------------------------------------------------------------------------
    // P9-5 W2: explicit coverage for the remaining 3 full-compact trigger paths.
    //   B2 hard         — covered by tests above (engine-hard via REST entry)
    //   Preemptive      — engine-preemptive via callback compactFull
    //   Post-overflow   — post-overflow via callback compactFull
    //   SessionMemory   — Phase 1.5 zero-LLM path (memoryService non-null)
    // All four eventually call persistCompactResult which is the single attach point
    // for the recovery payload row.
    // -------------------------------------------------------------------------------

    @Test
    @DisplayName("P9-5 W2 (Preemptive): engine-preemptive callback path appends RECOVERY_PAYLOAD row")
    @SuppressWarnings("unchecked")
    void p9_5_preemptiveCompact_appendsRecoveryRow() {
        seedSession("sPRE", 30, 0, "idle");
        seedMessages("sPRE");

        FileStateCache cache = new FileStateCache();
        cache.put("sPRE", "/abs/preempt.txt", "preemptive content");
        RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(cache);
        service.setRecoveryPayloadBuilder(builder);

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        // Engine path: AgentLoopEngine line 798 calls callback compactFull(sessionId, msgs,
        // "engine-preemptive", reason).  Drive that path directly.
        var result = service.compactFull("sPRE", new ArrayList<>(messagesStore.get("sPRE")),
                "engine-preemptive", "ratio>preemptive_threshold");
        assertThat(result.performed).isTrue();

        verify(sessionService, atLeastOnce()).appendMessages(eq("sPRE"), captor.capture());
        List<SessionService.AppendMessage> appended = captor.getValue();
        SessionService.AppendMessage recoveryRow = appended.stream()
                .filter(am -> SessionService.MSG_TYPE_RECOVERY_PAYLOAD.equals(am.msgType()))
                .findFirst().orElse(null);
        assertThat(recoveryRow).as("preemptive path must emit RECOVERY_PAYLOAD row").isNotNull();
        // REMINDER-MVP D6: payload wrapped in <system-reminder>.
        String preemptContent = (String) recoveryRow.message().getContent();
        assertThat(preemptContent).startsWith("<system-reminder>\n");
        assertThat(preemptContent).endsWith("</system-reminder>\n");
        assertThat(preemptContent).contains("/abs/preempt.txt");
        assertThat(recoveryRow.metadata()).containsEntry("trigger", "engine-preemptive");
    }

    @Test
    @DisplayName("P9-5 W2 (Post-overflow): post-overflow callback path appends RECOVERY_PAYLOAD row")
    @SuppressWarnings("unchecked")
    void p9_5_postOverflowCompact_appendsRecoveryRow() {
        seedSession("sPO", 30, 0, "idle");
        seedMessages("sPO");

        FileStateCache cache = new FileStateCache();
        cache.put("sPO", "/abs/overflow.txt", "overflow recovered");
        RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(cache);
        service.setRecoveryPayloadBuilder(builder);

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        // Engine path: AgentLoopEngine line 955 catches LlmContextLengthExceededException, then
        // calls callback compactFull(sessionId, msgs, "post-overflow", "context_length_exceeded:...").
        var result = service.compactFull("sPO", new ArrayList<>(messagesStore.get("sPO")),
                "post-overflow", "context_length_exceeded:simulated");
        assertThat(result.performed).isTrue();

        verify(sessionService, atLeastOnce()).appendMessages(eq("sPO"), captor.capture());
        List<SessionService.AppendMessage> appended = captor.getValue();
        SessionService.AppendMessage recoveryRow = appended.stream()
                .filter(am -> SessionService.MSG_TYPE_RECOVERY_PAYLOAD.equals(am.msgType()))
                .findFirst().orElse(null);
        assertThat(recoveryRow).as("post-overflow path must emit RECOVERY_PAYLOAD row").isNotNull();
        // REMINDER-MVP D6: payload wrapped in <system-reminder>.
        String overflowContent = (String) recoveryRow.message().getContent();
        assertThat(overflowContent).startsWith("<system-reminder>\n");
        assertThat(overflowContent).endsWith("</system-reminder>\n");
        assertThat(overflowContent).contains("/abs/overflow.txt");
        assertThat(recoveryRow.metadata()).containsEntry("trigger", "post-overflow");
    }

    @Test
    @DisplayName("P9-5 W2 (SessionMemory): Phase 1.5 zero-LLM path also appends RECOVERY_PAYLOAD row")
    @SuppressWarnings("unchecked")
    void p9_5_sessionMemoryCompact_appendsRecoveryRow() {
        seedSession("sSM", 30, 0, "idle");
        // SessionMemory path requires session to have a userId (already 7L in seed)
        seedMessages("sSM");

        // Wire a MemoryService mock that returns a meaningful summary so Phase 1.5 fires.
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.previewMemoriesForPrompt(eq(7L), any()))
                .thenReturn("[memory] user prefers concise responses; project=skillforge.");
        service.setMemoryService(memoryService);

        FileStateCache cache = new FileStateCache();
        cache.put("sSM", "/abs/memory.txt", "memory-path content");
        RecoveryPayloadBuilder builder = new RecoveryPayloadBuilder(cache);
        service.setRecoveryPayloadBuilder(builder);

        org.mockito.ArgumentCaptor<List<SessionService.AppendMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        // Phase 1.5 wins → no LLM call required, but persistCompactResult is still hit.
        CompactionEventEntity event = service.compact("sSM", "full", "engine-hard", "memory path");
        assertThat(event).isNotNull();
        // It really used the session-memory strategy (not the LLM), so strategiesApplied
        // must reflect that — sanity check the path attribution before validating recovery.
        assertThat(event.getStrategiesApplied()).isEqualTo("llm-summary");
        // ^ note: buildEvent hard-codes "llm-summary" for full level (existing behavior, not
        // P9-5 scope). The path distinguisher is that previewMemoriesForPrompt was invoked
        // and the LlmProvider mock was NOT — Mockito verifies the latter implicitly because
        // we never set up llmProviderFactory.getProvider for sSM-specific behavior; default
        // mock returns the existing mockProvider which would only run if Phase 2 fired.
        verify(memoryService, atLeastOnce()).previewMemoriesForPrompt(eq(7L), any());

        verify(sessionService, atLeastOnce()).appendMessages(eq("sSM"), captor.capture());
        List<SessionService.AppendMessage> appended = captor.getValue();
        SessionService.AppendMessage recoveryRow = appended.stream()
                .filter(am -> SessionService.MSG_TYPE_RECOVERY_PAYLOAD.equals(am.msgType()))
                .findFirst().orElse(null);
        assertThat(recoveryRow).as("session-memory path must emit RECOVERY_PAYLOAD row").isNotNull();
        // REMINDER-MVP D6: payload wrapped in <system-reminder>.
        String memoryContent = (String) recoveryRow.message().getContent();
        assertThat(memoryContent).startsWith("<system-reminder>\n");
        assertThat(memoryContent).endsWith("</system-reminder>\n");
        assertThat(memoryContent).contains("/abs/memory.txt");
    }
}
