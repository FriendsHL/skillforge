package com.skillforge.server.history.query;

import com.skillforge.core.skill.SkillContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.history.HistoryCanonicalSelector;
import com.skillforge.server.history.HistoryRefCodec;
import com.skillforge.server.history.HistoryToolInputValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryCursorCodec;
import com.skillforge.server.history.HistoryProtocolException;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(JpaHistoryQueryStore.class)
class JpaHistoryQueryStorePostgresIT extends AbstractPostgresIT {

    @Autowired private JpaHistoryQueryStore store;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository messageRepository;

    @Test
    void captureCutoff_isOwnerScopedAndNeverCrossesTrustedPreIntentFrontier() {
        String sessionId = createSession(701L, 8L, null);
        SessionMessageEntity prefix = append(sessionId, 0, "\"prefix\"");
        append(sessionId, 1, "\"current intent must stay outside snapshot\"");
        CurrentSessionHistoryScope scope = scope(
                sessionId, 701L, 8L, prefix.getId(), prefix.getSeqNo());

        HistoryCursorCodec.SnapshotCutoff cutoff = store.captureCutoff(scope);

        assertThat(cutoff.maxMessageId()).isEqualTo(prefix.getId());
        assertThat(cutoff.maxMessageSeq()).isZero();
        assertThat(store.loadMessages(scope, cutoff, 10))
                .extracting(HistoryQueryStore.MessageRow::contentJson)
                .containsExactly("\"prefix\"");

        CurrentSessionHistoryScope wrongOwner = scope(
                sessionId, 702L, 8L, prefix.getId(), prefix.getSeqNo());
        assertThatThrownBy(() -> store.requireCurrentScope(wrongOwner))
                .isInstanceOf(HistoryProtocolException.class)
                .extracting(error -> ((HistoryProtocolException) error).getCode())
                .isEqualTo("HISTORY_UNAVAILABLE");
    }

    @Test
    void requireCurrentScope_marksLegacyOnlyAndRejectsStaleEpoch() {
        String sessionId = createSession(801L, 2L, "[{\"role\":\"user\"}]");

        assertThat(store.requireCurrentScope(scope(sessionId, 801L, 2L, -1, -1)).legacyOnly())
                .isTrue();
        assertThatThrownBy(() -> store.requireCurrentScope(
                scope(sessionId, 801L, 1L, -1, -1)))
                .isInstanceOf(HistoryProtocolException.class)
                .extracting(error -> ((HistoryProtocolException) error).getCode())
                .isEqualTo("HISTORY_STALE");
    }

    @Test
    void narrowSelectorsRead100001RowSessionAndKeepHistoryPairingOutsideRange() {
        String sessionId = createSession(901L, 3L, "[]");
        jdbcTemplate.update("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, msg_type, content_json, metadata_json,
                     message_type, created_at)
                SELECT ?, series, 'user', 'NORMAL', to_json(('row ' || series)::text)::text,
                       '{}', 'normal', clock_timestamp()
                FROM generate_series(1, 100001) AS series
                """, sessionId);
        long maxId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM t_session_message WHERE session_id = ?", Long.class, sessionId);
        var scope = scope(sessionId, 901L, 3L, maxId, 100001);
        var cutoff = store.captureCutoff(scope);
        assertThat(store.loadMessages(scope, cutoff,
                HistoryQueryStore.Selection.range(50000L, 50000L, false), 8))
                .extracting(HistoryQueryStore.MessageRow::seqNo).containsExactly(50000L);
        ObjectMapper mapper = new ObjectMapper();
        HistoryRefCodec refs = new HistoryRefCodec();
        var service = new SessionHistoryQueryService(store, new HistoryEvidenceMaterializer(
                mapper, new HistoryAuthorizedProjection(mapper), refs,
                new FailClosedHistoryCanonicalArchiveResolver()), new HistoryCanonicalSelector(mapper),
                new HistoryCursorCodec(mapper), refs);
        var validator = new HistoryToolInputValidator();
        assertThat(service.read(scope, validator.validateReadInput(Map.of("tail", 1))).events())
                .singleElement().satisfies(event -> assertThat(event.logicalSeq()).isEqualTo(100001));
        assertThat(service.read(scope, validator.validateReadInput(Map.of(
                "aroundSeq", 50000, "before", 1, "after", 1))).events())
                .extracting(event -> event.logicalSeq()).containsExactly(49999L, 50000L, 50001L);
        assertThat(service.read(scope, validator.validateReadInput(Map.of(
                "refs", List.of("msg:e3:id" + maxId + ":block0")))).events()).hasSize(1);

        var intent = append(sessionId, 100002, """
                [{"type":"tool_use","id":"reuse","name":"SessionHistoryRead","input":{"tail":1}}]
                """);
        intent.setRole("assistant");
        messageRepository.saveAndFlush(intent);
        var result = append(sessionId, 100003, """
                [{"type":"tool_result","tool_use_id":"reuse","content":"derived secret"}]
                """);
        var newScope = scope(sessionId, 901L, 3L, result.getId(), 100003);
        assertThat(service.search(newScope, validator.validateSearchInput(Map.of(
                "seqFrom", 100003, "seqTo", 100003))).locators()).isEmpty();
        var ordinaryIntent = append(sessionId, 100004, """
                [{"type":"tool_use","id":"reuse","name":"FileRead","input":{"path":"p"}}]
                """);
        ordinaryIntent.setRole("assistant");
        messageRepository.saveAndFlush(ordinaryIntent);
        var ordinaryResult = append(sessionId, 100005, """
                [{"type":"tool_result","tool_use_id":"reuse","content":"original"}]
                """);
        newScope = scope(sessionId, 901L, 3L, ordinaryResult.getId(), 100005);
        assertThat(service.read(newScope, validator.validateReadInput(Map.of(
                "refs", List.of("msg:e3:id" + ordinaryResult.getId() + ":block0")))).events())
                .singleElement().satisfies(event -> assertThat(event.toolName()).isEqualTo("FileRead"));
        var hasher = new com.skillforge.server.session.ArchivePayloadIdentityHasher();
        String archiveId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO t_tool_result_archive
                    (archive_id, session_id, session_message_id, block_index, tool_use_id,
                     original_chars, content, canonical_payload_hash, payload_hash_version, created_at)
                VALUES (?, ?, ?, 0, 'reuse', 8, 'original', ?, ?, clock_timestamp())
                """, archiveId, sessionId, ordinaryResult.getId(),
                hasher.hash("reuse", "original", false, null),
                com.skillforge.server.session.ArchivePayloadIdentityHasher.VERSION);
        var canonicalService = new SessionHistoryQueryService(store, new HistoryEvidenceMaterializer(
                mapper, new HistoryAuthorizedProjection(mapper), refs, new CanonicalHistoryArchiveResolver(
                new com.skillforge.server.session.CanonicalToolResultOccurrenceResolver(hasher))),
                new HistoryCanonicalSelector(mapper), new HistoryCursorCodec(mapper), refs);
        assertThat(canonicalService.read(newScope, validator.validateReadInput(Map.of(
                "archiveRef", "archive:e3:id" + archiveId, "offset", 0, "maxChars", 100))).events())
                .singleElement().satisfies(event -> {
                    assertThat(event.ref()).isEqualTo("archive:e3:id" + archiveId);
                    assertThat(event.toolName()).isEqualTo("FileRead");
                    assertThat(event.content()).contains("original");
                });
    }

    @Test
    void malformedReusedIntentCannotExposeHistoryResultThroughNarrowRead() {
        String sessionId = createSession(902L, 3L, "[]");
        var history = append(sessionId, 0, """
                [{"type":"tool_use","id":"x","name":"SessionHistoryRead","input":{"tail":1}}]
                """);
        history.setRole("assistant");
        messageRepository.saveAndFlush(history);
        var malformed = append(sessionId, 1, """
                [{"type":"tool_use","id":"x","name":"FileRead"}]
                """);
        malformed.setRole("assistant");
        messageRepository.saveAndFlush(malformed);
        var result = append(sessionId, 2, """
                [{"type":"tool_result","tool_use_id":"x","content":"history output"}]
                """);
        var scope = scope(sessionId, 902L, 3L, result.getId(), 2);
        var cutoff = store.captureCutoff(scope);
        assertThat(store.loadPairingContext(scope, cutoff, List.of(result.getId()), 10))
                .extracting(HistoryQueryStore.MessageRow::id).containsExactly(history.getId());
        ObjectMapper mapper = new ObjectMapper();
        HistoryRefCodec refs = new HistoryRefCodec();
        var materializer = new HistoryEvidenceMaterializer(mapper, new HistoryAuthorizedProjection(mapper),
                refs, new FailClosedHistoryCanonicalArchiveResolver());
        var service = new SessionHistoryQueryService(store, materializer, new HistoryCanonicalSelector(mapper),
                new HistoryCursorCodec(mapper), refs);
        var validator = new HistoryToolInputValidator();
        assertThat(service.search(scope, validator.validateSearchInput(Map.of("query", "history output")))
                .locators()).isEmpty();
        assertThat(service.search(scope, validator.validateSearchInput(Map.of("seqFrom", 2, "seqTo", 2)))
                .locators()).isEmpty();
    }

    private String createSession(long userId, long epoch, String messagesJson) {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setAgentId(1L);
        session.setHistoryEpoch(epoch);
        session.setMessagesJson(messagesJson);
        return sessionRepository.saveAndFlush(session).getId();
    }

    private SessionMessageEntity append(String sessionId, long seq, String contentJson) {
        SessionMessageEntity message = new SessionMessageEntity();
        message.setSessionId(sessionId);
        message.setSeqNo(seq);
        message.setRole("user");
        message.setMsgType("NORMAL");
        message.setMessageType("normal");
        message.setContentJson(contentJson);
        message.setMetadataJson("{}");
        return messageRepository.saveAndFlush(message);
    }

    private static CurrentSessionHistoryScope scope(
            String sessionId, long userId, long epoch, long messageId, long seq) {
        SkillContext context = new SkillContext();
        context.setSessionId(sessionId);
        context.setUserId(userId);
        context.setToolUseId("toolu_history");
        return CurrentSessionHistoryScope.from(context, epoch, messageId, seq);
    }
}
