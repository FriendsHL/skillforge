package com.skillforge.server.history.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryCanonicalSelector;
import com.skillforge.server.history.HistoryCursorCodec;
import com.skillforge.server.history.HistoryProtocolException;
import com.skillforge.server.history.HistoryRefCodec;
import com.skillforge.server.history.HistoryToolInputValidator;
import com.skillforge.server.history.SessionHistoryReadResponse;
import com.skillforge.server.history.SessionHistorySearchResponse;
import com.skillforge.server.session.ArchivePayloadIdentityHasher;
import com.skillforge.server.session.CanonicalToolResultOccurrenceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionHistoryQueryServiceTest {

    private static final long USER_ID = 41L;
    private static final long EPOCH = 3L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HistoryToolInputValidator validator = new HistoryToolInputValidator();
    private final FakeStore store = new FakeStore();
    private SessionHistoryQueryService service;
    private CurrentSessionHistoryScope scope;

    @BeforeEach
    void setUp() {
        HistoryRefCodec refs = new HistoryRefCodec();
        HistoryAuthorizedProjection projection = new HistoryAuthorizedProjection(objectMapper);
        HistoryEvidenceMaterializer materializer = new HistoryEvidenceMaterializer(
                objectMapper, projection, refs,
                new FailClosedHistoryCanonicalArchiveResolver());
        service = new SessionHistoryQueryService(
                store, materializer, new HistoryCanonicalSelector(objectMapper),
                new HistoryCursorCodec(objectMapper), refs);
        SkillContext context = new SkillContext();
        context.setSessionId("session-a");
        context.setUserId(USER_ID);
        context.setToolUseId("toolu_current_history");
        scope = CurrentSessionHistoryScope.from(context, EPOCH, 100, 100);
    }

    @Test
    void search_limitOneContinuesBetweenBlocksBeforeDerivedSummaryWithoutSkipping() {
        store.messages.add(message(10, 10, "assistant", """
                [{"type":"text","text":"needle first"},
                 {"type":"text","text":"needle second"}]
                """));
        store.summaries.add(new HistoryQueryStore.SummaryRow(
                5, 0, 9, "needle summary", null, Instant.ofEpochSecond(5)));
        Map<String, Object> initial = Map.of("query", "needle", "limit", 1);

        SessionHistorySearchResponse first = service.search(
                scope, validator.validateSearchInput(initial));
        SessionHistorySearchResponse second = service.search(
                scope, validator.validateSearchInput(Map.of(
                        "query", "needle", "limit", 1, "cursor", first.cursor())));
        SessionHistorySearchResponse third = service.search(
                scope, validator.validateSearchInput(Map.of(
                        "query", "needle", "limit", 1, "cursor", second.cursor())));

        assertThat(first.locators()).extracting(SessionHistorySearchResponse.Locator::ref)
                .containsExactly("msg:e3:id10:block0");
        assertThat(second.locators()).extracting(SessionHistorySearchResponse.Locator::ref)
                .containsExactly("msg:e3:id10:block1");
        assertThat(third.locators()).extracting(SessionHistorySearchResponse.Locator::ref)
                .containsExactly("summary:e3:id5");
        assertThat(third.exhaustive()).isTrue();
    }

    @Test
    void search_permanentlyExcludesHistoryToolUseAndItsExactPairedResult() {
        store.messages.add(message(1, 1, "assistant", """
                [{"type":"tool_use","id":"toolu_history_old","name":"SessionHistoryRead", "input":{"query":"needle"}},
                 {"type":"tool_use","id":"toolu_normal","name":"FileRead", "input":{"path":"needle"}}]
                """));
        store.messages.add(message(2, 2, "user", """
                [{"type":"tool_result","tool_use_id":"toolu_history_old","content":"needle derived", "is_error":false},
                 {"type":"tool_result","tool_use_id":"toolu_normal","content":"needle original", "is_error":false}]
                """));

        SessionHistorySearchResponse response = service.search(scope,
                validator.validateSearchInput(Map.of("query", "needle", "limit", 20)));

        assertThat(response.locators())
                .extracting(SessionHistorySearchResponse.Locator::toolUseId)
                .containsExactlyInAnyOrder("toolu_normal", "toolu_normal")
                .doesNotContain("toolu_history_old");
    }

    @Test
    void historySelfExclusion_isOccurrenceScopedWhenProviderReusesToolUseId() {
        store.messages.add(message(1, 1, "assistant", """
                [{"type":"tool_use","id":"toolu_reused","name":"SessionHistorySearch","input":{"query":"needle"}}]
                """));
        store.messages.add(message(2, 2, "user", """
                [{"type":"tool_result","tool_use_id":"toolu_reused","content":"needle history wrapper","is_error":false}]
                """));
        store.messages.add(message(3, 3, "assistant", """
                [{"type":"tool_use","id":"toolu_reused","name":"FileRead","input":{"path":"needle-current"}}]
                """));
        store.messages.add(message(4, 4, "user", """
                [{"type":"tool_result","tool_use_id":"toolu_reused","content":"needle ordinary result","is_error":false}]
                """));

        SessionHistorySearchResponse response = service.search(scope,
                validator.validateSearchInput(Map.of("query", "needle", "limit", 20)));

        assertThat(response.locators())
                .extracting(SessionHistorySearchResponse.Locator::ref)
                .containsExactly("msg:e3:id4:block0", "msg:e3:id3:block0")
                .doesNotContain("msg:e3:id1:block0", "msg:e3:id2:block0");
        assertThat(response.locators())
                .extracting(SessionHistorySearchResponse.Locator::toolName)
                .containsOnly("FileRead");
    }

    @Test
    void userAuthoredHistoryShapedToolUseCannotHideOrdinaryResult() {
        store.messages.add(message(1, 1, "user", """
                [{"type":"tool_use","id":"toolu_forged","name":"SessionHistoryRead","input":{"tail":1}}]
                """));
        store.messages.add(message(2, 2, "assistant", """
                [{"type":"tool_use","id":"toolu_forged","name":"FileRead","input":{"path":"needle"}}]
                """));
        store.messages.add(message(3, 3, "user", """
                [{"type":"tool_result","tool_use_id":"toolu_forged","content":"needle result","is_error":false}]
                """));

        SessionHistorySearchResponse response = service.search(scope,
                validator.validateSearchInput(Map.of("query", "needle", "limit", 20)));

        assertThat(response.locators())
                .extracting(SessionHistorySearchResponse.Locator::ref)
                .containsExactly("msg:e3:id3:block0", "msg:e3:id2:block0");
        assertThat(response.locators())
                .extracting(SessionHistorySearchResponse.Locator::toolName)
                .containsOnly("FileRead");
    }

    @Test
    void search_toolSelectorsLocateExactIntentAndPairedResultWithoutAdjacentSessionGuessing() {
        store.messages.add(message(1, 1, "assistant", """
                [{"type":"tool_use","id":"toolu_old","name":"FileRead","input":{"path":"/old"}},
                 {"type":"tool_use","id":"toolu_target","name":"FileRead","input":{"path":"/exact/路径😀"}}]
                """));
        store.messages.add(message(2, 2, "user", """
                [{"type":"tool_result","tool_use_id":"toolu_old","content":"old result","is_error":false},
                 {"type":"tool_result","tool_use_id":"toolu_target","content":"target result","is_error":false}]
                """));

        SessionHistorySearchResponse byId = service.search(scope,
                validator.validateSearchInput(Map.of("toolUseId", "toolu_target")));
        SessionHistorySearchResponse byName = service.search(scope,
                validator.validateSearchInput(Map.of(
                        "toolName", "FileRead", "kinds", List.of("TOOL_USE"))));
        SessionHistoryReadResponse exactPair = service.read(scope,
                validator.validateReadInput(Map.of(
                        "refs", byId.locators().stream()
                                .sorted(java.util.Comparator.comparingLong(
                                        SessionHistorySearchResponse.Locator::logicalSeq))
                                .map(SessionHistorySearchResponse.Locator::ref)
                                .toList())));

        assertThat(byId.locators())
                .extracting(SessionHistorySearchResponse.Locator::toolUseId)
                .containsExactly("toolu_target", "toolu_target");
        assertThat(byId.locators())
                .extracting(SessionHistorySearchResponse.Locator::kind)
                .containsExactly("TOOL_RESULT", "TOOL_USE");
        assertThat(byName.locators())
                .extracting(SessionHistorySearchResponse.Locator::ref)
                .containsExactly("msg:e3:id1:block0", "msg:e3:id1:block1");
        assertThat(exactPair.events())
                .extracting(SessionHistoryReadResponse.Event::kind)
                .containsExactly("TOOL_USE", "TOOL_RESULT");
        assertThat(exactPair.events())
                .extracting(SessionHistoryReadResponse.Event::content)
                .containsExactly("{\"path\":\"/exact/路径😀\"}",
                        "{\"content\":\"target result\",\"isError\":false}");
    }

    @Test
    void search_conflictingRawFactsPrecedeSummaryAndNewestOriginalIsFirst() {
        store.messages.add(message(1, 1, "user", "\"deploy version is v1\""));
        store.messages.add(message(2, 2, "user", "\"correction: deploy version is v2\""));
        store.summaries.add(new HistoryQueryStore.SummaryRow(
                5, 0, 2, "deploy version is v1", null, Instant.ofEpochSecond(5)));

        SessionHistorySearchResponse response = service.search(scope,
                validator.validateSearchInput(Map.of("query", "deploy version", "limit", 10)));

        assertThat(response.locators())
                .extracting(SessionHistorySearchResponse.Locator::evidenceClass)
                .containsExactly("ORIGINAL", "ORIGINAL", "DERIVED_SUMMARY");
        assertThat(response.locators())
                .extracting(SessionHistorySearchResponse.Locator::logicalSeq)
                .containsExactly(2L, 1L, 2L);
        assertThat(response.locators().get(0).preview()).contains("v2");
        assertThat(response.locators().get(2).summaryState()).isEqualTo("ACTIVE");
    }

    @Test
    void search_noMatchIsExhaustiveAndDoesNotInventEvidence() {
        store.messages.add(message(1, 1, "user", "\"known fact only\""));

        SessionHistorySearchResponse response = service.search(scope,
                validator.validateSearchInput(Map.of("query", "missing-uuid-000")));

        assertThat(response.locators()).isEmpty();
        assertThat(response.cursor()).isNull();
        assertThat(response.exhaustive()).isTrue();
    }

    @Test
    void readBlock_pagesByUnicodeCodePointAndFrozenCutoffIgnoresLaterAppend() {
        store.messages.add(message(1, 1, "user", "\"A😀中B\""));
        Map<String, Object> firstInput = Map.of(
                "archiveRef", "msg:e3:id1:block0", "offset", 1, "maxChars", 1);

        SessionHistoryReadResponse first = service.read(
                scope, validator.validateReadInput(firstInput));
        store.messages.add(message(200, 200, "user", "\"later\""));
        SessionHistoryReadResponse second = service.read(
                scope, validator.validateReadInput(Map.of(
                        "archiveRef", "msg:e3:id1:block0", "offset", 1, "maxChars", 1,
                        "cursor", first.cursor())));

        assertThat(first.events()).extracting(SessionHistoryReadResponse.Event::content)
                .containsExactly("😀");
        assertThat(first.events().get(0).complete()).isFalse();
        assertThat(second.events()).extracting(SessionHistoryReadResponse.Event::content)
                .containsExactly("中");
        assertThat(second.events().get(0).codePointOffset()).isEqualTo(2);
    }

    @Test
    void readRefsDeduplicatedByValidatorPreserveCallerOrderAndTailIsAscending() {
        store.messages.add(message(1, 1, "user", "\"one\""));
        store.messages.add(message(2, 2, "assistant", "\"two\""));
        store.messages.add(message(3, 3, "user", "\"three\""));

        SessionHistoryReadResponse refs = service.read(scope, validator.validateReadInput(Map.of(
                "refs", List.of(
                        "msg:e3:id3:block0", "msg:e3:id1:block0", "msg:e3:id3:block0"))));
        SessionHistoryReadResponse tail = service.read(
                scope, validator.validateReadInput(Map.of("tail", 2)));

        assertThat(refs.events()).extracting(SessionHistoryReadResponse.Event::logicalSeq)
                .containsExactly(3L, 1L);
        assertThat(tail.events()).extracting(SessionHistoryReadResponse.Event::logicalSeq)
                .containsExactly(2L, 3L);
    }

    @Test
    void readRangeAndAroundReturnStableAscendingTimelineSlices() {
        for (int seq = 1; seq <= 6; seq++) {
            store.messages.add(message(seq, seq, seq % 2 == 0 ? "assistant" : "user",
                    "\"event-" + seq + "\""));
        }

        SessionHistoryReadResponse range = service.read(scope,
                validator.validateReadInput(Map.of("seqFrom", 2, "seqTo", 4)));
        SessionHistoryReadResponse around = service.read(scope,
                validator.validateReadInput(Map.of(
                        "aroundSeq", 4, "before", 2, "after", 1)));

        assertThat(range.events())
                .extracting(SessionHistoryReadResponse.Event::logicalSeq)
                .containsExactly(2L, 3L, 4L);
        assertThat(around.events())
                .extracting(SessionHistoryReadResponse.Event::logicalSeq)
                .containsExactly(2L, 3L, 4L, 5L);
        assertThat(range.truncated()).isFalse();
        assertThat(around.truncated()).isFalse();
    }

    @Test
    void canonicalArchive_replacesExactRawOccurrenceAndRedirectsItsRawRef() {
        HistoryRefCodec refs = new HistoryRefCodec();
        ArchivePayloadIdentityHasher hasher = new ArchivePayloadIdentityHasher();
        HistoryEvidenceMaterializer materializer = new HistoryEvidenceMaterializer(
                objectMapper, new HistoryAuthorizedProjection(objectMapper), refs,
                new CanonicalHistoryArchiveResolver(
                        new CanonicalToolResultOccurrenceResolver(hasher)));
        service = new SessionHistoryQueryService(
                store, materializer, new HistoryCanonicalSelector(objectMapper),
                new HistoryCursorCodec(objectMapper), refs);
        store.messages.add(message(1, 1, "assistant", """
                [{"type":"tool_use","id":"tool-1","name":"FileRead","input":{"path":"p"}}]
                """));
        store.messages.add(message(2, 2, "user", """
                [{"type":"tool_result","tool_use_id":"tool-1","content":"exact body","is_error":true,"error_type":"FAILED"}]
                """));
        store.archives.add(new HistoryQueryStore.ArchiveRow(
                1L, "arc-1", 2L, 0, "tool-1", null, "exact body",
                hasher.hash("tool-1", "exact body", true, "FAILED"),
                ArchivePayloadIdentityHasher.VERSION, Instant.ofEpochSecond(3)));

        SessionHistorySearchResponse search = service.search(
                scope, validator.validateSearchInput(Map.of("query", "exact body")));
        SessionHistoryReadResponse read = service.read(
                scope, validator.validateReadInput(Map.of(
                        "archiveRef", "archive:e3:idarc-1", "offset", 0,
                        "maxChars", 100)));

        assertThat(search.locators()).extracting(SessionHistorySearchResponse.Locator::ref)
                .containsExactly("archive:e3:idarc-1");
        assertThat(read.events()).extracting(SessionHistoryReadResponse.Event::content)
                .containsExactly(
                        "{\"content\":\"exact body\",\"errorType\":\"FAILED\","
                                + "\"isError\":true}");
        assertThatThrownBy(() -> service.read(scope, validator.validateReadInput(Map.of(
                "refs", List.of("msg:e3:id2:block0")))))
                .isInstanceOfSatisfying(HistoryProtocolException.class,
                        failure -> assertThat(failure.getCode())
                                .isEqualTo("HISTORY_REF_REDIRECT"));
    }

    @Test
    void narrowResultReadsRetainPredecessorPairingWithoutExposingContextRows() {
        store.messages.add(message(1, 1, "assistant", """
                [{"type":"tool_use","id":"reused","name":"SessionHistoryRead","input":{"tail":1}}]
                """));
        store.messages.add(message(2, 2, "user", """
                [{"type":"tool_result","tool_use_id":"reused","content":"history output"}]
                """));
        store.messages.add(message(3, 3, "assistant", """
                [{"type":"tool_use","id":"reused","name":"FileRead","input":{"path":"p"}}]
                """));
        store.messages.add(message(4, 4, "user", """
                [{"type":"tool_result","tool_use_id":"reused","content":"original output"}]
                """));

        assertThat(service.search(scope, validator.validateSearchInput(Map.of(
                "seqFrom", 2, "seqTo", 2))).locators()).isEmpty();
        var read = service.read(scope, validator.validateReadInput(Map.of(
                "refs", List.of("msg:e3:id4:block0"))));
        assertThat(read.events()).singleElement().satisfies(event -> {
            assertThat(event.toolName()).isEqualTo("FileRead");
            assertThat(event.ref()).isEqualTo("msg:e3:id4:block0");
        });
    }

    @Test
    void tailWindowSkipsIneligibleRowsAndPreservesSummaryBoundaryOrdering() {
        store.messages.add(message(1, 1, "user", "\"old eligible\""));
        for (int id = 2; id <= 600; id++) {
            store.messages.add(message(id, id, "system", "\"hidden\""));
        }
        SkillContext context = new SkillContext();
        context.setSessionId("session-a");
        context.setUserId(USER_ID);
        var windowScope = CurrentSessionHistoryScope.from(context, EPOCH, 600, 600);
        assertThat(service.read(windowScope, validator.validateReadInput(Map.of("tail", 1))).events())
                .extracting(SessionHistoryReadResponse.Event::ref).containsExactly("msg:e3:id1:block0");
        for (int id = 1; id <= 513; id++) {
            store.summaries.add(new HistoryQueryStore.SummaryRow(
                    id, 0, 600, "summary " + id, null, Instant.ofEpochSecond(id)));
        }
        assertThat(service.read(windowScope, validator.validateReadInput(Map.of("tail", 1))).events())
                .extracting(SessionHistoryReadResponse.Event::ref).containsExactly("summary:e3:id513");
    }

    @Test
    void narrowSelectorsRemainUsableBeyond100kMessages() {
        for (int id = 1; id <= 100_001; id++) {
            store.messages.add(message(id, id, "user", "\"row " + id + "\""));
        }
        SkillContext context = new SkillContext();
        context.setSessionId("session-a");
        context.setUserId(USER_ID);
        var largeScope = CurrentSessionHistoryScope.from(context, EPOCH, 100_001, 100_001);

        assertThat(service.search(largeScope, validator.validateSearchInput(Map.of(
                "seqFrom", 100_001, "seqTo", 100_001))).locators())
                .extracting(SessionHistorySearchResponse.Locator::ref)
                .containsExactly("msg:e3:id100001:block0");
        assertThat(service.read(largeScope, validator.validateReadInput(Map.of(
                "refs", List.of("msg:e3:id1:block0")))).events())
                .extracting(SessionHistoryReadResponse.Event::ref)
                .containsExactly("msg:e3:id1:block0");
        assertThat(service.read(largeScope, validator.validateReadInput(Map.of("tail", 1))).events())
                .extracting(SessionHistoryReadResponse.Event::ref)
                .containsExactly("msg:e3:id100001:block0");
        assertThat(service.read(largeScope, validator.validateReadInput(Map.of(
                "aroundSeq", 50_000, "before", 1, "after", 1))).events())
                .extracting(SessionHistoryReadResponse.Event::ref)
                .containsExactly("msg:e3:id49999:block0", "msg:e3:id50000:block0", "msg:e3:id50001:block0");
    }

    private static HistoryQueryStore.MessageRow message(
            long id, long seq, String role, String contentJson) {
        return new HistoryQueryStore.MessageRow(
                id, seq, role, "NORMAL", contentJson, "normal", null, null,
                Instant.ofEpochSecond(seq));
    }

    private static final class FakeStore implements HistoryQueryStore {
        private final List<MessageRow> messages = new ArrayList<>();
        private final List<SummaryRow> summaries = new ArrayList<>();
        private final List<ArchiveRow> archives = new ArrayList<>();

        @Override
        public ScopeState requireCurrentScope(CurrentSessionHistoryScope scope) {
            if (scope.userId() != USER_ID || !"session-a".equals(scope.sessionId())) {
                throw new AssertionError("owner scope was not preserved");
            }
            return new ScopeState(EPOCH, false);
        }

        @Override
        public HistoryCursorCodec.SnapshotCutoff captureCutoff(CurrentSessionHistoryScope scope) {
            long messageId = messages.stream().mapToLong(MessageRow::id)
                    .filter(value -> value <= scope.preIntentMaxMessageId()).max().orElse(0);
            long seq = messages.stream().filter(value -> value.id() <= messageId)
                    .mapToLong(MessageRow::seqNo)
                    .filter(value -> value <= scope.preIntentMaxSeq()).max().orElse(0);
            long summaryId = summaries.stream().mapToLong(SummaryRow::id).max().orElse(0);
            long archiveId = archives.stream().mapToLong(ArchiveRow::rowId).max().orElse(0);
            return new HistoryCursorCodec.SnapshotCutoff(
                    messageId, seq, summaryId, archiveId,
                    scope.preIntentMaxMessageId(), scope.preIntentMaxSeq());
        }

        @Override
        public List<MessageRow> loadMessages(
                CurrentSessionHistoryScope scope,
                HistoryCursorCodec.SnapshotCutoff cutoff,
                int hardLimit) {
            return messages.stream()
                    .filter(value -> value.id() <= cutoff.maxMessageId()
                            && value.seqNo() <= cutoff.maxMessageSeq())
                    .limit((long) hardLimit + 1).toList();
        }

        @Override
        public List<MessageRow> loadMessages(CurrentSessionHistoryScope scope,
                HistoryCursorCodec.SnapshotCutoff cutoff, Selection selection, int hardLimit) {
            java.util.Comparator<MessageRow> order = java.util.Comparator.comparingLong(MessageRow::seqNo);
            if (!selection.ascending()) order = order.reversed();
            return messages.stream()
                    .filter(row -> row.id() <= cutoff.maxMessageId() && row.seqNo() <= cutoff.maxMessageSeq())
                    .filter(row -> selection.seqFrom() == null || row.seqNo() >= selection.seqFrom())
                    .filter(row -> selection.seqTo() == null || row.seqNo() <= selection.seqTo())
                    .filter(row -> selection.messageIds() == null || selection.messageIds().contains(row.id())
                            || archives.stream().anyMatch(a -> a.rowId() <= cutoff.maxArchiveRowId()
                            && a.sessionMessageId() == row.id() && selection.archiveIds().contains(a.archiveId())))
                    .sorted(order).limit((long) hardLimit + 1).toList();
        }

        @Override
        public List<SummaryRow> loadSummaries(CurrentSessionHistoryScope scope,
                HistoryCursorCodec.SnapshotCutoff cutoff, Selection selection, int hardLimit) {
            java.util.Comparator<SummaryRow> order = java.util.Comparator.comparingLong(SummaryRow::endSeq)
                    .thenComparing(SummaryRow::createdAt).thenComparingLong(SummaryRow::id);
            if (!selection.ascending()) order = order.reversed();
            return summaries.stream().filter(row -> row.id() <= cutoff.maxSummaryId())
                    .filter(row -> selection.seqFrom() == null || row.endSeq() >= selection.seqFrom())
                    .filter(row -> selection.seqTo() == null || row.endSeq() <= selection.seqTo())
                    .filter(row -> selection.summaryIds() == null || selection.summaryIds().contains(row.id()))
                    .sorted(order).limit((long) hardLimit + 1).toList();
        }

        @Override
        public List<ArchiveRow> loadArchivesForMessages(CurrentSessionHistoryScope scope,
                HistoryCursorCodec.SnapshotCutoff cutoff, List<Long> ids, int hardLimit) {
            return archives.stream().filter(row -> row.rowId() <= cutoff.maxArchiveRowId())
                    .filter(row -> ids.contains(row.sessionMessageId()))
                    .limit((long) hardLimit + 1).toList();
        }

        @Override
        public List<MessageRow> loadPairingContext(CurrentSessionHistoryScope scope,
                HistoryCursorCodec.SnapshotCutoff cutoff, List<Long> ids, int hardLimit) {
            Map<Long, MessageRow> found = new java.util.LinkedHashMap<>();
            ObjectMapper mapper = new ObjectMapper();
            for (MessageRow target : messages) {
                if (!ids.contains(target.id())) continue;
                try {
                    var content = mapper.readTree(target.contentJson());
                    if (!content.isArray()) continue;
                    for (var result : content) {
                        if (!"tool_result".equals(result.path("type").asText())) continue;
                        String id = result.path("tool_use_id").asText();
                        MessageRow predecessor = null;
                        for (MessageRow prior : messages) {
                            if (prior.seqNo() >= target.seqNo() || prior.id() > cutoff.maxMessageId()
                                    || !"NORMAL".equals(prior.msgType()) || !"normal".equals(prior.messageType())
                                    || prior.controlId() != null) continue;
                            var priorContent = mapper.readTree(prior.contentJson());
                            if (!priorContent.isArray()) continue;
                            for (var block : priorContent) {
                                boolean match = "assistant".equals(prior.role())
                                        && "tool_use".equals(block.path("type").asText())
                                        && id.equals(block.path("id").asText())
                                        || "user".equals(prior.role())
                                        && "tool_result".equals(block.path("type").asText())
                                        && id.equals(block.path("tool_use_id").asText());
                                if (match && (predecessor == null || predecessor.seqNo() < prior.seqNo())) {
                                    predecessor = prior;
                                }
                            }
                        }
                        if (predecessor != null) found.put(predecessor.id(), predecessor);
                    }
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }
            return found.values().stream().limit((long) hardLimit + 1).toList();
        }

        @Override
        public List<SummaryRow> loadSummaries(
                CurrentSessionHistoryScope scope,
                HistoryCursorCodec.SnapshotCutoff cutoff,
                int hardLimit) {
            return summaries.stream().filter(value -> value.id() <= cutoff.maxSummaryId())
                    .limit((long) hardLimit + 1).toList();
        }

        @Override
        public List<ArchiveRow> loadArchives(
                CurrentSessionHistoryScope scope,
                HistoryCursorCodec.SnapshotCutoff cutoff,
                int hardLimit) {
            return archives.stream().filter(value -> value.rowId() <= cutoff.maxArchiveRowId())
                    .limit((long) hardLimit + 1).toList();
        }
    }
}
