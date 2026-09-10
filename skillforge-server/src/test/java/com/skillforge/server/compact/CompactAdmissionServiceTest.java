package com.skillforge.server.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.service.CompactAdmissionService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CompactAdmissionServiceTest {

    private static final String SESSION_ID = "compact-admission-session";

    private SessionRepository sessionRepository;
    private SessionMessageRepository messageRepository;
    private SessionSummaryRepository summaryRepository;
    private SessionToolAttemptRepository attemptRepository;
    private SessionMessageInboxRepository inboxRepository;
    private PersistedMessageCodec messageCodec;
    private ObjectMapper objectMapper;
    private SessionService sessionService;
    private CompactAdmissionService admissionService;

    private final AtomicReference<SessionEntity> session = new AtomicReference<>();
    private final AtomicReference<List<SessionMessageEntity>> messages =
            new AtomicReference<>(List.of());
    private final AtomicReference<List<SessionToolAttemptEntity>> attempts =
            new AtomicReference<>(List.of());
    private final AtomicReference<List<SessionMessageInboxEntity>> inbox =
            new AtomicReference<>(List.of());

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(SessionMessageRepository.class);
        summaryRepository = mock(SessionSummaryRepository.class);
        attemptRepository = mock(SessionToolAttemptRepository.class);
        inboxRepository = mock(SessionMessageInboxRepository.class);
        sessionService = mock(SessionService.class);
        objectMapper = new ObjectMapper();
        messageCodec = new PersistedMessageCodec(objectMapper);

        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        admissionService = new CompactAdmissionService(
                properties,
                sessionRepository,
                messageRepository,
                summaryRepository,
                attemptRepository,
                inboxRepository,
                messageCodec,
                objectMapper,
                sessionService);

        SessionEntity current = new SessionEntity();
        current.setId(SESSION_ID);
        current.setUserId(17L);
        current.setAgentId(3L);
        current.setHistoryEpoch(8L);
        current.setActiveLoopId("11111111-1111-4111-8111-111111111111");
        current.setLoopFence(12L);
        current.setMessageCount(1);
        session.set(current);
        setMessages(List.of(messageRow(91L, 44L, Message.user("durable tail"))));

        when(sessionRepository.findByIdForUpdate(SESSION_ID))
                .thenAnswer(ignored -> Optional.ofNullable(session.get()));
        when(messageRepository.findBySessionIdOrderBySeqNoAsc(anyString(), any()))
                .thenAnswer(ignored -> page(messages.get()));
        when(summaryRepository.findBySessionIdOrderByStartSeqAsc(SESSION_ID))
                .thenReturn(List.of());
        when(attemptRepository.findBySessionIdAndStateIn(anyString(), any()))
                .thenAnswer(ignored -> attempts.get());
        when(inboxRepository.findBySessionIdOrderByIdAsc(anyString(), any()))
                .thenAnswer(ignored -> inbox.get());
        when(sessionService.getContextMessages(SESSION_ID)).thenAnswer(ignored ->
                messages.get().stream().map(this::decodeMessage).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user-manual", "engine-hard", "engine-preemptive", "post-overflow"})
    @DisplayName("normal Full sources share one closed durable admission snapshot")
    void capture_normalFullSource_returnsClosedSnapshot(String source) {
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", source);

        assertThat(snapshot.enforced()).isTrue();
        assertThat(snapshot.source()).isEqualTo(source);
        assertThat(snapshot.historyEpoch()).isEqualTo(8L);
        assertThat(snapshot.tail().messageId()).isEqualTo(91L);
        assertThat(snapshot.tail().seqNo()).isEqualTo(44L);
        assertThat(snapshot.openAttempt()).isNull();
    }

    @Test
    @DisplayName("normal compact fails closed while any unresolved Tool attempt exists")
    void capture_normalFullWithUnresolvedAttempt_rejectsBeforeProvider() {
        attempts.set(List.of(attempt(
                DurableToolAttemptState.WAITING_USER, "FileEdit", 91L, 44L)));

        assertThatThrownBy(() -> admissionService.capture(
                SESSION_ID, "full", "engine-hard"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved");
    }

    @Test
    @DisplayName("normal Light uses the same unresolved-attempt admission barrier")
    void capture_normalLightWithUnresolvedAttempt_rejectsBeforeStrategy() {
        attempts.set(List.of(attempt(
                DurableToolAttemptState.INTENT_COMMITTED, "FileEdit", 91L, 44L)));

        assertThatThrownBy(() -> admissionService.capture(
                SESSION_ID, "light", "engine-soft"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved");
    }

    @Test
    @DisplayName("master-on rejects a legacy-only or partially backfilled transcript")
    void capture_rowStoreCountDoesNotMatchSession_rejectsBeforeProjection() {
        messages.set(List.of());
        when(sessionService.getContextMessages(SESSION_ID))
                .thenReturn(List.of(Message.user("legacy CLOB fallback")));

        assertThatThrownBy(() -> admissionService.capture(
                SESSION_ID, "full", "engine-hard"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row store is not authoritative");
    }

    @Test
    @DisplayName("master flag off preserves the legacy path without admission reads")
    void capture_masterDisabled_returnsDisabledSnapshotWithoutDatabaseReads() {
        SessionHistoryProperties disabled = new SessionHistoryProperties();
        CompactAdmissionService disabledService = new CompactAdmissionService(
                disabled,
                sessionRepository,
                messageRepository,
                summaryRepository,
                attemptRepository,
                inboxRepository,
                messageCodec,
                objectMapper,
                sessionService);

        CompactAdmissionService.AdmissionSnapshot snapshot = disabledService.capture(
                SESSION_ID, "full", "engine-hard");

        assertThat(snapshot.enforced()).isFalse();
        verifyNoInteractions(sessionRepository, messageRepository, summaryRepository,
                attemptRepository, inboxRepository, sessionService);

        List<Message> legacy = new ArrayList<>();
        legacy.add(Message.user("legacy transient input"));
        assertThat(disabledService.prepareFullInput(snapshot, legacy).compactInput())
                .containsExactlyElementsOf(legacy);
        assertThat(disabledService.prepareLightInput(snapshot, legacy)).isSameAs(legacy);
    }

    @Test
    @DisplayName("agent-tool Full uses the exact persisted compact intent and excludes it from input")
    void prepareFullInput_agentToolExecutingCompactAttempt_returnsPreIntentPrefix() {
        Message prefix = Message.user("acknowledged prefix");
        Message assistant = assistantCompactIntent("compact-call");
        SessionMessageEntity prefixRow = messageRow(90L, 43L, prefix);
        SessionMessageEntity assistantRow = messageRow(91L, 44L, assistant);
        setMessages(List.of(prefixRow, assistantRow));
        attempts.set(List.of(attempt(
                DurableToolAttemptState.EXECUTING, "compact_context", 91L, 43L)));

        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "agent-tool");
        CompactAdmissionService.PreparedMessages prepared =
                admissionService.prepareFullInput(snapshot, List.of(prefix, assistant));

        assertThat(snapshot.openAttempt()).isNotNull();
        assertThat(snapshot.openAttempt().preIntentMaxSeq()).isEqualTo(43L);
        assertThat(prepared.compactInput()).hasSize(1);
        assertThat(messageCodec.writeMessage(prepared.compactInput().get(0)))
                .isEqualTo(messageCodec.writeMessage(prefix));
        assertThat(prepared.preservedSuffix()).hasSize(1);
        assertThat(messageCodec.writeMessage(prepared.preservedSuffix().get(0)))
                .isEqualTo(messageCodec.writeMessage(assistant));
    }

    @Test
    @DisplayName("agent-tool preserves the complete multi-tool assistant intent and compacts only its durable prefix")
    void prepareFullInput_agentToolMultiToolIntent_preservesAllProviderOrdinals() {
        Message prefix = Message.user("acknowledged prefix");
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(
                ContentBlock.toolUse("read-call", "FileRead", Map.of("path", "/tmp/a")),
                ContentBlock.toolUse("compact-call", "compact_context",
                        Map.of("level", "full", "reason", "long context")),
                ContentBlock.toolUse("bash-call", "Bash", Map.of("cmd", "echo done"))));
        setMessages(List.of(
                messageRow(90L, 43L, prefix),
                messageRow(91L, 44L, assistant)));

        SessionToolAttemptEntity attempt = attempt(
                DurableToolAttemptState.EXECUTING, "compact_context", 91L, 43L);
        attempt.setAssistantPayloadHash(sha256(messageCodec.writeMessage(assistant)));
        attempt.setManifestJson("{\"schemaVersion\":1,\"calls\":["
                + "{\"providerOrdinal\":0,\"toolUseId\":\"read-call\",\"toolName\":\"FileRead\","
                + "\"input\":{\"path\":\"/tmp/a\"},\"replaySafety\":\"READ_ONLY_REPLAYABLE\"},"
                + "{\"providerOrdinal\":1,\"toolUseId\":\"compact-call\",\"toolName\":\"compact_context\","
                + "\"input\":{\"level\":\"full\",\"reason\":\"long context\"},\"replaySafety\":\"UNKNOWN\"},"
                + "{\"providerOrdinal\":2,\"toolUseId\":\"bash-call\",\"toolName\":\"Bash\","
                + "\"input\":{\"cmd\":\"echo done\"},\"replaySafety\":\"MUTATING\"}],"
                + "\"replaySafety\":\"UNKNOWN\"}");
        attempt.setManifestHash(sha256(attempt.getManifestJson()));
        attempts.set(List.of(attempt));

        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "agent-tool");
        CompactAdmissionService.PreparedMessages prepared =
                admissionService.prepareFullInput(snapshot, List.of(prefix, assistant));

        assertThat(prepared.compactInput()).hasSize(1);
        assertThat(messageCodec.writeMessage(prepared.compactInput().get(0)))
                .isEqualTo(messageCodec.writeMessage(prefix));
        assertThat(prepared.preservedSuffix()).hasSize(1);
        assertThat(messageCodec.writeMessage(prepared.preservedSuffix().get(0)))
                .isEqualTo(messageCodec.writeMessage(assistant));
        assertThat(prepared.preservedSuffix().get(0).getToolUseBlocks())
                .extracting(com.skillforge.core.model.ToolUseBlock::getId)
                .containsExactly("read-call", "compact-call", "bash-call");
    }

    @Test
    @DisplayName("normal Full rejects a stale or mutated in-memory prefix before the provider")
    void prepareFullInput_normalFullMutatedPrefix_rejectsBeforeProvider() {
        Message durable = Message.user("authoritative durable input");
        setMessages(List.of(messageRow(91L, 44L, durable)));
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "engine-hard");

        assertThatThrownBy(() -> admissionService.prepareFullInput(
                snapshot, List.of(Message.user("stale in-memory input"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritative durable model view");
    }

    @Test
    @DisplayName("normal Full uses the server-captured view instead of the caller object graph")
    void prepareFullInput_matchingCopy_returnsAuthoritativeMessages() {
        Message durable = Message.user("authoritative durable input");
        setMessages(List.of(messageRow(91L, 44L, durable)));
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "engine-hard");
        Message callerCopy = Message.user("authoritative durable input");

        CompactAdmissionService.PreparedMessages prepared =
                admissionService.prepareFullInput(snapshot, List.of(callerCopy));

        assertThat(prepared.compactInput()).hasSize(1);
        assertThat(prepared.compactInput().get(0)).isNotSameAs(callerCopy);
        assertThat(prepared.compactInput().get(0).getContent())
                .isEqualTo("authoritative durable input");
    }

    @Test
    @DisplayName("agent-tool rejects an exact intent when its in-memory pre-intent prefix is missing")
    void prepareFullInput_agentToolMissingPrefix_rejectsBeforeProvider() {
        Message prefix = Message.user("acknowledged prefix");
        Message assistant = assistantCompactIntent("compact-call");
        setMessages(List.of(
                messageRow(90L, 43L, prefix),
                messageRow(91L, 44L, assistant)));
        attempts.set(List.of(attempt(
                DurableToolAttemptState.EXECUTING, "compact_context", 91L, 43L)));
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "agent-tool");

        assertThatThrownBy(() -> admissionService.prepareFullInput(
                snapshot, List.of(assistant)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritative durable model view");
    }

    @Test
    @DisplayName("agent-tool rejects a durable intent whose claimed frontier is not adjacent")
    void capture_agentToolWithInterveningDurableRow_rejectsBeforeProvider() {
        Message assistant = assistantCompactIntent("compact-call");
        setMessages(List.of(
                messageRow(90L, 43L, Message.user("claimed frontier")),
                messageRow(905L, 435L, Message.user("intervening durable row")),
                messageRow(91L, 44L, assistant)));
        attempts.set(List.of(attempt(
                DurableToolAttemptState.EXECUTING, "compact_context", 91L, 43L)));

        assertThatThrownBy(() -> admissionService.capture(
                SESSION_ID, "full", "agent-tool"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frontier");
    }

    @Test
    @DisplayName("Phase 3 rejects a model-view projection change even when raw rows are unchanged")
    void revalidate_authoritativeModelViewChanged_rejectsStaleSnapshot() {
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "engine-hard");
        when(sessionService.getContextMessages(SESSION_ID))
                .thenReturn(List.of(Message.user("projection changed")));

        assertThatThrownBy(() -> admissionService.revalidate(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("agent-tool Full cannot start before durable intent and execution claim exist")
    void capture_agentToolWithoutOpenAttempt_rejectsBeforeProvider() {
        assertThatThrownBy(() -> admissionService.capture(
                SESSION_ID, "full", "agent-tool"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable compact intent");
    }

    @Test
    @DisplayName("Phase 3 rejects append/content changes captured by the transcript provenance token")
    void revalidate_messageChanged_rejectsStaleSnapshot() {
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "engine-hard");
        setMessages(List.of(messageRow(92L, 45L, Message.user("concurrent append"))));

        assertThatThrownBy(() -> admissionService.revalidate(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("Phase 3 rejects epoch and loop-fence changes")
    void revalidate_epochAndFenceChanged_rejectsStaleSnapshot() {
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "engine-hard");
        session.get().setHistoryEpoch(9L);
        session.get().setLoopFence(13L);

        assertThatThrownBy(() -> admissionService.revalidate(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("Phase 3 rejects an inbox transition even when the transcript tail is unchanged")
    void revalidate_inboxChanged_rejectsStaleSnapshot() {
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "engine-hard");
        SessionMessageInboxEntity queued = new SessionMessageInboxEntity();
        queued.setId(301L);
        queued.setInboxId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
        queued.setSessionId(SESSION_ID);
        queued.setUserId(17L);
        queued.setMessageJson("{\"role\":\"USER\",\"content\":\"queued\"}");
        queued.setCreatedAt(Instant.parse("2026-09-03T00:00:00Z"));
        inbox.set(List.of(queued));

        assertThatThrownBy(() -> admissionService.revalidate(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("Phase 3 rejects an open-attempt transition without writing stale compact output")
    void revalidate_attemptChanged_rejectsStaleSnapshot() {
        Message assistant = assistantCompactIntent("compact-call");
        setMessages(List.of(
                messageRow(90L, 43L, Message.user("prefix")),
                messageRow(91L, 44L, assistant)));
        SessionToolAttemptEntity attempt = attempt(
                DurableToolAttemptState.EXECUTING, "compact_context", 91L, 43L);
        attempts.set(List.of(attempt));
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "agent-tool");
        attempt.setExecutionGeneration(2L);

        assertThatThrownBy(() -> admissionService.revalidate(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    @DisplayName("Phase 3 treats a closed open-attempt as stale rather than a fresh admission")
    void revalidate_attemptClosed_rejectsAsStale() {
        Message assistant = assistantCompactIntent("compact-call");
        setMessages(List.of(
                messageRow(90L, 43L, Message.user("prefix")),
                messageRow(91L, 44L, assistant)));
        attempts.set(List.of(attempt(
                DurableToolAttemptState.EXECUTING, "compact_context", 91L, 43L)));
        CompactAdmissionService.AdmissionSnapshot snapshot =
                admissionService.capture(SESSION_ID, "full", "agent-tool");
        attempts.set(List.of());

        assertThatThrownBy(() -> admissionService.revalidate(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Compact admission snapshot is stale");
    }

    private SessionToolAttemptEntity attempt(
            DurableToolAttemptState state,
            String toolName,
            long assistantMessageId,
            long preIntentMaxSeq) {
        SessionToolAttemptEntity attempt = new SessionToolAttemptEntity();
        attempt.setId(501L);
        attempt.setSessionId(SESSION_ID);
        attempt.setStepId(UUID.fromString("33333333-3333-4333-8333-333333333333"));
        attempt.setHistoryEpoch(8L);
        attempt.setOriginLoopId(session.get().getActiveLoopId());
        attempt.setOriginFence(session.get().getLoopFence());
        attempt.setAssistantMessageId(assistantMessageId);
        Message assistant = "compact_context".equals(toolName)
                ? assistantCompactIntent("compact-call")
                : assistantToolIntent("tool-call", toolName);
        attempt.setAssistantPayloadHash(sha256(messageCodec.writeMessage(assistant)));
        attempt.setPreIntentMaxMessageId(preIntentMaxSeq < 0 ? -1L : 90L);
        attempt.setPreIntentMaxSeq(preIntentMaxSeq);
        String toolUseId = "compact_context".equals(toolName) ? "compact-call" : "tool-call";
        String input = "compact_context".equals(toolName)
                ? "{\"level\":\"full\",\"reason\":\"long context\"}"
                : "{}";
        attempt.setManifestJson("{\"schemaVersion\":1,\"calls\":[{\"providerOrdinal\":0,\"toolUseId\":\""
                + toolUseId + "\",\"toolName\":\"" + toolName + "\",\"input\":" + input + ","
                + "\"replaySafety\":\"UNKNOWN\"}],\"replaySafety\":\"UNKNOWN\"}");
        attempt.setManifestHash(sha256(attempt.getManifestJson()));
        attempt.setReplaySafety("UNKNOWN");
        attempt.setState(state.name());
        attempt.setExecutionLoopId(session.get().getActiveLoopId());
        attempt.setExecutionFence(session.get().getLoopFence());
        attempt.setExecutionOwnerInstanceId("compact-owner");
        attempt.setExecutionGeneration(1L);
        attempt.setClaimRequestId(UUID.fromString("44444444-4444-4444-8444-444444444444"));
        return attempt;
    }

    private SessionMessageEntity messageRow(long id, long seqNo, Message message) {
        PersistedMessageCodec.EncodedRow encoded = messageCodec.encodeRow(
                new PersistedMessageCodec.PersistedMessage(
                        message, "NORMAL", "normal", null, Map.of()));
        SessionMessageEntity row = new SessionMessageEntity();
        row.setId(id);
        row.setSessionId(SESSION_ID);
        row.setSeqNo(seqNo);
        row.setRole(encoded.role());
        row.setContentJson(encoded.contentJson());
        row.setReasoningContent(encoded.reasoningContent());
        row.setMsgType(encoded.msgType());
        row.setMessageType(encoded.messageType());
        row.setMetadataJson(encoded.metadataJson());
        row.setCreatedAt(Instant.parse("2026-09-03T00:00:00Z"));
        return row;
    }

    private void setMessages(List<SessionMessageEntity> rows) {
        messages.set(List.copyOf(rows));
        session.get().setMessageCount(rows.size());
    }

    private Message decodeMessage(SessionMessageEntity row) {
        return messageCodec.decodeRow(new PersistedMessageCodec.EncodedRow(
                row.getRole(),
                row.getContentJson(),
                row.getReasoningContent(),
                row.getMsgType(),
                row.getMessageType(),
                row.getControlId(),
                row.getAnsweredAt(),
                row.getMetadataJson(),
                row.getTraceId())).message();
    }

    private static Message assistantCompactIntent(String id) {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(ContentBlock.toolUse(
                id, "compact_context", Map.of("level", "full", "reason", "long context"))));
        return assistant;
    }

    private static Message assistantToolIntent(String id, String toolName) {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(ContentBlock.toolUse(id, toolName, Map.of())));
        return assistant;
    }

    private static <T> Page<T> page(List<T> values) {
        return new PageImpl<>(values);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
