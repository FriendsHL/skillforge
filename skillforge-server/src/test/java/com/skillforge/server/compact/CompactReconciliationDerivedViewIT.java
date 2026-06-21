package com.skillforge.server.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionSummaryEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CompactionEventRepository;
import com.skillforge.server.repository.SessionCompactionCheckpointRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2a — <b>de-risking investigation</b> (§4):
 * with the range-model flag ON we now have P1 range WRITE + P2a derived READ + the EXISTING
 * (pre-redesign) reconciliation in {@link SessionService#updateSessionMessages}. Does the old
 * commonPrefix/messageEquals append-suffix reconciliation ALREADY persist only the genuinely new
 * real turns — without the heavy §3 provenance plumbing?
 *
 * <p>This drives the REAL three-phase full compact (range-model write path) against a REAL
 * {@link SessionService} backed by Postgres, then simulates an engine loop cycle and calls the
 * real {@code updateSessionMessages}, asserting:
 * <ul>
 *   <li>exactly the N new turns persist as NORMAL rows</li>
 *   <li>NO summary row leaks into {@code t_session_message} (INV-4)</li>
 *   <li>no dup-append; covered rows are not rewritten (created_at preserved)</li>
 *   <li>the derived view after == derived view before + N new turns</li>
 * </ul>
 */
@DisplayName("Reconciliation under derived range-model view (P2a §4 investigation)")
class CompactReconciliationDerivedViewIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository sessionMessageRepository;
    @Autowired private SessionSummaryRepository sessionSummaryRepository;
    @Autowired private CompactionEventRepository eventRepository;
    @Autowired private SessionCompactionCheckpointRepository checkpointRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private SessionService sessionService;
    private com.skillforge.server.service.CompactionService compactionService;

    private final LlmProvider mockProvider = new LlmProvider() {
        @Override public String getName() { return "mock"; }
        @Override public LlmResponse chat(LlmRequest request) {
            LlmResponse r = new LlmResponse();
            r.setContent("RANGE SUMMARY TEXT");
            return r;
        }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) { }
    };

    @BeforeEach
    void setUp() {
        sessionSummaryRepository.deleteAll();
        sessionMessageRepository.deleteAll();
        eventRepository.deleteAll();
        checkpointRepository.deleteAll();
        sessionRepository.deleteAll();

        sessionService = new SessionService(
                sessionRepository, sessionMessageRepository, agentRepository,
                new SessionMessageStoreProperties(), new ObjectMapper(), transactionManager);
        sessionService.setSessionSummaryRepository(sessionSummaryRepository);
        sessionService.setRangeModelEnabled(true);

        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        LlmProperties llmProperties = mock(LlmProperties.class);
        ChatEventBroadcaster broadcaster = mock(ChatEventBroadcaster.class);
        when(llmProperties.getDefaultProvider()).thenReturn("mock");
        when(llmProperties.getProviders()).thenReturn(new HashMap<>());
        when(llmProviderFactory.getProvider(anyString())).thenReturn(mockProvider);

        compactionService = new com.skillforge.server.service.CompactionService(
                sessionRepository, eventRepository, checkpointRepository, sessionService,
                new LightCompactStrategy(), new FullCompactStrategy(),
                llmProviderFactory, llmProperties, broadcaster, transactionManager);
        compactionService.setAgentRepository(agentRepository);
        compactionService.setSessionSummaryRepository(sessionSummaryRepository);
        compactionService.setSessionMessageRepository(sessionMessageRepository);
        compactionService.setRangeModelEnabled(true);
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(7L);
        s.setAgentId(1L);
        s.setTitle("recon-derived-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        s.setMessageCount(30);
        s.setLastCompactedAtMessageCount(0);
        sessionRepository.save(s);
        return s.getId();
    }

    /** 30 real message rows: 8 filler + tool_use/tool_result pair + 20 tail (mirrors the unit test seed). */
    private void seedMessages(String sid) {
        List<SessionService.AppendMessage> appends = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            appends.add(new SessionService.AppendMessage(
                    Message.user("front filler " + i), SessionService.MSG_TYPE_NORMAL,
                    java.util.Collections.emptyMap()));
        }
        Message tu = new Message();
        tu.setRole(Message.Role.ASSISTANT);
        tu.setContent(List.of(ContentBlock.toolUse("t1", "Bash", Map.of("cmd", "echo"))));
        appends.add(new SessionService.AppendMessage(tu, SessionService.MSG_TYPE_NORMAL,
                java.util.Collections.emptyMap()));
        Message tr = new Message();
        tr.setRole(Message.Role.USER);
        tr.setContent(List.of(ContentBlock.toolResult("t1", "out", false)));
        appends.add(new SessionService.AppendMessage(tr, SessionService.MSG_TYPE_NORMAL,
                java.util.Collections.emptyMap()));
        for (int i = 0; i < 20; i++) {
            appends.add(new SessionService.AppendMessage(
                    Message.user("tail " + i), SessionService.MSG_TYPE_NORMAL,
                    java.util.Collections.emptyMap()));
        }
        sessionService.appendMessages(sid, appends);
    }

    private List<SessionMessageEntity> rowsAsc(String sid) {
        return sessionMessageRepository
                .findBySessionIdOrderBySeqNoAsc(sid, PageRequest.of(0, 500)).getContent();
    }

    @Test
    @DisplayName("§4: after flagged full compact, old reconciliation persists ONLY the N new turns")
    void oldReconciliation_persistsOnlyNewTurns_noSummaryRow_noDup() {
        String sid = newSession();
        seedMessages(sid);

        long rowsBeforeCompact = sessionMessageRepository.countBySessionId(sid);
        assertThat(rowsBeforeCompact).isEqualTo(30);

        // (a) Real flagged full compact (P1 range-model write path).
        compactionService.compact(sid, "full", "engine-hard", "p2a investigation");

        // A summary range was written; ZERO message rows added/removed by compaction.
        List<SessionSummaryEntity> activeSummaries =
                sessionSummaryRepository.findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(sid);
        assertThat(activeSummaries).hasSize(1);
        assertThat(sessionMessageRepository.countBySessionId(sid)).isEqualTo(30);

        // (b) Derived view now = [summary] + uncovered tail.
        SessionService.ContextWithProvenance before =
                sessionService.getContextMessagesWithProvenance(sid);
        // FullCompactStrategy wraps the raw LLM output in a "[Context summary from N messages...]"
        // prefix, so assert containment of the mock's text rather than exact equality.
        assertThat((String) before.messages().get(0).getContent()).contains("RANGE SUMMARY TEXT");
        assertThat(before.provenance()[0]).isEqualTo(SessionService.PROVENANCE_SUMMARY);
        int derivedSizeBefore = before.messages().size();
        // Snapshot covered rows' created_at to prove they are never rewritten.
        List<SessionMessageEntity> rowsBefore = rowsAsc(sid);

        // (c) Simulate the engine result = derived view + N new real turns (assistant/tool/user).
        Message newAssistant = Message.assistant("brand new assistant turn");
        Message newUser = Message.user("brand new user turn");
        List<Message> engineResult = new ArrayList<>(before.messages());
        engineResult.add(newAssistant);
        engineResult.add(newUser);
        int n = 2;

        // (d) Call the REAL reconciliation.
        sessionService.updateSessionMessages(sid, engineResult, 100, 50, "trace-loop");

        // (e) Assertions.
        // e1: exactly N new NORMAL rows persisted (30 → 32). No dup-append.
        List<SessionMessageEntity> rowsAfter = rowsAsc(sid);
        assertThat(rowsAfter).hasSize(30 + n);

        // e2: NO summary row leaked into t_session_message (INV-4). The injected summary text must
        // not appear as any NORMAL row content.
        assertThat(rowsAfter).noneMatch(r -> {
            String c = r.getContentJson();
            return c != null && c.contains("RANGE SUMMARY TEXT");
        });

        // e3: covered/historical rows are NOT rewritten — created_at preserved for the first 30.
        for (int i = 0; i < rowsBefore.size(); i++) {
            assertThat(rowsAfter.get(i).getSeqNo()).isEqualTo(rowsBefore.get(i).getSeqNo());
            assertThat(rowsAfter.get(i).getCreatedAt())
                    .as("covered/historical row seq=%s must not be rewritten", rowsBefore.get(i).getSeqNo())
                    .isEqualTo(rowsBefore.get(i).getCreatedAt());
        }

        // e4: the two new rows carry the new content + traceId.
        assertThat(rowsAfter.get(30).getContentJson()).contains("brand new assistant turn");
        assertThat(rowsAfter.get(31).getContentJson()).contains("brand new user turn");
        assertThat(rowsAfter.get(30).getTraceId()).isEqualTo("trace-loop");

        // e5: covered rows' marker is intact — they did NOT lose compacted_by_summary_id (no rewrite).
        long stillMarked = rowsAfter.stream().filter(r -> r.getCompactedBySummaryId() != null).count();
        assertThat(stillMarked)
                .as("covered rows keep their marker (proves no full rewrite blew it away)")
                .isGreaterThan(0);

        // e6: derived view AFTER == derived view BEFORE + N new turns.
        SessionService.ContextWithProvenance after =
                sessionService.getContextMessagesWithProvenance(sid);
        assertThat(after.messages()).hasSize(derivedSizeBefore + n);
        assertThat(after.messages().get(after.messages().size() - 2).getTextContent())
                .isEqualTo("brand new assistant turn");
        assertThat(after.messages().get(after.messages().size() - 1).getTextContent())
                .isEqualTo("brand new user turn");
        // The new tail rows' provenance are real seq_nos (30, 31), not the summary sentinel.
        assertThat(after.provenance()[after.provenance().length - 2]).isEqualTo(30L);
        assertThat(after.provenance()[after.provenance().length - 1]).isEqualTo(31L);
    }
}
